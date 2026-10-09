package ink.underflo.wristbrief.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingHandoffTest {
    @Test
    fun `skip and start reading land on Today without opening settings`() {
        val handoff = resolveOnboardingHandoff(OnboardingAction.StartReading)
        assertEquals(MobileDestination.Today, handoff.destination)
        assertNull(handoff.sourcesAction)
        assertFalse(handoff.opensSettings)
    }

    @Test
    fun `explore action selects the Explore tab instead of being ignored`() {
        val handoff = resolveOnboardingHandoff(OnboardingAction.Explore)
        assertEquals(MobileDestination.Explore, handoff.destination)
        assertFalse(handoff.opensSettings)
    }

    @Test
    fun `add feed opens the Sources editor on top of Today`() {
        val handoff = resolveOnboardingHandoff(OnboardingAction.AddFeed)
        assertEquals(MobileDestination.Today, handoff.destination)
        assertEquals(OnboardingAction.AddFeed, handoff.sourcesAction)
        assertTrue(handoff.opensSettings)
    }

    @Test
    fun `import opml opens the OPML picker on top of Today`() {
        val handoff = resolveOnboardingHandoff(OnboardingAction.ImportOpml)
        assertEquals(MobileDestination.Today, handoff.destination)
        assertEquals(OnboardingAction.ImportOpml, handoff.sourcesAction)
        assertTrue(handoff.opensSettings)
    }

    @Test
    fun `every action resolves to a bottom bar destination`() {
        OnboardingAction.entries.forEach { action ->
            assertTrue(action.name, resolveOnboardingHandoff(action).destination.showsInBottomBar)
        }
    }
}
