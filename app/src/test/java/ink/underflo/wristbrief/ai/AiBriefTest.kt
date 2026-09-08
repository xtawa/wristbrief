package ink.underflo.wristbrief.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiBriefTest {
    @Test
    fun parseAiBrief_readsStructuredGatewayPayload() {
        val brief = parseAiBrief(
            """{
              "summary":"Long compatibility summary",
              "model":"fake-model",
              "structured":{
                "tiny":"Tiny glance",
                "brief":"A concise structured brief.",
                "bullets":["Fact one","Fact two"],
                "topics":["Wear OS","RSS"],
                "sourceLanguage":"en",
                "outputLanguage":"en",
                "schemaVersion":"1",
                "promptVersion":"1"
              }
            }""".trimIndent()
        )

        assertEquals("Tiny glance", brief.tiny)
        assertEquals("A concise structured brief.", brief.brief)
        assertEquals(listOf("Fact one", "Fact two"), brief.bullets)
        assertEquals(listOf("Wear OS", "RSS"), brief.topics)
        assertEquals("en", brief.outputLanguage)
    }

    @Test(expected = AiSummaryException::class)
    fun parseAiBrief_rejectsMissingStructuredObject() {
        parseAiBrief("""{"summary":"legacy only"}""")
    }

    @Test(expected = AiSummaryException::class)
    fun parseAiBrief_rejectsBlankRequiredField() {
        parseAiBrief(
            """{"structured":{"tiny":"","brief":"Brief","bullets":["Fact"],"topics":[],"sourceLanguage":"en","outputLanguage":"en","schemaVersion":"1","promptVersion":"1"}}"""
        )
    }

    @Test
    fun failureMapping_keepsQuotaAndProviderStatesExplicit() {
        assertTrue(aiFailureState(AiSummaryFailure.Quota) is AiBriefUiState.QuotaExceeded)
        assertTrue(aiFailureState(AiSummaryFailure.ProviderUnavailable) is AiBriefUiState.ProviderUnavailable)
        assertEquals(
            "AI network issue",
            (aiFailureState(AiSummaryFailure.Network) as AiBriefUiState.Error).message
        )
    }
}
