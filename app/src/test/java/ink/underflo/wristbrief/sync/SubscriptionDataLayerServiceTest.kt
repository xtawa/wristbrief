package ink.underflo.wristbrief.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class SubscriptionDataLayerServiceTest {
    @Test fun decoder_acceptsV1Subscriptions() {
        val decoded = SubscriptionDataLayerService.decodeSubscriptions("""{"version":1,"subscriptions":[{"id":"a","title":"Feed","url":"https://example.com/feed","enabled":false}]}""")!!
        assertEquals(1, decoded.size); assertFalse(decoded.single().enabled)
    }

    @Test fun decoder_rejectsUnknownVersionAndNonHttpsFeed() {
        assertNull(SubscriptionDataLayerService.decodeSubscriptions("""{"version":2,"subscriptions":[]}"""))
        assertNull(SubscriptionDataLayerService.decodeSubscriptions("""{"version":1,"subscriptions":[{"id":"a","title":"Feed","url":"http://example.com/feed"}]}"""))
    }
}
