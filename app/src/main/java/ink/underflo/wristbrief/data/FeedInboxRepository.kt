package ink.underflo.wristbrief.data

import java.net.URI

/** A user-selected feed that should participate in the local inbox. */
data class FeedSubscription(
    val id: String,
    val title: String,
    val url: String,
    val enabled: Boolean = true
)

sealed interface SubscriptionMutationResult {
    data object Success : SubscriptionMutationResult
    data class InvalidUrl(val url: String) : SubscriptionMutationResult
    data class DuplicateSubscription(val existingId: String) : SubscriptionMutationResult
    data class MissingSubscription(val id: String) : SubscriptionMutationResult
}

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

    fun addSubscription(subscription: FeedSubscription): SubscriptionMutationResult {
        if (!isValidSubscriptionUrl(subscription.url)) {
            return SubscriptionMutationResult.InvalidUrl(subscription.url)
        }
        duplicateSubscriptionId(subscription.url, excludingId = null)?.let {
            return SubscriptionMutationResult.DuplicateSubscription(it)
        }

        store.saveSubscriptions(store.subscriptions() + subscription.normalizedForStorage())
        return SubscriptionMutationResult.Success
    }

    fun updateSubscription(subscription: FeedSubscription): SubscriptionMutationResult {
        if (!isValidSubscriptionUrl(subscription.url)) {
            return SubscriptionMutationResult.InvalidUrl(subscription.url)
        }
        val current = store.subscriptions()
        if (current.none { it.id == subscription.id }) {
            return SubscriptionMutationResult.MissingSubscription(subscription.id)
        }
        duplicateSubscriptionId(subscription.url, excludingId = subscription.id)?.let {
            return SubscriptionMutationResult.DuplicateSubscription(it)
        }

        val normalized = subscription.normalizedForStorage()
        store.saveSubscriptions(current.map { if (it.id == subscription.id) normalized else it })
        return SubscriptionMutationResult.Success
    }

    fun renameSubscription(id: String, title: String): SubscriptionMutationResult {
        val current = store.subscriptions()
        val existing = current.firstOrNull { it.id == id }
            ?: return SubscriptionMutationResult.MissingSubscription(id)
        return updateSubscription(existing.copy(title = title.trim()))
    }

    fun setSubscriptionEnabled(id: String, enabled: Boolean): SubscriptionMutationResult {
        val current = store.subscriptions()
        val existing = current.firstOrNull { it.id == id }
            ?: return SubscriptionMutationResult.MissingSubscription(id)
        if (existing.enabled == enabled) return SubscriptionMutationResult.Success

        store.saveSubscriptions(current.map { if (it.id == id) it.copy(enabled = enabled) else it })
        return SubscriptionMutationResult.Success
    }

    fun removeSubscription(id: String): SubscriptionMutationResult {
        val current = store.subscriptions()
        if (current.none { it.id == id }) {
            return SubscriptionMutationResult.MissingSubscription(id)
        }

        store.saveSubscriptions(current.filterNot { it.id == id })
        store.saveCachedItems(store.cachedItems().filterNot { it.feedId == id })
        return SubscriptionMutationResult.Success
    }

    /**
     * Backward-compatible helper for internal callers that previously relied on upsert semantics.
     * New product flows should prefer explicit add/update operations so duplicate and missing state
     * cannot be silently overwritten.
     */
    fun upsertSubscription(subscription: FeedSubscription): SubscriptionMutationResult {
        return if (store.subscriptions().any { it.id == subscription.id }) {
            updateSubscription(subscription)
        } else {
            addSubscription(subscription)
        }
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

    private fun duplicateSubscriptionId(url: String, excludingId: String?): String? {
        val normalized = normalizeIdentityUrl(url) ?: url.trim()
        return store.subscriptions().firstOrNull { existing ->
            existing.id != excludingId && normalizeIdentityUrl(existing.url) == normalized
        }?.id
    }
}

private fun FeedSubscription.normalizedForStorage(): FeedSubscription = copy(
    title = title.trim(),
    url = normalizeIdentityUrl(url) ?: url.trim()
)

internal fun isValidSubscriptionUrl(value: String): Boolean {
    val raw = value.trim()
    return runCatching {
        val uri = URI(raw)
        uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()
    }.getOrDefault(false)
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
