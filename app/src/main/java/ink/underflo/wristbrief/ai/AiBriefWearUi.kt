package ink.underflo.wristbrief.ai

data class WearAiBriefPresentation(
    val label: String,
    val detail: String,
    val actionEnabled: Boolean,
    val showReadyBrief: Boolean
)

fun isAiGatewayConfigured(gatewayUrl: String, gatewayToken: String): Boolean =
    gatewayUrl.startsWith("https://") && gatewayToken.isNotBlank()

fun AiBriefUiState.toWearPresentation(isGatewayConfigured: Boolean): WearAiBriefPresentation = when (this) {
    AiBriefUiState.Idle -> if (isGatewayConfigured) {
        WearAiBriefPresentation(
            label = "Generate AI brief",
            detail = "Original reader stays available",
            actionEnabled = true,
            showReadyBrief = false
        )
    } else {
        WearAiBriefPresentation(
            label = "AI brief unavailable",
            detail = "Configure the gateway for this build",
            actionEnabled = false,
            showReadyBrief = false
        )
    }

    AiBriefUiState.Loading -> WearAiBriefPresentation(
        label = "Summarizing…",
        detail = "Reader and podcast remain usable",
        actionEnabled = false,
        showReadyBrief = false
    )

    is AiBriefUiState.Ready -> WearAiBriefPresentation(
        label = brief.tiny,
        detail = brief.brief,
        actionEnabled = false,
        showReadyBrief = true
    )

    AiBriefUiState.QuotaExceeded -> WearAiBriefPresentation(
        label = "AI quota reached",
        detail = "Original article and podcast are still available",
        actionEnabled = false,
        showReadyBrief = false
    )

    AiBriefUiState.ProviderUnavailable -> WearAiBriefPresentation(
        label = "AI temporarily unavailable",
        detail = "Tap to retry; original content still works",
        actionEnabled = isGatewayConfigured,
        showReadyBrief = false
    )

    is AiBriefUiState.Error -> WearAiBriefPresentation(
        label = message,
        detail = "Tap to retry; original content still works",
        actionEnabled = isGatewayConfigured,
        showReadyBrief = false
    )
}
