package ink.underflo.wristbrief.sync

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.wear.remote.interactions.RemoteActivityHelper
import java.net.URI

/** Opens a feed/article URL on the paired phone using the Wear remote-activity API. */
class ContinueOnPhoneLauncher(context: Context) {
    private val remoteActivityHelper = RemoteActivityHelper(context.applicationContext)

    fun open(rawUrl: String) = normalizedContinueUrl(rawUrl)?.let { url ->
        remoteActivityHelper.startRemoteActivity(
            Intent(Intent.ACTION_VIEW)
                .setData(Uri.parse(url))
                .addCategory(Intent.CATEGORY_BROWSABLE),
        )
    }
}

/** Only browser-safe HTTP(S) URLs may cross the remote-activity boundary. */
internal fun normalizedContinueUrl(rawUrl: String): String? = runCatching {
    val value = rawUrl.trim()
    val uri = URI(value)
    require(uri.scheme.equals("https", ignoreCase = true) || uri.scheme.equals("http", ignoreCase = true))
    require(!uri.host.isNullOrBlank())
    uri.normalize().toASCIIString()
}.getOrNull()
