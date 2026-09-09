package ink.underflo.wristbrief.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneLongSummaryPresentationTest {
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
    fun invalidResponseBecomesSafeErrorState() {
        val state = phoneLongSummaryUiState("{\"structured\":{\"schemaVersion\":\"1\"}}")

        assertEquals(
            PhoneLongSummaryUiState.Error("Long summary response was invalid or unsupported."),
            state,
        )
    }
}
