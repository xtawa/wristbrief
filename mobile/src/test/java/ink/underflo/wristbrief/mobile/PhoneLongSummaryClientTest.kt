package ink.underflo.wristbrief.mobile

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        val payload = Json.parseToJsonElement(transport.jsonBody).jsonObject
        assertEquals("Example", payload.getValue("title").jsonPrimitive.content)
        assertEquals("Article body", payload.getValue("content").jsonPrimitive.content)
        assertEquals("Long phone summary", result.text)
        assertEquals("model-a", result.model)
    }

    @Test
    fun `byok request uses fixed gateway route and key header`() {
        val transport = RecordingTransport(validResponse())
        val result = PhoneLongSummaryClient(transport).summarizeByok(
            gatewayUrl = "https://gateway.example.com/",
            gatewayToken = "session-token",
            title = "Example",
            content = "Article body",
            config = PhoneByokConfig(
                provider = PhoneByokProvider.OpenRouter,
                model = " openai/gpt-5-mini ",
                apiKey = " user-provider-key ",
            ),
        )

        assertEquals("https://gateway.example.com/v1/byok/summary", transport.endpoint)
        assertEquals("session-token", transport.bearerToken)
        assertEquals("user-provider-key", transport.headers["X-WristBrief-BYOK-Key"])
        val payload = Json.parseToJsonElement(transport.jsonBody).jsonObject
        assertEquals("openrouter", payload.getValue("provider").jsonPrimitive.content)
        assertEquals("openai/gpt-5-mini", payload.getValue("model").jsonPrimitive.content)
        assertEquals("Article body", payload.getValue("content").jsonPrimitive.content)
        assertFalse(transport.jsonBody.contains("user-provider-key"))
        assertEquals("Long phone summary", result.text)
    }

    @Test
    fun `gemini byok request emits only allowlisted provider id`() {
        val transport = RecordingTransport(validResponse())
        PhoneLongSummaryClient(transport).summarizeByok(
            gatewayUrl = "https://gateway.example.com",
            gatewayToken = "session-token",
            title = null,
            content = "body",
            config = PhoneByokConfig(
                provider = PhoneByokProvider.Gemini,
                model = "gemini-2.5-flash",
                apiKey = "provider-key",
            ),
        )

        val payload = Json.parseToJsonElement(transport.jsonBody).jsonObject
        assertEquals("gemini", payload.getValue("provider").jsonPrimitive.content)
        assertEquals("gemini-2.5-flash", payload.getValue("model").jsonPrimitive.content)
    }

    @Test
    fun `maps byok provider credential rejection separately from gateway auth`() {
        assertEquals(
            PhoneSummaryFailure.ByokProviderUnauthorized,
            mapPhoneSummaryStatus(status = 422, isByokRequest = true),
        )
        assertEquals(
            PhoneSummaryFailure.InvalidResponse,
            mapPhoneSummaryStatus(status = 422, isByokRequest = false),
        )
        assertEquals(
            PhoneSummaryFailure.Unauthorized,
            mapPhoneSummaryStatus(status = 401, isByokRequest = true),
        )
    }

    @Test
    fun `omits null title from request payload`() {
        val transport = RecordingTransport(validResponse())
        PhoneLongSummaryClient(transport).summarize(
            gatewayUrl = "https://gateway.example.com",
            gatewayToken = "token",
            title = null,
            content = "body",
        )

        val payload = Json.parseToJsonElement(transport.jsonBody).jsonObject
        assertTrue("title" !in payload)
        assertEquals("body", payload.getValue("content").jsonPrimitive.content)
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
    fun `rejects invalid byok config before transport`() {
        val transport = RecordingTransport(validResponse())
        val error = runCatching {
            PhoneLongSummaryClient(transport).summarizeByok(
                gatewayUrl = "https://gateway.example.com",
                gatewayToken = "token",
                title = null,
                content = "body",
                config = PhoneByokConfig(
                    provider = PhoneByokProvider.OpenRouter,
                    model = "bad\nmodel",
                    apiKey = "provider-key",
                ),
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertEquals("", transport.endpoint)
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
    fun `does not expose byok key when transport fails`() {
        val client = PhoneLongSummaryClient(
            object : PhoneSummaryTransport {
                override fun post(endpoint: String, bearerToken: String, jsonBody: String): String = validResponse()

                override fun postWithHeaders(
                    endpoint: String,
                    bearerToken: String,
                    jsonBody: String,
                    headers: Map<String, String>,
                ): String {
                    throw PhoneSummaryRequestException(PhoneSummaryFailure.ProviderUnavailable)
                }
            },
        )

        val error = runCatching {
            client.summarizeByok(
                gatewayUrl = "https://gateway.example.com",
                gatewayToken = "session",
                title = null,
                content = "body",
                config = PhoneByokConfig(
                    provider = PhoneByokProvider.Gemini,
                    model = "gemini-2.5-flash",
                    apiKey = "never-log-this-key",
                ),
            )
        }.exceptionOrNull() as PhoneSummaryRequestException

        assertEquals(PhoneSummaryFailure.ProviderUnavailable, error.failure)
        assertFalse(error.message.orEmpty().contains("never-log-this-key"))
    }

    @Test
    fun `does not expose byok key for provider credential rejection`() {
        val client = PhoneLongSummaryClient(
            object : PhoneSummaryTransport {
                override fun post(endpoint: String, bearerToken: String, jsonBody: String): String = validResponse()

                override fun postWithHeaders(
                    endpoint: String,
                    bearerToken: String,
                    jsonBody: String,
                    headers: Map<String, String>,
                ): String {
                    throw PhoneSummaryRequestException(PhoneSummaryFailure.ByokProviderUnauthorized)
                }
            },
        )

        val error = runCatching {
            client.summarizeByok(
                gatewayUrl = "https://gateway.example.com",
                gatewayToken = "session",
                title = null,
                content = "body",
                config = PhoneByokConfig(
                    provider = PhoneByokProvider.OpenRouter,
                    model = "openai/gpt-5-mini",
                    apiKey = "revoked-provider-secret",
                ),
            )
        }.exceptionOrNull() as PhoneSummaryRequestException

        assertEquals(PhoneSummaryFailure.ByokProviderUnauthorized, error.failure)
        assertFalse(error.message.orEmpty().contains("revoked-provider-secret"))
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
        var headers: Map<String, String> = emptyMap()

        override fun post(endpoint: String, bearerToken: String, jsonBody: String): String {
            this.endpoint = endpoint
            this.bearerToken = bearerToken
            this.jsonBody = jsonBody
            this.headers = emptyMap()
            return response
        }

        override fun postWithHeaders(
            endpoint: String,
            bearerToken: String,
            jsonBody: String,
            headers: Map<String, String>,
        ): String {
            this.endpoint = endpoint
            this.bearerToken = bearerToken
            this.jsonBody = jsonBody
            this.headers = headers
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
