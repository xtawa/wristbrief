package ink.underflo.wristbrief.media

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PodcastPlaybackServiceTest {
    @Test
    fun mediaControllerConnectsToPlaybackServiceWithoutPreparingMedia() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val controller = connectController(context)

        try {
            // buildAsync() completing successfully proves the controller connected to the target
            // MediaSessionService without needing to prepare media.
            assertNotNull(controller)
        } finally {
            releaseController(controller)
        }
    }

    @Test
    fun serviceRetainsEpisodePositionAndSpeedAcrossControllerReconnect() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val serviceIntent = Intent(context, PodcastPlaybackService::class.java)
        context.startService(serviceIntent)

        var firstController: MediaController? = null
        var secondController: MediaController? = null
        try {
            val configuredController = connectController(context)
            firstController = configuredController
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                configuredController.setMediaItem(
                    MediaItem.Builder()
                        .setMediaId("ci-episode")
                        .setUri("https://example.com/ci-episode.mp3")
                        .build(),
                    12_000L,
                )
                configuredController.playbackParameters = PlaybackParameters(1.5f)
                assertEquals("ci-episode", configuredController.currentMediaItem?.mediaId)
                assertEquals(12_000L, configuredController.currentPosition)
                assertEquals(1.5f, configuredController.playbackParameters.speed, 0.001f)
            }
            releaseController(configuredController)
            firstController = null

            val reconnectedController = connectController(context)
            secondController = reconnectedController
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                assertEquals("ci-episode", reconnectedController.currentMediaItem?.mediaId)
                assertEquals(12_000L, reconnectedController.currentPosition)
                assertEquals(1.5f, reconnectedController.playbackParameters.speed, 0.001f)
            }
        } finally {
            firstController?.let(::releaseController)
            secondController?.let(::releaseController)
            context.stopService(serviceIntent)
        }
    }

    private fun connectController(context: Context): MediaController {
        val token = SessionToken(
            context,
            ComponentName(context, PodcastPlaybackService::class.java),
        )
        return MediaController.Builder(context, token)
            .buildAsync()
            .get(10, TimeUnit.SECONDS)
    }

    private fun releaseController(controller: MediaController) {
        // MediaController methods, including release(), must run on the controller's
        // application thread. The controller is built with the app main looper here.
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            controller.release()
        }
    }
}
