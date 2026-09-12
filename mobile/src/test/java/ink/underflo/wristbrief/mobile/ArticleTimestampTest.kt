package ink.underflo.wristbrief.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale

class ArticleTimestampTest {
    @Test
    fun formatsRfc822TimestampsForTheCurrentDayAndOtherDates() {
        val now = Instant.parse("2026-09-12T20:00:00Z")
        val zone = ZoneOffset.UTC

        val yesterday = formatArticleTimestamp(
            "Fri, 11 Sep 2026 18:26:10 +0000",
            now,
            zone,
            Locale.US,
            "Yesterday %1\$s",
        )
        val today = formatArticleTimestamp(
            "Sat, 12 Sep 2026 08:15:00 +0000",
            now,
            zone,
            Locale.US,
            "Yesterday %1\$s",
        )

        assertTrue(yesterday!!.startsWith("Yesterday 6:26"))
        assertTrue(today!!.startsWith("8:15"))
        assertEquals(
            "Sep 10, 2026",
            formatArticleTimestamp(
                "Thu, 10 Sep 2026 21:31:19 +0000",
                now,
                zone,
                Locale.US,
                "Yesterday %1\$s",
            ),
        )
    }

    @Test
    fun parsesIsoTimestampsAndRejectsMalformedValues() {
        assertEquals(
            Instant.parse("2026-09-12T08:00:00Z"),
            parseArticleInstant("2026-09-12T08:00:00Z"),
        )
        assertNull(parseArticleInstant("not-a-date"))
    }
}
