package ink.underflo.wristbrief.sync

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

    @Test fun decoder_rejectsUnknownVersionAndNonHttpsFeed() {
        assertNull(SubscriptionDataLayerService.decodeSubscriptions("""{"version":2,"subscriptions":[]}"""))
        assertNull(SubscriptionDataLayerService.decodeSubscriptions("""{"version":1,"subscriptions":[{"id":"a","title":"Feed","url":"http://example.com/feed"}]}"""))
    }
}
