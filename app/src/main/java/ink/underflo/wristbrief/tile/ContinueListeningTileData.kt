package ink.underflo.wristbrief.tile

import ink.underflo.wristbrief.data.CachedFeedItem
import ink.underflo.wristbrief.media.PodcastEpisodeProgress

private const val MAX_CONTINUE_TILE_TITLE_CODE_POINTS = 44

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

internal fun String.compactTileText(maxCodePoints: Int = MAX_CONTINUE_TILE_TITLE_CODE_POINTS): String {
    if (maxCodePoints <= 0) return ""
    val normalized = trim().replace(Regex("\\s+"), " ")
    val codePointCount = normalized.codePointCount(0, normalized.length)
    if (codePointCount <= maxCodePoints) return normalized
    val endIndex = normalized.offsetByCodePoints(0, maxCodePoints - 1)
    return normalized.substring(0, endIndex).trimEnd() + "…"
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
