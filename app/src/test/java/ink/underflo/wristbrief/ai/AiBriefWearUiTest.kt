package ink.underflo.wristbrief.ai

import ink.underflo.wristbrief.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiBriefWearUiTest {
    private val brief = AiBrief(
        tiny = "Tiny glance",
        brief = "Short watch-sized brief",
        bullets = listOf("One"),
        topics = listOf("Wear"),
        sourceLanguage = "en",
        outputLanguage = "en",
        schemaVersion = "1",
        promptVersion = "1"
    )

    @Test
    fun missingOrInsecureGatewayUrlIsABuildConfigurationGapNotASignInProblem() {
        assertEquals(AiGatewayAvailability.NotConfigured, aiGatewayAvailability("", "", sessionToken = "wbs_token"))
        assertEquals(AiGatewayAvailability.NotConfigured, aiGatewayAvailability("http://gateway.example", "token"))

        val presentation = AiBriefUiState.Idle.toWearPresentation(AiGatewayAvailability.NotConfigured)
        assertEquals(WearText.Res(R.string.wear_ai_not_configured), presentation.label)
        assertFalse(presentation.actionEnabled)
        assertFalse(presentation.showReadyBrief)
    }

    @Test
    fun httpsGatewayWithoutSessionAsksForPhoneSignIn() {
        assertEquals(AiGatewayAvailability.SignedOut, aiGatewayAvailability("https://gateway.example", "", sessionToken = null))

        val presentation = AiBriefUiState.Idle.toWearPresentation(AiGatewayAvailability.SignedOut)
        assertEquals(WearText.Res(R.string.wear_ai_brief_unavailable), presentation.label)
        assertEquals(WearText.Res(R.string.wear_ai_signin_phone), presentation.detail)
        assertFalse(presentation.actionEnabled)
    }

    @Test
    fun gatewayConfiguration_requiresHttpsAndToken() {
        assertEquals(AiGatewayAvailability.Available, aiGatewayAvailability("https://gateway.example", "token", sessionToken = null))
        assertEquals(AiGatewayAvailability.Available, aiGatewayAvailability("https://gateway.example", "", sessionToken = "wbs_token"))
        assertTrue(isAiGatewayConfigured("https://gateway.example", "token"))
        assertFalse(isAiGatewayConfigured("http://gateway.example", "token"))
    }

    @Test
    fun gatewayConfiguration_usesWearAccountSessionRuntimeWhenTokenBlank() {
        ink.underflo.wristbrief.sync.WearAccountSessionRuntime.clear()
        assertFalse(isAiGatewayConfigured("https://gateway.example", ""))
        val revisionBefore = ink.underflo.wristbrief.sync.WearAccountSessionRuntime.revision.value

        ink.underflo.wristbrief.sync.WearAccountSessionRuntime.set(
            ink.underflo.wristbrief.sync.WearAccountSession(
                "wear-token",
                java.time.Instant.now().plusSeconds(3600),
                "usr_test"
            )
        )
        assertTrue(isAiGatewayConfigured("https://gateway.example", ""))
        // Open screens observe this revision to re-evaluate availability without navigation.
        assertTrue(ink.underflo.wristbrief.sync.WearAccountSessionRuntime.revision.value > revisionBefore)

        ink.underflo.wristbrief.sync.WearAccountSessionRuntime.clear()
        assertFalse(isAiGatewayConfigured("https://gateway.example", ""))
    }

    @Test
    fun idleWithGatewayOffersGeneration() {
        val presentation = AiBriefUiState.Idle.toWearPresentation(AiGatewayAvailability.Available)
        assertEquals(WearText.Res(R.string.wear_ai_generate_prompt), presentation.label)
        assertTrue(presentation.actionEnabled)
    }

    @Test
    fun loading_keepsOriginalReaderAvailable() {
        val presentation = AiBriefUiState.Loading.toWearPresentation(AiGatewayAvailability.Available)

        assertEquals(WearText.Res(R.string.wear_ai_summarizing), presentation.label)
        assertEquals(WearText.Res(R.string.wear_ai_reader_podcast_usable), presentation.detail)
        assertFalse(presentation.actionEnabled)
    }

    @Test
    fun ready_mapsTinyAndBriefToWearSurfaceVerbatim() {
        val presentation = AiBriefUiState.Ready(brief).toWearPresentation(AiGatewayAvailability.Available)

        assertEquals(WearText.Raw("Tiny glance"), presentation.label)
        assertEquals(WearText.Raw("Short watch-sized brief"), presentation.detail)
        assertTrue(presentation.showReadyBrief)
    }

    @Test
    fun providerFailure_allowsRetryWithoutBlockingReader() {
        val presentation = AiBriefUiState.ProviderUnavailable.toWearPresentation(AiGatewayAvailability.Available)

        assertTrue(presentation.actionEnabled)
        assertEquals(WearText.Res(R.string.wear_ai_retry_hint), presentation.detail)
    }

    @Test
    fun failuresUseLocalizedResourcesAndUnauthorizedDoesNotOfferPointlessRetry() {
        val unauthorized = AiBriefUiState.Error(AiSummaryFailure.Unauthorized).toWearPresentation(AiGatewayAvailability.Available)
        assertEquals(WearText.Res(R.string.wear_ai_access_unavailable), unauthorized.label)
        assertEquals(WearText.Res(R.string.wear_ai_signin_again_phone), unauthorized.detail)
        assertFalse(unauthorized.actionEnabled)

        val network = AiBriefUiState.Error(AiSummaryFailure.Network).toWearPresentation(AiGatewayAvailability.Available)
        assertEquals(WearText.Res(R.string.wear_ai_network_issue), network.label)
        assertTrue(network.actionEnabled)

        val quota = AiBriefUiState.QuotaExceeded.toWearPresentation(AiGatewayAvailability.Available)
        assertEquals(WearText.Res(R.string.wear_ai_quota_reached), quota.label)
        assertFalse(quota.actionEnabled)
    }
}
