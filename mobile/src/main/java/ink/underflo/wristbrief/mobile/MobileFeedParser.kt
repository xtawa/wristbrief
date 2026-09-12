package ink.underflo.wristbrief.mobile

import java.io.InputStream
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

data class ParsedFeedItem(
    val title: String,
    val link: String?,
    val description: String?,
    val published: String?,
    val audioUrl: String?,
    val guid: String?,
)

class MobileFeedParser {
    fun parse(inputStream: InputStream): List<ParsedFeedItem> {
        val factory = XmlPullParserFactory.newInstance().apply {
            isNamespaceAware = true
        }
        val parser = factory.newPullParser()
        parser.setInput(inputStream, null)
        return parse(parser)
    }

    fun parse(parser: XmlPullParser): List<ParsedFeedItem> {
        val items = mutableListOf<ParsedFeedItem>()
        var event = parser.eventType
        var inItem = false
        var title: String? = null
        var link: String? = null
        var description: String? = null
        var published: String? = null
        var audioUrl: String? = null
        var guid: String? = null

        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val name = parser.name.lowercase()
                    when (name) {
                        "item", "entry" -> {
                            inItem = true
                            title = null
                            link = null
                            description = null
                            published = null
                            audioUrl = null
                            guid = null
                        }
                        "guid", "id" -> if (inItem) {
                            guid = parser.nextText().trim().ifBlank { null }
                        }
                        "title" -> if (inItem) {
                            title = parser.nextText().trim().ifBlank { null }
                        }
                        "description", "summary", "content", "encoded", "content:encoded" -> if (inItem) {
                            // XmlPullParser exposes a namespaced content:encoded
                            // element as the local name "encoded" when namespace
                            // processing is enabled. Keep the richest field so
                            // a short summary cannot overwrite full content.
                            val candidate = parser.nextText().trim()
                            if (candidate.isNotBlank() && candidate.length > (description?.length ?: 0)) {
                                description = candidate
                            }
                        }
                        "pubdate", "published", "updated" -> if (inItem) {
                            published = parser.nextText().trim().ifBlank { null }
                        }
                        "link" -> if (inItem) {
                            val href = parser.getAttributeValue(null, "href")
                            val rel = parser.getAttributeValue(null, "rel")
                            val type = parser.getAttributeValue(null, "type")
                            if (!href.isNullOrBlank()) {
                                if (rel.equals("enclosure", ignoreCase = true) && type?.startsWith("audio/") == true) {
                                    audioUrl = href
                                } else if (rel.isNullOrBlank() || rel.equals("alternate", ignoreCase = true)) {
                                    link = href
                                }
                            } else {
                                link = parser.nextText().trim().ifBlank { link }
                            }
                        }
                        "enclosure" -> if (inItem) {
                            val type = parser.getAttributeValue(null, "type")
                            val url = parser.getAttributeValue(null, "url")
                            if (type?.startsWith("audio/") == true && !url.isNullOrBlank()) {
                                audioUrl = url
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name.lowercase() in setOf("item", "entry")) {
                        if (inItem && !title.isNullOrBlank()) {
                            items += ParsedFeedItem(
                                title = title!!,
                                link = link,
                                description = description,
                                published = published,
                                audioUrl = audioUrl,
                                guid = guid,
                            )
                        }
                        inItem = false
                    }
                }
            }
            event = parser.next()
        }
        return items
    }
}
