package ink.underflo.wristbrief.sync

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountSessionDataLayerTest {
    private val token = "wbs_${"A".repeat(43)}"

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
