package ink.underflo.wristbrief.mobile.artifacts

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Persistence + restore of an in-flight transcript job across a simulated process
 * restart. The store is an in-memory implementation of the same interface the SQLite
 * store backs, so the resume logic is exercised without an emulator.
 */
class TranscriptJobResumeTest {

    private class InMemoryTranscriptJobStore : TranscriptJobStore {
        val rows = linkedMapOf<String, TranscriptJob>()

        override fun find(audioUrl: String): TranscriptJob? = rows[audioUrl]

        override fun listInFlight(limit: Int): List<TranscriptJob> =
            rows.values.filter { it.status != TranscriptStatus.COMPLETED }.take(limit)

        override fun upsert(job: TranscriptJob) {
            rows[job.audioUrl] = job
        }

        override fun markCompleted(
            audioUrl: String,
            contentCode: String,
            source: String,
            artifactId: String,
            nowEpochMs: Long,
        ) {
            val existing = rows[audioUrl] ?: return
            rows[audioUrl] = existing.copy(
                status = TranscriptStatus.COMPLETED,
                contentCode = contentCode,
                source = source,
                artifactId = artifactId,
                errorCode = null,
                retryable = false,
                updatedAtEpochMs = nowEpochMs,
            )
        }

        override fun markFailed(
            audioUrl: String,
            errorCode: String,
            message: String,
            retryable: Boolean,
            nowEpochMs: Long,
        ) {
            val existing = rows[audioUrl] ?: return
            rows[audioUrl] = existing.copy(
                errorCode = errorCode,
                retryable = retryable,
                updatedAtEpochMs = nowEpochMs,
            )
        }

        override fun clear(audioUrl: String) {
            rows.remove(audioUrl)
        }

        override fun pruneOlderThan(thresholdEpochMs: Long) {
            rows.entries.removeAll { it.value.updatedAtEpochMs < thresholdEpochMs }
        }

        fun pendingRows(): List<TranscriptJob> = rows.values.toList()
    }

    private class FakeTranscriptCache : TranscriptCache {
        private val cache = mutableMapOf<String, Pair<String, TranscriptPayload>>()
        override fun get(contentCode: String): TranscriptPayload? = cache[contentCode]?.second
        override fun put(contentCode: String, artifactId: String, payload: TranscriptPayload) {
            cache[contentCode] = Pair(artifactId, payload)
        }
        override fun remove(contentCode: String) {
            cache.remove(contentCode)
        }
    }

    private fun payload(contentCode: String) = TranscriptPayload(
        contentCode = contentCode,
        language = "en",
        durationMs = 480_000L,
        fullText = "Segmented text.",
        segments = listOf(TranscriptSegment(id = 1, startMs = 0L, endMs = 240_000L, text = "Segmented text.")),
    )

    private val request = EpisodeTranscriptRequest(
        audioUrl = "https://cdn.example.com/long-episode.mp3",
        title = "Long episode",
    )

    /** Records what the fake gateway actually did, so no network is involved. */
    private class FakeGateway(
        var statusResponses: MutableList<TranscriptFetchResult> = mutableListOf(),
    ) : TranscriptGatewayApi {
        var requestCount = 0
        val polledJobIds = mutableListOf<String>()

        override suspend fun requestTranscript(request: EpisodeTranscriptRequest): TranscriptFetchResult {
            requestCount++
            return TranscriptFetchResult.Processing(
                contentCode = "WBEP-LONG-JOB",
                jobId = "job_durable_1",
                status = "queued",
            )
        }

        override suspend fun checkJobStatus(jobId: String): TranscriptFetchResult {
            polledJobIds.add(jobId)
            return statusResponses.removeFirstOrNull()
                ?: TranscriptFetchResult.Processing("WBEP-LONG-JOB", jobId, status = "running")
        }
    }

    /** No wall-clock waiting: the polling cadence is injected as a no-op. */
    private val noPause: suspend (Long) -> Unit = { }

    @Test
    fun pendingJobSurvivesProcessDeathAndResumesPolling() = runBlocking {
        val store = InMemoryTranscriptJobStore()
        val cache = FakeTranscriptCache()
        val gateway = FakeGateway()

        // --- first process: start the job, then die before it finishes ---------------
        val firstProcess = TranscriptJobService(
            repository = DefaultTranscriptRepository(cache, gateway),
            gatewayApi = gateway,
            store = store,
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            nowEpochMs = { 1_000L },
        )
        val pending = TranscriptJob(
            audioUrl = request.audioUrl,
            jobId = "",
            contentCode = null,
            status = TranscriptStatus.UNKNOWN,
            title = request.title,
            createdAtEpochMs = 1_000L,
            updatedAtEpochMs = 1_000L,
        )
        store.upsert(pending)
        val started = firstProcess.runFetchResult(pending, gateway.requestTranscript(request))
        assertNotNull("the request must produce a pollable job", started)
        assertEquals("the server job id must be persisted", "job_durable_1", started!!.jobId)
        assertEquals(TranscriptStatus.QUEUED, started.status)

        // The job id and content code are on disk before any polling happens.
        assertEquals("job_durable_1", store.find(request.audioUrl)?.jobId)
        assertEquals("WBEP-LONG-JOB", store.find(request.audioUrl)?.contentCode)

        // --- second process: fresh objects, same durable store ------------------------
        gateway.statusResponses = mutableListOf(
            TranscriptFetchResult.Processing("WBEP-LONG-JOB", "job_durable_1", status = "running"),
            TranscriptFetchResult.Ready(
                contentCode = "WBEP-LONG-JOB",
                artifactId = "art_long",
                source = "generated",
                quota = TranscriptQuotaInfo(1, 1.0f, 1.0f),
                payload = payload("WBEP-LONG-JOB"),
            ),
        )
        val secondProcess = TranscriptJobService(
            repository = DefaultTranscriptRepository(cache, gateway),
            gatewayApi = gateway,
            store = store,
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            nowEpochMs = { 2_000L },
            pause = noPause,
        )
        secondProcess.resumePending()

        // resumePending() re-arms and polls to a terminal state in this same call, because
        // the injected pause never parks on wall-clock time. Job id plus content code on
        // disk is the only reason it can do that.
        val resolved = secondProcess.currentJob(request.audioUrl)
        assertNotNull("resumePending must re-publish the persisted job", resolved)
        assertEquals("the persisted job id must be reused, not re-requested", "job_durable_1", resolved!!.jobId)
        assertEquals(TranscriptStatus.COMPLETED, resolved.status)
        assertEquals("art_long", resolved.artifactId)
        assertTrue(
            "polling must target the persisted job id, saw ${gateway.polledJobIds}",
            gateway.polledJobIds.contains("job_durable_1"),
        )
        // The completed payload is in the cache and reopenable without a new request.
        assertNotNull(
            "the finished transcript must be cached for offline reopen",
            DefaultTranscriptRepository(cache, gateway).getCached("WBEP-LONG-JOB"),
        )
        assertEquals("a resumed job must not need a second request", 1, gateway.requestCount)
    }

    @Test
    fun resumePendingStartsPollingWithoutOpeningTheTranscriptScreen() = runBlocking {
        val store = InMemoryTranscriptJobStore()
        val cache = FakeTranscriptCache()
        val gateway = FakeGateway(
            statusResponses = mutableListOf(
                TranscriptFetchResult.Ready(
                    contentCode = "WBEP-LONG-JOB",
                    artifactId = "art_auto",
                    source = "generated",
                    quota = TranscriptQuotaInfo(1, 1.0f, 1.0f),
                    payload = payload("WBEP-LONG-JOB"),
                ),
            ),
        )
        store.upsert(
            TranscriptJob(
                audioUrl = request.audioUrl,
                jobId = "job_after_restart",
                contentCode = "WBEP-LONG-JOB",
                status = TranscriptStatus.UNKNOWN,
                title = request.title,
                createdAtEpochMs = 1_000L,
                updatedAtEpochMs = 1_000L,
            ),
        )
        val service = TranscriptJobService(
            repository = DefaultTranscriptRepository(cache, gateway),
            gatewayApi = gateway,
            store = store,
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            nowEpochMs = { 2_000L },
            pause = noPause,
        )

        // No transcript screen is opened: this is the app-start path only.
        service.resumePending()

        assertEquals(
            "re-arm must poll the persisted job id",
            listOf("job_after_restart"),
            gateway.polledJobIds,
        )
        assertEquals(
            "re-arm must settle the persisted row without a new request",
            TranscriptStatus.COMPLETED,
            store.find(request.audioUrl)?.status,
        )
        assertEquals("re-arm must never re-POST the request", 0, gateway.requestCount)
        assertNotNull(
            "re-arm must cache the finished transcript for offline reopen",
            DefaultTranscriptRepository(cache, gateway).getCached("WBEP-LONG-JOB"),
        )
    }

    @Test
    fun jobTheServerNoLongerHasSettlesTerminalAndStopsPolling() {
        val store = InMemoryTranscriptJobStore()
        val cache = FakeTranscriptCache()
        val gateway = FakeGateway(
            statusResponses = mutableListOf(
                TranscriptFetchResult.Failure("HTTP_404", "{\"error\":\"job_not_found\"}"),
            ),
        )
        store.upsert(
            TranscriptJob(
                audioUrl = request.audioUrl,
                jobId = "job_gone",
                contentCode = "WBEP-GONE",
                status = TranscriptStatus.QUEUED,
                createdAtEpochMs = 1_000L,
                updatedAtEpochMs = 1_000L,
            ),
        )
        val service = TranscriptJobService(
            repository = DefaultTranscriptRepository(cache, gateway),
            gatewayApi = gateway,
            store = store,
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            nowEpochMs = { 3_000L },
            pause = noPause,
        )
        val job = store.find(request.audioUrl)!!
        runBlocking { service.runPollLoop(job) }
        val settled = store.find(request.audioUrl)
        assertNotNull("the job row must survive a terminal failure", settled)
        assertEquals(
            "a 404 job probe must settle as a terminal not-found, got ${settled!!.errorCode}",
            TranscriptGatewayErrors.NOT_FOUND,
            settled.errorCode,
        )
        assertTrue("a gone job must not be retryable", !settled.retryable)
        // Exactly one probe: a terminal outcome is never polled again.
        assertEquals("a terminal outcome must stop polling after one probe", 1, gateway.polledJobIds.size)

        val uiState = TranscriptJobPresentation.from(settled, null, null)
        assertTrue("a gone job must present as Failed, got $uiState", uiState is TranscriptUiState.Failed)
        assertTrue(
            "a gone job must not offer the retry action",
            !(uiState as TranscriptUiState.Failed).retryable,
        )
    }

    @Test
    fun staleJobsArePrunedOnResume() {
        val store = InMemoryTranscriptJobStore()
        val gateway = FakeGateway()
        store.upsert(
            TranscriptJob(
                audioUrl = "https://cdn.example.com/old.mp3",
                jobId = "job_old",
                contentCode = null,
                status = TranscriptStatus.QUEUED,
                createdAtEpochMs = 1L,
                updatedAtEpochMs = 1L,
            ),
        )
        val service = TranscriptJobService(
            repository = DefaultTranscriptRepository(FakeTranscriptCache(), gateway),
            gatewayApi = gateway,
            store = store,
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            nowEpochMs = { STALE_THRESHOLD + 10L },
            pause = noPause,
        )
        service.resumePending()

        assertNull(
            "a job older than the retention window must be dropped",
            store.find("https://cdn.example.com/old.mp3"),
        )
        assertTrue("the pending table must be empty after pruning", store.pendingRows().isEmpty())
    }

    @Test
    fun retryRerequestsAndKeepsTheSameEpisodeIdentity() = runBlocking {
        val store = InMemoryTranscriptJobStore()
        val cache = FakeTranscriptCache()
        val gateway = FakeGateway(statusResponses = mutableListOf())
        val service = TranscriptJobService(
            repository = DefaultTranscriptRepository(cache, gateway),
            gatewayApi = gateway,
            store = store,
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            nowEpochMs = { 5_000L },
            pause = noPause,
        )
        service.retryJob(request)
        // Idempotent from the user's perspective: the same episode is re-requested and the
        // durable row keeps one identity rather than accumulating duplicates.
        service.retryJob(request)
        assertEquals(
            "a retried episode must keep exactly one durable row",
            1,
            store.pendingRows().count { it.audioUrl == request.audioUrl },
        )
    }

    private companion object {
        const val STALE_THRESHOLD = TranscriptJobService.STALE_JOB_MAX_AGE_MS + 1_000L
    }
}
