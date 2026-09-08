package ink.underflo.wristbrief.tile

import ink.underflo.wristbrief.data.CachedFeedItem
import ink.underflo.wristbrief.media.PodcastEpisodeProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContinueListeningTileDataTest {
    @Test
    fun selects_latest_cached_audio_item_with_saved_progress() {
        val result = mapContinueListeningTileData(
            cachedItems = listOf(
                item("old", "Old episode", 10),
                item("new", "New episode", 20),
                item("article", "No audio", 30, audioUrl = null)
            ),
            progress = listOf(
                PodcastEpisodeProgress("old", 65_000L, 1f),
                PodcastEpisodeProgress("new", 125_000L, 1.5f),
                PodcastEpisodeProgress("article", 200_000L, 1f)
            )
        )

        requireNotNull(result)
        assertEquals("new", result.episodeId)
        assertEquals("New episode", result.title)
        assertEquals("Resume 2:05 · 1.5×", result.resumeLabel)
    }

    @Test
    fun excludes_completed_or_unmatched_progress() {
        assertNull(
            mapContinueListeningTileData(
                cachedItems = listOf(item("done", "Done", 10)),
                progress = listOf(PodcastEpisodeProgress("done", 0L, 1f))
            )
        )
    }

    @Test
    fun compacts_long_whitespace_heavy_titles() {
        val result = mapContinueListeningTileData(
            cachedItems = listOf(item("ep", "  A   very long podcast episode title that should remain compact on Wear  ", 10)),
            progress = listOf(PodcastEpisodeProgress("ep", 3_661_000L, 2f))
        )

        requireNotNull(result)
        assertEquals("A very long podcast episode title that sho…", result.title)
        assertEquals("Resume 1:01:01 · 2×", result.resumeLabel)
    }

    private fun item(
        id: String,
        title: String,
        cachedAt: Long,
        audioUrl: String? = "https://example.com/$id.mp3"
    ) = CachedFeedItem(
        id = id,
        feedId = "feed",
        feedTitle = "Feed",
        title = title,
        link = null,
        description = null,
        published = null,
        audioUrl = audioUrl,
        cachedAtEpochMs = cachedAt
    )
}
