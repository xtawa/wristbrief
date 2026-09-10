package ink.underflo.wristbrief

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import ink.underflo.wristbrief.media.PodcastPlaybackService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {
    @Test
    fun configuredWearDeviceIsRoundAndInSupportedWidthClass() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val configuration = context.resources.configuration

        assertTrue("Wear release checks require a round display", configuration.isScreenRound)
        assertTrue(
            "Wear release checks target 192-240dp-class round displays; actual=${configuration.screenWidthDp}dp",
            configuration.screenWidthDp in 192..240,
        )
    }

    @Test
    fun mainActivityStartsWithoutFinishing() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertFalse(activity.isFinishing)
                assertFalse(activity.isDestroyed)
            }
        }
    }

    @Test
    fun mainActivitySurvivesRecreationAndReturnsResumed() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.recreate()
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
            scenario.onActivity { activity ->
                assertFalse(activity.isFinishing)
                assertFalse(activity.isDestroyed)
            }
        }
    }

    @Test
    fun mainActivitySurvivesBackgroundAndResume() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.moveToState(Lifecycle.State.CREATED)
            assertEquals(Lifecycle.State.CREATED, scenario.state)
            scenario.moveToState(Lifecycle.State.RESUMED)
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
            scenario.onActivity { activity ->
                assertFalse(activity.isFinishing)
                assertFalse(activity.isDestroyed)
            }
        }
    }

    @Suppress("DEPRECATION")
    @Test
    fun playbackServiceSurvivesActivityRecreationAndBackgroundCycle() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val serviceIntent = Intent(context, PodcastPlaybackService::class.java)
        val activityManager = context.getSystemService(ActivityManager::class.java)

        try {
            context.startService(serviceIntent)
            assertTrue(isPlaybackServiceRunning(activityManager))

            ActivityScenario.launch(MainActivity::class.java).use { activityScenario ->
                activityScenario.recreate()
                assertEquals(Lifecycle.State.RESUMED, activityScenario.state)
                activityScenario.moveToState(Lifecycle.State.CREATED)
                assertEquals(Lifecycle.State.CREATED, activityScenario.state)
                activityScenario.moveToState(Lifecycle.State.RESUMED)
                assertEquals(Lifecycle.State.RESUMED, activityScenario.state)
            }

            assertTrue(isPlaybackServiceRunning(activityManager))
        } finally {
            context.stopService(serviceIntent)
        }
    }

    @Suppress("DEPRECATION")
    private fun isPlaybackServiceRunning(activityManager: ActivityManager): Boolean =
        activityManager.getRunningServices(Int.MAX_VALUE).any { serviceInfo ->
            serviceInfo.service.className == PodcastPlaybackService::class.java.name
        }
}
