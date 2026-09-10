package ink.underflo.wristbrief.mobile

import android.content.Context
import com.google.android.gms.wearable.Wearable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

interface AccountSessionBridge {
    fun publish(session: AccountSession)
    fun clear()
}

object AccountSessionBridgeContract {
    const val PATH = "/wristbrief/account-session/v1"

    fun encodeSet(session: AccountSession): ByteArray {
        require(validSessionToken(session.sessionToken))
        return buildJsonObject {
            put("version", 1)
            put("operation", "set")
            put("sessionToken", session.sessionToken)
            put("expiresAt", session.expiresAt)
            put("userId", session.user.id)
        }.toString().encodeToByteArray()
    }

    fun encodeClear(): ByteArray = buildJsonObject {
        put("version", 1)
        put("operation", "clear")
    }.toString().encodeToByteArray()
}

class GoogleWearAccountSessionBridge(context: Context) : AccountSessionBridge {
    private val nodeClient = Wearable.getNodeClient(context.applicationContext)
    private val messageClient = Wearable.getMessageClient(context.applicationContext)

    override fun publish(session: AccountSession) = send(AccountSessionBridgeContract.encodeSet(session))

    override fun clear() = send(AccountSessionBridgeContract.encodeClear())

    private fun send(payload: ByteArray) {
        nodeClient.connectedNodes.addOnSuccessListener { nodes ->
            nodes.forEach { node ->
                // Best effort: the scoped WristBrief session is short-lived and replaceable.
                messageClient.sendMessage(node.id, AccountSessionBridgeContract.PATH, payload)
            }
        }
    }
}
