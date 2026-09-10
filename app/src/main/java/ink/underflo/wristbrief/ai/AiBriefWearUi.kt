package ink.underflo.wristbrief.ai

import ink.underflo.wristbrief.sync.WearAccountSessionRuntime

data class WearAiBriefPresentation(
    val label: String,
    val detail: String,
    val actionEnabled: Boolean,
    val showReadyBrief: Boolean
)

fun isAiGatewayConfigured(gatewayUrl: String, gatewayToken: String): Boolean =
    gatewayUrl.startsWith("https://") && (gatewayToken.isNotBlank() || WearAccountSessionRuntime.currentToken() != null)

fun AiBriefUiState.toWearPresentation(isGatewayConfigured: Boolean): WearAiBriefPresentation = when (this) {
    AiBriefUiState.Idle -> if (isGatewayConfigured) {
        WearAiBriefPresentation("Generate AI brief", "Original reader stays available", true, false)
    } else {
        WearAiBriefPresentation("AI brief unavailable", "Sign in on the paired phone", false, false)
    }
    AiBriefUiState.Loading -> WearAiBriefPresentation("Summarizing…", "Reader and podcast remain usable", false, false)
    is AiBriefUiState.Ready -> WearAiBriefPresentation(brief.tiny, brief.brief, false, true)
    AiBriefUiState.QuotaExceeded -> WearAiBriefPresentation("AI quota reached", "Original article and podcast are still available", false, false)
    AiBriefUiState.ProviderUnavailable -> WearAiBriefPresentation("AI temporarily unavailable", "Tap to retry; original content still works", isGatewayConfigured, false)
    is AiBriefUiState.Error -> WearAiBriefPresentation(message, "Tap to retry; original content still works", isGatewayConfigured, false)
}
