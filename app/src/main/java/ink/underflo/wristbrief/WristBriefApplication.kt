package ink.underflo.wristbrief

import android.app.Application
import ink.underflo.wristbrief.sync.WearAccountSessionRuntime

class WristBriefApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        WearAccountSessionRuntime.initialize(this)
    }
}
