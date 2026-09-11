package ink.underflo.wristbrief.mobile

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import ink.underflo.wristbrief.mobile.db.LegacyDataMigration
import ink.underflo.wristbrief.mobile.db.SqliteMobileFeedStore
import ink.underflo.wristbrief.mobile.db.SqliteMobileInboxStore
import ink.underflo.wristbrief.mobile.db.WristBriefDatabaseHelper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SqliteStoresAndMigrationTest {

    private lateinit var context: Context
    private lateinit var dbHelper: WristBriefDatabaseHelper
    private lateinit var feedStore: SqliteMobileFeedStore
    private lateinit var inboxStore: SqliteMobileInboxStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("wristbrief_test.db")
        context.getSharedPreferences("wristbrief_migration", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("wristbrief_mobile_feeds", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("wristbrief_mobile_inbox", Context.MODE_PRIVATE).edit().clear().commit()

        dbHelper = WristBriefDatabaseHelper(context, databaseName = "wristbrief_test.db")
        feedStore = SqliteMobileFeedStore(dbHelper)
        inboxStore = SqliteMobileInboxStore(dbHelper, maxRetentionItems = 50)
    }

    @After
    fun tearDown() {
        dbHelper.close()
        context.deleteDatabase("wristbrief_test.db")
    }

    @Test
    fun feedStore_crud_andMetadataParity() {
        assertTrue(feedStore.load().isEmpty())

        val feed1 = MobileFeedSubscription(
            id = "feed-1",
            title = "Android Dev",
            url = "https://android-developers.googleblog.com/feed",
            enabled = true,
            sendToWatch = true,
            category = "Tech",
            watchKeywords = listOf("compose", "wear"),
        )
        val feed2 = MobileFeedSubscription(
            id = "feed-2",
            title = "News Podcast",
            url = "https://example.com/podcast.xml",
            enabled = false,
            sendToWatch = false,
            category = "News",
            watchKeywords = emptyList(),
        )

        feedStore.save(listOf(feed1, feed2))
        val loaded = feedStore.load()
        assertEquals(2, loaded.size)
        assertEquals(feed1, loaded.find { it.id == "feed-1" })
        assertEquals(feed2, loaded.find { it.id == "feed-2" })

        // Update feed1 and remove feed2
        val updatedFeed1 = feed1.copy(title = "Android Developers Blog Updated", enabled = false)
        feedStore.save(listOf(updatedFeed1))
        val reloaded = feedStore.load()
        assertEquals(1, reloaded.size)
        assertEquals("Android Developers Blog Updated", reloaded[0].title)
        assertFalse(reloaded[0].enabled)
    }

    @Test
    fun inboxStore_savesAndPrunesRetention() {
        assertTrue(inboxStore.load().isEmpty())

        val items = (1..60).map { i ->
            MobileFeedItem(
                id = "item-$i",
                feedId = "feed-1",
                feedTitle = "Test Feed",
                title = "Title $i",
                link = "https://example.com/item/$i",
                description = "Description $i",
                published = "2026-09-11",
                audioUrl = if (i % 2 == 0) "https://example.com/audio/$i.mp3" else null,
                cachedAtEpochMs = i * 1000L,
            )
        }

        inboxStore.save(items)
        val loaded = inboxStore.load()
        assertEquals(50, loaded.size)
        // Should retain the 50 newest items (items 11 to 60)
        assertEquals("item-60", loaded.first().id)
        assertEquals("item-11", loaded.last().id)
    }

    @Test
    fun legacyDataMigration_migratesFromSharedPreferences() {
        val legacyFeedStore = SharedPreferencesMobileFeedStore(context)
        val legacyInboxStore = SharedPreferencesMobileInboxStore(context)

        val feed = MobileFeedSubscription(
            id = "legacy-feed-1",
            title = "Legacy Feed",
            url = "https://legacy.example.com/feed",
            category = "Legacy",
        )
        val item = MobileFeedItem(
            id = "legacy-item-1",
            feedId = "legacy-feed-1",
            feedTitle = "Legacy Feed",
            title = "Legacy Article",
            link = "https://legacy.example.com/1",
            description = "Legacy Desc",
            published = "2026-09-10",
            audioUrl = null,
            cachedAtEpochMs = 5000L,
        )

        legacyFeedStore.save(listOf(feed))
        legacyInboxStore.save(listOf(item))

        assertTrue(feedStore.load().isEmpty())
        assertTrue(inboxStore.load().isEmpty())

        LegacyDataMigration.performIfNeeded(context, feedStore, inboxStore)

        val migratedFeeds = feedStore.load()
        val migratedItems = inboxStore.load()

        assertEquals(1, migratedFeeds.size)
        assertEquals("legacy-feed-1", migratedFeeds[0].id)
        assertEquals(1, migratedItems.size)
        assertEquals("legacy-item-1", migratedItems[0].id)

        // Subsequent call is idempotent and does nothing
        LegacyDataMigration.performIfNeeded(context, feedStore, inboxStore)
        assertEquals(1, feedStore.load().size)
    }
}
