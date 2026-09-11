package ink.underflo.wristbrief.mobile.media

/** Request passed from UI to the Podcast controller layer. */
data class PodcastPlaybackRequest(
    val id: String,
    val title: String,
    val audioUrl: String,
    val feedTitle: String = "",
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
