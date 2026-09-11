package ink.underflo.wristbrief.sync

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountSessionDataLayerTest {
    private val token = "wbs_${"A".repeat(43)}"

    @Test
    fun dataItemContractMatchesPhoneBridge() {
        assertEquals("/wristbrief/account-session/v1", AccountSessionDataLayerService.PATH)
        assertEquals("payload", AccountSessionDataLayerService.PAYLOAD_KEY)
    }

    @Test
    fun decodesScopedSessionAndClearMessages() {
        val set = WearAccountSessionMessageCodec.decode(
            """{"version":1,"operation":"set","sessionToken":"$token","expiresAt":"2026-10-01T00:00:00Z","userId":"usr_123"}""".encodeToByteArray()
        )
        assertTrue(set is WearAccountSessionMessageCodec.Message.Set)
        assertEquals(token, (set as WearAccountSessionMessageCodec.Message.Set).session.token)

        val clear = WearAccountSessionMessageCodec.decode("""{"version":1,"operation":"clear"}""".encodeToByteArray())
        assertEquals(WearAccountSessionMessageCodec.Message.Clear, clear)
    }

    @Test
    fun invalidLatestSetFailsClosedInsteadOfKeepingPreviousSession() {
        var cleared = false
        var wrote = false
        val expired = WearAccountSession(token, Instant.parse("2026-09-10T00:00:00Z"), "usr_123")

        applyWearAccountSessionMessage(
            message = WearAccountSessionMessageCodec.Message.Set(expired),
            now = Instant.parse("2026-09-11T00:00:00Z"),
            write = { session, now ->
                wrote = true
                isValidWearAccountSession(session, now)
            },
            clear = { cleared = true },
        )

        assertTrue(wrote)
        assertTrue(cleared)
    }

    @Test
    fun malformedLatestStateFailsClosed() {
        var cleared = false
        applyWearAccountSessionMessage(
            message = null,
            now = Instant.parse("2026-09-11T00:00:00Z"),
            write = { _, _ -> false },
            clear = { cleared = true },
        )
        assertTrue(cleared)
    }

    @Test
    fun validLatestSetDoesNotClear() {
        var cleared = false
        val session = WearAccountSession(token, Instant.parse("2026-10-01T00:00:00Z"), "usr_123")
        assertTrue(isValidWearAccountSession(session, Instant.parse("2026-09-11T00:00:00Z")))

        applyWearAccountSessionMessage(
            message = WearAccountSessionMessageCodec.Message.Set(session),
            now = Instant.parse("2026-09-11T00:00:00Z"),
            write = { incoming, now -> isValidWearAccountSession(incoming, now) },
            clear = { cleared = true },
        )

        assertFalse(cleared)
    }

    @Test
    fun runtimeRejectsExpiredSession() {
        WearAccountSessionRuntime.set(
            WearAccountSession(token, Instant.parse("2026-09-10T00:00:00Z"), "usr_123")
        )
        assertNull(WearAccountSessionRuntime.currentToken(Instant.parse("2026-09-11T00:00:00Z")))
    }

    @Test
    fun runtimeProvidesOnlyUnexpiredScopedToken() {
        WearAccountSessionRuntime.set(
            WearAccountSession(token, Instant.parse("2026-10-01T00:00:00Z"), "usr_123")
        )
        assertEquals(token, WearAccountSessionRuntime.currentToken(Instant.parse("2026-09-11T00:00:00Z")))
        WearAccountSessionRuntime.clear()
        assertNull(WearAccountSessionRuntime.currentToken(Instant.parse("2026-09-11T00:00:00Z")))
    }
}
