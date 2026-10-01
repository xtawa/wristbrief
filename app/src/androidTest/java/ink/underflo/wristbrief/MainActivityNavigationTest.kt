package ink.underflo.wristbrief

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performRotaryScrollInput
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityNavigationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private fun string(id: Int): String = composeRule.activity.getString(id)

    private fun scrollToAndClick(text: String) {
        val matcher = hasText(text)
        composeRule.onNode(hasScrollAction()).performScrollToNode(matcher)
        composeRule.onNode(matcher).assertIsDisplayed().performClick()
        composeRule.waitForIdle()
    }

    private fun assertOnScreen(text: String) {
        val matcher = hasText(text)
        composeRule.onNode(hasScrollAction()).performScrollToNode(matcher)
        composeRule.onNode(matcher).assertIsDisplayed()
    }

    private fun returnToInbox() {
        scrollToAndClick(string(R.string.wear_back_to_inbox))
        assertOnScreen(string(R.string.app_name))
    }

    /**
     * Inbox "More" hub: Library opens the Saved screen and Settings opens Feed management. Each
     * destination returns via "Back to Inbox". Selectors come from resources so they follow
     * the device locale and the current labels.
     */
    @Test
    fun libraryAndSettingsRemainReachableAcrossWearReleaseProfiles() {
        assertOnScreen(string(R.string.app_name))

        scrollToAndClick(string(R.string.wear_library_title))
        assertOnScreen(string(R.string.wear_saved_title))
        returnToInbox()

        scrollToAndClick(string(R.string.wear_settings_title))
        assertOnScreen(string(R.string.wear_feeds_title))
        returnToInbox()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun inboxRespondsToRotaryInputAcrossWearReleaseProfiles() {
        val scrollable = composeRule.onNode(hasScrollAction())
        scrollable.performScrollToNode(hasText(string(R.string.app_name)))
        composeRule.waitForIdle()

        val before = scrollable.fetchSemanticsNode().config[
            SemanticsProperties.VerticalScrollAxisRange,
        ].value()

        scrollable.performRotaryScrollInput {
            rotateToScrollVertically(600f)
        }
        composeRule.waitForIdle()

        val after = scrollable.fetchSemanticsNode().config[
            SemanticsProperties.VerticalScrollAxisRange,
        ].value()

        assertTrue(
            "Expected rotary input to move the Inbox scroll position (before=$before, after=$after)",
            after > before,
        )
    }

    @Test
    fun settingsDestinationSurvivesActivityRecreationAcrossWearReleaseProfiles() {
        scrollToAndClick(string(R.string.wear_settings_title))
        assertOnScreen(string(R.string.wear_feeds_title))

        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()

        assertOnScreen(string(R.string.wear_feeds_title))
        returnToInbox()
    }

    @Test
    fun libraryDestinationSurvivesActivityRecreationAcrossWearReleaseProfiles() {
        scrollToAndClick(string(R.string.wear_library_title))
        assertOnScreen(string(R.string.wear_saved_title))

        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()

        assertOnScreen(string(R.string.wear_saved_title))
        returnToInbox()
    }
}
