package ink.underflo.wristbrief

import android.content.ComponentName
import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ink.underflo.wristbrief.media.PodcastPlaybackService
import java.util.concurrent.TimeUnit
import kotlin.math.abs
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
    fun configuredFontScaleReflectsReleaseMatrixArgument() {
        val expected = InstrumentationRegistry.getArguments()
            .getString("expectedFontScale")
            ?.toFloatOrNull()
            ?: return
        val actual = ApplicationProvider.getApplicationContext<Context>()
            .resources
            .configuration
            .fontScale

        if (expected > 1f) {
            assertTrue(
                "Expected a large-font Wear configuration for requested scale $expected but app reports $actual",
                actual > 1f,
            )
        } else {
            assertTrue(
                "Expected default Wear font scale $expected but app reports $actual",
                abs(actual - expected) < 0.01f,
            )
        }
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

    @Test
    fun mediaSessionControllerSurvivesActivityRecreationAndBackgroundCycle() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sessionToken = SessionToken(
            context,
            ComponentName(context, PodcastPlaybackService::class.java),
        )
        val controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
        val controller = controllerFuture.get(15, TimeUnit.SECONDS)

        try {
            assertTrue("MediaController must connect to the playback service", controller.isConnected)

            ActivityScenario.launch(MainActivity::class.java).use { activityScenario ->
                activityScenario.recreate()
                assertEquals(Lifecycle.State.RESUMED, activityScenario.state)
                assertTrue(
                    "Playback session must outlive activity recreation",
                    controller.isConnected,
                )

                activityScenario.moveToState(Lifecycle.State.CREATED)
                assertEquals(Lifecycle.State.CREATED, activityScenario.state)
                assertTrue(
                    "Playback session must remain available while the activity is backgrounded",
                    controller.isConnected,
                )

                activityScenario.moveToState(Lifecycle.State.RESUMED)
                assertEquals(Lifecycle.State.RESUMED, activityScenario.state)
                assertTrue(
                    "Playback session must remain connected after foreground resume",
                    controller.isConnected,
                )
            }
        } finally {
            MediaController.releaseFuture(controllerFuture)
        }
    }
}
