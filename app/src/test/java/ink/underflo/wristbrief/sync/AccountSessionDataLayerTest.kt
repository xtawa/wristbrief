package ink.underflo.wristbrief.sync

import ink.underflo.wristbrief.data.CachedFeedItem
import ink.underflo.wristbrief.data.FeedStore
import ink.underflo.wristbrief.data.FeedSubscription
import ink.underflo.wristbrief.media.PodcastEpisodeProgress
import ink.underflo.wristbrief.tile.mapContinueListeningTileData
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountSessionDataLayerTest {
    private val token = "wbs_${"A".repeat(43)}"

    @Test
    fun dataItemContractMatchesPhoneBridge() {
        assertEquals("/wristbrief/account-session/v1", AccountSessionDataLayerService.PATH)
        assertEquals("payload", AccountSessionDataLayerService.PAYLOAD_KEY)
    }

    @Test
    fun decodesScopedSessionAndClearMessages() {
        val set = WearAccountSessionMessageCodec.decode(
            """{"version":1,"operation":"set","sessionToken":"$token","expiresAt":"2026-10-01T00:00:00Z","userId":"usr_123"}""".encodeToByteArray()
        )
        assertTrue(set is WearAccountSessionMessageCodec.Message.Set)
        assertEquals(token, (set as WearAccountSessionMessageCodec.Message.Set).session.token)

        val clear = WearAccountSessionMessageCodec.decode("""{"version":1,"operation":"clear"}""".encodeToByteArray())
        assertEquals(WearAccountSessionMessageCodec.Message.Clear, clear)
    }

    @Test
    fun invalidLatestSetFailsClosedInsteadOfKeepingPreviousSession() {
        var cleared = false
        var wrote = false
        val expired = WearAccountSession(token, Instant.parse("2026-09-10T00:00:00Z"), "usr_123")

        applyWearAccountSessionMessage(
            message = WearAccountSessionMessageCodec.Message.Set(expired),
            now = Instant.parse("2026-09-11T00:00:00Z"),
            write = { session, now ->
                wrote = true
                isValidWearAccountSession(session, now)
            },
            clear = { cleared = true },
        )

        assertTrue(wrote)
        assertTrue(cleared)
    }

    @Test
    fun malformedLatestStateFailsClosed() {
        var cleared = false
        applyWearAccountSessionMessage(
            message = null,
            now = Instant.parse("2026-09-11T00:00:00Z"),
            write = { _, _ -> false },
            clear = { cleared = true },
        )
        assertTrue(cleared)
    }

    @Test
    fun validLatestSetDoesNotClear() {
        var cleared = false
        val session = WearAccountSession(token, Instant.parse("2026-10-01T00:00:00Z"), "usr_123")
        assertTrue(isValidWearAccountSession(session, Instant.parse("2026-09-11T00:00:00Z")))

        applyWearAccountSessionMessage(
            message = WearAccountSessionMessageCodec.Message.Set(session),
            now = Instant.parse("2026-09-11T00:00:00Z"),
            write = { incoming, now -> isValidWearAccountSession(incoming, now) },
            clear = { cleared = true },
        )

        assertFalse(cleared)
    }

    @Test
    fun runtimeRejectsExpiredSession() {
        WearAccountSessionRuntime.set(
            WearAccountSession(token, Instant.parse("2026-09-10T00:00:00Z"), "usr_123")
        )
        assertNull(WearAccountSessionRuntime.currentToken(Instant.parse("2026-09-11T00:00:00Z")))
    }

    @Test
    fun runtimeProvidesOnlyUnexpiredScopedToken() {
        WearAccountSessionRuntime.set(
            WearAccountSession(token, Instant.parse("2026-10-01T00:00:00Z"), "usr_123")
        )
        assertEquals(token, WearAccountSessionRuntime.currentToken(Instant.parse("2026-09-11T00:00:00Z")))
        WearAccountSessionRuntime.clear()
        assertNull(WearAccountSessionRuntime.currentToken(Instant.parse("2026-09-11T00:00:00Z")))
    }

    @Test
    fun accountSwitchTriggersPurge() {
        var purged = false
        val sessionB = WearAccountSession(token, Instant.parse("2026-10-01T00:00:00Z"), "usr_B")

        applyWearAccountSessionMessage(
            message = WearAccountSessionMessageCodec.Message.Set(sessionB),
            currentUserId = "usr_A",
            now = Instant.parse("2026-09-11T00:00:00Z"),
            write = { _, _ -> true },
            clear = {},
            onAccountPurge = { purged = true },
        )

        assertTrue(purged)
    }

    @Test
    fun sameAccountDoesNotTriggerPurge() {
        var purged = false
        val sessionA = WearAccountSession(token, Instant.parse("2026-10-01T00:00:00Z"), "usr_A")

        applyWearAccountSessionMessage(
            message = WearAccountSessionMessageCodec.Message.Set(sessionA),
            currentUserId = "usr_A",
            now = Instant.parse("2026-09-11T00:00:00Z"),
            write = { _, _ -> true },
            clear = {},
            onAccountPurge = { purged = true },
        )

        assertFalse(purged)
    }

    @Test
    fun clearMessageTriggersPurge() {
        var purged = false
        var cleared = false

        applyWearAccountSessionMessage(
            message = WearAccountSessionMessageCodec.Message.Clear,
            currentUserId = "usr_A",
            now = Instant.parse("2026-09-11T00:00:00Z"),
            write = { _, _ -> true },
            clear = { cleared = true },
            onAccountPurge = { purged = true },
        )

        assertTrue(cleared)
        assertTrue(purged)
    }

    @Test
    fun accountPurgeRemovesCachedEpisodesAndListeningHistorySoTilesCannotShowPreviousAccount() {
        val episode = CachedFeedItem(
            id = "guid:episode-a",
            feedId = "feed-a",
            feedTitle = "Account A Podcast",
            title = "Account A private episode",
            link = "https://example.com/a",
            description = null,
            published = null,
            audioUrl = "https://example.com/a.mp3",
            cachedAtEpochMs = 1L,
        )
        val feedStore = InMemoryFeedStore(
            subscriptions = listOf(FeedSubscription("feed-a", "Account A Podcast", "https://example.com/feed")),
            cached = listOf(episode),
            readIds = setOf(episode.id),
            savedIds = setOf(episode.id),
        )
        val progressStore = WearPlaybackSyncManagerTest.FakePodcastProgressStore().apply {
            save(PodcastEpisodeProgress(episode.id, positionMs = 600_000L, durationMs = 1_800_000L))
        }
        // Precondition: before the purge the Continue listening tile would surface account A's episode.
        assertEquals(episode.id, mapContinueListeningTileData(feedStore.cachedItems(), progressStore.all())?.episodeId)

        var clocksCleared = false
        var outboxCleared = false
        val fence = ink.underflo.wristbrief.media.PlaybackAccountFence()
        val oldAccountGeneration = fence.currentGeneration()
        var stopRequestedAfterClear = false
        fence.registerStopHandler { stopRequestedAfterClear = progressStore.all().isEmpty() }
        var oldCheckpointAllowedDuringClear: Boolean? = null
        purgeWearAccountScopedData(
            feedStore = feedStore,
            progressStore = progressStore,
            clearItemStateClocks = { clocksCleared = true },
            clearSyncOutbox = {
                outboxCleared = true
                // Inside the purge transaction the old account's item is already fenced out.
                oldCheckpointAllowedDuringClear = fence.allowsPersistence(oldAccountGeneration)
            },
            fence = fence,
        )
        assertEquals(false, oldCheckpointAllowedDuringClear)
        assertFalse(fence.allowsPersistence(oldAccountGeneration))
        // Live sessions are asked to stop only once the stores are already empty.
        assertTrue(stopRequestedAfterClear)

        assertTrue(feedStore.subscriptions().isEmpty())
        assertTrue(feedStore.cachedItems().isEmpty())
        assertTrue(feedStore.readItemIds().isEmpty())
        assertTrue(feedStore.savedItemIds().isEmpty())
        assertTrue(progressStore.all().isEmpty())
        assertTrue(clocksCleared)
        assertTrue(outboxCleared)
        assertNull(mapContinueListeningTileData(feedStore.cachedItems(), progressStore.all()))
    }

    private class InMemoryFeedStore(
        private var subscriptions: List<FeedSubscription>,
        private var cached: List<CachedFeedItem>,
        private var readIds: Set<String>,
        private var savedIds: Set<String>,
    ) : FeedStore {
        override fun subscriptions(): List<FeedSubscription> = subscriptions
        override fun saveSubscriptions(subscriptions: List<FeedSubscription>) { this.subscriptions = subscriptions }
        override fun cachedItems(): List<CachedFeedItem> = cached
        override fun saveCachedItems(items: List<CachedFeedItem>) { cached = items }
        override fun readItemIds(): Set<String> = readIds
        override fun saveReadItemIds(itemIds: Set<String>) { readIds = itemIds }
        override fun savedItemIds(): Set<String> = savedIds
        override fun saveSavedItemIds(itemIds: Set<String>) { savedIds = itemIds }
    }
}
