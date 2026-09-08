package ink.underflo.wristbrief.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileDestinationTest {
    @Test
    fun `phone shell starts on feed management`() {
        assertEquals(MobileDestination.Feeds, initialMobileDestination())
    }

    @Test
    fun `all foundation destinations expose unique stable routes`() {
        val routes = MobileDestination.entries.map { it.route }

        assertEquals(routes.size, routes.toSet().size)
        assertTrue(routes.all { it.isNotBlank() })
        assertEquals(
            setOf("feeds", "ai-provider", "membership"),
            routes.toSet(),
        )
    }
}
