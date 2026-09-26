package ink.underflo.wristbrief.mobile.artifacts

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Owns the lifecycle of transcript requests so that a long job is not tied to the
 * transcript screen or to a single composition.
 *
 * Every observed server state is written to the durable [TranscriptJobStore] before it
 * is published, so [resumePending] can re-arm in-flight jobs after process death.
 * Nothing here infers state from a timer: `queued` and `processing` come from the
 * gateway's own job status.
 */
class TranscriptJobService(
    private val repository: TranscriptRepository,
    private val gatewayApi: TranscriptGatewayApi,
    private val store: TranscriptJobStore,
    private val scope: CoroutineScope,
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
    /**
     * Pause between polls. Injectable so tests can advance deterministically instead of
     * waiting on wall-clock time; production uses the real coroutine delay.
     */
    private val pause: suspend (Long) -> Unit = { millis -> kotlinx.coroutines.delay(millis) },
) {
    private val _activeJobs = MutableStateFlow<Map<String, TranscriptJob?>>(emptyMap())

    /** Jobs keyed by their source audio URL; `null` means the job record was cleared. */
    val activeJobs: StateFlow<Map<String, TranscriptJob?>> = _activeJobs.asStateFlow()

    private val pollingJobs = mutableMapOf<String, Job>()
    private val trackedJobIds = mutableMapOf<String, String>()

    /**
     * Re-arms everything that was still in flight when the process ended. Safe to call
     * on every launch; a job already being polled is left alone.
     */
    fun resumePending() {
        store.pruneOlderThan(nowEpochMs() - STALE_JOB_MAX_AGE_MS)
        store.listInFlight().forEach { job ->
            when {
                job.status == TranscriptStatus.COMPLETED && job.contentCode != null ->
                    publish(job.audioUrl, job)
                job.jobId.isNotBlank() -> {
                    publish(job.audioUrl, job)
                    startPolling(job)
                }
            }
        }
    }

    /**
     * Shows a transcript for [request]. Prefers the local cache (no request, no quota),
     * otherwise reuses the persisted job for that audio URL, otherwise starts a new one.
     * Returning to the page never cancels a running job.
     */
    fun open(request: EpisodeTranscriptRequest) {
        val key = request.audioUrl
        val persisted = store.find(key)
        if (persisted != null && persisted.status == TranscriptStatus.COMPLETED) {
            val payload = persisted.contentCode?.let { repository.getCached(it) }
            if (payload != null) {
                publish(key, persisted)
                return
            }
        }
        val published = _activeJobs.value[key]
        if (published != null &&
            (pollingJobs[key]?.isActive == true || published.status == TranscriptStatus.COMPLETED)
        ) {
            return
        }
        if (persisted != null && persisted.jobId.isNotBlank() && persisted.status != TranscriptStatus.COMPLETED) {
            publish(key, persisted)
            startPolling(persisted)
            return
        }
        launchRequest(request)
    }

    /** Explicit user intent: ask the gateway again for this episode's transcript. */
    fun retryJob(request: EpisodeTranscriptRequest) {
        launchRequest(request, replace = true)
    }

    /** Probes an already-persisted job once more without creating a second request. */
    fun refreshJob(audioUrl: String) {
        val job = store.find(audioUrl) ?: return
        if (job.jobId.isBlank() || job.status == TranscriptStatus.COMPLETED) return
        // Re-polling only helps a job the server may still hold; a terminal not-found or
        // non-retryable failure stays terminal.
        if (job.errorCode != null && !job.retryable) return
        publish(audioUrl, job)
        startPolling(job, force = true)
    }

    /** Drops the local record so the next open starts from a real request again. */
    fun forget(audioUrl: String) {
        pollingJobs.remove(audioUrl)?.cancel()
        trackedJobIds.remove(audioUrl)
        store.clear(audioUrl)
        publish(audioUrl, null)
    }

    fun currentJob(audioUrl: String): TranscriptJob? = _activeJobs.value[audioUrl] ?: store.find(audioUrl)

    private fun launchRequest(request: EpisodeTranscriptRequest, replace: Boolean = false) {
        val key = request.audioUrl
        if (replace) pollingJobs.remove(key)?.cancel()
        trackedJobIds.remove(key)
        val now = nowEpochMs()
        val previous = store.find(key)
        val pending = TranscriptJob(
            audioUrl = key,
            jobId = "",
            contentCode = null,
            status = TranscriptStatus.UNKNOWN,
            title = request.title ?: previous?.title,
            feedUrl = request.feedUrl ?: previous?.feedUrl,
            guid = request.guid ?: previous?.guid,
            durationMs = request.durationMs ?: previous?.durationMs,
            errorCode = null,
            retryable = true,
            createdAtEpochMs = previous?.createdAtEpochMs ?: now,
            updatedAtEpochMs = now,
        )
        store.upsert(pending)
        publish(key, pending)
        scope.launch {
            val result = repository.fetchTranscript(request)
            val active = runFetchResult(pending, result)
            if (active != null) runPollLoop(active)
        }
    }
    private fun startPolling(job: TranscriptJob, force: Boolean = false) {
        val key = job.audioUrl
        // A job that already settled terminally must never be polled again: a 404/not-found
        // job has no id left on the server, and a non-retryable failure cannot improve.
        if (job.status == TranscriptStatus.COMPLETED) return
        if (job.errorCode != null && !job.retryable) return
        if (!force && pollingJobs[key]?.isActive == true) return
        pollingJobs[key]?.cancel()
        trackedJobIds[key] = job.jobId
        pollingJobs[key] = scope.launch { runPollLoop(job) }
    }

    /**
     * Polls [initial] until the gateway reports a terminal status. Suspends for the
     * server-provided cadence when there is one; otherwise a slow fixed cadence keeps a
     * long job alive without hammering the gateway.
     */
    internal suspend fun runPollLoop(initial: TranscriptJob) {
        val key = initial.audioUrl
        var current = initial
        var attempt = 0
        while (true) {
            val snapshot = repository.pollJobOnce(current.jobId)
            when (snapshot) {
                is TranscriptJobSnapshot.Ready -> {
                    // Settle through the repository so the payload is written to the same
                    // cache every other path uses: a transcript that finished while the app
                    // was away must be reopenable offline, not only re-fetchable.
                    repository.cacheReadySnapshot(snapshot)
                    store.markCompleted(
                        audioUrl = key,
                        contentCode = snapshot.contentCode,
                        source = snapshot.source,
                        artifactId = snapshot.artifactId,
                        nowEpochMs = nowEpochMs(),
                    )
                    publish(key, store.find(key) ?: current.copy(status = TranscriptStatus.COMPLETED))
                    trackedJobIds.remove(key)
                    pollingJobs.remove(key)
                    return
                }
                is TranscriptJobSnapshot.Failed -> {
                    store.markFailed(
                        audioUrl = key,
                        errorCode = snapshot.errorCode,
                        message = snapshot.message,
                        retryable = snapshot.retryable,
                        nowEpochMs = nowEpochMs(),
                    )
                    publish(key, store.find(key))
                    trackedJobIds.remove(key)
                    pollingJobs.remove(key)
                    return
                }
                is TranscriptJobSnapshot.JobGone -> {
                    store.markFailed(
                        audioUrl = key,
                        errorCode = snapshot.errorCode,
                        message = snapshot.message,
                        retryable = false,
                        nowEpochMs = nowEpochMs(),
                    )
                    publish(key, store.find(key))
                    trackedJobIds.remove(key)
                    pollingJobs.remove(key)
                    return
                }
                is TranscriptJobSnapshot.Active -> {
                    val jobId = snapshot.jobId.ifBlank { current.jobId }
                    val updated = current.copy(
                        jobId = jobId,
                        contentCode = snapshot.contentCode ?: current.contentCode,
                        status = snapshot.status,
                        // Keep the cadence the server last asked for, so a job that declared
                        // a slow poll interval is not probed faster than it asked.
                        pollAfterMs = snapshot.pollAfterMs ?: current.pollAfterMs,
                        updatedAtEpochMs = nowEpochMs(),
                    )
                    trackedJobIds[key] = jobId
                    val persisted = store.find(key)
                    if (persisted == null || persisted.jobId != jobId || persisted.status != snapshot.status) {
                        store.upsert(updated)
                    }
                    current = updated
                    publish(key, updated)

                    val waitMs = when {
                        attempt == 0 -> FIRST_POLL_DELAY_MS
                        updated.pollAfterMs != null && updated.pollAfterMs!! > 0L -> updated.pollAfterMs!!
                        else -> STEADY_POLL_DELAY_MS
                    }
                    attempt++
                    if (waitMs > 0L) pause(waitMs)
                }
            }
        }
    }

    /** Applies the initial request result. Returns a job to keep polling, if any. */
    internal fun runFetchResult(job: TranscriptJob, result: TranscriptFetchResult): TranscriptJob? {
        val key = job.audioUrl
        val now = nowEpochMs()
        return when (result) {
            is TranscriptFetchResult.Ready -> {
                val contentCode = result.contentCode.ifBlank { job.contentCode.orEmpty() }
                store.markCompleted(
                    audioUrl = key,
                    contentCode = contentCode,
                    source = result.source,
                    artifactId = result.artifactId,
                    nowEpochMs = now,
                )
                publish(key, store.find(key) ?: job.copy(status = TranscriptStatus.COMPLETED))
                null
            }
            is TranscriptFetchResult.Processing -> {
                val started = job.copy(
                    jobId = result.jobId.ifBlank { job.jobId },
                    contentCode = result.contentCode.ifBlank { job.contentCode.orEmpty() }.takeIf { it.isNotEmpty() },
                    status = TranscriptStatus.fromWire(result.status),
                    errorCode = null,
                    updatedAtEpochMs = now,
                )
                store.upsert(started)
                publish(key, started)
                if (started.jobId.isNotBlank()) started else null
            }
            is TranscriptFetchResult.Failure -> {
                val code = TranscriptGatewayErrors.normalizeFromFailure(result.errorCode, result.message)
                store.markFailed(
                    audioUrl = key,
                    errorCode = code,
                    message = result.message,
                    retryable = TranscriptGatewayErrors.isRetryable(code),
                    nowEpochMs = now,
                )
                publish(key, store.find(key))
                null
            }
        }
    }

    private fun publish(key: String, job: TranscriptJob?) {
        _activeJobs.value = _activeJobs.value + (key to job)
    }

    companion object {
        const val FIRST_POLL_DELAY_MS = 3_000L
        const val STEADY_POLL_DELAY_MS = 10_000L
        const val STALE_JOB_MAX_AGE_MS = 7L * 24L * 60L * 60L * 1_000L
    }
}
