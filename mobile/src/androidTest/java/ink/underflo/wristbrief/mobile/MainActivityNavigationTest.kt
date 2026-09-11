package ink.underflo.wristbrief.mobile

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityNavigationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun membershipDestinationSurvivesActivityRecreation() {
        composeRule.onNode(hasText("Membership", substring = false)).performClick()
        composeRule.onNode(hasText("Plans and prices below come from Google Play.", substring = true)).assertIsDisplayed()

        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()

        composeRule.onNode(hasText("Plans and prices below come from Google Play.", substring = true)).assertIsDisplayed()
    }
}
