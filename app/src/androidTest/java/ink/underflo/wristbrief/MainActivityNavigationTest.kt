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

    @Test
    fun savedAndFeedsRemainReachableAcrossWearReleaseProfiles() {
        val inboxTitleMatcher = hasText("WristBrief")
        composeRule.onNode(inboxTitleMatcher).assertIsDisplayed()

        val savedMatcher = hasText("Saved ·", substring = true)
        composeRule.onNode(hasScrollAction()).performScrollToNode(savedMatcher)
        composeRule.onNode(savedMatcher).performClick()

        val backMatcher = hasText("Back to Inbox")
        composeRule.onNode(hasScrollAction()).performScrollToNode(backMatcher)
        composeRule.onNode(backMatcher).assertIsDisplayed().performClick()

        val feedsMatcher = hasText("Feeds")
        composeRule.onNode(hasScrollAction()).performScrollToNode(feedsMatcher)
        composeRule.onNode(feedsMatcher).performClick()

        composeRule.onNode(hasScrollAction()).performScrollToNode(backMatcher)
        composeRule.onNode(backMatcher).assertIsDisplayed().performClick()

        composeRule.onNode(hasScrollAction()).performScrollToNode(inboxTitleMatcher)
        composeRule.onNode(inboxTitleMatcher).assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun inboxRespondsToRotaryInputAcrossWearReleaseProfiles() {
        val scrollable = composeRule.onNode(hasScrollAction())
        scrollable.performScrollToNode(hasText("WristBrief"))
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
    fun feedsDestinationSurvivesActivityRecreationAcrossWearReleaseProfiles() {
        val feedsMatcher = hasText("Feeds")
        composeRule.onNode(hasScrollAction()).performScrollToNode(feedsMatcher)
        composeRule.onNode(feedsMatcher).performClick()

        val backMatcher = hasText("Back to Inbox")
        composeRule.onNode(hasScrollAction()).performScrollToNode(backMatcher)
        composeRule.onNode(backMatcher).assertIsDisplayed()

        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()

        composeRule.onNode(hasScrollAction()).performScrollToNode(backMatcher)
        composeRule.onNode(backMatcher).assertIsDisplayed().performClick()

        val inboxTitleMatcher = hasText("WristBrief")
        composeRule.onNode(hasScrollAction()).performScrollToNode(inboxTitleMatcher)
        composeRule.onNode(inboxTitleMatcher).assertIsDisplayed()
    }

    @Test
    fun savedDestinationSurvivesActivityRecreationAcrossWearReleaseProfiles() {
        val savedMatcher = hasText("Saved ·", substring = true)
        composeRule.onNode(hasScrollAction()).performScrollToNode(savedMatcher)
        composeRule.onNode(savedMatcher).performClick()

        val backMatcher = hasText("Back to Inbox")
        composeRule.onNode(hasScrollAction()).performScrollToNode(backMatcher)
        composeRule.onNode(backMatcher).assertIsDisplayed()

        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()

        composeRule.onNode(hasScrollAction()).performScrollToNode(backMatcher)
        composeRule.onNode(backMatcher).assertIsDisplayed().performClick()

        val inboxTitleMatcher = hasText("WristBrief")
        composeRule.onNode(hasScrollAction()).performScrollToNode(inboxTitleMatcher)
        composeRule.onNode(inboxTitleMatcher).assertIsDisplayed()
    }
}
