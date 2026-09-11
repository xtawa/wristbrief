package ink.underflo.wristbrief.mobile.media

import android.content.Context
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlin.math.abs

private const val STORE_VERSION = "v1"
private const val PREFS_NAME = "mobile_podcast_progress"
private const val PREFS_KEY = "episode_progress_v1"
internal const val PROGRESS_CHECKPOINT_MS = 15_000L
internal const val COMPLETION_RESTART_WINDOW_MS = 30_000L

/** Durable per-episode playback state. */
data class PodcastEpisodeProgress(
    val episodeId: String,
    val positionMs: Long,
    val playbackSpeed: Float = 1f,
    val durationMs: Long = 0L,
    val isPlaying: Boolean = false,
    val lastPlayedAtEpochMs: Long = System.currentTimeMillis(),
    val completed: Boolean = false,
) {
    init {
        require(episodeId.isNotBlank()) { "Episode id is required" }
        require(positionMs >= 0L) { "Position cannot be negative" }
        require(playbackSpeed in SUPPORTED_PLAYBACK_SPEEDS) { "Unsupported playback speed" }
    }
}

interface PodcastProgressStore {
    fun get(episodeId: String): PodcastEpisodeProgress?
    fun all(): List<PodcastEpisodeProgress>
    fun save(progress: PodcastEpisodeProgress)
    fun getLatestActive(): PodcastEpisodeProgress? = all().firstOrNull { !it.completed && it.positionMs > 0L }
    fun delete(episodeId: String) {}
}

class SharedPreferencesPodcastProgressStore(context: Context) : PodcastProgressStore {
    private val preferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun get(episodeId: String): PodcastEpisodeProgress? =
        decodePodcastProgress(preferences.getString(PREFS_KEY, null).orEmpty())[episodeId]

    override fun all(): List<PodcastEpisodeProgress> =
        decodePodcastProgress(preferences.getString(PREFS_KEY, null).orEmpty()).values.toList()

    override fun save(progress: PodcastEpisodeProgress) {
        val all = decodePodcastProgress(preferences.getString(PREFS_KEY, null).orEmpty()).toMutableMap()
        all[progress.episodeId] = progress
        preferences.edit().putString(PREFS_KEY, encodePodcastProgress(all.values)).apply()
    }

    override fun delete(episodeId: String) {
        val all = decodePodcastProgress(preferences.getString(PREFS_KEY, null).orEmpty()).toMutableMap()
        if (all.remove(episodeId) != null) {
            preferences.edit().putString(PREFS_KEY, encodePodcastProgress(all.values)).apply()
        }
    }
}

val SUPPORTED_PLAYBACK_SPEEDS = listOf(1f, 1.25f, 1.5f, 1.75f, 2f)

fun normalizedResumePosition(savedPositionMs: Long, durationMs: Long): Long {
    if (savedPositionMs <= 0L) return 0L
    if (durationMs <= 0L) return savedPositionMs
    return if (savedPositionMs >= (durationMs - COMPLETION_RESTART_WINDOW_MS).coerceAtLeast(0L)) 0L
    else savedPositionMs.coerceAtMost(durationMs)
}

fun shouldCheckpoint(lastSavedPositionMs: Long, currentPositionMs: Long): Boolean =
    abs(currentPositionMs - lastSavedPositionMs) >= PROGRESS_CHECKPOINT_MS

fun nextPlaybackSpeed(current: Float): Float {
    val index = SUPPORTED_PLAYBACK_SPEEDS.indexOfFirst { abs(it - current) < 0.01f }
    return SUPPORTED_PLAYBACK_SPEEDS[(if (index == -1) 0 else index + 1) % SUPPORTED_PLAYBACK_SPEEDS.size]
}

fun formatPlaybackTime(positionMs: Long, durationMs: Long): String {
    fun format(valueMs: Long): String {
        val totalSeconds = valueMs.coerceAtLeast(0L) / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) "%d:%02d:%02d".format(hours, minutes, seconds)
        else "%02d:%02d".format(minutes, seconds)
    }

    return if (durationMs > 0L) "${format(positionMs)} / ${format(durationMs)}" else format(positionMs)
}

internal fun encodePodcastProgress(items: Collection<PodcastEpisodeProgress>): String = buildString {
    append(STORE_VERSION)
    items.sortedBy { it.episodeId }.forEach { item ->
        append('\n')
        append(encodeField(item.episodeId))
        append('\t')
        append(item.positionMs)
        append('\t')
        append(item.playbackSpeed)
    }
}

internal fun decodePodcastProgress(encoded: String): Map<String, PodcastEpisodeProgress> {
    if (encoded.isBlank()) return emptyMap()
    val lines = encoded.lineSequence().toList()
    if (lines.firstOrNull() != STORE_VERSION) return emptyMap()

    return lines.drop(1).mapNotNull { line ->
        val parts = line.split('\t')
        if (parts.size != 3) return@mapNotNull null
        runCatching {
            val id = decodeField(parts[0])
            PodcastEpisodeProgress(
                episodeId = id,
                positionMs = parts[1].toLong(),
                playbackSpeed = parts[2].toFloat(),
            )
        }.getOrNull()
    }.associateBy { it.episodeId }
}

private fun encodeField(value: String): String = Base64.getUrlEncoder().withoutPadding()
    .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

private fun decodeField(value: String): String = String(
    Base64.getUrlDecoder().decode(value),
    StandardCharsets.UTF_8,
)
