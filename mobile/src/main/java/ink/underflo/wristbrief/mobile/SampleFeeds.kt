package ink.underflo.wristbrief.mobile

data class SampleFeed(
    val id: String,
    val title: String,
    val url: String,
    val category: String,
    val description: String,
    val isPodcast: Boolean = false,
)

object SampleFeeds {
    val curatedFeeds: List<SampleFeed> = listOf(
        SampleFeed(
            id = "android-developers",
            title = "Android Developers",
            url = "https://feeds.feedburner.com/blogspot/hsDu",
            category = "Tech",
            description = "Official news and announcements",
        ),
        SampleFeed(
            id = "npr-news-now",
            title = "NPR News Now",
            url = "https://feeds.npr.org/500005/podcast.xml",
            category = "News",
            description = "5-minute hourly news podcast",
            isPodcast = true,
        ),
        SampleFeed(
            id = "bbc-world-news",
            title = "BBC World News",
            url = "https://feeds.bbci.co.uk/news/world/rss.xml",
            category = "News",
            description = "Global news and current affairs",
        ),
    )
}
