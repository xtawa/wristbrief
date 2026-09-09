package ink.underflo.wristbrief.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PhoneLongSummaryTest {
    @Test
    fun `phone parser selects long summary rather than watch brief`() {
        val parsed = parsePhoneLongSummaryResponse(response(long = "Long phone summary", brief = "Watch brief"))

        assertEquals("Long phone summary", parsed.text)
        assertEquals("en", parsed.sourceLanguage)
        assertEquals("zh-CN", parsed.outputLanguage)
        assertEquals("test-model", parsed.model)
    }

    @Test
    fun `phone parser rejects missing long summary`() {
        assertThrows(IllegalArgumentException::class.java) {
            parsePhoneLongSummaryResponse(response(long = "   ", brief = "Watch brief"))
        }
    }

    @Test
    fun `phone parser rejects oversized long summary`() {
        assertThrows(IllegalArgumentException::class.java) {
            parsePhoneLongSummaryResponse(response(long = "x".repeat(6001), brief = "Watch brief"))
        }
    }

    @Test
    fun `phone parser rejects stale schema`() {
        assertThrows(IllegalArgumentException::class.java) {
            parsePhoneLongSummaryResponse(response(long = "Long phone summary", brief = "Watch brief", schemaVersion = "1"))
        }
    }

    private fun response(
        long: String,
        brief: String,
        schemaVersion: String = "2",
    ): String = """
        {
          "summary": ${jsonString(brief)},
          "model": "test-model",
          "structured": {
            "tiny": "Tiny",
            "brief": ${jsonString(brief)},
            "long": ${jsonString(long)},
            "bullets": ["Fact"],
            "topics": ["Topic"],
            "sourceLanguage": "en",
            "outputLanguage": "zh-CN",
            "schemaVersion": "$schemaVersion",
            "promptVersion": "2"
          }
        }
    """.trimIndent()

    private fun jsonString(value: String): String = buildString {
        append('"')
        value.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                else -> append(char)
            }
        }
        append('"')
    }
}
