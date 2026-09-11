package ink.underflo.wristbrief

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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

        composeRule.onNode(hasText("Saved ·", substring = true))
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithText("Back to Inbox")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()

        composeRule.onNodeWithText("Feeds")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithText("Back to Inbox")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()

        composeRule.onNodeWithText("WristBrief").assertIsDisplayed()
    }
}
