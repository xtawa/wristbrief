package ink.underflo.wristbrief.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class GlanceSurfaceUpdateFingerprintTest {
    @Test
    fun ignoresTransientAndSavedOnlyState() {
        val base = state(
            items = listOf(
                item("one", "First brief"),
                item("two", "Second brief"),
            )
        )
        val changed = base.copy(
            items = base.items.map { it.copy(isSaved = true) },
            savedItems = base.items.map { it.copy(isSaved = true) },
            isLoading = true,
            isOfflineFallback = true,
            errorMessage = "temporary",
        )

        assertEquals(
            base.glanceSurfaceUpdateFingerprint(),
            changed.glanceSurfaceUpdateFingerprint(),
        )
    }

    @Test
    fun readChangeChangesRenderedSurfaceFingerprint() {
        val base = state(
            items = listOf(
                item("one", "First brief"),
                item("two", "Second brief"),
            )
        )
        val changed = base.copy(
            items = listOf(
                item("one", "First brief", isRead = true),
                item("two", "Second brief"),
            )
        )

        assertNotEquals(
            base.glanceSurfaceUpdateFingerprint(),
            changed.glanceSurfaceUpdateFingerprint(),
        )
    }

    @Test
    fun normalizedWhitespaceDoesNotWakeGlanceSurfaces() {
        val spaced = state(items = listOf(item("one", "  First   brief  ")))
        val normalized = state(items = listOf(item("one", "First brief")))

        assertEquals(
            spaced.glanceSurfaceUpdateFingerprint(),
            normalized.glanceSurfaceUpdateFingerprint(),
        )
    }

    @Test
    fun titleOutsideRenderedGlanceBudgetDoesNotWakeSurfaces() {
        val base = state(
            items = listOf(
                item("one", "First brief"),
                item("two", "Second brief"),
                item("three", "Third brief"),
            )
        )
        val changed = base.copy(
            items = listOf(
                item("one", "First brief"),
                item("two", "Second brief"),
                item("three", "Changed but not rendered"),
            )
        )

        assertEquals(
            base.glanceSurfaceUpdateFingerprint(),
            changed.glanceSurfaceUpdateFingerprint(),
        )
    }

    @Test
    fun allReadFallbackTracksTheTwoTitlesRenderedByTheTile() {
        val base = state(
            items = listOf(
                item("one", "First brief", isRead = true),
                item("two", "Second brief", isRead = true),
                item("three", "Third brief", isRead = true),
            )
        )
        val renderedChange = base.copy(
            items = listOf(
                item("one", "Updated first", isRead = true),
                item("two", "Second brief", isRead = true),
                item("three", "Third brief", isRead = true),
            )
        )

        assertNotEquals(
            base.glanceSurfaceUpdateFingerprint(),
            renderedChange.glanceSurfaceUpdateFingerprint(),
        )
    }

    private fun state(items: List<InboxItemUi>) = InboxUiState(
        items = items,
        unreadCount = items.count { !it.isRead },
        hasSubscriptions = true,
    )

    private fun item(
        id: String,
        title: String,
        isRead: Boolean = false,
    ) = InboxItemUi(
        id = id,
        title = title,
        source = "Feed",
        summary = "Summary",
        timeLabel = "",
        isPodcast = false,
        isRead = isRead,
    )
}
