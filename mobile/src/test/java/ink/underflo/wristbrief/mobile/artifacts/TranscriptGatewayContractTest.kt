package ink.underflo.wristbrief.mobile.artifacts

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Contract tests for the transcript client against the FROZEN gateway responses.
 *
 * The parsing is exercised directly (the HTTP client only talks to HTTPS, so a loopback
 * server cannot be used from a JVM unit test) and the polling/resume behaviour through the
 * repository with a fake gateway. No network.
 */
class TranscriptGatewayContractTest {

    private val payloadJson = """
        {"schemaVersion":"1","contentCode":"WBEP-CONTRACT-0001","language":"en","durationMs":480000,
         "fullText":"Clip one.","segments":[{"id":1,"startMs":0,"endMs":240000,"text":"Clip one."}]}
    """.trimIndent()

    private fun payload() = TranscriptPayload.fromJsonString(payloadJson)

    /**
     * Matches the `fetchContent: suspend (contentCode: String) -> TranscriptPayload?` shape
     * so it can be passed by reference, and never returns content: these tests exercise the
     * "no transcript payload" cases.
     */
    private fun noContent(@Suppress("UNUSED_PARAMETER") contentCode: String): TranscriptPayload? = null

    private class FakeCache : TranscriptCache {
        val stored = mutableMapOf<String, TranscriptPayload>()
        override fun get(contentCode: String): TranscriptPayload? = stored[contentCode]
        override fun put(contentCode: String, artifactId: String, payload: TranscriptPayload) {
            stored[contentCode] = payload
        }
        override fun remove(contentCode: String) {
            stored.remove(contentCode)
        }
    }

    // --- POST /v1/transcripts/request ----------------------------------------------------

    @Test
    fun requestReadyResponseCarriesSourceAndQuota() = runBlocking {
        val body = """
            {"status":"ready","contentCode":"WBEP-CONTRACT-0001","artifactId":"art_1",
             "source":"shared_cache","quota":{"normalUnits":1,"multiplier":0.2,"chargedUnits":0.2}}
        """.trimIndent()

        val result = TranscriptResponseParser.parseRequestResponse(body) { payload() }

        assertTrue("expected Ready, got $result", result is TranscriptFetchResult.Ready)
        val ready = result as TranscriptFetchResult.Ready
        assertEquals("shared_cache", ready.source)
        assertEquals("art_1", ready.artifactId)
        assertEquals(0.2f, ready.quota.multiplier, 0.0001f)
        assertNotNull("the payload must be fetched via GET /v1/transcripts/{contentCode}", ready.payload)
    }

    @Test
    fun requestProcessingResponseCarriesAPollableJobId() = runBlocking {
        val body = """{"status":"processing","contentCode":"WBEP-CONTRACT-0001","jobId":"job_1"}"""

        val result = TranscriptResponseParser.parseRequestResponse(body, ::noContent)

        assertTrue("expected Processing, got $result", result is TranscriptFetchResult.Processing)
        val processing = result as TranscriptFetchResult.Processing
        assertEquals("job_1", processing.jobId)
        assertEquals("WBEP-CONTRACT-0001", processing.contentCode)
        assertEquals("processing", processing.status)
    }

    @Test
    fun requestErrorResponseSurfacesTheServerCode() = runBlocking {
        val result = TranscriptResponseParser.parseRequestResponse(
            """{"error":"invalid_audio_url","message":"unsupported scheme"}""",
            ::noContent,
        )
        assertTrue("expected Failure, got $result", result is TranscriptFetchResult.Failure)
        assertEquals("invalid_audio_url", (result as TranscriptFetchResult.Failure).errorCode)
    }

    // --- GET /v1/transcripts/jobs/:id -----------------------------------------------------

    @Test
    fun completedStatusWithConditionalArtifactAndQuotaParsesFully() = runBlocking {
        val body = """
            {"jobId":"job_1","contentId":"c1","contentCode":"WBEP-CONTRACT-0001",
             "status":"completed","artifactId":"art_9","attemptCount":1,"errorCode":null,
             "updatedAt":1700000000000,
             "quota":{"normalUnits":1,"multiplier":0.2,"chargedUnits":0.2}}
        """.trimIndent()

        val result = TranscriptResponseParser.parseJobStatusResponse(body, fetchContent = { payload() })

        assertTrue("expected Ready, got $result", result is TranscriptFetchResult.Ready)
        val ready = result as TranscriptFetchResult.Ready
        assertEquals("WBEP-CONTRACT-0001", ready.contentCode)
        assertEquals("art_9", ready.artifactId)
        assertEquals(0.2f, ready.quota.chargedUnits, 0.0001f)
        assertNotNull(ready.payload)
    }

    @Test
    fun completionWithoutArtifactIdOrQuotaIsNormalNotAnError() = runBlocking {
        // Frozen contract: artifactId and quota are present ONLY when the caller may read
        // the artifact, so a restricted completion has neither and is still completed.
        val body = """
            {"jobId":"job_1","contentId":"c1","contentCode":"WBEP-CONTRACT-0001",
             "status":"completed","attemptCount":2,"errorCode":null,"updatedAt":1700000000000}
        """.trimIndent()

        val result = TranscriptResponseParser.parseJobStatusResponse(body, fetchContent = { payload() })

        assertTrue("expected Ready, got $result", result is TranscriptFetchResult.Ready)
        val ready = result as TranscriptFetchResult.Ready
        assertEquals("", ready.artifactId)
        assertEquals(1, ready.quota.normalUnits)
        assertEquals(1.0f, ready.quota.multiplier, 0.0001f)
        assertNotNull("content still comes from the transcript GET", ready.payload)
        assertTrue(
            "the absence of artifactId must not be treated as a failure",
            TranscriptResponseParser.parseJobStatusResponse(body, fetchContent = ::noContent) is TranscriptFetchResult.Ready,
        )
    }

    @Test
    fun queuedAndRunningAreDistinctNonTerminalStates() = runBlocking {
        val queued = TranscriptResponseParser.parseJobStatusResponse(
            """{"jobId":"job_1","contentCode":"WBEP-CONTRACT-0001","status":"queued","attemptCount":0,"updatedAt":1}""",
            ::noContent,
        )
        val running = TranscriptResponseParser.parseJobStatusResponse(
            """{"jobId":"job_1","contentCode":"WBEP-CONTRACT-0001","status":"running","attemptCount":1,"updatedAt":2}""",
            ::noContent,
        )

        assertEquals("queued", (queued as TranscriptFetchResult.Processing).status)
        assertEquals("running", (running as TranscriptFetchResult.Processing).status)
        assertEquals(TranscriptStatus.QUEUED, (queued.toSnapshot() as TranscriptJobSnapshot.Active).status)
        assertEquals(TranscriptStatus.PROCESSING, (running.toSnapshot() as TranscriptJobSnapshot.Active).status)
    }

    @Test
    fun failedStatusCarriesTheServerErrorCode() = runBlocking {
        val body = """
            {"jobId":"job_1","contentCode":"WBEP-CONTRACT-0001","status":"failed",
             "attemptCount":3,"errorCode":"audio_fetch_failed","updatedAt":3}
        """.trimIndent()

        val result = TranscriptResponseParser.parseJobStatusResponse(body, ::noContent)

        assertTrue("expected Failure, got $result", result is TranscriptFetchResult.Failure)
        // The server's own code must be preserved, not replaced with a generic one.
        assertEquals("audio_fetch_failed", (result as TranscriptFetchResult.Failure).errorCode)
    }

    @Test
    fun emptyTranscriptPayloadIsNotAnError() = runBlocking {
        // GET /v1/transcripts/{contentCode} answers 404 with no `transcript` key when the
        // caller has no grant: that is "no content yet", not a protocol error.
        val body = """{"jobId":"job_1","contentCode":"WBEP-CONTRACT-0001","status":"completed","updatedAt":4}"""

        val result = TranscriptResponseParser.parseJobStatusResponse(body, ::noContent) as TranscriptFetchResult.Ready

        assertNull(result.payload)
        assertEquals("WBEP-CONTRACT-0001", result.contentCode)
    }

    // --- errors --------------------------------------------------------------------------

    @Test
    fun jobNotFound404IsTerminalJobGone() = runBlocking {
        val failure = TranscriptResponseParser.parseErrorResponse(404, """{"error":"job_not_found"}""")

        // The canonical code, explicitly: a regression in the canonicalisation layer must
        // be named by this assertion rather than surfacing as a mysterious state mismatch.
        assertEquals(TranscriptGatewayErrors.NOT_FOUND, failure.errorCode)
        assertFalse("a not-found job must never be retried", failure.isRetryable)
        assertTrue("expected JobGone, got ${failure.toSnapshot()}", failure.toSnapshot() is TranscriptJobSnapshot.JobGone)
    }

    @Test
    fun everyNotFoundSpellingCanonicalisesToTheSameCode() = runBlocking {
        // The frozen 404 body says job_not_found, but no other spelling may leak through.
        val spellings = listOf("job_not_found", "not_found", "not_yours", "job_gone")
        spellings.forEach { spelling ->
            val failure = TranscriptResponseParser.parseErrorResponse(404, """{"error":"$spelling"}""")
            assertEquals(
                "the 404 body spelling '$spelling' must canonicalise to NOT_FOUND",
                TranscriptGatewayErrors.NOT_FOUND,
                failure.errorCode,
            )
            assertTrue(
                "'$spelling' must map to JobGone, got ${failure.toSnapshot()}",
                failure.toSnapshot() is TranscriptJobSnapshot.JobGone,
            )
            assertFalse("'$spelling' must not be retryable", failure.isRetryable)
        }
        // A bare 404 with no usable body must canonicalise too.
        assertEquals(
            TranscriptGatewayErrors.NOT_FOUND,
            TranscriptResponseParser.parseErrorResponse(404, "").errorCode,
        )
        assertEquals(
            TranscriptGatewayErrors.NOT_FOUND,
            TranscriptGatewayErrors.normalize("HTTP_410"),
        )
    }

    @Test
    fun job404WithNonJsonBodyStillBecomesTerminalNotFound() {
        val failure = TranscriptResponseParser.parseErrorResponse(404, "<html>not found</html>")

        assertEquals(TranscriptGatewayErrors.NOT_FOUND, failure.errorCode)
        assertFalse(failure.isRetryable)
        assertTrue(failure.toSnapshot() is TranscriptJobSnapshot.JobGone)
    }

    @Test
    fun unauthorizedStatusIsTerminalAndAsksForSignIn() {
        val failure = TranscriptResponseParser.parseErrorResponse(401, """{"error":"unauthorized"}""")

        assertEquals(TranscriptGatewayErrors.UNAUTHORIZED, failure.errorCode)
        assertEquals(TranscriptFailureKind.UNAUTHORIZED, TranscriptGatewayErrors.classify(failure.errorCode))
        assertFalse(failure.isRetryable)
    }

    @Test
    fun serverErrorIsRetryableAndKeepsTheJobInFlight() {
        val failure = TranscriptResponseParser.parseErrorResponse(503, """{"error":"server_error"}""")

        assertTrue("a 5xx must be retryable, got ${failure.errorCode}", failure.isRetryable)
        assertTrue(
            "a 5xx probe must not settle the job, got ${failure.toSnapshot()}",
            failure.toSnapshot() is TranscriptJobSnapshot.Active,
        )
    }

    @Test
    fun badRequestIsTerminalAndNotRetryable() {
        val failure = TranscriptResponseParser.parseErrorResponse(400, """{"error":"invalid_request"}""")

        assertFalse("a 400 must not offer retry, got ${failure.errorCode}", failure.isRetryable)
        assertEquals(TranscriptFailureKind.TERMINAL, TranscriptGatewayErrors.classify(failure.errorCode))
    }

    // --- server-provided polling cadence --------------------------------------------------

    @Test
    fun serverPollAfterBodyFieldBecomesThePollingCadence() = runBlocking {
        val body = """
            {"jobId":"job_1","contentCode":"WBEP-CONTRACT-0001","status":"running",
             "attemptCount":1,"updatedAt":5,"pollAfterMs":45000}
        """.trimIndent()

        val result = TranscriptResponseParser.parseJobStatusResponse(
            body,
            fetchContent = ::noContent,
            retryAfterMs = 9_000L,
        ) as TranscriptFetchResult.Processing

        assertEquals("the body field wins over the header", 45_000L, result.pollAfterMs)
    }

    @Test
    fun retryAfterHeaderBecomesThePollingCadence() = runBlocking {
        val body = """
            {"jobId":"job_1","contentCode":"WBEP-CONTRACT-0001","status":"queued","attemptCount":0,"updatedAt":6}
        """.trimIndent()

        val result = TranscriptResponseParser.parseJobStatusResponse(
            body,
            fetchContent = ::noContent,
            retryAfterMs = 30_000L,
        ) as TranscriptFetchResult.Processing

        assertEquals(30_000L, result.pollAfterMs)
    }

    @Test
    fun noServerHintMeansNoInventedCadence() = runBlocking {
        val body = """
            {"jobId":"job_1","contentCode":"WBEP-CONTRACT-0001","status":"running","attemptCount":1,"updatedAt":7}
        """.trimIndent()

        val result = TranscriptResponseParser.parseJobStatusResponse(body, fetchContent = ::noContent)
            as TranscriptFetchResult.Processing

        assertNull("the client must not invent a cadence the server did not send", result.pollAfterMs)
    }

    @Test
    fun retryAfterParsesSecondsAndHttpDates() {
        assertEquals(30_000L, TranscriptResponseParser.parseRetryAfterMs("30"))
        assertEquals(0L, TranscriptResponseParser.parseRetryAfterMs("0"))
        assertNull("a missing header means no cadence", TranscriptResponseParser.parseRetryAfterMs(null))
        assertNull("a non-numeric, non-date value is ignored", TranscriptResponseParser.parseRetryAfterMs("soon"))

        // An HTTP-date in the past must not produce a negative wait.
        val past = TranscriptResponseParser.parseRetryAfterMs("Wed, 21 Oct 2015 07:28:00 GMT")
        assertNotNull(past)
        assertTrue("a past HTTP-date must clamp to zero, got $past", past!! >= 0L)
    }

    @Test
    fun provenanceValuesAreLabelledAndUnknownOnesAreNotGuessed() {
        assertEquals("existing_access", TranscriptResponseParser.sourceLabel("existing_access"))
        assertEquals("shared_cache", TranscriptResponseParser.sourceLabel("shared_cache"))
        assertEquals("generated", TranscriptResponseParser.sourceLabel("generated"))
        // Unrecognized provenance is reported as unknown; nothing branches on it.
        assertEquals("unknown", TranscriptResponseParser.sourceLabel("brand_new_source"))
        assertEquals("unknown", TranscriptResponseParser.sourceLabel(""))
    }

    // --- resumable polling through the repository -----------------------------------------

    @Test
    fun pollJobUsesTheServerCadenceAndSettlesOnCompletion() = runBlocking {
        val providerCalls = AtomicInteger(0)
        val cache = FakeCache()
        val probes = AtomicInteger(0)
        val api = object : TranscriptGatewayApi {
            override suspend fun requestTranscript(request: EpisodeTranscriptRequest) =
                TranscriptFetchResult.Processing("WBEP-CONTRACT-0001", "job_contract_1", status = "queued")

            override suspend fun checkJobStatus(jobId: String): TranscriptFetchResult {
                return if (probes.incrementAndGet() < 2) {
                    TranscriptFetchResult.Processing(
                        contentCode = "WBEP-CONTRACT-0001",
                        jobId = jobId,
                        status = "running",
                        // Server-provided cadence, far shorter than the caller's default.
                        pollAfterMs = 25L,
                    )
                } else {
                    TranscriptFetchResult.Ready(
                        contentCode = "WBEP-CONTRACT-0001",
                        artifactId = "art_9",
                        source = "generated",
                        quota = TranscriptQuotaInfo(1, 1f, 1f),
                        payload = payload(),
                    )
                }
            }
        }
        val repo = DefaultTranscriptRepository(cache, api)

        val startedAtMs = System.currentTimeMillis()
        val snapshot = repo.pollJob(
            jobId = "job_contract_1",
            // Deliberately 200x the server cadence: if the server's hint were ignored, this
            // test would take 5 seconds instead of milliseconds. Elapsed time is the direct
            // proof that the server cadence won.
            delayMs = 5_000L,
            pollAfterMsProvider = { active ->
                providerCalls.incrementAndGet()
                // When the server supplied a cadence this hook must NOT be consulted, so
                // reaching here at all means the precedence rule broke. `throw` rather than
                // Assert.fail keeps the failure message AND satisfies the Long? return type,
                // because a throw expression has type Nothing.
                throw AssertionError(
                    "the caller's fallback cadence must not be consulted when the server " +
                        "sent one; got $active",
                )
            },
        )
        val elapsedMs = System.currentTimeMillis() - startedAtMs

        assertTrue("expected Ready, got $snapshot", snapshot is TranscriptJobSnapshot.Ready)
        assertEquals("art_9", (snapshot as TranscriptJobSnapshot.Ready).artifactId)
        assertEquals(
            "the server cadence must win, so the fallback hook must never be consulted",
            0,
            providerCalls.get(),
        )
        assertTrue(
            "the server cadence (25ms) must beat the caller default (5000ms); elapsed ${elapsedMs}ms",
            elapsedMs < 2_000L,
        )
        assertNotNull("completion must cache the payload", cache.get("WBEP-CONTRACT-0001"))
    }

    @Test
    fun withoutAServerCadenceTheCallerHookIsTheFallback() = runBlocking {
        // The other half of the precedence rule: no server hint -> the hook is the source.
        val hookValues = mutableListOf<Long?>()
        val probes = AtomicInteger(0)
        val api = object : TranscriptGatewayApi {
            override suspend fun requestTranscript(request: EpisodeTranscriptRequest) =
                TranscriptFetchResult.Processing("WBEP-CONTRACT-0001", "job_contract_1", status = "queued")

            override suspend fun checkJobStatus(jobId: String): TranscriptFetchResult {
                return if (probes.incrementAndGet() < 2) {
                    TranscriptFetchResult.Processing(
                        contentCode = "WBEP-CONTRACT-0001",
                        jobId = jobId,
                        status = "running",
                        // No pollAfterMs and no Retry-After: the server asked for nothing.
                        pollAfterMs = null,
                    )
                } else {
                    TranscriptFetchResult.Ready(
                        contentCode = "WBEP-CONTRACT-0001",
                        artifactId = "art_fallback",
                        source = "generated",
                        quota = TranscriptQuotaInfo(1, 1f, 1f),
                        payload = payload(),
                    )
                }
            }
        }
        val repo = DefaultTranscriptRepository(FakeCache(), api)

        val snapshot = repo.pollJob(
            jobId = "job_contract_1",
            delayMs = 5_000L,
            pollAfterMsProvider = { active ->
                hookValues.add((active as? TranscriptJobSnapshot.Active)?.pollAfterMs)
                // A short cadence so the test stays fast; this proves the hook is used.
                10L
            },
        )

        assertTrue("expected Ready, got $snapshot", snapshot is TranscriptJobSnapshot.Ready)
        assertTrue(
            "without a server hint the hook must be consulted, saw $hookValues",
            hookValues.isNotEmpty(),
        )
        assertTrue(
            "the hook must observe that the server sent no cadence, saw $hookValues",
            hookValues.all { it == null },
        )
    }

    @Test
    fun pollUntilReadyStopsOnTerminalFailureAfterOneProbe() = runBlocking {
        val probes = AtomicInteger(0)
        val api = object : TranscriptGatewayApi {
            override suspend fun requestTranscript(request: EpisodeTranscriptRequest) =
                TranscriptFetchResult.Processing("WBEP-CONTRACT-0001", "job_gone", status = "queued")

            override suspend fun checkJobStatus(jobId: String): TranscriptFetchResult {
                probes.incrementAndGet()
                return TranscriptFetchResult.Failure(
                    "job_not_found",
                    """{"error":"job_not_found"}""",
                    kind = TranscriptFailureKind.TERMINAL,
                )
            }
        }
        val repo = DefaultTranscriptRepository(FakeCache(), api)

        val result = repo.pollUntilReady("job_gone", maxAttempts = 10, delayMs = 1L)

        assertTrue("expected Failure, got $result", result is TranscriptFetchResult.Failure)
        assertEquals(
            "a terminal not-found job must never be retried (code=${TranscriptGatewayErrors.NOT_FOUND})",
            // The canonical constant, never a literal spelling: a literal either drifts when
            // canonicalisation changes or turns a refactor into a red test.
            TranscriptGatewayErrors.NOT_FOUND,
            (result as TranscriptFetchResult.Failure).errorCode,
        )
        assertTrue(
            "${result.errorCode} must be classified terminal",
            !result.isRetryable,
        )
        assertEquals("a terminal outcome must be probed exactly once", 1, probes.get())
    }

    @Test
    fun pollUntilReadyKeepsPollingWhileTheJobIsActive() = runBlocking {
        // A long job must not be abandoned by an artificial attempt ceiling.
        val cache = FakeCache()
        val probes = AtomicInteger(0)
        val api = object : TranscriptGatewayApi {
            override suspend fun requestTranscript(request: EpisodeTranscriptRequest) =
                TranscriptFetchResult.Processing("WBEP-CONTRACT-0001", "job_contract_1", status = "queued")

            override suspend fun checkJobStatus(jobId: String): TranscriptFetchResult {
                return if (probes.incrementAndGet() < 4) {
                    TranscriptFetchResult.Processing(
                        contentCode = "WBEP-CONTRACT-0001",
                        jobId = jobId,
                        status = "running",
                        pollAfterMs = 1L,
                    )
                } else {
                    TranscriptFetchResult.Ready(
                        contentCode = "WBEP-CONTRACT-0001",
                        artifactId = "art_long",
                        source = "generated",
                        quota = TranscriptQuotaInfo(1, 1f, 1f),
                        payload = payload(),
                    )
                }
            }
        }
        val repo = DefaultTranscriptRepository(cache, api)

        val result = repo.pollUntilReady("job_contract_1", maxAttempts = 10, delayMs = 1L)

        assertTrue("expected Ready after several probes, got $result", result is TranscriptFetchResult.Ready)
        assertEquals("all probes must have happened", 4, probes.get())
        assertNotNull(cache.get("WBEP-CONTRACT-0001"))
    }
}
