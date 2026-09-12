package ink.underflo.wristbrief.mobile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

private val rfc822DateFormatters = listOf(
    DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US),
    DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm Z", Locale.US),
)

fun parseArticleInstant(value: String): Instant? {
    val trimmed = value.trim()
    return runCatching { OffsetDateTime.parse(trimmed, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant() }.getOrNull()
        ?: rfc822DateFormatters.firstNotNullOfOrNull { formatter ->
            runCatching { OffsetDateTime.parse(trimmed, formatter).toInstant() }.getOrNull()
        }
}

fun formatArticleTimestamp(
    value: String,
    now: Instant = Instant.now(),
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
    yesterdayPattern: String,
): String? {
    val instant = parseArticleInstant(value) ?: return null
    val published = instant.atZone(zone)
    val today = now.atZone(zone).toLocalDate()
    val publishedDate = published.toLocalDate()
    val time = published.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale))
    return when (publishedDate) {
        today -> time
        today.minusDays(1) -> yesterdayPattern.format(time)
        else -> published.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
    }
}

@Composable
internal fun formattedArticleTimestamp(value: String): String {
    val yesterdayPattern = stringResource(R.string.article_time_yesterday)
    return remember(value, yesterdayPattern) {
        formatArticleTimestamp(value, yesterdayPattern = yesterdayPattern) ?: value
    }
}
