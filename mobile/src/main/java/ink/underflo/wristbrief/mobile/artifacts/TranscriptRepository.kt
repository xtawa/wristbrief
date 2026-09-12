package ink.underflo.wristbrief.mobile.artifacts

import org.json.JSONObject

data class EpisodeTranscriptRequest(
    val audioUrl: String,
    val feedUrl: String? = null,
    val guid: String? = null,
    val title: String? = null,
    val durationMs: Long? = null,
)

interface TranscriptRepository {
    suspend fun fetchTranscript(request: EpisodeTranscriptRequest): TranscriptFetchResult
    suspend fun pollUntilReady(jobId: String, maxAttempts: Int = 10, delayMs: Long = 1000L): TranscriptFetchResult =
        TranscriptFetchResult.Processing(contentCode = "", jobId = jobId)
    fun getCached(contentCode: String): TranscriptPayload?
}

class DefaultTranscriptRepository(
    private val cacheStore: TranscriptCache,
    private val gatewayApi: TranscriptGatewayApi,
) : TranscriptRepository {

    override fun getCached(contentCode: String): TranscriptPayload? {
        return cacheStore.get(contentCode)
    }

    override suspend fun fetchTranscript(request: EpisodeTranscriptRequest): TranscriptFetchResult {
        val result = gatewayApi.requestTranscript(request)
        if (result is TranscriptFetchResult.Ready && result.payload != null) {
            cacheStore.put(result.contentCode, result.artifactId, result.payload)
        }
        return result
    }

    override suspend fun pollUntilReady(jobId: String, maxAttempts: Int, delayMs: Long): TranscriptFetchResult {
        var attempts = 0
        while (attempts < maxAttempts) {
            val status = gatewayApi.checkJobStatus(jobId)
            if (status is TranscriptFetchResult.Ready) {
                if (status.payload != null) {
                    cacheStore.put(status.contentCode, status.artifactId, status.payload)
                }
                return status
            }
            if (status is TranscriptFetchResult.Failure) {
                return status
            }
            attempts++
            if (attempts < maxAttempts && delayMs > 0) {
                kotlinx.coroutines.delay(delayMs)
            }
        }
        return TranscriptFetchResult.Processing(contentCode = "", jobId = jobId)
    }
}

interface TranscriptGatewayApi {
    suspend fun requestTranscript(request: EpisodeTranscriptRequest): TranscriptFetchResult
    suspend fun checkJobStatus(jobId: String): TranscriptFetchResult
}
