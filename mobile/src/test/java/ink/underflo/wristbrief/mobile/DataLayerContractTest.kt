package ink.underflo.wristbrief.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DataLayerContractTest {
    @Test fun payload_roundTripsVersionedLightweightState() {
        val payload = WearSyncPayload(subscriptions = listOf(SyncFeed("a", "Feed A", "https://example.com/a", true)), readItemIds = setOf("r1"), savedItemIds = setOf("s1"))
        assertEquals(payload, WearDataLayerContract.decode(WearDataLayerContract.encode(payload)))
        assertTrue(WearDataLayerContract.encode(payload).length < 4096)
    }

    @Test(expected = IllegalArgumentException::class)
    fun futurePayloadVersion_isRejected() { WearDataLayerContract.decode("{\"version\":2,\"subscriptions\":[]}") }
}
