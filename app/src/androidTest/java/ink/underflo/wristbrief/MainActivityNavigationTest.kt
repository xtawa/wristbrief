package ink.underflo.wristbrief

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityNavigationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun savedAndFeedsRemainReachableAcrossWearReleaseProfiles() {
        composeRule.onNodeWithText("WristBrief").assertIsDisplayed()

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

        composeRule.onNodeWithText("WristBrief").assertIsDisplayed()
    }
}
