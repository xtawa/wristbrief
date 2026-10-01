package ink.underflo.wristbrief.mobile

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WearSyncGateTest {
    private class RecordingPublisher : FeedSyncPublisher {
        val published = mutableListOf<List<MobileFeedSubscription>>()
        override fun publish(feeds: List<MobileFeedSubscription>) { published += feeds }
    }

    private class InMemoryFeedStore(var feeds: List<MobileFeedSubscription>) : MobileFeedStore {
        override fun load(): List<MobileFeedSubscription> = feeds
        override fun save(feeds: List<MobileFeedSubscription>) { this.feeds = feeds }
    }

    /** Mirrors the guard every Data Layer publisher applies before putting an item. */
    private class GatedPublisher(private val gate: WearSyncGate, private val delegate: FeedSyncPublisher) : FeedSyncPublisher {
        override fun publish(feeds: List<MobileFeedSubscription>) {
            if (!gate.isEnabled()) return
            delegate.publish(feeds)
        }
    }

    @Test
    fun `always-on gate is enabled`() {
        assertTrue(WearSyncGate.AlwaysOn.isEnabled())
    }

    @Test
    fun `a disabled gate suppresses publishing and re-enabling resumes it`() = runBlocking {
        var enabled = false
        val recorder = RecordingPublisher()
        val feed = MobileFeedSubscription(id = "f", title = "Feed", url = "https://example.com/feed.xml")
        val manager = MobileFeedManager(
            InMemoryFeedStore(listOf(feed)),
            object : FeedProbe { override suspend fun validate(url: String): String? = null },
            GatedPublisher({ enabled }, recorder),
        )

        manager.setEnabled("f", false)
        assertTrue("nothing reaches the watch while the preference is off", recorder.published.isEmpty())

        enabled = true
        manager.republishToWatch()
        assertEquals(1, recorder.published.size)
        assertEquals(listOf("f"), recorder.published.single().map { it.id })
    }
}
