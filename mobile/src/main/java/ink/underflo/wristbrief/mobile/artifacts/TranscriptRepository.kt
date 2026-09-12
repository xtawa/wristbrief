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
}

interface TranscriptGatewayApi {
    suspend fun requestTranscript(request: EpisodeTranscriptRequest): TranscriptFetchResult
    suspend fun checkJobStatus(jobId: String): TranscriptFetchResult
}
