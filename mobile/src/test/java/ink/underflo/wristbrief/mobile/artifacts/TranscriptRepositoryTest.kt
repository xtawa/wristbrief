package ink.underflo.wristbrief.mobile.artifacts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptRepositoryTest {

    private class FakeTranscriptCache : TranscriptCache {
        private val cache = mutableMapOf<String, Pair<String, TranscriptPayload>>()
        override fun get(contentCode: String): TranscriptPayload? = cache[contentCode]?.second
        override fun put(contentCode: String, artifactId: String, payload: TranscriptPayload) {
            cache[contentCode] = Pair(artifactId, payload)
        }
        override fun remove(contentCode: String) {
            cache.remove(contentCode)
        }
    }

    @Test
    fun transcriptPayloadJsonRoundtrip() {
        val payload = TranscriptPayload(
            contentCode = "WBEP-7Q2M-4H9D-K8XR",
            language = "en",
            durationMs = 120_000L,
            fullText = "Hello world from episode 1.",
            segments = listOf(
                TranscriptSegment(id = 1, startMs = 0L, endMs = 3000L, text = "Hello world", speaker = "Host"),
                TranscriptSegment(id = 2, startMs = 3000L, endMs = 6000L, text = "from episode 1.", speaker = "Guest"),
            ),
        )

        val json = payload.toJsonString()
        val restored = TranscriptPayload.fromJsonString(json)

        assertEquals("WBEP-7Q2M-4H9D-K8XR", restored.contentCode)
        assertEquals("en", restored.language)
        assertEquals(120_000L, restored.durationMs)
        assertEquals(2, restored.segments.size)
        assertEquals("Host", restored.segments[0].speaker)
        assertEquals("Hello world", restored.segments[0].text)
    }

    @Test
    fun repositoryReturnsCachedWithoutCallingGateway() {
        val cache = FakeTranscriptCache()
        val payload = TranscriptPayload(
            contentCode = "WBEP-CACHED-CODE-1234",
            language = "en",
            durationMs = 60_000L,
            fullText = "Cached text",
            segments = emptyList(),
        )
        cache.put("WBEP-CACHED-CODE-1234", "art_1", payload)

        var gatewayCalled = false
        val fakeGateway = object : TranscriptGatewayApi {
            override suspend fun requestTranscript(request: EpisodeTranscriptRequest): TranscriptFetchResult {
                gatewayCalled = true
                return TranscriptFetchResult.Failure("fail", "Should not call")
            }
            override suspend fun checkJobStatus(jobId: String): TranscriptFetchResult {
                return TranscriptFetchResult.Failure("fail", "Should not call")
            }
        }

        val repo = DefaultTranscriptRepository(cache, fakeGateway)
        val cached = repo.getCached("WBEP-CACHED-CODE-1234")

        assertNotNull(cached)
        assertEquals("Cached text", cached?.fullText)
        assertTrue(!gatewayCalled)
    }
}
