package ink.underflo.wristbrief.data

import android.util.Xml
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser

data class FeedItem(
    val title: String,
    val link: String?,
    val description: String?,
    val published: String?,
    val audioUrl: String?
)

class FeedRepository(private val client: OkHttpClient = OkHttpClient()) {
    fun load(url: String): List<FeedItem> {
        require(url.startsWith("https://")) { "Only HTTPS feeds are allowed" }
        val request = Request.Builder().url(url).header("User-Agent", "WristBrief/0.1").build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Feed request failed: ${response.code}" }
            val body = response.body ?: error("Empty feed response")
            val parser = Xml.newPullParser().apply {
                setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                setInput(body.charStream())
            }
            return parse(parser)
        }
    }

    private fun parse(parser: XmlPullParser): List<FeedItem> {
        val items = mutableListOf<FeedItem>()
        var event = parser.eventType
        var inItem = false
        var title: String? = null
        var link: String? = null
        var description: String? = null
        var published: String? = null
        var audioUrl: String? = null

        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name.lowercase()) {
                    "item", "entry" -> {
                        inItem = true
                        title = null; link = null; description = null; published = null; audioUrl = null
                    }
                    "title" -> if (inItem) title = parser.nextText().trim()
                    "description", "summary", "content" -> if (inItem) description = parser.nextText().trim()
                    "pubdate", "published", "updated" -> if (inItem) published = parser.nextText().trim()
                    "link" -> if (inItem) {
                        val href = parser.getAttributeValue(null, "href")
                        link = href ?: parser.nextText().trim().ifBlank { null }
                    }
                    "enclosure" -> if (inItem && parser.getAttributeValue(null, "type")?.startsWith("audio/") == true) {
                        audioUrl = parser.getAttributeValue(null, "url")
                    }
                }
            } else if (event == XmlPullParser.END_TAG && parser.name.lowercase() in setOf("item", "entry")) {
                if (inItem && !title.isNullOrBlank()) items += FeedItem(title!!, link, description, published, audioUrl)
                inItem = false
            }
            event = parser.next()
        }
        return items
    }
}
