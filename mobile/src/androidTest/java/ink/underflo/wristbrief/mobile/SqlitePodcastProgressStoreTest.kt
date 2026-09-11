package ink.underflo.wristbrief.mobile

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import ink.underflo.wristbrief.mobile.db.LegacyDataMigration
import ink.underflo.wristbrief.mobile.db.SqliteMobileFeedStore
import ink.underflo.wristbrief.mobile.db.SqliteMobileInboxStore
import ink.underflo.wristbrief.mobile.db.SqlitePodcastProgressStore
import ink.underflo.wristbrief.mobile.db.WristBriefDatabaseHelper
import ink.underflo.wristbrief.mobile.media.PodcastEpisodeProgress
import ink.underflo.wristbrief.mobile.media.SharedPreferencesPodcastProgressStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SqlitePodcastProgressStoreTest {

    private lateinit var context: Context
    private lateinit var dbHelper: WristBriefDatabaseHelper
    private lateinit var progressStore: SqlitePodcastProgressStore
    private lateinit var feedStore: SqliteMobileFeedStore
    private lateinit var inboxStore: SqliteMobileInboxStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("wristbrief_podcast_test.db")
        context.getSharedPreferences("wristbrief_migration", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("mobile_podcast_progress", Context.MODE_PRIVATE).edit().clear().commit()

        dbHelper = WristBriefDatabaseHelper(context, databaseName = "wristbrief_podcast_test.db")
        progressStore = SqlitePodcastProgressStore(dbHelper)
        feedStore = SqliteMobileFeedStore(dbHelper)
        inboxStore = SqliteMobileInboxStore(dbHelper)
    }

    @After
    fun tearDown() {
        dbHelper.close()
        context.deleteDatabase("wristbrief_podcast_test.db")
    }

    @Test
    fun save_and_get_preservesAllPlaybackFields() {
        assertNull(progressStore.get("ep-1"))

        val progress = PodcastEpisodeProgress(
            episodeId = "ep-1",
            positionMs = 75_000L,
            playbackSpeed = 1.5f,
            durationMs = 300_000L,
            isPlaying = true,
            lastPlayedAtEpochMs = 1700000000000L,
            completed = false,
        )
        progressStore.save(progress)

        val retrieved = progressStore.get("ep-1")
        assertNotNull(retrieved)
        assertEquals("ep-1", retrieved?.episodeId)
        assertEquals(75_000L, retrieved?.positionMs)
        assertEquals(1.5f, retrieved?.playbackSpeed)
        assertEquals(300_000L, retrieved?.durationMs)
        assertEquals(true, retrieved?.isPlaying)
        assertEquals(1700000000000L, retrieved?.lastPlayedAtEpochMs)
        assertEquals(false, retrieved?.completed)
    }

    @Test
    fun all_ordersByLastPlayedAtDesc() {
        val older = PodcastEpisodeProgress("ep-older", 10_000L, 1f, 100_000L, false, 1000L, false)
        val newer = PodcastEpisodeProgress("ep-newer", 20_000L, 1f, 100_000L, false, 5000L, false)

        progressStore.save(older)
        progressStore.save(newer)

        val all = progressStore.all()
        assertEquals(2, all.size)
        assertEquals("ep-newer", all[0].episodeId)
        assertEquals("ep-older", all[1].episodeId)
    }

    @Test
    fun getLatestActive_returnsOnlyNonCompletedWithPositivePosition() {
        val completed = PodcastEpisodeProgress("ep-done", 300_000L, 1f, 300_000L, false, 1000L, completed = true)
        val zeroPos = PodcastEpisodeProgress("ep-zero", 0L, 1f, 300_000L, false, 2000L, completed = false)
        val inProgress = PodcastEpisodeProgress("ep-active", 45_000L, 1.25f, 300_000L, true, 3000L, completed = false)

        progressStore.save(completed)
        progressStore.save(zeroPos)
        progressStore.save(inProgress)

        val active = progressStore.getLatestActive()
        assertNotNull(active)
        assertEquals("ep-active", active?.episodeId)
        assertEquals(45_000L, active?.positionMs)

        // Once marked completed, it is no longer returned as active
        progressStore.save(inProgress.copy(completed = true))
        val activeAfterComplete = progressStore.getLatestActive()
        assertNull(activeAfterComplete)
    }

    @Test
    fun delete_removesItem() {
        val p = PodcastEpisodeProgress("ep-del", 10_000L, 1f)
        progressStore.save(p)
        assertNotNull(progressStore.get("ep-del"))

        progressStore.delete("ep-del")
        assertNull(progressStore.get("ep-del"))
    }

    @Test
    fun legacyDataMigration_migratesSharedPreferencesToSqlite() {
        val sharedPrefsStore = SharedPreferencesPodcastProgressStore(context)
        sharedPrefsStore.save(PodcastEpisodeProgress("legacy-ep-1", 90_000L, 1.75f))
        sharedPrefsStore.save(PodcastEpisodeProgress("legacy-ep-2", 45_000L, 1.25f))

        assertTrue(progressStore.all().isEmpty())

        LegacyDataMigration.performIfNeeded(context, feedStore, inboxStore, progressStore)

        val migrated1 = progressStore.get("legacy-ep-1")
        val migrated2 = progressStore.get("legacy-ep-2")

        assertNotNull(migrated1)
        assertEquals(90_000L, migrated1?.positionMs)
        assertEquals(1.75f, migrated1?.playbackSpeed)

        assertNotNull(migrated2)
        assertEquals(45_000L, migrated2?.positionMs)
        assertEquals(1.25f, migrated2?.playbackSpeed)
    }
}
