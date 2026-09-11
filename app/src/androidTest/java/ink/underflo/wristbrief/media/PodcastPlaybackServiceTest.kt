package ink.underflo.wristbrief.media

import android.content.ComponentName
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PodcastPlaybackServiceTest {
    @Test
    fun mediaControllerConnectsToPlaybackServiceWithoutPreparingMedia() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val token = SessionToken(
            context,
            ComponentName(context, PodcastPlaybackService::class.java),
        )
        val future = MediaController.Builder(context, token).buildAsync()
        val controller = future.get(10, TimeUnit.SECONDS)

        try {
            // buildAsync() completing successfully proves the controller connected to the target
            // MediaSessionService without needing to prepare media.
            assertNotNull(controller)
        } finally {
            // MediaController methods, including release(), must run on the controller's
            // application thread. The controller is built with the app main looper here.
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                controller.release()
            }
        }
    }
}
