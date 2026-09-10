package ink.underflo.wristbrief.mobile

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AccountSessionBridgeTest {
    @Test
    fun setPayloadContainsOnlyScopedWristBriefSessionFields() {
        val token = "wbs_${"A".repeat(43)}"
        val session = AccountSession(token, "2026-10-01T00:00:00Z", AccountUser("usr_123"))
        val root = Json.parseToJsonElement(AccountSessionBridgeContract.encodeSet(session).decodeToString()).jsonObject

        assertEquals("1", root["version"]?.jsonPrimitive?.content)
        assertEquals("set", root["operation"]?.jsonPrimitive?.content)
        assertEquals(token, root["sessionToken"]?.jsonPrimitive?.content)
        assertEquals("2026-10-01T00:00:00Z", root["expiresAt"]?.jsonPrimitive?.content)
        assertEquals("usr_123", root["userId"]?.jsonPrimitive?.content)
        assertFalse(root.containsKey("idToken"))
        assertFalse(root.containsKey("purchaseToken"))
        assertFalse(root.containsKey("providerKey"))
        assertFalse(root.containsKey("authorization"))
    }

    @Test
    fun clearPayloadCarriesNoCredential() {
        val raw = AccountSessionBridgeContract.encodeClear().decodeToString()
        val root = Json.parseToJsonElement(raw).jsonObject
        assertEquals("clear", root["operation"]?.jsonPrimitive?.content)
        assertFalse(root.containsKey("sessionToken"))
    }
}
