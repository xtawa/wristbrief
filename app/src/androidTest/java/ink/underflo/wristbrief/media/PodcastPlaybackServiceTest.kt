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
    fun serviceRetainsEpisodeSeekAndSpeedAcrossControllerReconnect() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val serviceIntent = Intent(context, PodcastPlaybackService::class.java)
        context.startService(serviceIntent)

        var firstController: MediaController? = null
        var secondController: MediaController? = null
        try {
            firstController = connectController(context)
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                firstController.setMediaItem(
                    MediaItem.Builder()
                        .setMediaId("ci-episode")
                        .setUri("https://example.com/ci-episode.mp3")
                        .build()
                )
                firstController.seekTo(12_000L)
                firstController.playbackParameters = PlaybackParameters(1.5f)
            }
            releaseController(firstController)
            firstController = null

            secondController = connectController(context)
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                assertEquals("ci-episode", secondController.currentMediaItem?.mediaId)
                assertEquals(12_000L, secondController.currentPosition)
                assertEquals(1.5f, secondController.playbackParameters.speed, 0.001f)
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
