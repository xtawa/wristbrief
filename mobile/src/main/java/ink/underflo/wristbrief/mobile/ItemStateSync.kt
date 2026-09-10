package ink.underflo.wristbrief.mobile

/** Device identifier used only as a deterministic conflict tie-breaker. */
enum class SyncOrigin { PHONE, WEAR }

data class VersionedFlag(
    val value: Boolean,
    val changedAtEpochMs: Long,
    val origin: SyncOrigin,
)

data class ItemStateClock(
    val itemId: String,
    val read: VersionedFlag? = null,
    val saved: VersionedFlag? = null,
)

/**
 * Merge read and saved independently. Newer timestamps win; equal timestamps use
 * a stable origin ordering so both devices converge regardless of delivery order.
 */
fun mergeItemState(local: ItemStateClock?, remote: ItemStateClock): ItemStateClock {
    require(remote.itemId.isNotBlank()) { "itemId must not be blank" }
    require(local == null || local.itemId == remote.itemId) { "Cannot merge different items" }
    return ItemStateClock(
        itemId = remote.itemId,
        read = chooseFlag(local?.read, remote.read),
        saved = chooseFlag(local?.saved, remote.saved),
    )
}

private fun chooseFlag(local: VersionedFlag?, remote: VersionedFlag?): VersionedFlag? = when {
    local == null -> remote
    remote == null -> local
    remote.changedAtEpochMs > local.changedAtEpochMs -> remote
    remote.changedAtEpochMs < local.changedAtEpochMs -> local
    remote.origin.ordinal > local.origin.ordinal -> remote
    else -> local
}
