package ink.underflo.wristbrief.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class PlaybackAccountFenceTest {
    @Test
    fun itemsLoadedBeforeRevokeCanNoLongerPersist() {
        val fence = PlaybackAccountFence()
        val loadedUnderOldAccount = fence.currentGeneration()
        assertTrue(fence.allowsPersistence(loadedUnderOldAccount))

        fence.revoke()

        assertFalse(fence.allowsPersistence(loadedUnderOldAccount))
        // An item loaded after the revoke belongs to the new account and persists normally.
        assertTrue(fence.allowsPersistence(fence.currentGeneration()))
    }

    @Test
    fun revokeAdvancesGenerationBeforeNotifyingStopHandlers() {
        val fence = PlaybackAccountFence()
        val before = fence.currentGeneration()
        var generationSeenByHandler = -1L
        fence.registerStopHandler { generationSeenByHandler = fence.currentGeneration() }

        val after = fence.revoke()

        assertEquals(before + 1, after)
        assertEquals(after, generationSeenByHandler)
    }

    @Test
    fun unregisteredOrFailingHandlersDoNotBlockTheFence() {
        val fence = PlaybackAccountFence()
        var calls = 0
        val handler: () -> Unit = { calls++ }
        fence.registerStopHandler { error("service already gone") }
        fence.registerStopHandler(handler)
        fence.revoke()
        fence.unregisterStopHandler(handler)
        fence.revoke()

        assertEquals(1, calls)
        assertEquals(2L, fence.currentGeneration())
    }

    @Test
    fun checkpointCannotStartBetweenRevokeAndPurge() {
        val fence = PlaybackAccountFence()
        val oldGeneration = fence.currentGeneration()
        val store = mutableMapOf("ep" to 1L)
        val purgeEntered = CountDownLatch(1)
        val releasePurge = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val purge = executor.submit {
                fence.revokeAndRun {
                    purgeEntered.countDown()
                    // Hold the purge open; a racing checkpoint must not get in here.
                    releasePurge.await(5, TimeUnit.SECONDS)
                    store.clear()
                }
            }
            assertTrue(purgeEntered.await(5, TimeUnit.SECONDS))
            val checkpointRan = AtomicBoolean(false)
            val checkpoint = executor.submit {
                checkpointRan.set(fence.runIfCurrent(oldGeneration) { store["ep"] = 9_000L })
            }
            // The checkpoint blocks on the fence lock while the purge is in progress.
            Thread.sleep(100)
            assertFalse(checkpoint.isDone)
            releasePurge.countDown()
            purge.get(5, TimeUnit.SECONDS)
            checkpoint.get(5, TimeUnit.SECONDS)

            assertFalse("old-account checkpoint must be refused after the purge", checkpointRan.get())
            assertTrue("cleared progress must stay cleared", store.isEmpty())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun checkpointThatWinsTheRaceIsDeletedByThePurgeAndNewAccountWritesSurvive() {
        val fence = PlaybackAccountFence()
        val oldGeneration = fence.currentGeneration()
        val store = mutableMapOf<String, Long>()

        assertTrue(fence.runIfCurrent(oldGeneration) { store["old"] = 1_000L })
        val newGeneration = fence.revokeAndRun { store.clear() }
        assertFalse(fence.runIfCurrent(oldGeneration) { store["old"] = 2_000L })
        assertTrue(fence.runIfCurrent(newGeneration) { store["new"] = 3_000L })

        assertEquals(mapOf("new" to 3_000L), store)
    }

    @Test
    fun concurrentCheckpointsNeverSurviveARevokeAndPurge() {
        repeat(50) {
            val fence = PlaybackAccountFence()
            val oldGeneration = fence.currentGeneration()
            val store = java.util.concurrent.ConcurrentHashMap<String, Long>()
            val writesAfterPurge = CopyOnWriteArrayList<Long>()
            val purged = AtomicBoolean(false)
            val start = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(4)
            try {
                val writers = (0 until 3).map { worker ->
                    executor.submit {
                        start.await()
                        repeat(200) { step ->
                            fence.runIfCurrent(oldGeneration) {
                                if (purged.get()) writesAfterPurge += step.toLong()
                                store["ep$worker"] = step.toLong()
                            }
                        }
                    }
                }
                val purge = executor.submit {
                    start.await()
                    fence.revokeAndRun {
                        store.clear()
                        purged.set(true)
                    }
                }
                start.countDown()
                writers.forEach { it.get(5, TimeUnit.SECONDS) }
                purge.get(5, TimeUnit.SECONDS)

                assertTrue("no old-account write may land after the purge", writesAfterPurge.isEmpty())
                assertTrue(store.isEmpty())
            } finally {
                executor.shutdownNow()
            }
        }
    }
}
