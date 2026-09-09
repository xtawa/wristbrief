package ink.underflo.wristbrief.mobile

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneLongSummaryClientTest {
    @Test
    fun `public default constructor remains available without exposing transport`() {
        assertNotNull(PhoneLongSummaryClient())
    }

    @Test
    fun `sends authenticated https request and parses long summary`() {
        val transport = RecordingTransport(validResponse())
        val result = PhoneLongSummaryClient(transport).summarize(
            gatewayUrl = "https://gateway.example.com/",
            gatewayToken = "secret-token",
            title = "Example",
            content = "Article body",
        )

        assertEquals("https://gateway.example.com/v1/summary", transport.endpoint)
        assertEquals("secret-token", transport.bearerToken)
        val payload = JSONObject(transport.jsonBody)
        assertEquals("Example", payload.getString("title"))
        assertEquals("Article body", payload.getString("content"))
        assertEquals("Long phone summary", result.text)
        assertEquals("model-a", result.model)
    }

    @Test
    fun `rejects non https gateway`() {
        val error = runCatching {
            PhoneLongSummaryClient(RecordingTransport(validResponse())).summarize(
                gatewayUrl = "http://gateway.example.com",
                gatewayToken = "token",
                title = null,
                content = "body",
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun `does not expose token when transport fails`() {
        val client = PhoneLongSummaryClient(
            object : PhoneSummaryTransport {
                override fun post(endpoint: String, bearerToken: String, jsonBody: String): String {
                    throw PhoneSummaryRequestException(PhoneSummaryFailure.Unauthorized)
                }
            },
        )

        val error = runCatching {
            client.summarize(
                gatewayUrl = "https://gateway.example.com",
                gatewayToken = "do-not-leak",
                title = null,
                content = "body",
            )
        }.exceptionOrNull() as PhoneSummaryRequestException

        assertEquals(PhoneSummaryFailure.Unauthorized, error.failure)
        assertTrue(error.message.orEmpty().contains("do-not-leak").not())
    }

    @Test
    fun `maps malformed successful response to safe invalid response`() {
        val error = runCatching {
            PhoneLongSummaryClient(RecordingTransport("{}")).summarize(
                gatewayUrl = "https://gateway.example.com",
                gatewayToken = "token",
                title = null,
                content = "body",
            )
        }.exceptionOrNull() as PhoneSummaryRequestException

        assertEquals(PhoneSummaryFailure.InvalidResponse, error.failure)
    }

    private class RecordingTransport(
        private val response: String,
    ) : PhoneSummaryTransport {
        var endpoint: String = ""
        var bearerToken: String = ""
        var jsonBody: String = ""

        override fun post(endpoint: String, bearerToken: String, jsonBody: String): String {
            this.endpoint = endpoint
            this.bearerToken = bearerToken
            this.jsonBody = jsonBody
            return response
        }
    }

    private fun validResponse(): String = """
        {
          "model": "model-a",
          "structured": {
            "schemaVersion": "2",
            "promptVersion": "2",
            "brief": "Watch brief",
            "long": "Long phone summary",
            "sourceLanguage": "en",
            "outputLanguage": "en"
          }
        }
    """.trimIndent()
}
