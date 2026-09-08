package ink.underflo.wristbrief.ai

import org.json.JSONObject

data class AiBrief(
    val tiny: String,
    val brief: String,
    val bullets: List<String>,
    val topics: List<String>,
    val sourceLanguage: String,
    val outputLanguage: String,
    val schemaVersion: String,
    val promptVersion: String
)

sealed interface AiBriefUiState {
    data object Idle : AiBriefUiState
    data object Loading : AiBriefUiState
    data class Ready(val brief: AiBrief) : AiBriefUiState
    data object QuotaExceeded : AiBriefUiState
    data object ProviderUnavailable : AiBriefUiState
    data class Error(val message: String) : AiBriefUiState
}

internal fun parseAiBrief(body: String): AiBrief {
    val structured = JSONObject(body).optJSONObject("structured")
        ?: throw AiSummaryException(AiSummaryFailure.InvalidResponse)

    fun requiredString(name: String): String = structured.optString(name).trim().takeIf { it.isNotEmpty() }
        ?: throw AiSummaryException(AiSummaryFailure.InvalidResponse)

    fun stringList(name: String): List<String> {
        val array = structured.optJSONArray(name)
            ?: throw AiSummaryException(AiSummaryFailure.InvalidResponse)
        return buildList {
            for (index in 0 until array.length()) {
                val value = array.optString(index).trim()
                if (value.isEmpty()) throw AiSummaryException(AiSummaryFailure.InvalidResponse)
                add(value)
            }
        }
    }

    return AiBrief(
        tiny = requiredString("tiny"),
        brief = requiredString("brief"),
        bullets = stringList("bullets"),
        topics = stringList("topics"),
        sourceLanguage = requiredString("sourceLanguage"),
        outputLanguage = requiredString("outputLanguage"),
        schemaVersion = requiredString("schemaVersion"),
        promptVersion = requiredString("promptVersion")
    )
}

enum class AiSummaryFailure { Quota, ProviderUnavailable, Unauthorized, InvalidResponse, Network }

class AiSummaryException(val failure: AiSummaryFailure) : RuntimeException(failure.name)

fun aiFailureState(failure: AiSummaryFailure): AiBriefUiState = when (failure) {
    AiSummaryFailure.Quota -> AiBriefUiState.QuotaExceeded
    AiSummaryFailure.ProviderUnavailable -> AiBriefUiState.ProviderUnavailable
    AiSummaryFailure.Unauthorized -> AiBriefUiState.Error("AI access unavailable")
    AiSummaryFailure.InvalidResponse -> AiBriefUiState.Error("AI brief unavailable")
    AiSummaryFailure.Network -> AiBriefUiState.Error("AI network issue")
}
