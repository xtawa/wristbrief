package ink.underflo.wristbrief.mobile

import android.content.Context
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import java.net.URI
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import ink.underflo.wristbrief.mobile.sync.CloudSyncOutbox
import ink.underflo.wristbrief.mobile.sync.enqueueSubscription

data class MobileFeedSubscription(
    val id: String,
    val title: String,
    val url: String,
    val enabled: Boolean = true,
    val sendToWatch: Boolean = true,
    val category: String? = null,
    val watchKeywords: List<String> = emptyList(),
)

sealed interface FeedMutationResult {
    data class Success(val feeds: List<MobileFeedSubscription>) : FeedMutationResult
    data class Error(val message: String) : FeedMutationResult
}

fun normalizeFeedUrl(raw: String): String? = runCatching {
    val uri = URI(raw.trim())
    if (!uri.scheme.equals("https", true) || uri.host.isNullOrBlank() || uri.userInfo != null) return null
    URI("https", null, uri.host.lowercase(), if (uri.port == 443) -1 else uri.port, uri.path.ifBlank { "/" }, uri.query, null).toASCIIString()
}.getOrNull()

sealed interface FeedUrlValidationResult {
    data object Valid : FeedUrlValidationResult
    data object Empty : FeedUrlValidationResult
    data object NotHttps : FeedUrlValidationResult
    data object InvalidFormat : FeedUrlValidationResult
    data object Duplicate : FeedUrlValidationResult
}

fun validateFeedUrlInput(
    rawUrl: String,
    existingUrls: Set<String>,
    currentFeedUrl: String? = null,
): FeedUrlValidationResult {
    val trimmed = rawUrl.trim()
    if (trimmed.isBlank()) return FeedUrlValidationResult.Empty
    if (!trimmed.startsWith("https://", ignoreCase = true)) return FeedUrlValidationResult.NotHttps
    val normalized = normalizeFeedUrl(trimmed) ?: return FeedUrlValidationResult.InvalidFormat
    val currentNormalized = currentFeedUrl?.let(::normalizeFeedUrl)
    val existingNormalized = existingUrls.mapNotNull(::normalizeFeedUrl).toSet()
    if (normalized != currentNormalized && normalized in existingNormalized) {
        return FeedUrlValidationResult.Duplicate
    }
    return FeedUrlValidationResult.Valid
}

fun normalizeFeedCategory(raw: String?): String? = raw?.trim()?.replace(Regex("\\s+"), " ")?.take(80)?.takeIf { it.isNotBlank() }

fun normalizeWatchKeywords(values: Iterable<String>): List<String> {
    val seen = linkedSetOf<String>()
    return values.asSequence()
        .map { it.trim().replace(Regex("\\s+"), " ").take(48) }
        .filter { it.isNotBlank() }
        .filter { seen.add(it.lowercase()) }
        .take(12)
        .toList()
}

fun normalizeWatchKeywords(raw: String): List<String> = normalizeWatchKeywords(raw.split(',', '\n'))

fun stableFeedId(url: String): String = MessageDigest.getInstance("SHA-256")
    .digest(url.toByteArray()).take(12).joinToString("") { "%02x".format(it) }

interface FeedProbe { suspend fun validate(url: String): String? }

class HttpFeedProbe(private val client: OkHttpClient = OkHttpClient()) : FeedProbe {
    override suspend fun validate(url: String): String? = withContext(Dispatchers.IO) {
        val response = client.newCall(Request.Builder().url(url).header("User-Agent", "WristBrief-Mobile/0.1").build()).execute()
        response.use {
            check(it.isSuccessful) { "Feed returned HTTP ${it.code}" }
            check(it.request.url.isHttps) { "Feed redirected to non-HTTPS URL" }
            val sample = it.peekBody(128 * 1024).string()
            val isFeed = Regex("<(rss|feed|rdf:RDF)(\\s|>)", RegexOption.IGNORE_CASE).containsMatchIn(sample)
            check(isFeed) { "URL did not return an RSS/Atom feed" }
            Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                .find(sample)?.groupValues?.get(1)?.replace(Regex("<[^>]+>"), "")?.trim()?.take(120)
        }
    }
}

interface MobileFeedStore {
    fun load(): List<MobileFeedSubscription>
    fun save(feeds: List<MobileFeedSubscription>)
}

internal fun decodeMobileFeedSubscriptions(raw: String): List<MobileFeedSubscription> {
    val json = Json { ignoreUnknownKeys = true }
    return json.parseToJsonElement(raw).jsonArray.map { element ->
        val o = element.jsonObject
        MobileFeedSubscription(
            id = o["id"]!!.jsonPrimitive.content,
            title = o["title"]!!.jsonPrimitive.content,
            url = o["url"]!!.jsonPrimitive.content,
            enabled = o["enabled"]?.jsonPrimitive?.booleanOrNull ?: true,
            sendToWatch = o["sendToWatch"]?.jsonPrimitive?.booleanOrNull ?: true,
            category = normalizeFeedCategory(o["category"]?.jsonPrimitive?.content),
            watchKeywords = normalizeWatchKeywords(
                o["watchKeywords"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(),
            ),
        )
    }
}

internal fun encodeMobileFeedSubscriptions(feeds: List<MobileFeedSubscription>): String = buildJsonArray {
    feeds.forEach { feed ->
        add(buildJsonObject {
            put("id", feed.id)
            put("title", feed.title)
            put("url", feed.url)
            put("enabled", feed.enabled)
            put("sendToWatch", feed.sendToWatch)
            normalizeFeedCategory(feed.category)?.let { put("category", it) }
            val keywords = normalizeWatchKeywords(feed.watchKeywords)
            if (keywords.isNotEmpty()) {
                put("watchKeywords", buildJsonArray { keywords.forEach { add(JsonPrimitive(it)) } })
            }
        })
    }
}.toString()

class SharedPreferencesMobileFeedStore(context: Context) : MobileFeedStore {
    private val prefs = context.applicationContext.getSharedPreferences("wristbrief_mobile_feeds", Context.MODE_PRIVATE)
    override fun load(): List<MobileFeedSubscription> = runCatching {
        val raw = prefs.getString("subscriptions_v1", null) ?: return emptyList()
        decodeMobileFeedSubscriptions(raw)
    }.getOrDefault(emptyList())
    override fun save(feeds: List<MobileFeedSubscription>) {
        prefs.edit().putString("subscriptions_v1", encodeMobileFeedSubscriptions(feeds)).apply()
    }
}

interface FeedSyncPublisher { fun publish(feeds: List<MobileFeedSubscription>) }

/**
 * Structural lifecycle notifications for subscription changes. Additions and
 * removals are content-affecting (the inbox should refresh); attribute toggles
 * are not. Cloud outbox enqueueing happens inside the manager itself.
 */
interface MobileFeedListener {
    fun onSubscriptionAdded(feed: MobileFeedSubscription) {}
    fun onSubscriptionUpdated(feed: MobileFeedSubscription) {}
    fun onSubscriptionRemoved(feed: MobileFeedSubscription) {}
}

internal fun wearSyncPayloadFor(feeds: List<MobileFeedSubscription>): WearSyncPayload = WearSyncPayload(
    subscriptions = feeds.filter { it.sendToWatch }.map {
        SyncFeed(it.id, it.title, it.url, it.enabled, normalizeWatchKeywords(it.watchKeywords))
    },
)

class GoogleWearFeedSyncPublisher(context: Context) : FeedSyncPublisher {
    private val dataClient = Wearable.getDataClient(context.applicationContext)
    override fun publish(feeds: List<MobileFeedSubscription>) {
        val payload = wearSyncPayloadFor(feeds)
        val request = PutDataMapRequest.create(WearDataLayerContract.PATH).apply {
            dataMap.putString(WearDataLayerContract.PAYLOAD_KEY, WearDataLayerContract.encode(payload))
            dataMap.putLong("updatedAt", System.currentTimeMillis())
        }.asPutDataRequest().setUrgent()
        dataClient.putDataItem(request)
    }
}

class MobileFeedManager(
    private val store: MobileFeedStore,
    private val probe: FeedProbe,
    private val publisher: FeedSyncPublisher,
    private val cloudOutbox: CloudSyncOutbox? = null,
    private val listener: MobileFeedListener? = null,
) {
    fun feeds(): List<MobileFeedSubscription> = store.load()

    suspend fun add(
        rawUrl: String,
        title: String,
        category: String? = null,
        watchKeywords: List<String> = emptyList(),
        sendToWatch: Boolean = true,
    ): FeedMutationResult {
        val url = normalizeFeedUrl(rawUrl) ?: return FeedMutationResult.Error("Use a valid HTTPS feed URL")
        if (store.load().any { normalizeFeedUrl(it.url) == url }) return FeedMutationResult.Error("Feed is already subscribed")
        val discovered = runCatching { probe.validate(url) }.getOrElse { return FeedMutationResult.Error(it.message ?: "Feed validation failed") }
        val feed = MobileFeedSubscription(
            stableFeedId(url),
            title.trim().ifBlank { discovered ?: URI(url).host },
            url,
            sendToWatch = sendToWatch,
            category = normalizeFeedCategory(category),
            watchKeywords = normalizeWatchKeywords(watchKeywords),
        )
        val result = persist(store.load() + feed)
        notifyAdded(feed)
        return result
    }

    suspend fun update(
        id: String,
        rawUrl: String,
        title: String,
        category: String? = null,
        watchKeywords: List<String>? = null,
        sendToWatch: Boolean? = null,
    ): FeedMutationResult {
        val url = normalizeFeedUrl(rawUrl) ?: return FeedMutationResult.Error("Use a valid HTTPS feed URL")
        val current = store.load(); val old = current.find { it.id == id } ?: return FeedMutationResult.Error("Feed no longer exists")
        if (current.any { it.id != id && normalizeFeedUrl(it.url) == url }) return FeedMutationResult.Error("Feed is already subscribed")
        val discovered = runCatching { probe.validate(url) }.getOrElse { return FeedMutationResult.Error(it.message ?: "Feed validation failed") }
        val updated = old.copy(
            id = stableFeedId(url),
            url = url,
            title = title.trim().ifBlank { discovered ?: old.title },
            sendToWatch = sendToWatch ?: old.sendToWatch,
            category = normalizeFeedCategory(category),
            watchKeywords = watchKeywords?.let(::normalizeWatchKeywords) ?: old.watchKeywords,
        )
        val result = persist(current.map { if (it.id == id) updated else it })
        notifyAdded(updated)
        return result
    }

    fun setEnabled(id: String, enabled: Boolean): FeedMutationResult =
        mutateAttribute(id) { it.copy(enabled = enabled) }

    fun setSendToWatch(id: String, sendToWatch: Boolean): FeedMutationResult =
        mutateAttribute(id) { it.copy(sendToWatch = sendToWatch) }

    fun setCategory(id: String, category: String?): FeedMutationResult =
        mutateAttribute(id) { it.copy(category = normalizeFeedCategory(category)) }

    fun setWatchKeywords(id: String, watchKeywords: Iterable<String>): FeedMutationResult =
        mutateAttribute(id) { it.copy(watchKeywords = normalizeWatchKeywords(watchKeywords)) }

    fun remove(id: String): FeedMutationResult {
        val existing = store.load().find { it.id == id } ?: return FeedMutationResult.Error("Feed no longer exists")
        val result = persist(store.load().filterNot { it.id == id })
        notifyRemoved(existing)
        return result
    }

    internal suspend fun validateForBulkImport(url: String): String? = probe.validate(url)

    internal fun persistBulkImport(feeds: List<MobileFeedSubscription>): FeedMutationResult.Success {
        val before = store.load().map { it.id }.toSet()
        val result = persist(feeds)
        feeds.filter { it.id !in before }.forEach(::notifyAdded)
        return result
    }

    private fun persist(feeds: List<MobileFeedSubscription>): FeedMutationResult.Success {
        store.save(feeds); publisher.publish(feeds); return FeedMutationResult.Success(feeds)
    }

    private fun mutateAttribute(id: String, transform: (MobileFeedSubscription) -> MobileFeedSubscription): FeedMutationResult {
        val current = store.load()
        val old = current.find { it.id == id } ?: return FeedMutationResult.Error("Feed no longer exists")
        val updated = transform(old)
        val result = persist(current.map { if (it.id == id) updated else it })
        notifyUpdated(updated)
        return result
    }

    private fun notifyAdded(feed: MobileFeedSubscription) {
        cloudOutbox?.enqueueSubscription(
            subscriptionId = feed.id,
            feedUrl = feed.url,
            nowEpochMs = System.currentTimeMillis(),
            title = feed.title,
            category = feed.category,
            enabled = feed.enabled,
            sendToWatch = feed.sendToWatch,
            watchKeywords = feed.watchKeywords,
        )
        listener?.onSubscriptionAdded(feed)
    }

    private fun notifyUpdated(feed: MobileFeedSubscription) {
        cloudOutbox?.enqueueSubscription(
            subscriptionId = feed.id,
            feedUrl = feed.url,
            nowEpochMs = System.currentTimeMillis(),
            title = feed.title,
            category = feed.category,
            enabled = feed.enabled,
            sendToWatch = feed.sendToWatch,
            watchKeywords = feed.watchKeywords,
        )
        listener?.onSubscriptionUpdated(feed)
    }

    /** Deletion is a cloud tombstone (user_subscriptions.deleted_at), never a global content delete. */
    private fun notifyRemoved(feed: MobileFeedSubscription) {
        cloudOutbox?.enqueueSubscription(
            subscriptionId = feed.id,
            feedUrl = feed.url,
            nowEpochMs = System.currentTimeMillis(),
            isDeleted = true,
        )
        listener?.onSubscriptionRemoved(feed)
    }
}
