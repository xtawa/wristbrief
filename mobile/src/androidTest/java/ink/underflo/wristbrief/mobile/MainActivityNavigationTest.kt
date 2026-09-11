package ink.underflo.wristbrief.mobile

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityNavigationTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Before
    fun resetState() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("onboarding", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("wristbrief_mobile_feeds", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("wristbrief_mobile_inbox", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun onboardingSkipNavigatesToTodayAndSurvivesRecreation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            composeRule.onNode(hasText("Skip", substring = false)).performClick()
            composeRule.onNode(hasText("Welcome to WristBrief", substring = true)).assertIsDisplayed()

            scenario.recreate()
            composeRule.waitForIdle()

            composeRule.onNode(hasText("Welcome to WristBrief", substring = true)).assertIsDisplayed()
        }
    }

    @Test
    fun onboardingAddSourceActionOpensRealFeedEditor() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            repeat(3) {
                composeRule.onNode(hasText("Continue", substring = false)).performClick()
            }
            composeRule.onNode(hasText("Add your first source", substring = false)).performClick()

            composeRule.onNode(hasText("HTTPS feed URL", substring = false)).assertIsDisplayed()
            scenario.recreate()
            composeRule.waitForIdle()
            composeRule.onNode(hasText("HTTPS feed URL", substring = false)).assertIsDisplayed()
        }
    }

    @Test
    fun feedEditorValidatesHttpsUrlAndBlocksInvalid() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            repeat(3) {
                composeRule.onNode(hasText("Continue", substring = false)).performClick()
            }
            composeRule.onNode(hasText("Add your first source", substring = false)).performClick()
            composeRule.onNode(hasText("HTTPS feed URL", substring = false)).assertIsDisplayed()

            composeRule.onNode(hasText("Save", substring = false)).assertIsNotEnabled()

            composeRule.onNode(hasText("HTTPS feed URL", substring = false)).performTextInput("http://insecure.com/feed")
            composeRule.waitForIdle()

            composeRule.onNode(hasText("URL must start with https://", substring = false)).assertIsDisplayed()
            composeRule.onNode(hasText("Save", substring = false)).assertIsNotEnabled()
        }
    }

    @Test
    fun settingsCanReplayOnboarding() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            composeRule.onNode(hasText("Skip", substring = false)).performClick()
            composeRule.onNode(hasText("Welcome to WristBrief", substring = true)).assertIsDisplayed()

            composeRule.onNode(hasText("Add your first source", substring = false)).performClick()
            composeRule.onNode(hasText("Sources", substring = true)).assertIsDisplayed()

            composeRule.onNode(hasText("About", substring = true)).performClick()
            composeRule.onNode(hasText("Replay onboarding guide", substring = true)).performClick()

            composeRule.onNode(hasText("Skip", substring = false)).assertIsDisplayed()
        }
    }

    @Test
    fun membershipDestinationInSettingsSurvivesActivityRecreation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            composeRule.onNode(hasText("Skip", substring = false)).performClick()
            composeRule.onNode(hasText("Add your first source", substring = false)).performClick()
            composeRule.onNode(hasText("Account & Membership", substring = true)).performClick()
            composeRule.onNode(hasText("Plans and prices below come from Google Play.", substring = true)).assertIsDisplayed()

            scenario.recreate()
            composeRule.waitForIdle()

            composeRule.onNode(hasText("Plans and prices below come from Google Play.", substring = true)).assertIsDisplayed()
        }
    }

    @Test
    fun settingsReplayOnboardingHasUniqueInteractiveButton() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            composeRule.onNode(hasText("Skip", substring = false)).performClick()
            composeRule.onNode(hasText("Add your first source", substring = false)).performClick()
            composeRule.onNode(hasText("About", substring = true)).performClick()

            composeRule.onNode(hasText("Onboarding guide", substring = true)).assertIsDisplayed()
            composeRule.onNodeWithTag("settings_replay_onboarding_button").assertIsDisplayed()
            composeRule.onNodeWithTag("settings_replay_onboarding_button").performClick()

            composeRule.onNode(hasText("Skip", substring = false)).assertIsDisplayed()
        }
    }

    @Test
    fun libraryDestinationSearchAndClearWorks() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            composeRule.onNode(hasText("Skip", substring = false)).performClick()
            composeRule.onNode(hasText("Library", substring = false)).performClick()
            composeRule.waitForIdle()

            composeRule.onNode(hasText("Search articles and sources…", substring = true)).assertIsDisplayed()

            composeRule.onNode(hasText("Search articles and sources…", substring = true)).performTextInput("nonexistent_test_term")
            composeRule.waitForIdle()

            composeRule.onNode(hasText("No articles match", substring = true)).assertIsDisplayed()
            composeRule.onNode(hasText("Clear search", substring = true)).assertIsDisplayed()

            composeRule.onNode(hasText("Clear search", substring = true)).performClick()
            composeRule.waitForIdle()

            composeRule.onNode(hasText("No items match your search or filter.", substring = true)).assertIsDisplayed()
        }
    }
}
