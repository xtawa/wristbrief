package ink.underflo.wristbrief.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneLongSummaryPresentationTest {
    @Test
    fun missingAuthenticatedResponseUsesPendingState() {
        assertEquals(
            PhoneLongSummaryUiState.AwaitingAuthenticatedGateway,
            phoneLongSummaryUiState(null),
        )
        assertEquals(
            PhoneLongSummaryUiState.AwaitingAuthenticatedGateway,
            phoneLongSummaryUiState("   "),
        )
    }

    @Test
    fun mapsValidGatewayResponseToReadyState() {
        val raw = """
            {
              "model": "gemini-test",
              "structured": {
                "schemaVersion": "2",
                "promptVersion": "2",
                "long": "A comfortable phone-length summary.",
                "sourceLanguage": "en",
                "outputLanguage": "zh-CN"
              }
            }
        """.trimIndent()

        val state = phoneLongSummaryUiState(raw)

        assertTrue(state is PhoneLongSummaryUiState.Ready)
        state as PhoneLongSummaryUiState.Ready
        assertEquals("A comfortable phone-length summary.", state.text)
        assertEquals("en → zh-CN", state.languageLabel)
        assertEquals("gemini-test", state.modelLabel)
    }

    @Test
    fun readyStateCanBeBuiltFromValidatedClientResult() {
        val state = phoneLongSummaryReadyState(
            PhoneLongSummary(
                text = "Validated client result",
                sourceLanguage = "en",
                outputLanguage = "zh-CN",
                model = "openrouter-test",
            ),
        )

        assertEquals(
            PhoneLongSummaryUiState.Ready(
                text = "Validated client result",
                languageLabel = "en → zh-CN",
                modelLabel = "openrouter-test",
            ),
            state,
        )
    }

    @Test
    fun sameLanguageUsesCompactLabelAndBlankModelIsHidden() {
        val raw = """
            {
              "model": "",
              "structured": {
                "schemaVersion": "2",
                "promptVersion": "2",
                "long": "Summary",
                "sourceLanguage": "en",
                "outputLanguage": "en"
              }
            }
        """.trimIndent()

        val state = phoneLongSummaryUiState(raw) as PhoneLongSummaryUiState.Ready

        assertEquals("en", state.languageLabel)
        assertNull(state.modelLabel)
    }

    @Test
    fun requestFailuresUseSafeUserFacingMessages() {
        assertEquals(
            PhoneLongSummaryUiState.Error("Gateway authentication was rejected. Check the access token and try again."),
            phoneLongSummaryFailureState(PhoneSummaryFailure.Unauthorized),
        )
        assertEquals(
            PhoneLongSummaryUiState.Error("The selected AI provider rejected this BYOK key. Check or replace the provider key and try again."),
            phoneLongSummaryFailureState(PhoneSummaryFailure.ByokProviderUnauthorized),
        )
        assertEquals(
            PhoneLongSummaryUiState.Error("Managed AI quota is currently exhausted."),
            phoneLongSummaryFailureState(PhoneSummaryFailure.Quota),
        )
        assertEquals(
            PhoneLongSummaryUiState.Error("The configured AI provider is temporarily unavailable."),
            phoneLongSummaryFailureState(PhoneSummaryFailure.ProviderUnavailable),
        )
    }

    @Test
    fun invalidResponseBecomesSafeErrorState() {
        val state = phoneLongSummaryUiState("{\"structured\":{\"schemaVersion\":\"1\"}}")

        assertEquals(
            PhoneLongSummaryUiState.Error("Long summary response was invalid or unsupported."),
            state,
        )
    }
}
