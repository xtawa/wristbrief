package ink.underflo.wristbrief.ai

import androidx.annotation.StringRes
import ink.underflo.wristbrief.R
import ink.underflo.wristbrief.sync.WearAccountSessionRuntime

/** Text that is either a localized resource or server/user content shown verbatim. */
sealed interface WearText {
    data class Res(@StringRes val id: Int) : WearText
    data class Raw(val value: String) : WearText
}

data class WearAiBriefPresentation(
    val label: WearText,
    val detail: WearText,
    val actionEnabled: Boolean,
    val showReadyBrief: Boolean
)

/**
 * Why AI is or is not reachable from the watch. A build without an HTTPS gateway URL is a
 * configuration gap, not something the user can fix by signing in, so the two are kept separate.
 */
enum class AiGatewayAvailability { Available, NotConfigured, SignedOut }

fun aiGatewayAvailability(
    gatewayUrl: String,
    gatewayToken: String,
    sessionToken: String? = WearAccountSessionRuntime.currentToken(),
): AiGatewayAvailability = when {
    !gatewayUrl.startsWith("https://") -> AiGatewayAvailability.NotConfigured
    gatewayToken.isBlank() && sessionToken.isNullOrBlank() -> AiGatewayAvailability.SignedOut
    else -> AiGatewayAvailability.Available
}

fun isAiGatewayConfigured(gatewayUrl: String, gatewayToken: String): Boolean =
    aiGatewayAvailability(gatewayUrl, gatewayToken) == AiGatewayAvailability.Available

private fun res(@StringRes id: Int) = WearText.Res(id)

fun AiBriefUiState.toWearPresentation(availability: AiGatewayAvailability): WearAiBriefPresentation {
    val available = availability == AiGatewayAvailability.Available
    return when (this) {
        AiBriefUiState.Idle -> when (availability) {
            AiGatewayAvailability.Available ->
                WearAiBriefPresentation(res(R.string.wear_ai_generate_prompt), res(R.string.wear_ai_reader_stays_available), true, false)
            AiGatewayAvailability.SignedOut ->
                WearAiBriefPresentation(res(R.string.wear_ai_brief_unavailable), res(R.string.wear_ai_signin_phone), false, false)
            AiGatewayAvailability.NotConfigured ->
                WearAiBriefPresentation(res(R.string.wear_ai_not_configured), res(R.string.wear_ai_content_still_available), false, false)
        }
        AiBriefUiState.Loading ->
            WearAiBriefPresentation(res(R.string.wear_ai_summarizing), res(R.string.wear_ai_reader_podcast_usable), false, false)
        is AiBriefUiState.Ready ->
            WearAiBriefPresentation(WearText.Raw(brief.tiny), WearText.Raw(brief.brief), false, true)
        AiBriefUiState.QuotaExceeded ->
            WearAiBriefPresentation(res(R.string.wear_ai_quota_reached), res(R.string.wear_ai_content_still_available), false, false)
        AiBriefUiState.ProviderUnavailable ->
            WearAiBriefPresentation(res(R.string.wear_ai_temporarily_unavailable), res(R.string.wear_ai_retry_hint), available, false)
        is AiBriefUiState.Error -> when (failure) {
            // Retrying with the same rejected session cannot succeed; the phone must refresh it.
            AiSummaryFailure.Unauthorized ->
                WearAiBriefPresentation(res(R.string.wear_ai_access_unavailable), res(R.string.wear_ai_signin_again_phone), false, false)
            AiSummaryFailure.Network ->
                WearAiBriefPresentation(res(R.string.wear_ai_network_issue), res(R.string.wear_ai_retry_hint), available, false)
            else ->
                WearAiBriefPresentation(res(R.string.wear_ai_brief_unavailable), res(R.string.wear_ai_retry_hint), available, false)
        }
    }
}
