package ink.underflo.wristbrief.mobile.artifacts

import org.json.JSONObject

data class EpisodeTranscriptRequest(
    val audioUrl: String,
    val feedUrl: String? = null,
    val guid: String? = null,
    val title: String? = null,
    val durationMs: Long? = null,
)

interface TranscriptRepository {
    suspend fun fetchTranscript(request: EpisodeTranscriptRequest): TranscriptFetchResult
    suspend fun pollUntilReady(jobId: String, maxAttempts: Int = 10, delayMs: Long = 1000L): TranscriptFetchResult =
        TranscriptFetchResult.Processing(contentCode = "", jobId = jobId)

    /**
     * One status probe for a job id. Unlike [pollUntilReady] this never loops, so a
     * resumable caller (the job service) can keep a long job alive across page exits
     * and process restarts while still publishing each observed server state.
     */
    suspend fun pollJobOnce(jobId: String): TranscriptJobSnapshot =
        PollingUnsupported.toSnapshot()

    /**
     * Probes a job until the server reports a terminal status.
     *
     * [pollAfterMsProvider] may return the cadence the gateway asked for (poll-after /
     * retry-after hint); it overrides [delayMs] for that cycle. There is no low
     * attempt ceiling: a long job keeps polling until it is terminal.
     */
    suspend fun pollJob(
        jobId: String,
        delayMs: Long = 5000L,
        maxAttempts: Int = Int.MAX_VALUE,
        pollAfterMsProvider: (TranscriptJobSnapshot) -> Long? = { null },
    ): TranscriptJobSnapshot = PollingUnsupported.toSnapshot()

    fun getCached(contentCode: String): TranscriptPayload?

    /**
     * Writes a finished snapshot into the offline cache.
     *
     * Called on every settle path, including the resume path after an app restart, so a
     * transcript that completed while the app was away is reopenable without a new request.
     */
    fun cacheReadySnapshot(snapshot: TranscriptJobSnapshot) {}
}

private object PollingUnsupported {
    fun toSnapshot(): TranscriptJobSnapshot = TranscriptJobSnapshot.Failed(
        errorCode = TranscriptGatewayErrors.UNKNOWN_STATUS,
        message = "This transcript repository cannot poll job status",
        retryable = false,
    )
}

class DefaultTranscriptRepository(
    private val cacheStore: TranscriptCache,
    private val gatewayApi: TranscriptGatewayApi,
) : TranscriptRepository {

    override fun getCached(contentCode: String): TranscriptPayload? {
        return cacheStore.get(contentCode)
    }

    override fun cacheReadySnapshot(snapshot: TranscriptJobSnapshot) {
        if (snapshot !is TranscriptJobSnapshot.Ready) return
        val payload = snapshot.payload ?: return
        if (snapshot.contentCode.isBlank()) return
        cacheStore.put(snapshot.contentCode, snapshot.artifactId, payload)
    }

    override suspend fun fetchTranscript(request: EpisodeTranscriptRequest): TranscriptFetchResult {
        val result = gatewayApi.requestTranscript(request)
        if (result is TranscriptFetchResult.Ready && result.payload != null) {
            cacheStore.put(result.contentCode, result.artifactId, result.payload)
        }
        return result
    }

    override suspend fun pollJobOnce(jobId: String): TranscriptJobSnapshot =
        gatewayApi.checkJobStatus(jobId).toSnapshot()

    override suspend fun pollJob(
        jobId: String,
        delayMs: Long,
        maxAttempts: Int,
        pollAfterMsProvider: (TranscriptJobSnapshot) -> Long?,
    ): TranscriptJobSnapshot {
        var attempts = 0
        var currentJobId = jobId
        while (attempts < maxAttempts) {
            val snapshot = pollJobOnce(currentJobId)
            when (snapshot) {
                is TranscriptJobSnapshot.Ready -> {
                    cacheReadySnapshot(snapshot)
                    return snapshot
                }
                is TranscriptJobSnapshot.Failed -> {
                    if (!snapshot.retryable) return snapshot
                }
                is TranscriptJobSnapshot.JobGone -> return snapshot
                is TranscriptJobSnapshot.Active -> Unit
            }
            attempts++
            if (attempts >= maxAttempts) return snapshot
            // The server's own cadence wins over the caller's default when it sent one.
            val serverCadence = (snapshot as? TranscriptJobSnapshot.Active)?.pollAfterMs
            val waitMs = serverCadence?.takeIf { it > 0L }
                ?: pollAfterMsProvider(snapshot)?.takeIf { it > 0L }
                ?: delayMs
            if (snapshot is TranscriptJobSnapshot.Active && snapshot.jobId.isNotBlank()) {
                currentJobId = snapshot.jobId
            }
            if (waitMs > 0L) kotlinx.coroutines.delay(waitMs)
        }
        return TranscriptJobSnapshot.Active(
            status = TranscriptStatus.UNKNOWN,
            contentCode = null,
            jobId = currentJobId,
        )
    }

    override suspend fun pollUntilReady(jobId: String, maxAttempts: Int, delayMs: Long): TranscriptFetchResult {
        val snapshot = pollJob(
            jobId = jobId,
            delayMs = delayMs,
            maxAttempts = maxAttempts,
            pollAfterMsProvider = { null },
        )
        return snapshot.toFetchResult()
    }
}

internal fun TranscriptJobSnapshot.toFetchResult(): TranscriptFetchResult = when (this) {
    is TranscriptJobSnapshot.Ready -> TranscriptFetchResult.Ready(
        contentCode = contentCode,
        artifactId = artifactId,
        source = source,
        quota = TranscriptQuotaInfo(normalUnits = 0, multiplier = 0f, chargedUnits = 0f),
        payload = payload,
    )

    is TranscriptJobSnapshot.Active -> TranscriptFetchResult.Processing(
        contentCode = contentCode.orEmpty(),
        jobId = jobId,
        status = status.wireValue,
    )
    is TranscriptJobSnapshot.Failed -> TranscriptFetchResult.Failure(
        errorCode = errorCode,
        message = message,
        kind = TranscriptGatewayErrors.classify(errorCode),
    )

    is TranscriptJobSnapshot.JobGone -> TranscriptFetchResult.Failure(
        errorCode = errorCode,
        message = message,
        kind = TranscriptFailureKind.TERMINAL,
    )
}

interface TranscriptGatewayApi {
    suspend fun requestTranscript(request: EpisodeTranscriptRequest): TranscriptFetchResult
    suspend fun checkJobStatus(jobId: String): TranscriptFetchResult
}
