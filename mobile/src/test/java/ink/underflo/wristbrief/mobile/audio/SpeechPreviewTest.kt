package ink.underflo.wristbrief.mobile.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Client-side contract for the existing `POST /v1/audio/speech` endpoint: the
 * 2000-character bound, the documented error codes, and the play/pause state machine.
 * No network and no emulator.
 */
class SpeechPreviewTest {

    // --- input bound ---------------------------------------------------------------------

    @Test
    fun serverBoundIsMirroredExactly() {
        assertEquals(2000, SpeechPreviewRequest.MAX_CHARACTERS)
    }

    @Test
    fun textAtTheBoundIsAcceptedAndOnePastItIsRejected() {
        val atLimit = SpeechPreviewRequest("a".repeat(2000))
        val overLimit = SpeechPreviewRequest("a".repeat(2001))

        assertTrue(atLimit.isWithinLimit)
        assertTrue(atLimit.isSendable)
        assertFalse(overLimit.isWithinLimit)
        assertFalse(overLimit.isSendable)
        assertEquals(-1, overLimit.remainingCharacters)
    }

    @Test
    fun countingMatchesTheServerWhichTrimsFirst() {
        val padded = SpeechPreviewRequest("   hello   ")
        assertEquals(5, padded.characterCount)
        assertEquals("hello", padded.trimmedText)
    }

    @Test
    fun blankTextIsNotSendableAndNeverSilentlyTruncated() {
        assertFalse(SpeechPreviewRequest("").isSendable)
        assertFalse(SpeechPreviewRequest("   \n\t ").isSendable)
        // Over-limit text is reported as too long; the request keeps the caller's text
        // verbatim so the UI can ask the user to shorten it themselves.
        val overLimit = SpeechPreviewRequest("b".repeat(2500))
        assertEquals(2500, overLimit.characterCount)
        assertEquals(2500, overLimit.text.length)
    }

    // --- error contract ------------------------------------------------------------------

    @Test
    fun bodyErrorCodesMapToThemselves() {
        assertEquals(SpeechPreviewErrors.UNAUTHORIZED, SpeechPreviewErrors.normalize("unauthorized", 401))
        assertEquals(SpeechPreviewErrors.REQUEST_TOO_LARGE, SpeechPreviewErrors.normalize("request_too_large", 413))
        assertEquals(SpeechPreviewErrors.INVALID_JSON, SpeechPreviewErrors.normalize("invalid_json", 400))
        assertEquals(SpeechPreviewErrors.INVALID_TEXT, SpeechPreviewErrors.normalize("invalid_text", 400))
        assertEquals(
            SpeechPreviewErrors.QUOTA_UNAVAILABLE,
            SpeechPreviewErrors.normalize("managed_ai_quota_unavailable", 429),
        )
        assertEquals(
            SpeechPreviewErrors.PROVIDER_UNAVAILABLE,
            SpeechPreviewErrors.normalize("tts_provider_unavailable", 503),
        )
        assertEquals(SpeechPreviewErrors.GENERATION_FAILED, SpeechPreviewErrors.normalize("tts_generation_failed", 502))
        assertEquals(SpeechPreviewErrors.METHOD_NOT_ALLOWED, SpeechPreviewErrors.normalize("method_not_allowed", 405))
    }

    @Test
    fun bareHttpStatusesMapToTheDocumentedCodes() {
        assertEquals(SpeechPreviewErrors.UNAUTHORIZED, SpeechPreviewErrors.normalize(null, 401))
        assertEquals(SpeechPreviewErrors.REQUEST_TOO_LARGE, SpeechPreviewErrors.normalize(null, 413))
        assertEquals(SpeechPreviewErrors.QUOTA_UNAVAILABLE, SpeechPreviewErrors.normalize(null, 429))
        assertEquals(SpeechPreviewErrors.PROVIDER_UNAVAILABLE, SpeechPreviewErrors.normalize(null, 503))
        assertEquals(SpeechPreviewErrors.GENERATION_FAILED, SpeechPreviewErrors.normalize(null, 502))
        assertEquals(SpeechPreviewErrors.METHOD_NOT_ALLOWED, SpeechPreviewErrors.normalize(null, 405))
        assertEquals(SpeechPreviewErrors.NETWORK_ERROR, SpeechPreviewErrors.normalize(null, null))
    }

    @Test
    fun retryIsOfferedOnlyForTransientFailures() {
        assertTrue(SpeechPreviewErrors.isRetryable(SpeechPreviewErrors.NETWORK_ERROR))
        assertTrue(SpeechPreviewErrors.isRetryable("tts_generation_failed"))
        assertFalse("missing provider will not fix itself", SpeechPreviewErrors.isRetryable("tts_provider_unavailable"))
        assertFalse(SpeechPreviewErrors.isRetryable("managed_ai_quota_unavailable"))
        assertFalse(SpeechPreviewErrors.isRetryable("unauthorized"))
        assertFalse(SpeechPreviewErrors.isRetryable("request_too_large"))
        assertFalse(SpeechPreviewErrors.isRetryable("invalid_text"))
        assertFalse(SpeechPreviewErrors.isRetryable("method_not_allowed"))
    }

    @Test
    fun everyFailureGetsAReasonTheUiCanLocalize() {
        fun reason(code: String, status: Int?) = SpeechPreviewIssue.from(
            SpeechPreviewResult.Failure(code = code, httpStatus = status, message = null),
            requestWasSendable = true,
        ).reason

        assertEquals(SpeechPreviewIssueReason.NETWORK, reason("network_error", null))
        assertEquals(SpeechPreviewIssueReason.UNAUTHORIZED, reason("unauthorized", 401))
        assertEquals(SpeechPreviewIssueReason.QUOTA_UNAVAILABLE, reason("managed_ai_quota_unavailable", 429))
        assertEquals(SpeechPreviewIssueReason.TEXT_TOO_LONG, reason("request_too_large", 413))
        assertEquals(SpeechPreviewIssueReason.TEXT_INVALID, reason("invalid_text", 400))
        assertEquals(SpeechPreviewIssueReason.PROVIDER_UNAVAILABLE, reason("tts_provider_unavailable", 503))
        assertEquals(SpeechPreviewIssueReason.GENERATION_FAILED, reason("tts_generation_failed", 502))
        assertEquals(SpeechPreviewIssueReason.SERVER_GONE, reason("method_not_allowed", 405))
    }

    // --- play/pause state machine --------------------------------------------------------

    private fun issue(type: Int) = SpeechPreviewIssue(
        reason = SpeechPreviewIssueReason.GENERATION_FAILED,
        errorCode = "playback_error_$type",
        canRetry = true,
    )

    @Test
    fun openThenStartedShowsPlayingAndPauseReturnsToReady() {
        var state = SpeechPreviewState()
        state = SpeechPlaybackReducer.reduce(state, SpeechPlayerEvent.Opened("speech_1"), ::issue)
        assertEquals(SpeechPlaybackState.PREPARING, state.playback)
        assertFalse(state.isPlayable)

        state = SpeechPlaybackReducer.reduce(state, SpeechPlayerEvent.Prepared(4_000L), ::issue)
        assertEquals(SpeechPlaybackState.READY, state.playback)
        assertTrue(state.isPlayable)

        state = SpeechPlaybackReducer.reduce(state, SpeechPlayerEvent.Started, ::issue)
        assertEquals(SpeechPlaybackState.PLAYING, state.playback)

        state = SpeechPlaybackReducer.reduce(state, SpeechPlayerEvent.Paused, ::issue)
        assertEquals(SpeechPlaybackState.READY, state.playback)
        assertTrue(state.isPlayable)
    }

    @Test
    fun endedPreviewStaysReplayable() {
        var state = SpeechPreviewState(playback = SpeechPlaybackState.PLAYING, mediaId = "speech_1")
        state = SpeechPlaybackReducer.reduce(state, SpeechPlayerEvent.Ended, ::issue)
        assertEquals(SpeechPlaybackState.ENDED, state.playback)
        assertTrue("a finished sample must be replayable", state.isPlayable)
    }

    @Test
    fun playbackFailureSurfacesAnErrorAndNeverLooksLikeSuccess() {
        var state = SpeechPreviewState(playback = SpeechPlaybackState.PREPARING, mediaId = "speech_1")
        state = SpeechPlaybackReducer.reduce(state, SpeechPlayerEvent.Failed(2001), ::issue)
        assertEquals(SpeechPlaybackState.ERROR, state.playback)
        assertNotNull(state.issue)
        assertFalse(state.isPlayable)
    }

    @Test
    fun releaseResetsEverything() {
        val state = SpeechPlaybackReducer.reduce(
            SpeechPreviewState(playback = SpeechPlaybackState.PLAYING, mediaId = "speech_1"),
            SpeechPlayerEvent.Released,
            ::issue,
        )
        assertEquals(SpeechPreviewState(), state)
        assertNull(state.mediaId)
        assertFalse(state.isPlayable)
    }

    // --- controller: disclosure, loading, retry -----------------------------------------

    private class FakePlayer : SpeechPreviewPlayer {
        override var eventListener: ((SpeechPlayerEvent) -> Unit)? = null
        var plays = 0
        var pauses = 0
        var stops = 0
        var releases = 0
        var lastBytes: ByteArray? = null

        override fun play(audio: ByteArray, mimeType: String, mediaId: String) {
            plays++
            lastBytes = audio
            eventListener?.invoke(SpeechPlayerEvent.Opened(mediaId))
            eventListener?.invoke(SpeechPlayerEvent.Prepared(3_000L))
            eventListener?.invoke(SpeechPlayerEvent.Started)
        }

        override fun play() {
            plays++
            eventListener?.invoke(SpeechPlayerEvent.Started)
        }

        override fun pause() {
            pauses++
            eventListener?.invoke(SpeechPlayerEvent.Paused)
        }

        override fun stop() {
            stops++
            eventListener?.invoke(SpeechPlayerEvent.Released)
        }

        override fun release() {
            releases++
        }
    }

    private class FakeApi(var result: SpeechPreviewResult) : SpeechPreviewApi {
        var calls = 0
        override suspend fun generate(request: SpeechPreviewRequest): SpeechPreviewResult {
            calls++
            return result
        }
    }

    private fun controller(
        api: SpeechPreviewApi,
        player: SpeechPreviewPlayer,
        before: () -> Unit = {},
    ): SpeechPreviewController = SpeechPreviewController(
        api = api,
        scope = CoroutineScope(Dispatchers.Unconfined),
        player = player,
        onBeforePlay = before,
    )

    @Test
    fun successPathShowsPlayingAndAlwaysDisclosesTheSyntheticVoice() = runBlocking {
        var yielded = false
        val player = FakePlayer()
        val api = FakeApi(SpeechPreviewResult.Success(byteArrayOf(1, 2, 3), "audio/mpeg"))
        val subject = controller(api, player) { yielded = true }

        subject.playOrLoad(SpeechPreviewRequest("Hello there"))

        assertTrue("podcast playback must yield the audio focus first", yielded)
        assertEquals("the endpoint must be called exactly once", 1, api.calls)
        assertEquals("the audio must be handed to the player exactly once", 1, player.plays)
        assertTrue(
            "after a successful play the button must show Pause; state=${subject.state.value}",
            subject.state.value.isPlaying,
        )
        assertTrue(
            "the AI-voice disclosure must be required for anything playable",
            subject.state.value.mustDiscloseSyntheticVoice,
        )
        assertNull("a successful play must not carry an error", subject.state.value.issue)
    }

    @Test
    fun playThenPauseThenReplayDoesNotRequestAudioAgain() = runBlocking {
        val player = FakePlayer()
        val api = FakeApi(SpeechPreviewResult.Success(byteArrayOf(9), "audio/mpeg"))
        val subject = controller(api, player)

        subject.playOrLoad(SpeechPreviewRequest("Sample"))
        assertTrue("the sample must be playing before it can be paused", subject.state.value.isPlaying)

        subject.togglePlay() // pause
        assertEquals("pause must reach the player once; state=${subject.state.value}", 1, player.pauses)
        assertFalse("a paused preview must not report playing", subject.state.value.isPlaying)

        subject.togglePlay() // resume
        assertEquals("resume must reach the player again", 2, player.plays)
        assertTrue("resuming must report playing", subject.state.value.isPlaying)
        assertEquals("audio must not be regenerated for replay", 1, api.calls)
    }

    @Test
    fun failedGenerationNeverLooksLikeSuccessAndRetryReRequests() = runBlocking {
        val player = FakePlayer()
        val api = FakeApi(
            SpeechPreviewResult.Failure("tts_generation_failed", 502, "provider error"),
        )
        val subject = controller(api, player)

        subject.playOrLoad(SpeechPreviewRequest("Sample"))

        assertFalse("a failed generation must never look like it is playing", subject.state.value.isPlaying)
        assertFalse("a failed generation must not offer playback", subject.state.value.canPlay)
        assertEquals("nothing may be played after a failed generation", 0, player.plays)
        val issue = subject.state.value.issue
        assertNotNull("a 502 failure must surface an issue, state=${subject.state.value}", issue)
        assertEquals(SpeechPreviewIssueReason.GENERATION_FAILED, issue!!.reason)
        assertTrue("a 502 generation failure is retryable", issue.canRetry)

        // Retry actually re-requests instead of replaying the failure.
        api.result = SpeechPreviewResult.Success(byteArrayOf(7), "audio/mpeg")
        subject.retry(SpeechPreviewRequest("Sample"))
        assertEquals("retry must re-request from the endpoint", 2, api.calls)
        assertTrue("retry must play the newly generated audio; state=${subject.state.value}", subject.state.value.isPlaying)
        assertNull("retry must clear the previous error", subject.state.value.issue)
    }

    @Test
    fun overLimitTextIsRejectedLocallyWithoutCallingTheEndpoint() = runBlocking {
        val player = FakePlayer()
        val api = FakeApi(SpeechPreviewResult.Success(byteArrayOf(1), "audio/mpeg"))
        val subject = controller(api, player)

        subject.playOrLoad(SpeechPreviewRequest("x".repeat(2001)))

        assertEquals("an over-limit sample must not be sent", 0, api.calls)
        assertEquals("an over-limit sample must not be played", 0, player.plays)
        assertEquals(
            "over-limit text must be reported as invalid input, state=${subject.state.value}",
            SpeechPreviewIssueReason.TEXT_INVALID,
            subject.state.value.issue?.reason,
        )
    }

    @Test
    fun quotaExhaustionIsReportedAndOffersNoRetry() = runBlocking {
        val player = FakePlayer()
        val api = FakeApi(
            SpeechPreviewResult.Failure("managed_ai_quota_unavailable", 429, null),
        )
        val subject = controller(api, player)

        subject.playOrLoad(SpeechPreviewRequest("Sample"))

        val issue = subject.state.value.issue
        assertEquals(
            "an exhausted quota must be reported as such, state=${subject.state.value}",
            SpeechPreviewIssueReason.QUOTA_UNAVAILABLE,
            issue?.reason,
        )
        assertFalse("an exhausted quota must not offer retry", issue!!.canRetry)
    }

    @Test
    fun stopClearsThePreviewSoNothingKeepsPlayingOnAnotherScreen() = runBlocking {
        val player = FakePlayer()
        val api = FakeApi(SpeechPreviewResult.Success(byteArrayOf(1), "audio/mpeg"))
        val subject = controller(api, player)

        subject.playOrLoad(SpeechPreviewRequest("Sample"))
        assertTrue("a generated sample must offer a stop control; state=${subject.state.value}", subject.state.value.canStop)

        subject.stop()

        assertEquals("stop must reach the player", 1, player.stops)
        assertFalse("stop must clear playing", subject.state.value.isPlaying)
        assertFalse("stop must clear the playable preview", subject.state.value.canPlay)
        assertFalse("stop must clear the stop control", subject.state.value.canStop)
        assertNull("stop must clear any error", subject.state.value.issue)
    }
}
