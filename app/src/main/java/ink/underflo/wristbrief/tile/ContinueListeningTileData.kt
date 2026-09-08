package ink.underflo.wristbrief.tile

import ink.underflo.wristbrief.data.CachedFeedItem
import ink.underflo.wristbrief.media.PodcastEpisodeProgress

data class ContinueListeningTileData(
    val episodeId: String,
    val title: String,
    val resumeLabel: String
)

internal fun mapContinueListeningTileData(
    cachedItems: List<CachedFeedItem>,
    progress: List<PodcastEpisodeProgress>
): ContinueListeningTileData? {
    val progressById = progress.asSequence()
        .filter { it.positionMs > 0L }
        .associateBy { it.episodeId }

    val item = cachedItems.asSequence()
        .filter { !it.audioUrl.isNullOrBlank() && it.id in progressById }
        .sortedByDescending { it.cachedAtEpochMs }
        .firstOrNull()
        ?: return null
    val saved = progressById.getValue(item.id)
    return ContinueListeningTileData(
        episodeId = item.id,
        title = item.title.compactTileText(),
        resumeLabel = "Resume ${formatResumeTime(saved.positionMs)} · ${formatSpeed(saved.playbackSpeed)}"
    )
}

private fun String.compactTileText(maxChars: Int = 44): String {
    val normalized = trim().replace(Regex("\\s+"), " ")
    if (normalized.length <= maxChars) return normalized
    return normalized.take((maxChars - 1).coerceAtLeast(0)).trimEnd() + "…"
}

private fun formatResumeTime(positionMs: Long): String {
    val totalSeconds = positionMs.coerceAtLeast(0L) / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%d:%02d".format(minutes, seconds)
}

private fun formatSpeed(speed: Float): String =
    if (speed % 1f == 0f) "${speed.toInt()}×" else "${speed}×"
