package ink.underflo.wristbrief.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ItemStateSyncTest {
    @Test fun newerRemoteReadWinsWithoutOverwritingNewerLocalSaved() {
        val local = ItemStateClock("item-1", VersionedFlag(false, 10, SyncOrigin.PHONE), VersionedFlag(true, 30, SyncOrigin.PHONE))
        val remote = ItemStateClock("item-1", VersionedFlag(true, 20, SyncOrigin.WEAR), VersionedFlag(false, 25, SyncOrigin.WEAR))
        val merged = mergeItemState(local, remote)
        assertTrue(merged.read!!.value)
        assertTrue(merged.saved!!.value)
    }

    @Test fun equalTimestampConvergesIndependentOfDeliveryOrder() {
        val phone = ItemStateClock("item-1", read = VersionedFlag(false, 50, SyncOrigin.PHONE))
        val wear = ItemStateClock("item-1", read = VersionedFlag(true, 50, SyncOrigin.WEAR))
        assertEquals(mergeItemState(phone, wear), mergeItemState(wear, phone))
        assertTrue(mergeItemState(phone, wear).read!!.value)
    }

    @Test fun missingRemoteFieldDoesNotClearExistingState() {
        val local = ItemStateClock("item-1", VersionedFlag(true, 10, SyncOrigin.PHONE), VersionedFlag(true, 11, SyncOrigin.PHONE))
        val merged = mergeItemState(local, ItemStateClock("item-1", read = VersionedFlag(false, 12, SyncOrigin.WEAR)))
        assertFalse(merged.read!!.value)
        assertTrue(merged.saved!!.value)
    }

    @Test fun wirePayloadRoundTripsIndependentFieldClocks() {
        val states = listOf(ItemStateClock("item-1", VersionedFlag(true, 100, SyncOrigin.WEAR), VersionedFlag(false, 90, SyncOrigin.PHONE)))
        assertEquals(states, ItemStateWireContract.decode(ItemStateWireContract.encode(states)))
    }

    @Test fun phoneOwnedSnapshotDoesNotEchoWearFieldsAndClockRollbackAdvancesLocalField() {
        val existing = ItemStateClock("item-1", read = VersionedFlag(true, 100, SyncOrigin.PHONE), saved = VersionedFlag(true, 120, SyncOrigin.WEAR))
        val next = localMutation(existing, "item-1", read = false, saved = null, nowEpochMs = 50, origin = SyncOrigin.PHONE)
        assertEquals(101, next.read!!.changedAtEpochMs)
        val owned = ownedStates(listOf(next), SyncOrigin.PHONE).single()
        assertFalse(owned.read!!.value)
        assertNull(owned.saved)
    }

    @Test fun pathsAreOwnedPerDeviceAndRemotePathNeverEchoesLocalPath() {
        assertEquals(ItemStateWireContract.PHONE_PATH, ItemStateWireContract.pathFor(SyncOrigin.PHONE))
        assertEquals(ItemStateWireContract.WEAR_PATH, ItemStateWireContract.remotePathFor(SyncOrigin.PHONE))
    }

    @Test(expected = IllegalArgumentException::class)
    fun futureWireVersionIsRejected() { ItemStateWireContract.decode("""{"version":2,"items":[]}""") }

    @Test(expected = IllegalStateException::class)
    fun missingWireItemsIsRejected() { ItemStateWireContract.decode("""{"version":1}""") }

    @Test(expected = IllegalArgumentException::class)
    fun differentItemIdsCannotBeMerged() { mergeItemState(ItemStateClock("a"), ItemStateClock("b")) }
}
