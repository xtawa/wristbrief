package ink.underflo.wristbrief.data

import org.xmlpull.v1.XmlPullParser

/**
 * Pure RSS/Atom item parser. Network retrieval stays in [FeedRepository] so this
 * class can be exercised with deterministic JVM unit tests.
 */
class FeedParser {
    fun parse(parser: XmlPullParser): List<FeedItem> {
        val items = mutableListOf<FeedItem>()
        var event = parser.eventType
        var inItem = false
        var title: String? = null
        var link: String? = null
        var description: String? = null
        var published: String? = null
        var audioUrl: String? = null
        var guid: String? = null
        var transcript: PodcastTranscript? = null

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
                            transcript = null
                        }

                        "guid", "id" -> if (inItem) {
                            guid = parser.nextText().trim().ifBlank { null }
                        }

                        "title" -> if (inItem) title = parser.nextText().trim().ifBlank { null }
                        "description", "summary", "content", "content:encoded" -> if (inItem) {
                            description = parser.nextText().trim().ifBlank { description }
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

                        "podcast:transcript" -> if (inItem && transcript == null) {
                            transcript = parseTranscript(parser)
                        }
                    }
                }

                XmlPullParser.END_TAG -> {
                    if (parser.name.lowercase() in setOf("item", "entry")) {
                        if (inItem && !title.isNullOrBlank()) {
                            items += FeedItem(
                                title = title!!,
                                link = link,
                                description = description,
                                published = published,
                                audioUrl = audioUrl,
                                guid = guid,
                                transcript = transcript
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

    private fun parseTranscript(parser: XmlPullParser): PodcastTranscript? {
        val url = parser.getAttributeValue(null, "url")?.trim().orEmpty()
        val type = parser.getAttributeValue(null, "type")?.trim().orEmpty()
        if (!url.startsWith("https://", ignoreCase = true) || type.isBlank()) return null

        return PodcastTranscript(
            url = url,
            type = type.lowercase(),
            language = parser.getAttributeValue(null, "language")?.trim()?.ifBlank { null },
            rel = parser.getAttributeValue(null, "rel")?.trim()?.lowercase()?.ifBlank { null }
        )
    }
}
