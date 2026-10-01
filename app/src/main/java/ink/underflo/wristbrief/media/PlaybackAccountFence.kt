package ink.underflo.wristbrief.media

import android.os.Bundle
import androidx.media3.common.MediaItem
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Process-wide account fence for podcast playback.
 *
 * Each media item is tagged with the fence generation that was current when the user asked to play it
 * (see [tagWithAccountGeneration]); the playback service reads that tag when the item loads.
 *
 * Two operations share one lock, so they can never interleave:
 * - [runIfCurrent]: playback checkpoints check the generation **and** write/publish progress as one
 *   step. A checkpoint therefore either finishes completely before a purge starts (and the purge then
 *   deletes what it wrote) or is refused.
 * - [revokeAndRun]: sign-out/account switch increments the generation and clears all account data
 *   inside the same lock. A checkpoint for an item loaded after the revoke waits until the purge has
 *   finished, so the new account's progress is never deleted by an old purge.
 *
 * After the lock is released, registered stop handlers are asked to stop and clear any live session
 * whose item is now stale.
 *
 * The fence is in memory only. If the process dies, the playback service dies with it, so there is no
 * session left that could revive old progress.
 */
internal class PlaybackAccountFence {
    private val lock = Any()
    @Volatile private var generation = 0L
    private val stopHandlers = CopyOnWriteArraySet<() -> Unit>()

    fun currentGeneration(): Long = generation

    /** True only when the item was loaded under the current account generation (advisory; use [runIfCurrent] to write). */
    fun allowsPersistence(loadedGeneration: Long): Boolean = loadedGeneration == generation

    /**
     * Runs [block] only if [loadedGeneration] is still current. Holds the fence lock for the whole
     * block, so a concurrent [revokeAndRun] cannot start in between. [block] must not block on the main
     * thread (it runs synchronously on the caller). Returns whether [block] ran.
     */
    fun runIfCurrent(loadedGeneration: Long, block: () -> Unit): Boolean = synchronized(lock) {
        if (loadedGeneration != generation) return false
        block()
        true
    }

    fun registerStopHandler(handler: () -> Unit) { stopHandlers += handler }

    fun unregisterStopHandler(handler: () -> Unit) { stopHandlers -= handler }

    /**
     * Fences out every item loaded so far, then runs [purge] while still holding the lock, then asks
     * every live session to stop. Returns the new generation.
     */
    fun revokeAndRun(purge: () -> Unit): Long {
        val next = synchronized(lock) {
            generation += 1
            purge()
            generation
        }
        stopHandlers.forEach { handler -> runCatching { handler() } }
        return next
    }

    /** Revoke with nothing to purge. */
    fun revoke(): Long = revokeAndRun {}

    companion object {
        val process = PlaybackAccountFence()

        internal const val GENERATION_EXTRA = "ink.underflo.wristbrief.accountGeneration"

        /** Generation stored on [item] by [tagWithAccountGeneration], or null for untagged items. */
        fun generationOf(item: MediaItem?): Long? =
            item?.requestMetadata?.extras
                ?.takeIf { it.containsKey(GENERATION_EXTRA) }
                ?.getLong(GENERATION_EXTRA)
    }
}

/** Tags a play request with the account generation that was current when the user asked to play it. */
internal fun MediaItem.tagWithAccountGeneration(generation: Long): MediaItem {
    val extras = Bundle(requestMetadata.extras ?: Bundle()).apply {
        putLong(PlaybackAccountFence.GENERATION_EXTRA, generation)
    }
    return buildUpon()
        .setRequestMetadata(requestMetadata.buildUpon().setExtras(extras).build())
        .build()
}
