package ink.underflo.wristbrief.complication

import android.app.PendingIntent
import android.content.Intent
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import ink.underflo.wristbrief.MainActivity
import ink.underflo.wristbrief.R
import ink.underflo.wristbrief.data.SharedPreferencesFeedStore

/** Cached/local-only complication provider. It never performs feed or AI network work. */
class UnreadComplicationDataSourceService : SuspendingComplicationDataSourceService() {
    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val store = SharedPreferencesFeedStore(this)
        val snapshot = buildUnreadComplicationSnapshot(
            subscriptions = store.subscriptions(),
            cachedItems = store.cachedItems(),
            readItemIds = store.readItemIds()
        )
        return snapshot.toComplicationData(request.complicationType, launchAppPendingIntent())
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        UnreadComplicationSnapshot(3, getString(R.string.complication_preview_headline))
            .toComplicationData(type, tapAction = null)

    private fun UnreadComplicationSnapshot.toComplicationData(
        type: ComplicationType,
        tapAction: PendingIntent?
    ): ComplicationData? {
        val description = PlainComplicationText.Builder(
            getString(R.string.complication_unread_description, unreadCount)
        ).build()
        val long = longText(
            caughtUp = { getString(R.string.complication_all_caught_up) },
            unread = { count -> getString(R.string.complication_unread_count, count) },
            unreadWithTitle = { count, title -> getString(R.string.complication_unread_with_title, count, title) },
        )
        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
                text = PlainComplicationText.Builder(shortText).build(),
                contentDescription = description
            ).setTapAction(tapAction).build()
            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(
                text = PlainComplicationText.Builder(long).build(),
                contentDescription = description
            ).setTapAction(tapAction).build()
            else -> null
        }
    }

    private fun launchAppPendingIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}
