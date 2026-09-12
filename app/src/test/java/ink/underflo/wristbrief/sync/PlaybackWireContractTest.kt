package ink.underflo.wristbrief.sync

import ink.underflo.wristbrief.media.PodcastEpisodeProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackWireContractTest {
    @Test
    fun pathsAndRemotePathsMatch() {
        assertEquals("/wristbrief/playback/v1/phone", PlaybackWireContract.PHONE_PATH)
        assertEquals("/wristbrief/playback/v1/wear", PlaybackWireContract.WEAR_PATH)
        assertEquals(PlaybackWireContract.PHONE_PATH, PlaybackWireContract.pathFor(SyncOrigin.PHONE))
        assertEquals(PlaybackWireContract.WEAR_PATH, PlaybackWireContract.pathFor(SyncOrigin.WEAR))
        assertEquals(PlaybackWireContract.PHONE_PATH, PlaybackWireContract.remotePathFor(SyncOrigin.WEAR))
        assertEquals(PlaybackWireContract.WEAR_PATH, PlaybackWireContract.remotePathFor(SyncOrigin.PHONE))
    }

    @Test
    fun payloadRoundTripsCorrectly() {
        val payload = PlaybackSyncPayload(
            version = 1,
            episodeId = "ep-123",
            positionMs = 45000L,
            durationMs = 1800000L,
            playbackSpeed = 1.5f,
            isPlaying = true,
            lastPlayedAtEpochMs = 1700000000000L,
            completed = false,
            origin = SyncOrigin.WEAR,
        )

        val encoded = PlaybackWireContract.encode(payload)
        val decoded = PlaybackWireContract.decode(encoded)

        assertEquals(payload.episodeId, decoded.episodeId)
        assertEquals(payload.positionMs, decoded.positionMs)
        assertEquals(payload.durationMs, decoded.durationMs)
        assertEquals(payload.playbackSpeed, decoded.playbackSpeed, 0.01f)
        assertEquals(payload.isPlaying, decoded.isPlaying)
        assertEquals(payload.lastPlayedAtEpochMs, decoded.lastPlayedAtEpochMs)
        assertEquals(payload.completed, decoded.completed)
        assertEquals(payload.origin, decoded.origin)
    }

    @Test
    fun decodeOwnedEnforcesExpectedOrigin() {
        val payload = PlaybackSyncPayload(
            episodeId = "ep-1",
            positionMs = 1000L,
            durationMs = 60000L,
            playbackSpeed = 1.0f,
            isPlaying = false,
            lastPlayedAtEpochMs = 100L,
            completed = false,
            origin = SyncOrigin.PHONE,
        )
        val encoded = PlaybackWireContract.encode(payload)
        val decoded = PlaybackWireContract.decodeOwned(encoded, SyncOrigin.PHONE)
        assertEquals(SyncOrigin.PHONE, decoded.origin)
    }

    @Test(expected = IllegalArgumentException::class)
    fun decodeOwnedRejectsForgedOrigin() {
        val payload = PlaybackSyncPayload(
            episodeId = "ep-1",
            positionMs = 1000L,
            durationMs = 60000L,
            playbackSpeed = 1.0f,
            isPlaying = false,
            lastPlayedAtEpochMs = 100L,
            completed = false,
            origin = SyncOrigin.WEAR,
        )
        val encoded = PlaybackWireContract.encode(payload)
        PlaybackWireContract.decodeOwned(encoded, SyncOrigin.PHONE)
    }

    @Test(expected = IllegalArgumentException::class)
    fun futureVersionIsRejected() {
        PlaybackWireContract.decode("""{"version":2,"episodeId":"ep-1","positionMs":0,"lastPlayedAtEpochMs":1,"origin":"PHONE"}""")
    }

    @Test
    fun conflictResolutionLastWriteWins() {
        val olderLocal = PodcastEpisodeProgress(
            episodeId = "ep-1",
            positionMs = 10000L,
            lastPlayedAtEpochMs = 1000L,
        )
        val newerIncoming = PlaybackSyncPayload(
            episodeId = "ep-1",
            positionMs = 25000L,
            durationMs = 60000L,
            playbackSpeed = 1.25f,
            isPlaying = true,
            lastPlayedAtEpochMs = 2000L,
            completed = false,
            origin = SyncOrigin.PHONE,
        )

        val resolved = PlaybackWireContract.resolvePlaybackConflict(olderLocal, newerIncoming)
        assertEquals(25000L, resolved.positionMs)
        assertEquals(2000L, resolved.lastPlayedAtEpochMs)
        assertEquals(1.25f, resolved.playbackSpeed, 0.01f)
    }

    @Test
    fun conflictResolutionKeepsNewerLocalWhenIncomingIsStale() {
        val newerLocal = PodcastEpisodeProgress(
            episodeId = "ep-1",
            positionMs = 50000L,
            lastPlayedAtEpochMs = 5000L,
        )
        val olderIncoming = PlaybackSyncPayload(
            episodeId = "ep-1",
            positionMs = 25000L,
            durationMs = 60000L,
            playbackSpeed = 1.0f,
            isPlaying = false,
            lastPlayedAtEpochMs = 2000L,
            completed = false,
            origin = SyncOrigin.PHONE,
        )

        val resolved = PlaybackWireContract.resolvePlaybackConflict(newerLocal, olderIncoming)
        assertEquals(50000L, resolved.positionMs)
        assertEquals(5000L, resolved.lastPlayedAtEpochMs)
    }

    @Test
    fun conflictResolutionTieBreakFavorsWear() {
        val localPhone = PodcastEpisodeProgress(
            episodeId = "ep-1",
            positionMs = 10000L,
            lastPlayedAtEpochMs = 3000L,
        )
        val incomingWear = PlaybackSyncPayload(
            episodeId = "ep-1",
            positionMs = 15000L,
            durationMs = 60000L,
            playbackSpeed = 1.0f,
            isPlaying = false,
            lastPlayedAtEpochMs = 3000L,
            completed = false,
            origin = SyncOrigin.WEAR,
        )

        val resolved = PlaybackWireContract.resolvePlaybackConflict(localPhone, incomingWear)
        assertEquals(15000L, resolved.positionMs) // Wear wins tie
    }
}
