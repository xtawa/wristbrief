package ink.underflo.wristbrief.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ItemStateSyncTest {
    @Test fun wearContractMatchesPhoneOwnedPaths() {
        assertEquals("/wristbrief/item-state/v1/phone", ItemStateWireContract.PHONE_PATH)
        assertEquals("/wristbrief/item-state/v1/wear", ItemStateWireContract.WEAR_PATH)
        assertEquals(ItemStateWireContract.PHONE_PATH, ItemStateWireContract.remotePathFor(SyncOrigin.WEAR))
    }

    @Test fun payloadRoundTripsAndMergeConverges() {
        val phone = ItemStateClock("item", read = VersionedFlag(false, 50, SyncOrigin.PHONE))
        val wear = ItemStateClock("item", read = VersionedFlag(true, 50, SyncOrigin.WEAR))
        val decoded = ItemStateWireContract.decode(ItemStateWireContract.encode(listOf(wear))).single()

        assertEquals(wear, decoded)
        assertEquals(mergeItemState(phone, decoded), mergeItemState(decoded, phone))
        assertTrue(mergeItemState(phone, decoded).read!!.value)
    }

    @Test(expected = IllegalArgumentException::class)
    fun futureWireVersionIsRejected() {
        ItemStateWireContract.decode("""{"version":2,"items":[]}""")
    }
}
