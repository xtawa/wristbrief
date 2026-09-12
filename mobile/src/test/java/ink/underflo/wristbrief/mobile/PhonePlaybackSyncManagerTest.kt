package ink.underflo.wristbrief.mobile

import ink.underflo.wristbrief.mobile.media.PodcastEpisodeProgress
import ink.underflo.wristbrief.mobile.media.PodcastProgressStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PhonePlaybackSyncManagerTest {
    private lateinit var fakeStore: FakePodcastProgressStore
    private val publishedPayloads = mutableListOf<String>()

    class FakePodcastProgressStore : PodcastProgressStore {
        private val items = mutableMapOf<String, PodcastEpisodeProgress>()
        override fun get(episodeId: String): PodcastEpisodeProgress? = items[episodeId]
        override fun all(): List<PodcastEpisodeProgress> = items.values.toList()
        override fun save(progress: PodcastEpisodeProgress) { items[progress.episodeId] = progress }
        override fun delete(episodeId: String) { items.remove(episodeId) }
    }

    @Before
    fun setup() {
        fakeStore = FakePodcastProgressStore()
        publishedPayloads.clear()
    }

    @Test
    fun publishLocalProgressEncodesWithPhoneOrigin() {
        val manager = PhonePlaybackSyncManager(
            progressStore = fakeStore,
            publishCallback = { publishedPayloads.add(it) },
        )

        val progress = PodcastEpisodeProgress(
            episodeId = "ep-test",
            positionMs = 12345L,
            durationMs = 90000L,
            playbackSpeed = 1.25f,
            isPlaying = true,
            lastPlayedAtEpochMs = 5000L,
            completed = false,
        )

        manager.publishLocalProgress(progress)
        assertEquals(1, publishedPayloads.size)

        val decoded = PlaybackWireContract.decode(publishedPayloads.first())
        assertEquals("ep-test", decoded.episodeId)
        assertEquals(SyncOrigin.PHONE, decoded.origin)
        assertEquals(12345L, decoded.positionMs)
    }

    @Test
    fun applyWearProgressAppliesNewerProgress() {
        val manager = PhonePlaybackSyncManager(
            progressStore = fakeStore,
            publishCallback = null,
        )

        fakeStore.save(
            PodcastEpisodeProgress(
                episodeId = "ep-1",
                positionMs = 5000L,
                lastPlayedAtEpochMs = 1000L,
            )
        )

        val wearPayload = PlaybackSyncPayload(
            episodeId = "ep-1",
            positionMs = 45000L,
            durationMs = 120000L,
            playbackSpeed = 1.5f,
            isPlaying = false,
            lastPlayedAtEpochMs = 2000L,
            completed = false,
            origin = SyncOrigin.WEAR,
        )

        val raw = PlaybackWireContract.encode(wearPayload)
        val applied = manager.applyWearProgress(raw)
        assertTrue(applied)

        val saved = fakeStore.get("ep-1")
        assertNotNull(saved)
        assertEquals(45000L, saved!!.positionMs)
        assertEquals(1.5f, saved.playbackSpeed, 0.01f)
        assertEquals(2000L, saved.lastPlayedAtEpochMs)
    }

    @Test
    fun applyWearProgressIgnoresStaleProgress() {
        val manager = PhonePlaybackSyncManager(
            progressStore = fakeStore,
            publishCallback = null,
        )

        fakeStore.save(
            PodcastEpisodeProgress(
                episodeId = "ep-1",
                positionMs = 80000L,
                lastPlayedAtEpochMs = 5000L,
            )
        )

        val wearPayload = PlaybackSyncPayload(
            episodeId = "ep-1",
            positionMs = 20000L,
            durationMs = 120000L,
            playbackSpeed = 1.0f,
            isPlaying = false,
            lastPlayedAtEpochMs = 3000L,
            completed = false,
            origin = SyncOrigin.WEAR,
        )

        val raw = PlaybackWireContract.encode(wearPayload)
        val applied = manager.applyWearProgress(raw)
        assertFalse(applied)

        val saved = fakeStore.get("ep-1")
        assertEquals(80000L, saved!!.positionMs)
    }

    @Test
    fun applyWearProgressRejectsForgedPhoneOrigin() {
        val manager = PhonePlaybackSyncManager(
            progressStore = fakeStore,
            publishCallback = null,
        )

        val forgedPayload = PlaybackSyncPayload(
            episodeId = "ep-1",
            positionMs = 45000L,
            durationMs = 120000L,
            playbackSpeed = 1.5f,
            isPlaying = false,
            lastPlayedAtEpochMs = 2000L,
            completed = false,
            origin = SyncOrigin.PHONE,
        )

        val raw = PlaybackWireContract.encode(forgedPayload)
        val applied = manager.applyWearProgress(raw)
        assertFalse(applied)
    }
}
