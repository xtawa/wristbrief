package ink.underflo.wristbrief.sync

import ink.underflo.wristbrief.media.PodcastEpisodeProgress
import ink.underflo.wristbrief.media.SUPPORTED_PLAYBACK_SPEEDS
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

data class PlaybackSyncPayload(
    val version: Int = PlaybackWireContract.VERSION,
    val episodeId: String,
    val positionMs: Long,
    val durationMs: Long,
    val playbackSpeed: Float,
    val isPlaying: Boolean,
    val lastPlayedAtEpochMs: Long,
    val completed: Boolean,
    val origin: SyncOrigin,
    val progressGeneration: Long = 1L,
    val playbackSessionId: String? = null,
) {
    init {
        require(episodeId.isNotBlank()) { "episodeId must not be blank" }
        require(positionMs >= 0L) { "positionMs must not be negative" }
        require(durationMs >= 0L) { "durationMs must not be negative" }
        require(playbackSpeed in SUPPORTED_PLAYBACK_SPEEDS) { "Unsupported playbackSpeed" }
        require(lastPlayedAtEpochMs >= 0L) { "lastPlayedAtEpochMs must not be negative" }
    }

    fun toProgress(): PodcastEpisodeProgress = PodcastEpisodeProgress(
        episodeId = episodeId,
        positionMs = positionMs,
        playbackSpeed = playbackSpeed,
        durationMs = durationMs,
        isPlaying = isPlaying,
        lastPlayedAtEpochMs = lastPlayedAtEpochMs,
        completed = completed,
    )
}

object PlaybackWireContract {
    const val PHONE_PATH = "/wristbrief/playback/v1/phone"
    const val WEAR_PATH = "/wristbrief/playback/v1/wear"
    const val PAYLOAD_KEY = "payload"
    const val VERSION = 1

    private val json = Json { ignoreUnknownKeys = true }

    fun pathFor(origin: SyncOrigin): String = if (origin == SyncOrigin.PHONE) PHONE_PATH else WEAR_PATH
    fun remotePathFor(localOrigin: SyncOrigin): String = if (localOrigin == SyncOrigin.PHONE) WEAR_PATH else PHONE_PATH

    fun encode(payload: PlaybackSyncPayload): String = buildJsonObject {
        put("version", payload.version)
        put("episodeId", payload.episodeId)
        put("positionMs", payload.positionMs)
        put("durationMs", payload.durationMs)
        put("playbackSpeed", payload.playbackSpeed.toDouble())
        put("isPlaying", payload.isPlaying)
        put("lastPlayedAtEpochMs", payload.lastPlayedAtEpochMs)
        put("completed", payload.completed)
        put("origin", payload.origin.name)
        put("progressGeneration", payload.progressGeneration)
        if (payload.playbackSessionId != null) {
            put("playbackSessionId", payload.playbackSessionId)
        }
    }.toString()

    fun encode(progress: PodcastEpisodeProgress, origin: SyncOrigin): String = encode(
        PlaybackSyncPayload(
            version = VERSION,
            episodeId = progress.episodeId,
            positionMs = progress.positionMs,
            durationMs = progress.durationMs,
            playbackSpeed = progress.playbackSpeed,
            isPlaying = progress.isPlaying,
            lastPlayedAtEpochMs = progress.lastPlayedAtEpochMs,
            completed = progress.completed,
            origin = origin,
        )
    )

    fun decode(raw: String): PlaybackSyncPayload {
        val root = json.parseToJsonElement(raw).jsonObject
        val version = root["version"]?.jsonPrimitive?.longOrNull?.toInt()
            ?: error("Missing version")
        require(version == VERSION) { "Unsupported playback version: $version" }
        val episodeId = root["episodeId"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: error("Missing or empty episodeId")
        val positionMs = root["positionMs"]?.jsonPrimitive?.longOrNull
            ?: error("Missing positionMs")
        val durationMs = root["durationMs"]?.jsonPrimitive?.longOrNull ?: 0L
        val speed = root["playbackSpeed"]?.jsonPrimitive?.doubleOrNull?.toFloat() ?: 1f
        val isPlaying = root["isPlaying"]?.jsonPrimitive?.booleanOrNull ?: false
        val lastPlayed = root["lastPlayedAtEpochMs"]?.jsonPrimitive?.longOrNull
            ?: error("Missing lastPlayedAtEpochMs")
        val completed = root["completed"]?.jsonPrimitive?.booleanOrNull ?: false
        val originStr = root["origin"]?.jsonPrimitive?.content ?: error("Missing origin")
        val origin = runCatching { SyncOrigin.valueOf(originStr) }.getOrNull()
            ?: error("Invalid origin: $originStr")
        val progressGen = root["progressGeneration"]?.jsonPrimitive?.longOrNull ?: 1L
        val sessionId = root["playbackSessionId"]?.jsonPrimitive?.content

        return PlaybackSyncPayload(
            version = version,
            episodeId = episodeId,
            positionMs = positionMs,
            durationMs = durationMs,
            playbackSpeed = speed,
            isPlaying = isPlaying,
            lastPlayedAtEpochMs = lastPlayed,
            completed = completed,
            origin = origin,
            progressGeneration = progressGen,
            playbackSessionId = sessionId,
        )
    }

    fun decodeOwned(raw: String, expectedOrigin: SyncOrigin): PlaybackSyncPayload {
        val payload = decode(raw)
        require(payload.origin == expectedOrigin) {
            "Playback payload origin ${payload.origin} does not match expected $expectedOrigin"
        }
        return payload
    }

    /**
     * Last-Write-Wins (LWW) conflict resolution between local progress and incoming remote payload.
     * Higher lastPlayedAtEpochMs wins.
     * Tie-break: WEAR > PHONE.
     */
    fun resolvePlaybackConflict(
        local: PodcastEpisodeProgress?,
        incoming: PlaybackSyncPayload,
    ): PodcastEpisodeProgress {
        if (local == null) return incoming.toProgress()
        if (local.episodeId != incoming.episodeId) return local

        val incomingNewer = when {
            incoming.lastPlayedAtEpochMs > local.lastPlayedAtEpochMs -> true
            incoming.lastPlayedAtEpochMs < local.lastPlayedAtEpochMs -> false
            else -> incoming.origin == SyncOrigin.WEAR // WEAR wins tie
        }

        return if (incomingNewer) incoming.toProgress() else local
    }
}
