package ink.underflo.wristbrief.media

/** Stable request passed from Wear UI to the MediaSession controller layer. */
data class PodcastPlaybackRequest(
    val id: String,
    val title: String,
    val audioUrl: String
) {
    init {
        require(id.isNotBlank()) { "Podcast id is required" }
        require(title.isNotBlank()) { "Podcast title is required" }
        requireHttpsPodcastUrl(audioUrl)
    }
}

internal fun requireHttpsPodcastUrl(url: String): String {
    require(url.startsWith("https://", ignoreCase = true)) {
        "Only HTTPS podcast media is allowed"
    }
    return url
}
