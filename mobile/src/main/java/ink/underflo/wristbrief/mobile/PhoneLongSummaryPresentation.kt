package ink.underflo.wristbrief.mobile

sealed interface PhoneLongSummaryUiState {
    data object AwaitingAuthenticatedGateway : PhoneLongSummaryUiState
    data object Loading : PhoneLongSummaryUiState
    data class Ready(
        val text: String,
        val languageLabel: String,
        val modelLabel: String?,
        val bullets: List<String> = emptyList(),
        val topics: List<String> = emptyList(),
    ) : PhoneLongSummaryUiState
    data class Error(val message: String) : PhoneLongSummaryUiState
}

fun phoneLongSummaryUiState(rawGatewayResponse: String?): PhoneLongSummaryUiState {
    if (rawGatewayResponse.isNullOrBlank()) {
        return PhoneLongSummaryUiState.AwaitingAuthenticatedGateway
    }

    return runCatching { parsePhoneLongSummaryResponse(rawGatewayResponse) }
        .fold(
            onSuccess = ::phoneLongSummaryReadyState,
            onFailure = { PhoneLongSummaryUiState.Error("Long summary response was invalid or unsupported.") },
        )
}

fun phoneLongSummaryReadyState(summary: PhoneLongSummary): PhoneLongSummaryUiState.Ready =
    PhoneLongSummaryUiState.Ready(
        text = summary.text,
        languageLabel = if (summary.sourceLanguage == summary.outputLanguage) {
            summary.outputLanguage
        } else {
            "${summary.sourceLanguage} → ${summary.outputLanguage}"
        },
        modelLabel = summary.model.takeIf { it.isNotBlank() },
        bullets = summary.bullets,
        topics = summary.topics,
    )

fun phoneLongSummaryFailureState(failure: PhoneSummaryFailure): PhoneLongSummaryUiState.Error =
    PhoneLongSummaryUiState.Error(
        when (failure) {
            PhoneSummaryFailure.Network -> "Could not reach WristBrief Gateway. Check the connection and try again."
            PhoneSummaryFailure.Unauthorized -> "Gateway authentication was rejected. Check the access token and try again."
            PhoneSummaryFailure.Quota -> "Managed AI quota is currently exhausted."
            PhoneSummaryFailure.ProviderUnavailable -> "The configured AI provider is temporarily unavailable."
            PhoneSummaryFailure.InvalidResponse -> "The Gateway returned an invalid or unsupported summary response."
        },
    )
