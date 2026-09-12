package ink.underflo.wristbrief.mobile.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Runtime owner of the (previously dormant) CloudSyncCoordinator. Sync cycles
 * run on a background scope, one at a time, and are triggered by:
 *  - app start / ON_RESUME (pull + outbox drain),
 *  - every local mutation enqueued to the outbox.
 * Signed-out users never start a cycle. Failures are swallowed here: the outbox
 * keeps mutations with exponential backoff and the next trigger retries them.
 */
class CloudSyncRuntime(
    private val coordinator: CloudSyncCoordinator,
    private val sessionTokenProvider: () -> String?,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    /** Returns the cycle's job, or null when signed out (nothing to sync). */
    fun requestSync(): Job? {
        val sessionToken = sessionTokenProvider() ?: return null
        return scope.launch {
            mutex.withLock {
                runCatching { coordinator.syncOnce(sessionToken) }
            }
        }
    }

    fun dispose() {
        scope.cancel()
    }
}
