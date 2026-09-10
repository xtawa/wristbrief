package ink.underflo.wristbrief

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ServiceScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import ink.underflo.wristbrief.media.PodcastPlaybackService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {
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
    fun playbackServiceSurvivesActivityRecreationAndBackgroundCycle() {
        ServiceScenario.launch(PodcastPlaybackService::class.java).use { serviceScenario ->
            var serviceIdentity = 0
            serviceScenario.onService { service ->
                serviceIdentity = System.identityHashCode(service)
            }

            ActivityScenario.launch(MainActivity::class.java).use { activityScenario ->
                activityScenario.recreate()
                assertEquals(Lifecycle.State.RESUMED, activityScenario.state)
                activityScenario.moveToState(Lifecycle.State.CREATED)
                assertEquals(Lifecycle.State.CREATED, activityScenario.state)
                activityScenario.moveToState(Lifecycle.State.RESUMED)
                assertEquals(Lifecycle.State.RESUMED, activityScenario.state)
            }

            serviceScenario.onService { service ->
                assertEquals(serviceIdentity, System.identityHashCode(service))
            }
        }
    }
}
