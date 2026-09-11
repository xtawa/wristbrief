package ink.underflo.wristbrief.mobile

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneLongSummaryClientTest {
    @Test fun `sends managed request to fixed route`() {
        val transport = RecordingTransport(validResponse())
        val result = PhoneLongSummaryClient(transport).summarize("https://gateway.example.com/", "session", "Example", "Body")
        assertEquals("https://gateway.example.com/v1/summary", transport.endpoint)
        assertEquals("session", transport.token)
        val payload = Json.parseToJsonElement(transport.body).jsonObject
        assertEquals("Example", payload.getValue("title").jsonPrimitive.content)
        assertEquals("Body", payload.getValue("content").jsonPrimitive.content)
        assertEquals("Long phone summary", result.text)
    }

    @Test fun `omits null title and never places token in body`() {
        val transport = RecordingTransport(validResponse())
        PhoneLongSummaryClient(transport).summarize("https://gateway.example.com", "do-not-leak", null, "Body")
        assertFalse(transport.body.contains("do-not-leak"))
        assertTrue("title" !in Json.parseToJsonElement(transport.body).jsonObject)
    }

    @Test fun `rejects non https gateway`() {
        val error = runCatching { PhoneLongSummaryClient(RecordingTransport(validResponse())).summarize("http://gateway.example.com", "token", null, "Body") }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test fun `maps response status without provider credential cases`() {
        assertEquals(PhoneSummaryFailure.Unauthorized, mapPhoneSummaryStatus(401))
        assertEquals(PhoneSummaryFailure.Quota, mapPhoneSummaryStatus(429))
        assertEquals(PhoneSummaryFailure.ProviderUnavailable, mapPhoneSummaryStatus(503))
        assertEquals(PhoneSummaryFailure.InvalidResponse, mapPhoneSummaryStatus(422))
    }

    private class RecordingTransport(private val response: String) : PhoneSummaryTransport {
        var endpoint = ""; var token = ""; var body = ""
        override fun post(endpoint: String, bearerToken: String, jsonBody: String): String {
            this.endpoint = endpoint; token = bearerToken; body = jsonBody; return response
        }
    }

    private fun validResponse() = """{"model":"model-a","structured":{"schemaVersion":"2","promptVersion":"2","long":"Long phone summary","sourceLanguage":"en","outputLanguage":"en"}}"""
}
