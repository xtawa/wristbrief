package ink.underflo.wristbrief.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
    val root = runCatching { Json.parseToJsonElement(body).jsonObject }
        .getOrElse { throw AiSummaryException(AiSummaryFailure.InvalidResponse) }
    val structured = root["structured"] as? JsonObject
        ?: throw AiSummaryException(AiSummaryFailure.InvalidResponse)

    fun requiredString(name: String): String = runCatching {
        structured[name]?.jsonPrimitive?.contentOrNull?.trim()
    }.getOrNull()?.takeIf { it.isNotEmpty() }
        ?: throw AiSummaryException(AiSummaryFailure.InvalidResponse)

    fun stringList(name: String): List<String> {
        val array = structured[name] as? JsonArray
            ?: throw AiSummaryException(AiSummaryFailure.InvalidResponse)
        return array.map { element ->
            runCatching { element.jsonPrimitive.contentOrNull?.trim() }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?: throw AiSummaryException(AiSummaryFailure.InvalidResponse)
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
