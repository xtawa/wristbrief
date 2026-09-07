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
    val audioUrl: String?,
    val guid: String? = null
)

class FeedRepository(
    private val client: OkHttpClient = OkHttpClient(),
    private val parser: FeedParser = FeedParser()
) : FeedLoader {
    override fun load(url: String): List<FeedItem> {
        require(url.startsWith("https://")) { "Only HTTPS feeds are allowed" }
        val request = Request.Builder().url(url).header("User-Agent", "WristBrief/0.1").build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Feed request failed: ${response.code}" }
            val body = response.body ?: error("Empty feed response")
            val xmlParser = Xml.newPullParser().apply {
                setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                setInput(body.charStream())
            }
            return parser.parse(xmlParser)
        }
    }
}
