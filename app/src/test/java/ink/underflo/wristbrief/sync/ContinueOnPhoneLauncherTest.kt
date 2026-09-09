package ink.underflo.wristbrief.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContinueOnPhoneLauncherTest {
    @Test
    fun `accepts normal http and https article urls`() {
        assertEquals("https://example.com/post", normalizedContinueUrl(" https://example.com/post "))
        assertEquals("http://example.com/episode", normalizedContinueUrl("http://example.com/episode"))
    }

    @Test
    fun `rejects non browser schemes and malformed urls`() {
        assertNull(normalizedContinueUrl("javascript:alert(1)"))
        assertNull(normalizedContinueUrl("file:///tmp/story"))
        assertNull(normalizedContinueUrl("not a url"))
        assertNull(normalizedContinueUrl("https:///missing-host"))
    }

    @Test
    fun `normalizes dot segments without inventing redirects`() {
        assertEquals("https://example.com/a/c", normalizedContinueUrl("https://example.com/a/b/../c"))
    }
}
