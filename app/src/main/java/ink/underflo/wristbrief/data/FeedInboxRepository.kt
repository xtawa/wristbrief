package ink.underflo.wristbrief.data

import java.net.URI

/** A user-selected feed that should participate in the local inbox. */
data class FeedSubscription(
    val id: String,
    val title: String,
    val url: String,
    val enabled: Boolean = true
)

/** Persisted feed item used for offline-first rendering. */
data class CachedFeedItem(
    val id: String,
    val feedId: String,
    val feedTitle: String,
    val title: String,
    val link: String?,
    val description: String?,
    val published: String?,
    val audioUrl: String?,
    val cachedAtEpochMs: Long
) {
    fun asFeedItem(): FeedItem = FeedItem(
        title = title,
        link = link,
        description = description,
        published = published,
        audioUrl = audioUrl
    )
}

interface FeedLoader {
    fun load(url: String): List<FeedItem>
}

interface FeedStore {
    fun subscriptions(): List<FeedSubscription>
    fun saveSubscriptions(subscriptions: List<FeedSubscription>)
    fun cachedItems(): List<CachedFeedItem>
    fun saveCachedItems(items: List<CachedFeedItem>)
}

data class FeedRefreshResult(
    val items: List<CachedFeedItem>,
    val failedFeedIds: Set<String>
) {
    val isOfflineFallback: Boolean
        get() = failedFeedIds.isNotEmpty()
}

/**
 * Coordinates remote feed loading with durable local cache.
 *
 * A failed feed refresh never deletes its last known items. Successful feeds
 * replace only their own previous cache, keeping partial refreshes resilient.
 */
class FeedInboxRepository(
    private val loader: FeedLoader,
    private val store: FeedStore,
    private val clock: () -> Long = System::currentTimeMillis
) {
    fun subscriptions(): List<FeedSubscription> = store.subscriptions()

    fun cachedItems(): List<CachedFeedItem> {
        val enabledIds = store.subscriptions()
            .asSequence()
            .filter { it.enabled }
            .mapTo(hashSetOf()) { it.id }
        if (enabledIds.isEmpty()) return emptyList()
        return store.cachedItems().filter { it.feedId in enabledIds }
    }

    fun upsertSubscription(subscription: FeedSubscription) {
        require(subscription.url.startsWith("https://")) { "Only HTTPS feeds are allowed" }
        val updated = store.subscriptions()
            .filterNot { it.id == subscription.id }
            .plus(subscription)
        store.saveSubscriptions(updated)
    }

    fun refresh(): FeedRefreshResult {
        val subscriptions = store.subscriptions().filter { it.enabled }
        val previous = store.cachedItems()
        if (subscriptions.isEmpty()) {
            return FeedRefreshResult(emptyList(), emptySet())
        }

        val failed = linkedSetOf<String>()
        val freshByFeed = linkedMapOf<String, List<CachedFeedItem>>()

        subscriptions.forEach { subscription ->
            val loaded = runCatching { loader.load(subscription.url) }
                .onFailure { failed += subscription.id }
                .getOrNull()
                ?: return@forEach

            val now = clock()
            freshByFeed[subscription.id] = loaded
                .map { item -> item.toCached(subscription, now) }
                .distinctBy { it.id }
        }

        val enabledIds = subscriptions.mapTo(hashSetOf()) { it.id }
        val retained = previous.filter { cached ->
            cached.feedId !in enabledIds || cached.feedId in failed
        }
        val refreshed = freshByFeed.values.flatten()
        val merged = (retained + refreshed)
            .distinctBy { it.id }
            .sortedByDescending { it.cachedAtEpochMs }

        store.saveCachedItems(merged)
        val visible = merged.filter { it.feedId in enabledIds }
        return FeedRefreshResult(visible, failed)
    }
}

private fun FeedItem.toCached(
    subscription: FeedSubscription,
    cachedAtEpochMs: Long
): CachedFeedItem {
    val stableId = guid?.trim()?.takeIf { it.isNotBlank() }?.let { "guid:$it" }
        ?: normalizeIdentityUrl(link)?.let { "link:$it" }
        ?: normalizeIdentityUrl(audioUrl)?.let { "audio:$it" }
        ?: "fallback:${subscription.id}:${title.trim()}:${published.orEmpty().trim()}"

    return CachedFeedItem(
        id = stableId,
        feedId = subscription.id,
        feedTitle = subscription.title,
        title = title,
        link = link,
        description = description,
        published = published,
        audioUrl = audioUrl,
        cachedAtEpochMs = cachedAtEpochMs
    )
}

/**
 * Normalizes only URL differences that are safe for identity comparison without
 * network access: scheme/host casing, default ports, fragments, empty paths and
 * a trailing root-equivalent slash. Query parameters are deliberately preserved.
 */
internal fun normalizeIdentityUrl(value: String?): String? {
    val raw = value?.trim()?.takeIf { it.isNotBlank() } ?: return null
    return runCatching {
        val uri = URI(raw)
        val scheme = uri.scheme?.lowercase() ?: return@runCatching raw
        val host = uri.host?.lowercase() ?: return@runCatching raw
        if (scheme != "http" && scheme != "https") return@runCatching raw

        val port = when {
            uri.port == -1 -> -1
            scheme == "http" && uri.port == 80 -> -1
            scheme == "https" && uri.port == 443 -> -1
            else -> uri.port
        }
        val rawPath = uri.rawPath.orEmpty()
        val path = when {
            rawPath.isEmpty() -> "/"
            rawPath.length > 1 && rawPath.endsWith('/') -> rawPath.dropLast(1)
            else -> rawPath
        }

        URI(scheme, uri.rawUserInfo, host, port, path, uri.rawQuery, null).toASCIIString()
    }.getOrElse { raw }
}
