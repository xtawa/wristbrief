package ink.underflo.wristbrief.mobile.artifacts

import ink.underflo.wristbrief.mobile.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone must show QUEUED, PROCESSING, FAILED+RETRY and COMPLETED as genuinely
 * distinct, real states. These tests pin that mapping, the retryable/terminal split, and
 * the absence of any inferred or word-level claim.
 */
class TranscriptStateMappingTest {

    private fun job(
        status: TranscriptStatus = TranscriptStatus.UNKNOWN,
        jobId: String = "job_1",
        contentCode: String? = null,
        errorCode: String? = null,
        retryable: Boolean = true,
        source: String? = null,
        title: String? = "Episode",
        durationMs: Long? = null,
    ) = TranscriptJob(
        audioUrl = "https://cdn.example.com/ep1.mp3",
        jobId = jobId,
        contentCode = contentCode,
        status = status,
        title = title,
        durationMs = durationMs,
        source = source,
        errorCode = errorCode,
        retryable = retryable,
        createdAtEpochMs = 1_000L,
        updatedAtEpochMs = 1_000L,
    )

    private fun payload(contentCode: String = "WBEP-TEST") = TranscriptPayload(
        contentCode = contentCode,
        language = "en",
        durationMs = 240_000L,
        fullText = "Clip one. Clip two.",
        segments = listOf(
            TranscriptSegment(id = 1, startMs = 0L, endMs = 240_000L, text = "Clip one."),
            TranscriptSegment(id = 2, startMs = 240_000L, endMs = 480_000L, text = "Clip two."),
        ),
    )

    // --- distinct states -----------------------------------------------------------------

    @Test
    fun noJobYetIsCheckingNotQueued() {
        val state = TranscriptJobPresentation.from(job = null, payload = null, cachedPayload = null)
        assertTrue(state is TranscriptUiState.Checking)
    }

    @Test
    fun queuedAndProcessingAreDifferentStatesAndDifferentLabels() {
        val queued = TranscriptJobPresentation.from(
            job = job(status = TranscriptStatus.QUEUED),
            payload = null,
            cachedPayload = null,
        )
        val processing = TranscriptJobPresentation.from(
            job = job(status = TranscriptStatus.PROCESSING),
            payload = null,
            cachedPayload = null,
        )

        assertTrue(queued is TranscriptUiState.Queued)
        assertTrue(processing is TranscriptUiState.Processing)
        // The two non-terminal states must not collapse into one presentation.
        assertNotEquals(queued::class, processing::class)
    }

    @Test
    fun serverWordsMapToDistinctNonTerminalStatuses() {
        assertEquals(TranscriptStatus.QUEUED, TranscriptStatus.fromWire("queued"))
        assertEquals(TranscriptStatus.PROCESSING, TranscriptStatus.fromWire("running"))
        assertEquals(TranscriptStatus.PROCESSING, TranscriptStatus.fromWire("processing"))
        assertEquals(TranscriptStatus.COMPLETED, TranscriptStatus.fromWire("completed"))
        assertEquals(TranscriptStatus.FAILED, TranscriptStatus.fromWire("failed"))
        // Unknown words are unknown; they are never silently reported as progress.
        assertEquals(TranscriptStatus.UNKNOWN, TranscriptStatus.fromWire("something_new"))
        assertEquals(TranscriptStatus.UNKNOWN, TranscriptStatus.fromWire(null))
    }

    @Test
    fun persistedJobWithoutAnObservedStatusIsCheckingNotNull() {
        // Straight after a restart the app knows the job id but has not seen a status.
        val state = TranscriptJobPresentation.from(
            job = job(status = TranscriptStatus.UNKNOWN, jobId = "job_persisted"),
            payload = null,
            cachedPayload = null,
        )
        assertTrue("expected Checking, got $state", state is TranscriptUiState.Checking)
    }

    @Test
    fun pendingJobWithNoServerIdShowsQueued() {
        val state = TranscriptJobPresentation.from(
            job = job(status = TranscriptStatus.UNKNOWN, jobId = "", contentCode = "WBEP-1"),
            payload = null,
            cachedPayload = null,
        )
        assertTrue("expected Queued, got $state", state is TranscriptUiState.Queued)
    }

    @Test
    fun completedJobCarriesRealDurationAndSegmentData() {
        val state = TranscriptJobPresentation.from(
            job = job(status = TranscriptStatus.COMPLETED, contentCode = "WBEP-DONE", source = "generated"),
            payload = payload("WBEP-DONE"),
            cachedPayload = null,
        )
        assertTrue(state is TranscriptUiState.Completed)
        val completed = state as TranscriptUiState.Completed
        assertEquals(240_000L, completed.durationMs)
        assertEquals(2, completed.payload?.segments?.size)
        assertEquals(R.string.transcript_source_generated, completed.sourceLabelRes)
    }

    @Test
    fun completedJobStillOpensWhenTheJobRecordWasNotUpdated() {
        // Cached artifact + non-completed job record must still show the transcript.
        val state = TranscriptJobPresentation.from(
            job = job(status = TranscriptStatus.PROCESSING, contentCode = "WBEP-CACHED"),
            payload = payload("WBEP-CACHED"),
            cachedPayload = null,
        )
        assertTrue(state is TranscriptUiState.Completed)
    }

    // --- failure + retry -----------------------------------------------------------------

    @Test
    fun failedJobShowsFailureWithRetryWhenRetryingCanHelp() {
        val state = TranscriptJobPresentation.from(
            job = job(status = TranscriptStatus.FAILED, errorCode = "HTTP_502", retryable = true),
            payload = null,
            cachedPayload = null,
        )
        assertTrue(state is TranscriptUiState.Failed)
        val failed = state as TranscriptUiState.Failed
        assertTrue(failed.retryable)
        assertEquals(R.string.transcript_error_generic, failed.messageRes)
    }

    @Test
    fun terminalFailureNeverOffersRetry() {
        val gone = TranscriptJobPresentation.from(
            job = job(status = TranscriptStatus.FAILED, errorCode = "not_found", retryable = true),
            payload = null,
            cachedPayload = null,
        ) as TranscriptUiState.Failed
        assertFalse("a gone job must not offer retry", gone.retryable)
        assertEquals(R.string.transcript_error_job_gone, gone.messageRes)

        val quota = TranscriptJobPresentation.from(
            job = job(status = TranscriptStatus.FAILED, errorCode = "managed_ai_quota_unavailable"),
            payload = null,
            cachedPayload = null,
        ) as TranscriptUiState.Failed
        assertFalse(quota.retryable)
        assertEquals(R.string.transcript_error_quota, quota.messageRes)

        val unauthorized = TranscriptJobPresentation.from(
            job = job(status = TranscriptStatus.FAILED, errorCode = "HTTP_401"),
            payload = null,
            cachedPayload = null,
        ) as TranscriptUiState.Failed
        assertFalse(unauthorized.retryable)
        assertEquals(R.string.transcript_error_sign_in, unauthorized.messageRes)
    }

    @Test
    fun transportAndServerFailuresAreRetryable() {
        assertTrue(TranscriptGatewayErrors.isRetryable("NETWORK_ERROR"))
        assertTrue(TranscriptGatewayErrors.isRetryable("HTTP_500"))
        assertTrue(TranscriptGatewayErrors.isRetryable("HTTP_503"))
        assertTrue(TranscriptGatewayErrors.isRetryable("HTTP_429"))
        assertTrue(TranscriptGatewayErrors.isRetryable("HTTP_408"))
        assertFalse(TranscriptGatewayErrors.isRetryable("HTTP_400"))
        assertFalse(TranscriptGatewayErrors.isRetryable("HTTP_404"))
        assertFalse(TranscriptGatewayErrors.isRetryable("HTTP_410"))
        assertFalse(TranscriptGatewayErrors.isRetryable("HTTP_401"))
        assertFalse(TranscriptGatewayErrors.isRetryable("not_found"))
        assertFalse(TranscriptGatewayErrors.isRetryable("managed_ai_quota_unavailable"))
        assertFalse(TranscriptGatewayErrors.isRetryable(TranscriptGatewayErrors.INVALID_GATEWAY_URL))
    }

    @Test
    fun httpFailuresNormalizeToStableCodes() {
        assertEquals(TranscriptGatewayErrors.UNAUTHORIZED, TranscriptGatewayErrors.normalizeFromFailure("HTTP_401"))
        assertEquals(TranscriptGatewayErrors.NOT_FOUND, TranscriptGatewayErrors.normalizeFromFailure("HTTP_404"))
        assertEquals(TranscriptGatewayErrors.QUOTA_UNAVAILABLE, TranscriptGatewayErrors.normalizeFromFailure("HTTP_429"))
        assertEquals(TranscriptGatewayErrors.NETWORK_ERROR, TranscriptGatewayErrors.normalizeFromFailure("NETWORK_ERROR"))
        assertEquals("HTTP_500", TranscriptGatewayErrors.normalizeFromFailure("HTTP_500"))
    }

    /** A 404 on a job probe is a real "not yours / gone" outcome, never transient. */
    @Test
    fun jobProbe404IsTerminalJobGoneNotTransient() {
        val snapshot = TranscriptFetchResult.Failure("HTTP_404", "{\"error\":\"job_not_found\"}").toSnapshot()
        assertTrue("expected JobGone, got $snapshot", snapshot is TranscriptJobSnapshot.JobGone)
    }

    @Test
    fun jobProbeTransportErrorKeepsTheJobInFlightInsteadOfFailingIt() {
        // A transport/5xx failure says nothing about the job itself, so it must NOT be
        // recorded as a terminal job failure: the job stays active and keeps polling.
        val snapshot = TranscriptFetchResult.Failure("NETWORK_ERROR", "timeout").toSnapshot()
        assertTrue("a transport error must not settle the job, got $snapshot", snapshot is TranscriptJobSnapshot.Active)
        assertEquals(TranscriptGatewayErrors.NETWORK_ERROR, (snapshot as TranscriptJobSnapshot.Active).message)

        val serverError = TranscriptFetchResult.Failure("HTTP_503", "unavailable").toSnapshot()
        assertTrue(
            "a 5xx must not settle the job, got $serverError",
            serverError is TranscriptJobSnapshot.Active,
        )
    }

    @Test
    fun terminalJobStatesAreNotRetryableAndRetryableOnesAre() {
        assertEquals(TranscriptFailureKind.TERMINAL, TranscriptGatewayErrors.classify("HTTP_400"))
        assertEquals(TranscriptFailureKind.TERMINAL, TranscriptGatewayErrors.classify("not_found"))
        assertEquals(TranscriptFailureKind.TERMINAL, TranscriptGatewayErrors.classify("job_not_found"))
        assertEquals(TranscriptFailureKind.UNAUTHORIZED, TranscriptGatewayErrors.classify("HTTP_401"))
        assertEquals(TranscriptFailureKind.RETRYABLE, TranscriptGatewayErrors.classify("HTTP_503"))
        assertEquals(TranscriptFailureKind.RETRYABLE, TranscriptGatewayErrors.classify("HTTP_429"))
        assertEquals(TranscriptFailureKind.RETRYABLE, TranscriptGatewayErrors.classify("NETWORK_ERROR"))

        // The classification must be readable from a Failure without the caller guessing.
        assertFalse(TranscriptFetchResult.Failure("HTTP_404", "").isRetryable)
        assertTrue(TranscriptFetchResult.Failure("HTTP_500", "").isRetryable)
        assertTrue(
            TranscriptFetchResult.Failure(
                "mystery",
                "",
                kind = TranscriptFailureKind.RETRYABLE,
            ).isRetryable,
        )
        assertFalse(
            TranscriptFetchResult.Failure(
                "HTTP_500",
                "",
                kind = TranscriptFailureKind.TERMINAL,
            ).isRetryable,
        )
    }

    @Test
    fun processingResultCarriesTheServersOwnWord() {
        val snapshot = TranscriptFetchResult.Processing(
            contentCode = "WBEP-X",
            jobId = "job_x",
            status = "running",
        ).toSnapshot()
        assertTrue(snapshot is TranscriptJobSnapshot.Active)
        assertEquals(TranscriptStatus.PROCESSING, (snapshot as TranscriptJobSnapshot.Active).status)
    }

    // --- transcript provenance -----------------------------------------------------------

    @Test
    fun sourceLabelsCoverTheFrozenSetAndStayNeutralForUnknownValues() {
        assertEquals(R.string.transcript_source_generated, transcriptSourceLabelRes("generated"))
        assertEquals(R.string.transcript_source_shared_cache, transcriptSourceLabelRes("shared_cache"))
        assertEquals(R.string.transcript_source_existing_access, transcriptSourceLabelRes("existing_access"))
        assertEquals(R.string.transcript_source_unknown, transcriptSourceLabelRes("something_new"))
        assertEquals(R.string.transcript_source_unknown, transcriptSourceLabelRes(""))
    }

    // --- segment-level truthfulness ------------------------------------------------------

    @Test
    fun completedSegmentsExposeClipLevelRangesOnly() {
        val state = TranscriptJobPresentation.from(
            job = job(status = TranscriptStatus.COMPLETED, contentCode = "WBEP-DONE"),
            payload = payload("WBEP-DONE"),
            cachedPayload = null,
        ) as TranscriptUiState.Completed
        val segments = state.payload!!.segments
        // Each segment spans a whole clip (4 minutes), so the UI can only localize a passage.
        assertEquals(0L, segments[0].startMs)
        assertEquals(TranscriptGranularity.CLIP_DURATION_MS, segments[0].endMs)
        assertEquals(TranscriptGranularity.CLIP_DURATION_MS, segments[1].startMs)
        assertEquals(2L * TranscriptGranularity.CLIP_DURATION_MS, segments[1].endMs)
        assertTrue(
            "the shipped granularity must be clip-level, got ${segments[0]}",
            TranscriptGranularity.isClipLevel(segments[0]),
        )
    }

    @Test
    fun wordLevelAndDiarizationAreDeclaredUnsupported() {
        // The product must not offer what the pipeline cannot produce.
        assertFalse(
            "word-level timestamps do not exist, so nothing may advertise them",
            TranscriptGranularity.SUPPORTS_WORD_LEVEL,
        )
        assertFalse(
            "there is no diarization, so no speaker affordance may be promised",
            TranscriptGranularity.SUPPORTS_SPEAKER_DIARIZATION,
        )
        // A word-shaped segment is NOT clip level: the helper must be able to tell them apart.
        val wordShaped = TranscriptSegment(id = 9, startMs = 0L, endMs = 400L, text = "hi")
        assertFalse("a 400 ms segment is not clip level", TranscriptGranularity.isClipLevel(wordShaped))
    }
}
