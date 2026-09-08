package ink.underflo.wristbrief.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiBriefWearUiTest {
    @Test
    fun idleWithoutGateway_isExplicitlyUnavailable() {
        val presentation = AiBriefUiState.Idle.toWearPresentation(isGatewayConfigured = false)

        assertEquals("AI brief unavailable", presentation.label)
        assertFalse(presentation.actionEnabled)
        assertFalse(presentation.showReadyBrief)
    }

    @Test
    fun gatewayConfiguration_requiresHttpsAndToken() {
        assertTrue(isAiGatewayConfigured("https://gateway.example", "token"))
        assertFalse(isAiGatewayConfigured("http://gateway.example", "token"))
        assertFalse(isAiGatewayConfigured("https://gateway.example", ""))
    }

    @Test
    fun loading_keepsOriginalReaderAvailable() {
        val presentation = AiBriefUiState.Loading.toWearPresentation(isGatewayConfigured = true)

        assertEquals("Summarizing…", presentation.label)
        assertTrue(presentation.detail.contains("Reader"))
        assertFalse(presentation.actionEnabled)
    }

    @Test
    fun ready_mapsTinyAndBriefToWearSurface() {
        val presentation = AiBriefUiState.Ready(
            AiBrief(
                tiny = "Tiny glance",
                brief = "Short watch-sized brief",
                bullets = listOf("One"),
                topics = listOf("Wear"),
                sourceLanguage = "en",
                outputLanguage = "en",
                schemaVersion = "1",
                promptVersion = "1"
            )
        ).toWearPresentation(isGatewayConfigured = true)

        assertEquals("Tiny glance", presentation.label)
        assertEquals("Short watch-sized brief", presentation.detail)
        assertTrue(presentation.showReadyBrief)
    }

    @Test
    fun providerFailure_allowsRetryWithoutBlockingReader() {
        val presentation = AiBriefUiState.ProviderUnavailable.toWearPresentation(isGatewayConfigured = true)

        assertTrue(presentation.actionEnabled)
        assertTrue(presentation.detail.contains("original content"))
    }
}
