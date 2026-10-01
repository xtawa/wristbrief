package ink.underflo.wristbrief.ui

import ink.underflo.wristbrief.media.PodcastPlaybackState

/**
 * Truthful Now Playing model. Without a loaded item there is no title, progress or working
 * transport control, so the screen shows an explicit empty state instead of an ID or "00:00 / 00:00".
 */
data class NowPlayingUi(
    val hasMedia: Boolean,
    /** Episode title from media metadata; null means the UI uses its localized generic label (never the media ID). */
    val title: String?,
    val progressLabel: String?,
    val isPlaying: Boolean,
    val playbackSpeed: Float,
)

fun PodcastPlaybackState.toNowPlayingUi(): NowPlayingUi {
    val hasMedia = !mediaId.isNullOrBlank()
    return NowPlayingUi(
        hasMedia = hasMedia,
        title = if (hasMedia) title?.trim()?.takeIf { it.isNotEmpty() } else null,
        progressLabel = if (hasMedia) progressLabel else null,
        isPlaying = hasMedia && isPlaying,
        playbackSpeed = playbackSpeed,
    )
}
