package ink.underflo.wristbrief.mobile

import android.content.Context
import java.io.InputStream
import java.io.FilterInputStream
import kotlinx.coroutines.CancellationException
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import ink.underflo.wristbrief.mobile.sync.enqueueItemState
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class MobileFeedItem(
    val id: String,
    val feedId: String,
    val feedTitle: String,
    val title: String,
    val link: String?,
    val description: String?,
    val published: String?,
    val audioUrl: String?,
    val cachedAtEpochMs: Long,
)

data class MobileRefreshResult(
    val totalCount: Int,
    val failedFeedTitles: List<String> = emptyList(),
    val isOfflineFallback: Boolean = false,
)

fun normalizeIdentityUrl(value: String?): String? {
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

fun mobileItemId(
    feedId: String,
    guid: String?,
    link: String?,
    audioUrl: String?,
    title: String,
    published: String?,
): String {
    val raw = guid?.trim()?.takeIf { it.isNotBlank() }?.let { "guid:$it" }
        ?: normalizeIdentityUrl(link)?.let { "link:$it" }
        ?: normalizeIdentityUrl(audioUrl)?.let { "audio:$it" }
        ?: "fallback:${title.trim()}:${published.orEmpty().trim()}"
    return if (raw.startsWith("$feedId:")) raw else "$feedId:$raw"
}

interface MobileInboxStore {
    fun load(): List<MobileFeedItem>
    fun save(items: List<MobileFeedItem>)
    /** Saved articles are outside the rolling cache's retention budget. */
    fun saveRetaining(items: List<MobileFeedItem>, protectedIds: Set<String>) = save(items)
    /** Optional capability: drop cached items belonging to one feed (subscription deletion cleanup). */
    fun deleteItemsForFeed(feedId: String) {}
}

internal fun decodeMobileFeedItems(raw: String): List<MobileFeedItem> = runCatching {
    val json = Json { ignoreUnknownKeys = true }
    json.parseToJsonElement(raw).jsonArray.map { element ->
        val obj = element.jsonObject
        MobileFeedItem(
            id = obj["id"]!!.jsonPrimitive.content,
            feedId = obj["feedId"]!!.jsonPrimitive.content,
            feedTitle = obj["feedTitle"]!!.jsonPrimitive.content,
            title = obj["title"]!!.jsonPrimitive.content,
            link = obj["link"]?.jsonPrimitive?.content,
            description = obj["description"]?.jsonPrimitive?.content,
            published = obj["published"]?.jsonPrimitive?.content,
            audioUrl = obj["audioUrl"]?.jsonPrimitive?.content,
            cachedAtEpochMs = obj["cachedAtEpochMs"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis(),
        )
    }
}.getOrDefault(emptyList())

internal fun encodeMobileFeedItems(items: List<MobileFeedItem>): String = buildJsonArray {
    items.forEach { item ->
        add(buildJsonObject {
            put("id", item.id)
            put("feedId", item.feedId)
            put("feedTitle", item.feedTitle)
            put("title", item.title)
            item.link?.let { put("link", it) }
            item.description?.let { put("description", it) }
            item.published?.let { put("published", it) }
            item.audioUrl?.let { put("audioUrl", it) }
            put("cachedAtEpochMs", item.cachedAtEpochMs)
        })
    }
}.toString()

class SharedPreferencesMobileInboxStore(context: Context) : MobileInboxStore {
    private val prefs = context.applicationContext.getSharedPreferences("wristbrief_mobile_inbox", Context.MODE_PRIVATE)

    override fun load(): List<MobileFeedItem> {
        val raw = prefs.getString("items_v1", null) ?: return emptyList()
        return decodeMobileFeedItems(raw)
    }

    override fun save(items: List<MobileFeedItem>) {
        prefs.edit().putString("items_v1", encodeMobileFeedItems(items)).apply()
    }
}

interface FeedItemFetcher {
    suspend fun fetch(url: String): List<ParsedFeedItem>
}

class HttpFeedItemFetcher(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build(),
    private val parser: MobileFeedParser = MobileFeedParser(),
) : FeedItemFetcher {
    override suspend fun fetch(url: String): List<ParsedFeedItem> = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "WristBrief-Mobile/0.1")
            .build()
        val response = client.newCall(req).execute()
        response.use { resp ->
            check(resp.isSuccessful) { "HTTP ${resp.code}" }
            check(resp.request.url.isHttps) { "Redirected to insecure URL" }
            val body = resp.body ?: return@withContext emptyList()
            check(body.contentLength() <= MAX_FEED_BYTES) { "Feed exceeds the 4 MiB limit" }
            parser.parse(BoundedFeedInputStream(body.byteStream(), MAX_FEED_BYTES))
        }
    }
}

internal const val MAX_FEED_BYTES = 4L * 1024 * 1024

internal class BoundedFeedInputStream(stream: InputStream, private val maxBytes: Long) : FilterInputStream(stream) {
    private var consumed = 0L
    override fun read(): Int = `in`.read().also { if (it != -1) checkLimit(1) }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        `in`.read(buffer, offset, length).also { if (it > 0) checkLimit(it.toLong()) }
    override fun skip(n: Long): Long = `in`.skip(n).also { checkLimit(it) }
    private fun checkLimit(count: Long) {
        consumed += count
        if (consumed > maxBytes) throw java.io.IOException("Feed exceeds the 4 MiB limit")
    }
}

data class MobileLibraryItemState(val isRead: Boolean = false, val isSaved: Boolean = false)

interface ItemStateReaderAndWriter {
    fun isRead(itemId: String): Boolean
    fun isSaved(itemId: String): Boolean
    fun setRead(itemId: String, isRead: Boolean)
    fun setSaved(itemId: String, isSaved: Boolean)

    /** Default keeps existing adapters compatible; persistent adapters can batch reads. */
    fun itemStates(itemIds: Collection<String>): Map<String, MobileLibraryItemState> =
        itemIds.associateWith { MobileLibraryItemState(isRead(it), isSaved(it)) }
}

class SyncManagerItemStateAdapter(
    private val syncManager: PhoneItemStateSyncManager,
    private val clock: () -> Long = System::currentTimeMillis,
    private val cloudOutbox: ink.underflo.wristbrief.mobile.sync.CloudSyncOutbox? = null,
    private val onCloudMutation: (() -> Unit)? = null,
) : ItemStateReaderAndWriter {
    override fun isRead(itemId: String): Boolean = syncManager.state(itemId)?.read?.value == true
    override fun isSaved(itemId: String): Boolean = syncManager.state(itemId)?.saved?.value == true
    override fun itemStates(itemIds: Collection<String>): Map<String, MobileLibraryItemState> {
        val snapshot = syncManager.states()
        return itemIds.associateWith { id ->
            MobileLibraryItemState(
                isRead = snapshot[id]?.read?.value == true,
                isSaved = snapshot[id]?.saved?.value == true,
            )
        }
    }
    override fun setRead(itemId: String, isRead: Boolean) {
        val now = clock()
        syncManager.recordRead(itemId, isRead, nowEpochMs = now)
        cloudOutbox?.let { outbox ->
            outbox.enqueueItemState(itemId, isRead = isRead, nowEpochMs = now)
            onCloudMutation?.invoke()
        }
    }
    override fun setSaved(itemId: String, isSaved: Boolean) {
        val now = clock()
        syncManager.recordSaved(itemId, isSaved, nowEpochMs = now)
        cloudOutbox?.let { outbox ->
            outbox.enqueueItemState(itemId, isSaved = isSaved, nowEpochMs = now)
            onCloudMutation?.invoke()
        }
    }
}

class MobileInboxRepository(
    private val feedManager: MobileFeedManager,
    private val store: MobileInboxStore,
    private val stateAdapter: ItemStateReaderAndWriter,
    private val fetcher: FeedItemFetcher,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Invoked with the refresh time after every completed [refresh] that contacted at least one feed. */
    private val onRefreshCompleted: ((Long) -> Unit)? = null,
) {
    fun items(): List<MobileFeedItem> = store.load()

    fun isRead(itemId: String): Boolean = stateAdapter.isRead(itemId)

    fun isSaved(itemId: String): Boolean = stateAdapter.isSaved(itemId)

    fun itemStates(itemIds: Collection<String>): Map<String, MobileLibraryItemState> =
        stateAdapter.itemStates(itemIds)

    fun setRead(itemId: String, isRead: Boolean) {
        stateAdapter.setRead(itemId, isRead)
    }

    fun setSaved(itemId: String, isSaved: Boolean) {
        stateAdapter.setSaved(itemId, isSaved)
    }

    fun unreadItems(): List<MobileFeedItem> = items().filterNot { isRead(it.id) }

    fun savedItems(): List<MobileFeedItem> = items().filter { isSaved(it.id) }

    suspend fun refresh(): MobileRefreshResult {
        val feeds = feedManager.feeds().filter { it.enabled }
        if (feeds.isEmpty()) {
            val subscribedIds = feedManager.feeds().map { it.id }.toSet()
            val cached = store.load().filter { it.feedId in subscribedIds }
            val savedIds = itemStates(cached.map { it.id }).filterValues { it.isSaved }.keys
            store.saveRetaining(cached, savedIds)
            return MobileRefreshResult(totalCount = cached.size)
        }

        val failedTitles = mutableListOf<String>()
        val fetchedItems = mutableListOf<MobileFeedItem>()
        val now = clock()

        val semaphore = Semaphore(4)
        val feedResults = coroutineScope {
            feeds.map { feed ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        try {
                            val parsed = fetcher.fetch(feed.url)
                            val items = parsed.map { p ->
                                MobileFeedItem(
                                    id = mobileItemId(feed.id, p.guid, p.link, p.audioUrl, p.title, p.published),
                                    feedId = feed.id,
                                    feedTitle = feed.title,
                                    title = p.title,
                                    link = p.link,
                                    description = p.description,
                                    published = p.published,
                                    audioUrl = p.audioUrl,
                                    cachedAtEpochMs = now,
                                )
                            }
                            Result.success(Pair(feed, items))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Result.failure(e)
                        }
                    }
                }
            }.awaitAll()
        }

        feedResults.forEachIndexed { index, res ->
            val feed = feeds[index]
            res.onSuccess { (_, items) ->
                fetchedItems.addAll(items)
            }.onFailure {
                failedTitles.add(feed.title)
            }
        }

        val previous = store.load()
        val failedIds = feeds.indices.filter { feedResults[it].isFailure }.map { feeds[it].id }.toSet()
        val subscribedIds = feedManager.feeds().map { it.id }.toSet()
        val savedIds = itemStates((previous + fetchedItems).map { it.id }).filterValues { it.isSaved }.keys
        val retained = previous.filter {
            it.feedId in failedIds || (it.feedId in subscribedIds && it.id in savedIds)
        }
        val combined = (fetchedItems + retained)
            .distinctBy { it.id }
            .sortedByDescending { it.cachedAtEpochMs }
        val stored = (combined.filter { it.id in savedIds } +
            combined.filterNot { it.id in savedIds }.take(500)).distinctBy { it.id }
        store.saveRetaining(stored, savedIds)
        onRefreshCompleted?.invoke(now)

        return MobileRefreshResult(
            totalCount = stored.size,
            failedFeedTitles = failedTitles,
            isOfflineFallback = failedTitles.isNotEmpty() && combined.isNotEmpty(),
        )
    }
}
