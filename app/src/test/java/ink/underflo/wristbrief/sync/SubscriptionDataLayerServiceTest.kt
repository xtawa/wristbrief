package ink.underflo.wristbrief.sync

import ink.underflo.wristbrief.data.FeedSubscription
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionDataLayerServiceTest {
    @Test fun decoder_acceptsV1SubscriptionsAndKeywords() {
        val decoded = SubscriptionDataLayerService.decodeSubscriptions("""{"version":1,"subscriptions":[{"id":"a","title":"Feed","url":"https://example.com/feed","enabled":false,"watchKeywords":[" AI ","ai","Wear OS"]}]}""")!!
        assertEquals(1, decoded.size)
        assertFalse(decoded.single().enabled)
        assertEquals(listOf("AI", "Wear OS"), decoded.single().watchKeywords)
    }

    @Test fun decoder_acceptsLegacyV1WithoutKeywords() {
        val decoded = SubscriptionDataLayerService.decodeSubscriptions("""{"version":1,"subscriptions":[{"id":"a","title":"Feed","url":"https://example.com/feed"}]}""")!!
        assertTrue(decoded.single().watchKeywords.isEmpty())
    }

    @Test fun decoder_acceptsExplicitEmptySubscriptionList() {
        assertEquals(emptyList<Any>(), SubscriptionDataLayerService.decodeSubscriptions("""{"version":1,"subscriptions":[]}"""))
    }

    @Test fun decoder_rejectsMissingSubscriptionsToAvoidAccidentalWipe() {
        assertNull(SubscriptionDataLayerService.decodeSubscriptions("""{"version":1}"""))
    }

    @Test fun decoder_rejectsUnknownVersionAndNonHttpsFeed() {
        assertNull(SubscriptionDataLayerService.decodeSubscriptions("""{"version":2,"subscriptions":[]}"""))
        assertNull(SubscriptionDataLayerService.decodeSubscriptions("""{"version":1,"subscriptions":[{"id":"a","title":"Feed","url":"http://example.com/feed"}]}"""))
    }

    @Test fun decoder_rejectsMalformedHttpsUrl() {
        assertNull(SubscriptionDataLayerService.decodeSubscriptions("""{"version":1,"subscriptions":[{"id":"a","title":"Feed","url":"https://"}]}"""))
    }

    @Test fun decoder_normalizesUrlAndTrimsIdentityFields() {
        val decoded = SubscriptionDataLayerService.decodeSubscriptions("""{"version":1,"subscriptions":[{"id":" a ","title":" Feed ","url":" HTTPS://Example.COM:443/feed/ "}]}""")!!
        assertEquals("a", decoded.single().id)
        assertEquals("Feed", decoded.single().title)
        assertEquals("https://example.com/feed", decoded.single().url)
    }

    @Test fun identicalNormalizedSnapshotDoesNotRequirePersistenceOrSurfaceRefresh() {
        val current = listOf(
            FeedSubscription(
                id = "a",
                title = "Feed",
                url = "https://example.com/feed",
                enabled = false,
                watchKeywords = listOf("AI", "Wear OS"),
            )
        )
        val incoming = SubscriptionDataLayerService.decodeSubscriptions(
            """{"version":1,"subscriptions":[{"id":" a ","title":" Feed ","url":"HTTPS://EXAMPLE.COM:443/feed/","enabled":false,"watchKeywords":[" AI ","ai","Wear OS"]}]}"""
        )!!
        assertFalse(subscriptionSnapshotChanged(current, incoming))
        assertTrue(subscriptionSnapshotChanged(current, incoming.map { it.copy(enabled = true) }))
        assertTrue(subscriptionSnapshotChanged(current, incoming + incoming.single().copy(id = "b", url = "https://example.com/b")))
    }

    @Test fun decoder_rejectsDuplicateTrimmedIds() {
        assertNull(SubscriptionDataLayerService.decodeSubscriptions("""{"version":1,"subscriptions":[{"id":"a","title":"One","url":"https://example.com/one"},{"id":" a ","title":"Two","url":"https://example.com/two"}]}"""))
    }

    @Test fun decoder_rejectsDuplicateLogicalUrls() {
        assertNull(SubscriptionDataLayerService.decodeSubscriptions("""{"version":1,"subscriptions":[{"id":"a","title":"One","url":"https://EXAMPLE.com:443/feed/"},{"id":"b","title":"Two","url":"https://example.com/feed"}]}"""))
    }
}
