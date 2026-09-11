package ink.underflo.wristbrief.mobile

import android.content.Context
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

interface AccountSessionBridge {
    fun publish(session: AccountSession)
    fun clear()
}

object AccountSessionBridgeContract {
    const val PATH = "/wristbrief/account-session/v1"
    const val PAYLOAD_KEY = "payload"

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
    private val dataClient = Wearable.getDataClient(context.applicationContext)

    override fun publish(session: AccountSession) = publishLatest(AccountSessionBridgeContract.encodeSet(session))

    override fun clear() = publishLatest(AccountSessionBridgeContract.encodeClear())

    private fun publishLatest(payload: ByteArray) {
        val request = PutDataMapRequest.create(AccountSessionBridgeContract.PATH).apply {
            dataMap.putByteArray(AccountSessionBridgeContract.PAYLOAD_KEY, payload)
            // Force a DataItem change even when a repeated clear/set payload is otherwise identical.
            dataMap.putLong("updatedAt", System.currentTimeMillis())
        }.asPutDataRequest().setUrgent()
        dataClient.putDataItem(request)
    }
}
