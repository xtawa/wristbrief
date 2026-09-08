package ink.underflo.wristbrief.data

import java.nio.charset.StandardCharsets
import java.util.Base64

object FeedStoreCodec {
    private const val VERSION = "v1"
    private const val ITEM_VERSION = "v2"

    fun encodeSubscriptions(items: List<FeedSubscription>): String = buildString {
        appendLine(VERSION)
        items.forEach { item ->
            append(item.id.enc()).append('\t')
            append(item.title.enc()).append('\t')
            append(item.url.enc()).append('\t')
            append(if (item.enabled) "1" else "0").append('\n')
        }
    }

    fun decodeSubscriptions(raw: String?): List<FeedSubscription> =
        decodeLines(raw, VERSION, expectedFields = 4) { fields ->
            FeedSubscription(
                id = fields[0].dec(),
                title = fields[1].dec(),
                url = fields[2].dec(),
                enabled = fields[3] == "1"
            )
        }.filter { it.id.isNotBlank() && it.url.startsWith("https://") }

    fun encodeItems(items: List<CachedFeedItem>): String = buildString {
        appendLine(ITEM_VERSION)
        items.forEach { item ->
            append(item.id.enc()).append('\t')
            append(item.feedId.enc()).append('\t')
            append(item.feedTitle.enc()).append('\t')
            append(item.title.enc()).append('\t')
            append(item.link.orEmpty().enc()).append('\t')
            append(item.description.orEmpty().enc()).append('\t')
            append(item.published.orEmpty().enc()).append('\t')
            append(item.audioUrl.orEmpty().enc()).append('\t')
            append(item.transcript?.url.orEmpty().enc()).append('\t')
            append(item.transcript?.type.orEmpty().enc()).append('\t')
            append(item.transcript?.language.orEmpty().enc()).append('\t')
            append(item.transcript?.rel.orEmpty().enc()).append('\t')
            append(item.cachedAtEpochMs).append('\n')
        }
    }

    fun decodeItems(raw: String?): List<CachedFeedItem> {
        if (raw.isNullOrBlank()) return emptyList()
        val version = raw.lineSequence().firstOrNull() ?: return emptyList()
        val decoded = when (version) {
            VERSION -> decodeLines(raw, VERSION, expectedFields = 9) { fields ->
                CachedFeedItem(
                    id = fields[0].dec(),
                    feedId = fields[1].dec(),
                    feedTitle = fields[2].dec(),
                    title = fields[3].dec(),
                    link = fields[4].dec().ifBlank { null },
                    description = fields[5].dec().ifBlank { null },
                    published = fields[6].dec().ifBlank { null },
                    audioUrl = fields[7].dec().ifBlank { null },
                    cachedAtEpochMs = fields[8].toLong()
                )
            }
            ITEM_VERSION -> decodeLines(raw, ITEM_VERSION, expectedFields = 13) { fields ->
                val transcriptUrl = fields[8].dec().ifBlank { null }
                val transcriptType = fields[9].dec().ifBlank { null }
                CachedFeedItem(
                    id = fields[0].dec(),
                    feedId = fields[1].dec(),
                    feedTitle = fields[2].dec(),
                    title = fields[3].dec(),
                    link = fields[4].dec().ifBlank { null },
                    description = fields[5].dec().ifBlank { null },
                    published = fields[6].dec().ifBlank { null },
                    audioUrl = fields[7].dec().ifBlank { null },
                    transcript = if (transcriptUrl != null && transcriptType != null) {
                        PodcastTranscript(
                            url = transcriptUrl,
                            type = transcriptType,
                            language = fields[10].dec().ifBlank { null },
                            rel = fields[11].dec().ifBlank { null }
                        )
                    } else null,
                    cachedAtEpochMs = fields[12].toLong()
                )
            }
            else -> emptyList()
        }
        return decoded.filter { it.id.isNotBlank() && it.feedId.isNotBlank() && it.title.isNotBlank() }
    }

    fun encodeItemIds(itemIds: Set<String>): String = buildString {
        appendLine(VERSION)
        itemIds.sorted().forEach { append(it.enc()).append('\n') }
    }

    fun decodeItemIds(raw: String?): Set<String> {
        if (raw.isNullOrBlank()) return emptySet()
        val lines = raw.lineSequence().toList()
        if (lines.firstOrNull() != VERSION) return emptySet()
        return lines.drop(1)
            .mapNotNull { encoded ->
                encoded.takeIf { it.isNotBlank() }
                    ?.let { runCatching { it.dec() }.getOrNull() }
                    ?.takeIf { it.isNotBlank() }
            }
            .toSet()
    }

    private inline fun <T> decodeLines(
        raw: String?,
        version: String,
        expectedFields: Int,
        transform: (List<String>) -> T
    ): List<T> {
        if (raw.isNullOrBlank()) return emptyList()
        val lines = raw.lineSequence().toList()
        if (lines.firstOrNull() != version) return emptyList()
        return lines.drop(1).mapNotNull { line ->
            if (line.isBlank()) return@mapNotNull null
            val fields = line.split('\t')
            if (fields.size != expectedFields) return@mapNotNull null
            runCatching { transform(fields) }.getOrNull()
        }
    }

    private fun String.enc(): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(toByteArray(StandardCharsets.UTF_8))

    private fun String.dec(): String = if (isBlank()) "" else String(
        Base64.getUrlDecoder().decode(this),
        StandardCharsets.UTF_8
    )
}
