package ink.underflo.wristbrief.complication

import android.content.ComponentName
import android.content.Context
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester

fun requestUnreadComplicationUpdate(context: Context) {
    val appContext = context.applicationContext
    ComplicationDataSourceUpdateRequester.create(
        appContext,
        ComponentName(appContext, UnreadComplicationDataSourceService::class.java)
    ).requestUpdateAll()
}
