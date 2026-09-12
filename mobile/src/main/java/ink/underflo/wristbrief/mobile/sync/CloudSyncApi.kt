package ink.underflo.wristbrief.mobile.sync

import ink.underflo.wristbrief.mobile.validGatewayOrigin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

private const val MAX_SYNC_RESPONSE_CHARS = 1_048_576

private fun BufferedReader.readTextLimited(): String {
    val result = StringBuilder()
    val buffer = CharArray(8_192)
    while (true) {
        val count = read(buffer)
        if (count < 0) return result.toString()
        if (result.length + count > MAX_SYNC_RESPONSE_CHARS) throw IllegalStateException("response_too_large")
        result.append(buffer, 0, count)
    }
}

interface CloudSyncApi {
    suspend fun push(sessionToken: String, deviceId: String, mutations: List<OutboxMutation>): Result<SyncPushResult>
    suspend fun pull(sessionToken: String, deviceId: String, cursor: Long, limit: Int = 100): Result<SyncPullResult>
}

class HttpCloudSyncApi(
    private val baseUrl: String,
) : CloudSyncApi {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun push(
        sessionToken: String,
        deviceId: String,
        mutations: List<OutboxMutation>,
    ): Result<SyncPushResult> = withContext(Dispatchers.IO) {
        runCatching {
            val gatewayOrigin = requireNotNull(validGatewayOrigin(baseUrl)) { "invalid_gateway_url" }
            val url = URL("$gatewayOrigin/v1/sync/push")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("Authorization", "Bearer $sessionToken")
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
            }

            val body = buildJsonObject {
                put("deviceId", deviceId)
                put("mutations", buildJsonArray {
                    mutations.forEach { add(it.toWireJson()) }
                })
            }.toString()

            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }

            val responseCode = conn.responseCode
            if (responseCode !in 200..299) {
                val errorStream = conn.errorStream ?: conn.inputStream
                val errorMsg = BufferedReader(InputStreamReader(errorStream, Charsets.UTF_8)).use { it.readTextLimited() }
                throw IllegalStateException("Sync push failed with HTTP $responseCode: $errorMsg")
            }

            val responseBody = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readTextLimited() }
            val root = json.parseToJsonElement(responseBody).jsonObject
            val applied = root["appliedCount"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
            val newCursor = root["newCursor"]?.jsonPrimitive?.longOrNull ?: 0L
            SyncPushResult(appliedCount = applied, newCursor = newCursor)
        }
    }

    override suspend fun pull(
        sessionToken: String,
        deviceId: String,
        cursor: Long,
        limit: Int,
    ): Result<SyncPullResult> = withContext(Dispatchers.IO) {
        runCatching {
            val gatewayOrigin = requireNotNull(validGatewayOrigin(baseUrl)) { "invalid_gateway_url" }
            val endpoint = "$gatewayOrigin/v1/sync/pull?cursor=$cursor&deviceId=$deviceId&limit=$limit"
            val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("Authorization", "Bearer $sessionToken")
                setRequestProperty("Accept", "application/json")
            }

            val responseCode = conn.responseCode
            if (responseCode !in 200..299) {
                val errorStream = conn.errorStream ?: conn.inputStream
                val errorMsg = BufferedReader(InputStreamReader(errorStream, Charsets.UTF_8)).use { it.readTextLimited() }
                throw IllegalStateException("Sync pull failed with HTTP $responseCode: $errorMsg")
            }

            val responseBody = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readTextLimited() }
            val root = json.parseToJsonElement(responseBody).jsonObject

            val resCursor = root["cursor"]?.jsonPrimitive?.longOrNull ?: cursor
            val hasMore = root["hasMore"]?.jsonPrimitive?.booleanOrNull ?: false

            val subs = mutableListOf<SyncSubscriptionDelta>()
            root["subscriptions"]?.jsonArray?.forEach { elem ->
                val obj = elem.jsonObject
                subs.add(
                    SyncSubscriptionDelta(
                        subscriptionId = obj["subscriptionId"]?.jsonPrimitive?.content ?: "",
                        feedUrl = obj["feedUrl"]?.jsonPrimitive?.content ?: "",
                        title = obj["title"]?.jsonPrimitive?.content,
                        category = obj["category"]?.jsonPrimitive?.content,
                        enabled = obj["enabled"]?.jsonPrimitive?.booleanOrNull ?: true,
                        sendToWatch = obj["sendToWatch"]?.jsonPrimitive?.booleanOrNull ?: true,
                        revision = obj["revision"]?.jsonPrimitive?.longOrNull ?: 1L,
                        updatedAtEpochMs = obj["updatedAt"]?.jsonPrimitive?.longOrNull ?: 0L,
                        deletedAtEpochMs = obj["deletedAt"]?.jsonPrimitive?.longOrNull,
                    )
                )
            }

            val states = mutableListOf<SyncItemStateDelta>()
            root["itemStates"]?.jsonArray?.forEach { elem ->
                val obj = elem.jsonObject
                states.add(
                    SyncItemStateDelta(
                        itemId = obj["itemId"]?.jsonPrimitive?.content ?: "",
                        isRead = obj["isRead"]?.jsonPrimitive?.booleanOrNull ?: false,
                        isSaved = obj["isSaved"]?.jsonPrimitive?.booleanOrNull ?: false,
                        readChangedAtEpochMs = obj["readChangedAt"]?.jsonPrimitive?.longOrNull,
                        savedChangedAtEpochMs = obj["savedChangedAt"]?.jsonPrimitive?.longOrNull,
                        revision = obj["revision"]?.jsonPrimitive?.longOrNull ?: 1L,
                        updatedAtEpochMs = obj["updatedAt"]?.jsonPrimitive?.longOrNull ?: 0L,
                    )
                )
            }

            val progress = mutableListOf<SyncPlaybackProgressDelta>()
            root["playbackProgress"]?.jsonArray?.forEach { elem ->
                val obj = elem.jsonObject
                progress.add(
                    SyncPlaybackProgressDelta(
                        contentId = obj["contentId"]?.jsonPrimitive?.content ?: "",
                        episodeLocalId = obj["episodeLocalId"]?.jsonPrimitive?.content,
                        positionMs = obj["positionMs"]?.jsonPrimitive?.longOrNull ?: 0L,
                        durationMs = obj["durationMs"]?.jsonPrimitive?.longOrNull ?: 0L,
                        playbackSpeed = (obj["playbackSpeed"]?.jsonPrimitive?.doubleOrNull ?: 1.0).toFloat(),
                        completed = obj["completed"]?.jsonPrimitive?.booleanOrNull ?: false,
                        progressGeneration = obj["progressGeneration"]?.jsonPrimitive?.longOrNull ?: 1L,
                        revision = obj["revision"]?.jsonPrimitive?.longOrNull ?: 1L,
                        updatedAtEpochMs = obj["updatedAt"]?.jsonPrimitive?.longOrNull ?: 0L,
                    )
                )
            }

            SyncPullResult(
                cursor = resCursor,
                hasMore = hasMore,
                subscriptions = subs,
                itemStates = states,
                playbackProgress = progress,
            )
        }
    }
}
