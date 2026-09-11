package ink.underflo.wristbrief.mobile

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val PHONE_SUMMARY_SCHEMA_VERSION = "2"
private const val PHONE_SUMMARY_PROMPT_VERSION = "2"
private const val MAX_PHONE_SUMMARY_CHARS = 6000

data class PhoneLongSummary(
    val text: String,
    val sourceLanguage: String,
    val outputLanguage: String,
    val model: String,
    val tiny: String = "",
    val brief: String = "",
    val bullets: List<String> = emptyList(),
    val topics: List<String> = emptyList(),
)

fun parsePhoneLongSummaryResponse(raw: String): PhoneLongSummary {
    val root = Json.parseToJsonElement(raw).jsonObject
    val structured = root["structured"]?.jsonObject ?: error("Missing structured summary")
    val schemaVersion = structured["schemaVersion"]?.jsonPrimitive?.content ?: error("Missing schema version")
    val promptVersion = structured["promptVersion"]?.jsonPrimitive?.content ?: error("Missing prompt version")
    require(schemaVersion == PHONE_SUMMARY_SCHEMA_VERSION) { "Unsupported summary schema" }
    require(promptVersion == PHONE_SUMMARY_PROMPT_VERSION) { "Unsupported summary prompt" }

    val text = structured["long"]?.jsonPrimitive?.content?.trim().orEmpty()
    require(text.isNotEmpty()) { "Missing phone summary" }
    require(text.length <= MAX_PHONE_SUMMARY_CHARS) { "Phone summary too long" }

    val sourceLanguage = structured["sourceLanguage"]?.jsonPrimitive?.content?.trim().orEmpty()
    val outputLanguage = structured["outputLanguage"]?.jsonPrimitive?.content?.trim().orEmpty()
    require(sourceLanguage.isNotEmpty()) { "Missing source language" }
    require(outputLanguage.isNotEmpty()) { "Missing output language" }

    val tiny = structured["tiny"]?.jsonPrimitive?.content?.trim().orEmpty()
    val brief = structured["brief"]?.jsonPrimitive?.content?.trim().orEmpty()
    val bullets = structured["bullets"]?.jsonArray?.mapNotNull {
        it.jsonPrimitive.content.trim().takeIf { s -> s.isNotEmpty() }
    } ?: emptyList()
    val topics = structured["topics"]?.jsonArray?.mapNotNull {
        it.jsonPrimitive.content.trim().takeIf { s -> s.isNotEmpty() }
    } ?: emptyList()

    return PhoneLongSummary(
        text = text,
        sourceLanguage = sourceLanguage,
        outputLanguage = outputLanguage,
        model = root["model"]?.jsonPrimitive?.content?.trim().orEmpty(),
        tiny = tiny,
        brief = brief,
        bullets = bullets,
        topics = topics,
    )
}
