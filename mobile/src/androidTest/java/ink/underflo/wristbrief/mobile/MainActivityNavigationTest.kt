package ink.underflo.wristbrief.mobile

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityNavigationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun resetOnboarding() {
        composeRule.activity.getSharedPreferences("onboarding", 0).edit().clear().commit()
        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()
    }

    @Test
    fun membershipDestinationSurvivesActivityRecreation() {
        composeRule.onNode(hasText("Skip", substring = false)).performClick()
        composeRule.onNode(hasText("Account", substring = false)).performClick()
        composeRule.onNode(hasText("Plans and prices below come from Google Play.", substring = true)).assertIsDisplayed()

        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()

        composeRule.onNode(hasText("Plans and prices below come from Google Play.", substring = true)).assertIsDisplayed()
    }

    @Test
    fun onboardingAddSourceActionOpensRealFeedEditor() {
        repeat(3) {
            composeRule.onNode(hasText("Continue", substring = false)).performClick()
        }
        composeRule.onNode(hasText("Add your first source", substring = false)).performClick()

        composeRule.onNode(hasText("HTTPS feed URL", substring = false)).assertIsDisplayed()
        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()
        composeRule.onNode(hasText("HTTPS feed URL", substring = false)).assertIsDisplayed()
    }
}
