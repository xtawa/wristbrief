package ink.underflo.wristbrief.mobile

import android.content.Context
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.AnnotatedString
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
        context.getSharedPreferences("wristbrief_mobile_item_state_sync", Context.MODE_PRIVATE).edit().clear().commit()
        ink.underflo.wristbrief.mobile.db.WristBriefDatabaseHelper(context).use { helper ->
            ink.underflo.wristbrief.mobile.db.SqliteMobileFeedStore(helper).save(emptyList())
            ink.underflo.wristbrief.mobile.db.SqliteMobileInboxStore(helper).save(emptyList())
        }
    }

    @Test
    fun onboardingSkipNavigatesToExploreAndSurvivesRecreation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            composeRule.onNode(hasText("Skip", substring = false)).performClick()
            composeRule.onNode(hasText("Discover high-signal sources", substring = true)).assertIsDisplayed()

            scenario.recreate()
            composeRule.waitForIdle()

            composeRule.onNode(hasText("Discover high-signal sources", substring = true)).assertIsDisplayed()
        }
    }

    @Test
    fun onboardingAddSourceActionOpensRealFeedEditor() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            repeat(4) {
                composeRule.onNode(hasText("Continue", substring = false)).performClick()
            }
            composeRule.onNode(hasText("Add your first source", substring = false)).performScrollTo().performClick()

            composeRule.onNode(hasText("HTTPS feed URL", substring = false)).assertIsDisplayed()
            scenario.recreate()
            composeRule.waitForIdle()
            composeRule.onNode(hasText("HTTPS feed URL", substring = false)).assertIsDisplayed()
        }
    }

    @Test
    fun feedEditorValidatesHttpsUrlAndBlocksInvalid() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            repeat(4) {
                composeRule.onNode(hasText("Continue", substring = false)).performClick()
            }
            composeRule.onNode(hasText("Add your first source", substring = false)).performScrollTo().performClick()
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
            composeRule.onNodeWithContentDescription("Settings").performClick()
            composeRule.onNode(hasText("Sources") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).assertIsSelected()

            composeRule.onNode(hasText("About")).performScrollTo().performClick()
            composeRule.onNodeWithTag("settings_replay_onboarding_button").performScrollTo().performClick()

            composeRule.onNode(hasText("Skip", substring = false)).assertIsDisplayed()
        }
    }

    @Test
    fun membershipDestinationInSettingsSurvivesActivityRecreation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            composeRule.onNode(hasText("Skip", substring = false)).performClick()
            composeRule.onNodeWithContentDescription("Settings").performClick()
            composeRule.onNode(hasText("Account & Membership")).performScrollTo().performClick()
            val pricingDisclaimer = hasText("Plans and prices below come from Google Play.", substring = true)
            composeRule.onNode(hasScrollToIndexAction()).performScrollToNode(pricingDisclaimer)
            composeRule.onNode(pricingDisclaimer).assertIsDisplayed()

            scenario.recreate()
            composeRule.waitForIdle()

            composeRule.onNode(hasScrollToIndexAction()).performScrollToNode(pricingDisclaimer)
            composeRule.onNode(pricingDisclaimer).assertIsDisplayed()
        }
    }

    @Test
    fun settingsReplayOnboardingHasUniqueInteractiveButton() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            composeRule.onNode(hasText("Skip", substring = false)).performClick()
            composeRule.onNodeWithContentDescription("Settings").performClick()
            composeRule.onNode(hasText("About")).performScrollTo().performClick()

            composeRule.onNodeWithTag("settings_replay_onboarding_button").performScrollTo().assertIsDisplayed()
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

            composeRule.onNodeWithTag(LibraryTestTags.SEARCH_FIELD).performTextInput("nonexistent_test_term")
            composeRule.waitForIdle()

            composeRule.onNodeWithTag(LibraryTestTags.SEARCH_CLEAR).performClick()
            composeRule.waitForIdle()

            composeRule.onNodeWithTag(LibraryTestTags.SEARCH_FIELD).assert(
                SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")),
            )
            composeRule.onNodeWithTag(LibraryTestTags.SEARCH_CLEAR).assertDoesNotExist()
            composeRule.onNodeWithTag(LibraryTestTags.EMPTY_ACTION).performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun librarySearchSurvivesSettingsAndActivityRecreation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            composeRule.onNode(hasText("Skip", substring = false)).performClick()
            composeRule.onNode(hasText("Library", substring = false)).performClick()
            composeRule.onNodeWithTag(LibraryTestTags.SEARCH_FIELD).performTextInput("retained query")

            composeRule.onNodeWithContentDescription("Settings").performClick()
            composeRule.waitForIdle()
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag(LibraryTestTags.SEARCH_FIELD).assertTextContains("retained query")

            scenario.recreate()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag(LibraryTestTags.SEARCH_FIELD).assertTextContains("retained query")
        }
    }

    @Test
    fun libraryQueryAndFilterSurviveRealArticleAndTabNavigation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val feed = MobileFeedSubscription("navigation-feed", "Navigation feed", "https://example.com/rss")
        val article = MobileFeedItem(
            id = "navigation-article",
            feedId = feed.id,
            feedTitle = feed.title,
            title = "Navigation regression article",
            link = null,
            description = "Offline content for a deterministic navigation check.",
            published = "2026-09-30T10:00:00Z",
            audioUrl = null,
            cachedAtEpochMs = 1L,
        )
        ink.underflo.wristbrief.mobile.db.WristBriefDatabaseHelper(context).use { helper ->
            ink.underflo.wristbrief.mobile.db.SqliteMobileFeedStore(helper).save(listOf(feed))
            ink.underflo.wristbrief.mobile.db.SqliteMobileInboxStore(helper).save(listOf(article))
        }
        val tabRole = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            composeRule.onNode(hasText("Skip")).performClick()
            composeRule.onNode(hasText("Library") and tabRole).performClick()
            composeRule.onNodeWithTag(LibraryTestTags.SEARCH_FIELD).performTextInput("Navigation")
            composeRule.onNodeWithTag(LibraryTestTags.filterChip(LibraryFilter.Articles)).performScrollTo().performClick()
            composeRule.onNodeWithTag(LibraryTestTags.row(article.id)).performScrollTo().performClick()
            composeRule.waitForIdle()
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag(LibraryTestTags.SEARCH_FIELD).performScrollTo().assertTextContains("Navigation")
            composeRule.onNodeWithTag(LibraryTestTags.filterChip(LibraryFilter.Articles)).performScrollTo().assertIsSelected()

            composeRule.onNode(hasText(context.getString(R.string.nav_today)) and tabRole).performClick()
            composeRule.waitForIdle()
            composeRule.onNode(hasText("Library") and tabRole).performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag(LibraryTestTags.SEARCH_FIELD).performScrollTo().assertTextContains("Navigation")
            composeRule.onNodeWithTag(LibraryTestTags.filterChip(LibraryFilter.Articles)).performScrollTo().assertIsSelected()
        }
    }
}
