package ink.underflo.wristbrief.mobile.articles

import android.content.Context
import ink.underflo.wristbrief.mobile.BuildConfig
import ink.underflo.wristbrief.mobile.validGatewayOrigin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Loads structured ArticleDocuments from the gateway (POST /v1/articles/resolve)
 * with a bounded on-disk cache for offline reading. Content priority — RSS
 * full-content vs server-side extraction — is decided by the gateway; the phone
 * never fetches article pages or renders HTML itself. The cached payload is the
 * gateway's own JSON, so one decoder serves both cache and network.
 */
class ArticleRepository(
    private val context: Context,
    private val baseUrl: String = BuildConfig.GATEWAY_BASE_URL,
    private val sessionTokenProvider: () -> String?,
) {
    suspend fun loadArticle(url: String?, rssContent: String?, title: String?, sourceName: String?): ArticleDocument? =
        withContext(Dispatchers.IO) {
            if (url.isNullOrBlank()) return@withContext null
            val key = articleCacheKey(url)
            readDiskCache(key)?.let { return@withContext it }
            val raw = resolveRemote(url, rssContent, title, sourceName) ?: return@withContext null
            decodeArticleDocument(raw)?.also { document ->
                runCatching {
                    val file = cacheFile(key)
                    file.parentFile?.mkdirs()
                    file.writeText(raw)
                }
            }
        }

    fun mediaUrl(mediaId: String): String? {
        val origin = validGatewayOrigin(baseUrl) ?: return null
        return "$origin/v1/media/$mediaId"
    }

    private fun resolveRemote(url: String, rssContent: String?, title: String?, sourceName: String?): String? {
        val sessionToken = sessionTokenProvider() ?: return null
        val origin = validGatewayOrigin(baseUrl) ?: return null
        return runCatching {
            val conn = (URL("$origin/v1/articles/resolve").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("Authorization", "Bearer $sessionToken")
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
            }
            val body = buildJsonObject {
                put("url", url)
                if (!rssContent.isNullOrBlank()) put("content", rssContent)
                if (!title.isNullOrBlank()) put("title", title)
                if (!sourceName.isNullOrBlank()) put("sourceName", sourceName)
            }.toString()
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }
            if (conn.responseCode !in 200..299) return@runCatching null
            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
        }.getOrNull()
    }

    private fun cacheFile(key: String): File = File(File(context.filesDir, "articles"), "$key.json")

    private fun readDiskCache(key: String): ArticleDocument? = runCatching {
        val file = cacheFile(key)
        if (!file.isFile) return null
        if (System.currentTimeMillis() - file.lastModified() > CACHE_TTL_MS) {
            file.delete()
            return null
        }
        decodeArticleDocument(file.readText())
    }.getOrNull()

    companion object {
        private const val CACHE_TTL_MS = 24 * 60 * 60 * 1000L

        fun articleCacheKey(url: String): String =
            MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
