package ink.underflo.wristbrief.media

import android.content.ComponentName
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
            // buildAsync() completing successfully is the Media3 contract that the controller
            // connected to the target MediaSessionService. Keep this smoke test on APIs that are
            // available in the project's pinned Media3 version instead of asserting newer token
            // inspection helpers.
            assertNotNull(controller)
        } finally {
            controller.release()
        }
    }
}
