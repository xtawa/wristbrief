package ink.underflo.wristbrief.mobile

sealed interface PhoneLongSummaryUiState {
    data object AwaitingAuthenticatedGateway : PhoneLongSummaryUiState
    data class Ready(
        val text: String,
        val languageLabel: String,
        val modelLabel: String?,
    ) : PhoneLongSummaryUiState
    data class Error(val message: String) : PhoneLongSummaryUiState
}

fun phoneLongSummaryUiState(rawGatewayResponse: String?): PhoneLongSummaryUiState {
    if (rawGatewayResponse.isNullOrBlank()) {
        return PhoneLongSummaryUiState.AwaitingAuthenticatedGateway
    }

    return runCatching { parsePhoneLongSummaryResponse(rawGatewayResponse) }
        .fold(
            onSuccess = { summary ->
                PhoneLongSummaryUiState.Ready(
                    text = summary.text,
                    languageLabel = if (summary.sourceLanguage == summary.outputLanguage) {
                        summary.outputLanguage
                    } else {
                        "${summary.sourceLanguage} → ${summary.outputLanguage}"
                    },
                    modelLabel = summary.model.takeIf { it.isNotBlank() },
                )
            },
            onFailure = { PhoneLongSummaryUiState.Error("Long summary response was invalid or unsupported.") },
        )
}
