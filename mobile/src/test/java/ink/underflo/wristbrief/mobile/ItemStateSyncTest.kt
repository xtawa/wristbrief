package ink.underflo.wristbrief.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ItemStateSyncTest {
    @Test fun newerRemoteReadWinsWithoutOverwritingNewerLocalSaved() {
        val local = ItemStateClock(
            "item-1",
            read = VersionedFlag(false, 10, SyncOrigin.PHONE),
            saved = VersionedFlag(true, 30, SyncOrigin.PHONE),
        )
        val remote = ItemStateClock(
            "item-1",
            read = VersionedFlag(true, 20, SyncOrigin.WEAR),
            saved = VersionedFlag(false, 25, SyncOrigin.WEAR),
        )

        val merged = mergeItemState(local, remote)

        assertTrue(merged.read!!.value)
        assertTrue(merged.saved!!.value)
    }

    @Test fun equalTimestampConvergesIndependentOfDeliveryOrder() {
        val phone = ItemStateClock("item-1", read = VersionedFlag(false, 50, SyncOrigin.PHONE))
        val wear = ItemStateClock("item-1", read = VersionedFlag(true, 50, SyncOrigin.WEAR))

        val phoneThenWear = mergeItemState(phone, wear)
        val wearThenPhone = mergeItemState(wear, phone)

        assertEquals(phoneThenWear, wearThenPhone)
        assertTrue(phoneThenWear.read!!.value)
    }

    @Test fun missingRemoteFieldDoesNotClearExistingState() {
        val local = ItemStateClock(
            "item-1",
            read = VersionedFlag(true, 10, SyncOrigin.PHONE),
            saved = VersionedFlag(true, 11, SyncOrigin.PHONE),
        )

        val merged = mergeItemState(local, ItemStateClock("item-1", read = VersionedFlag(false, 12, SyncOrigin.WEAR)))

        assertFalse(merged.read!!.value)
        assertTrue(merged.saved!!.value)
    }

    @Test(expected = IllegalArgumentException::class)
    fun differentItemIdsCannotBeMerged() {
        mergeItemState(ItemStateClock("a"), ItemStateClock("b"))
    }
}
