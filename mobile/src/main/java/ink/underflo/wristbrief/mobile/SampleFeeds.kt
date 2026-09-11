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
    val curatedFeeds = listOf(
        SampleFeed(
            id = "sample_android_dev",
            title = "Android Developers Blog",
            url = "https://android-developers.googleblog.com/feeds/posts/default",
            category = "Tech",
            description = "Official news and announcements from the Android team",
            isPodcast = false,
        ),
        SampleFeed(
            id = "sample_npr_news_now",
            title = "NPR News Now",
            url = "https://feeds.npr.org/500005/podcast.xml",
            category = "News",
            description = "Top stories updated hourly in a 5-minute podcast",
            isPodcast = true,
        ),
        SampleFeed(
            id = "sample_bbc_world",
            title = "BBC World Service",
            url = "https://feeds.bbci.co.uk/news/world/rss.xml",
            category = "News",
            description = "International news and analysis from the BBC",
            isPodcast = false,
        ),
    )
}
