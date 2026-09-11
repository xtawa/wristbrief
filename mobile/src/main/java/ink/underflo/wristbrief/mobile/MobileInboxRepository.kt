package ink.underflo.wristbrief.mobile

import android.content.Context
import java.io.InputStream
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
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
): String = guid?.trim()?.takeIf { it.isNotBlank() }?.let { "guid:$it" }
    ?: normalizeIdentityUrl(link)?.let { "link:$it" }
    ?: normalizeIdentityUrl(audioUrl)?.let { "audio:$it" }
    ?: "fallback:$feedId:${title.trim()}:${published.orEmpty().trim()}"

interface MobileInboxStore {
    fun load(): List<MobileFeedItem>
    fun save(items: List<MobileFeedItem>)
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
            val stream = resp.body?.byteStream() ?: return@withContext emptyList()
            parser.parse(stream)
        }
    }
}

interface ItemStateReaderAndWriter {
    fun isRead(itemId: String): Boolean
    fun isSaved(itemId: String): Boolean
    fun setRead(itemId: String, isRead: Boolean)
    fun setSaved(itemId: String, isSaved: Boolean)
}

class SyncManagerItemStateAdapter(
    private val syncManager: PhoneItemStateSyncManager,
    private val clock: () -> Long = System::currentTimeMillis,
) : ItemStateReaderAndWriter {
    override fun isRead(itemId: String): Boolean = syncManager.state(itemId)?.read?.value == true
    override fun isSaved(itemId: String): Boolean = syncManager.state(itemId)?.saved?.value == true
    override fun setRead(itemId: String, isRead: Boolean) {
        syncManager.recordRead(itemId, isRead, nowEpochMs = clock())
    }
    override fun setSaved(itemId: String, isSaved: Boolean) {
        syncManager.recordSaved(itemId, isSaved, nowEpochMs = clock())
    }
}

class MobileInboxRepository(
    private val feedManager: MobileFeedManager,
    private val store: MobileInboxStore,
    private val stateAdapter: ItemStateReaderAndWriter,
    private val fetcher: FeedItemFetcher,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun items(): List<MobileFeedItem> = store.load()

    fun isRead(itemId: String): Boolean = stateAdapter.isRead(itemId)

    fun isSaved(itemId: String): Boolean = stateAdapter.isSaved(itemId)

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
            store.save(emptyList())
            return MobileRefreshResult(totalCount = 0)
        }

        val failedTitles = mutableListOf<String>()
        val fetchedItems = mutableListOf<MobileFeedItem>()
        val now = clock()

        for (feed in feeds) {
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
                fetchedItems.addAll(items)
            } catch (e: Exception) {
                failedTitles.add(feed.title)
            }
        }

        val previous = store.load()
        val retained = if (failedTitles.isNotEmpty()) {
            val failedIds = feeds.filter { it.title in failedTitles }.map { it.id }.toSet()
            previous.filter { it.feedId in failedIds }
        } else {
            emptyList()
        }

        val combined = (fetchedItems + retained)
            .distinctBy { it.id }
            .sortedByDescending { it.cachedAtEpochMs }
            .take(200)

        store.save(combined)

        return MobileRefreshResult(
            totalCount = combined.size,
            failedFeedTitles = failedTitles,
            isOfflineFallback = failedTitles.isNotEmpty() && combined.isNotEmpty(),
        )
    }
}
