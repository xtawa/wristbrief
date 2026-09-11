package ink.underflo.wristbrief.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test fun localMutationKeepsClocksMonotonicAndOwnedSnapshotDoesNotEchoRemoteFields() {
        val remote = ItemStateClock("item", read = VersionedFlag(true, 100, SyncOrigin.PHONE))
        val local = localMutation(remote, "item", read = null, saved = true, nowEpochMs = 50, origin = SyncOrigin.WEAR)
        assertEquals(100, local.read!!.changedAtEpochMs)
        assertEquals(50, local.saved!!.changedAtEpochMs)
        val owned = ownedStates(listOf(local), SyncOrigin.WEAR).single()
        assertNull(owned.read)
        assertTrue(owned.saved!!.value)
    }

    @Test fun repeatedLocalMutationWithClockRollbackStillAdvancesTimestamp() {
        val old = ItemStateClock("item", read = VersionedFlag(true, 100, SyncOrigin.WEAR))
        val next = localMutation(old, "item", read = false, saved = null, nowEpochMs = 90, origin = SyncOrigin.WEAR)
        assertEquals(101, next.read!!.changedAtEpochMs)
    }

    @Test fun applyingRemoteFlagsReportsOnlyReadValueChangesForUnreadSurfaces() {
        val currentRead = setOf("already-read")
        val currentSaved = setOf("already-saved")
        val timestampOnlyAndSavedChange = applyItemStateFlags(
            currentRead,
            currentSaved,
            listOf(
                ItemStateClock("already-read", read = VersionedFlag(true, 500, SyncOrigin.PHONE)),
                ItemStateClock("new-save", saved = VersionedFlag(true, 500, SyncOrigin.PHONE)),
            ),
        )
        assertFalse(timestampOnlyAndSavedChange.unreadSurfacesChangedFrom(currentRead))
        assertEquals(setOf("already-saved", "new-save"), timestampOnlyAndSavedChange.savedItemIds)

        val unreadChange = applyItemStateFlags(
            currentRead,
            currentSaved,
            listOf(ItemStateClock("already-read", read = VersionedFlag(false, 501, SyncOrigin.PHONE))),
        )
        assertTrue(unreadChange.unreadSurfacesChangedFrom(currentRead))
        assertTrue(unreadChange.readItemIds.isEmpty())
    }

    @Test fun phoneChannelAcceptsOnlyPhoneOwnedFields() {
        val phone = ItemStateClock("item", read = VersionedFlag(true, 50, SyncOrigin.PHONE))
        assertEquals(phone, ItemStateWireContract.decodeOwned(ItemStateWireContract.encode(listOf(phone)), SyncOrigin.PHONE).single())
    }

    @Test(expected = IllegalArgumentException::class)
    fun phoneChannelRejectsWearOwnedField() {
        val forged = ItemStateClock("item", read = VersionedFlag(true, 50, SyncOrigin.WEAR))
        ItemStateWireContract.decodeOwned(ItemStateWireContract.encode(listOf(forged)), SyncOrigin.PHONE)
    }

    @Test(expected = IllegalArgumentException::class)
    fun duplicateItemRecordsAreRejected() {
        ItemStateWireContract.decode("""{"version":1,"items":[{"itemId":"item"},{"itemId":"item"}]}""")
    }

    @Test(expected = IllegalArgumentException::class)
    fun futureWireVersionIsRejected() {
        ItemStateWireContract.decode("""{"version":2,"items":[]}""")
    }
}
