package ink.underflo.wristbrief.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileDestinationTest {
    @Test
    fun `phone shell starts on Today`() {
        assertEquals(MobileDestination.Today, initialMobileDestination())
    }

    @Test
    fun `all foundation destinations expose unique stable routes`() {
        val routes = MobileDestination.entries.map { it.route }

        assertEquals(routes.size, routes.toSet().size)
        assertTrue(routes.all { it.isNotBlank() })
        assertEquals(
            setOf("today", "explore", "library", "now-playing", "ai-provider"),
            routes.toSet(),
        )
    }

    @Test
    fun `bottom bar shows the four primary destinations per uidocs`() {
        assertEquals(
            listOf(MobileDestination.Today, MobileDestination.Explore, MobileDestination.AiProvider, MobileDestination.Library),
            MobileDestination.entries.filter { it.showsInBottomBar },
        )
        // Playback is not a tab: it lives in the mini/expanded player surfaces.
        assertEquals(false, MobileDestination.NowPlaying.showsInBottomBar)
    }
}
