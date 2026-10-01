package ink.underflo.wristbrief.mobile

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.LocaleList
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.printToString
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import ink.underflo.wristbrief.mobile.db.SqliteMobileFeedStore
import ink.underflo.wristbrief.mobile.db.SqliteMobileInboxStore
import ink.underflo.wristbrief.mobile.db.WristBriefDatabaseHelper
import java.io.File
import java.util.Locale
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Opt-in native UI evidence. Fixtures are synthetic and never represent user content.
 * This exercises real app screens but does not validate live OAuth, Play or AI providers.
 */
class UiEvaluationTest {
    @get:Rule val composeRule = createEmptyComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val locale get() = Locale.forLanguageTag(arguments.getString("uxLocale", "en-US"))
    private val dark get() = arguments.getString("uxTheme", "light") == "dark"
    private val fontScale get() = arguments.getString("uxFontScale", "1.0").toFloat()
    private lateinit var localized: Context
    private var startedAt = 0L

    @Before fun prepareFixtures() {
        assumeTrue("Only run in the opt-in UX evidence workflow", arguments.getString("captureUx") == "true")
        context.getSharedPreferences("onboarding", Context.MODE_PRIVATE).edit().putBoolean("complete", true).commit()
        context.getSharedPreferences("wristbrief_mobile_item_state_sync", Context.MODE_PRIVATE).edit().clear().commit()
        AppPreferences(context).setThemeMode(if (dark) AppThemeMode.DARK else AppThemeMode.LIGHT)
        val feeds = listOf(
            MobileFeedSubscription("ux-tech", "Design & engineering / 设计与工程", "https://example.com/ux-feed", category = "Technology"),
            MobileFeedSubscription("ux-podcast", "Quiet conversations / 安静对话", "https://example.com/ux-podcast", category = "Podcasts"),
        )
        val items = (1..8).map { index ->
            val podcast = index % 3 == 0
            val feed = feeds[if (podcast) 1 else 0]
            MobileFeedItem(
                id = "ux-item-$index", feedId = feed.id, feedTitle = feed.title,
                title = if (podcast) "Test episode $index · 设计中的细节" else "Test article $index · 为更从容的阅读设计清晰的信息层级",
                link = null,
                description = "<p>Evaluation fixture only. This is a native screen, not a web mockup. 测试数据：检查长标题、行距、筛选及返回路径。</p>",
                published = "2026-09-${30 - index}T10:00:00Z",
                audioUrl = if (podcast) "https://example.com/test-episode.mp3" else null,
                cachedAtEpochMs = 1000L + index,
            )
        }
        WristBriefDatabaseHelper(context).use {
            SqliteMobileFeedStore(it).save(feeds)
            SqliteMobileInboxStore(it).save(items)
        }
        val config = Configuration(context.resources.configuration).apply {
            setLocales(LocaleList(this@UiEvaluationTest.locale))
            this.fontScale = this@UiEvaluationTest.fontScale
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }
        localized = context.createConfigurationContext(config)
    }

    private fun installLocalizedApp(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity { activity ->
            activity.setContent {
                CompositionLocalProvider(
                    LocalContext provides localized,
                    LocalConfiguration provides localized.resources.configuration,
                    LocalDensity provides Density(context.resources.displayMetrics.density, fontScale),
                ) { WristBriefMobileApp() }
            }
        }
        composeRule.waitForIdle()
    }

    private fun openTab(resource: Int) {
        val tab = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
        composeRule.onNode(hasText(localized.getString(resource)) and tab).performClick()
        composeRule.waitForIdle()
    }

    private fun capture(name: String) {
        composeRule.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        val directory = File(context.getExternalFilesDir(null), "ux-evaluation").apply { mkdirs() }
        val prefix = "${locale.toLanguageTag()}-${if (dark) "dark" else "light"}-${fontScale}-$name"
        val bitmap = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        assertTrue("Screenshot must contain actual device pixels", bitmap.width > 0 && bitmap.height > 0)
        File(directory, "$prefix.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        File(directory, "$prefix.semantics.txt").writeText(composeRule.onRoot(useUnmergedTree = true).printToString())
        File(directory, "$prefix.json").writeText(JSONObject().apply {
            put("screen", name); put("locale", locale.toLanguageTag()); put("dark", dark)
            put("fontScale", fontScale); put("widthPx", bitmap.width); put("heightPx", bitmap.height)
            put("screenWidthDp", context.resources.configuration.screenWidthDp)
            put("elapsedSinceLaunchMs", SystemClock.elapsedRealtime() - startedAt)
            put("fixture", true); put("liveServiceVerification", false)
        }.toString(2))
        bitmap.recycle()
    }

    @Test fun captureNativeProductTour() {
        startedAt = SystemClock.elapsedRealtime()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            installLocalizedApp(scenario)
            capture("01-home")
            openTab(R.string.nav_library)
            composeRule.onNodeWithTag(LibraryTestTags.SEARCH_FIELD).assertIsDisplayed()
            capture("02-library")
            composeRule.onNodeWithTag(LibraryTestTags.filterChip(LibraryFilter.Saved)).performScrollTo().performClick()
            composeRule.onNodeWithTag(LibraryTestTags.EMPTY_ACTION).performScrollTo().assertIsDisplayed()
            capture("03-saved-empty")
            composeRule.onNodeWithTag(LibraryTestTags.EMPTY_ACTION).performClick()
            composeRule.onNodeWithTag(LibraryTestTags.SEARCH_FIELD).performScrollTo().performTextInput("no-such-fixture")
            composeRule.onNodeWithTag(LibraryTestTags.SEARCH_FIELD).performImeAction()
            composeRule.onNodeWithTag(LibraryTestTags.EMPTY_ACTION).performScrollTo().assertIsDisplayed()
            capture("04-search-empty")
            composeRule.onNodeWithTag(LibraryTestTags.EMPTY_ACTION).performClick()
            composeRule.onNodeWithTag(LibraryTestTags.row("ux-item-1")).performScrollTo().performClick()
            capture("05-article")
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            composeRule.waitForIdle()
            openTab(R.string.nav_explore)
            capture("06-explore")
            openTab(R.string.nav_ai)
            capture("07-ask-ai")
            composeRule.onNodeWithContentDescription(localized.getString(R.string.nav_settings)).performClick()
            capture("08-sources")
            composeRule.onNode(hasText(localized.getString(R.string.settings_preferences_title))).performScrollTo().performClick()
            capture("09-preferences")
        }
    }

    @Test fun captureOnboarding() {
        context.getSharedPreferences("onboarding", Context.MODE_PRIVATE).edit().putBoolean("complete", false).commit()
        startedAt = SystemClock.elapsedRealtime()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            installLocalizedApp(scenario)
            capture("00-onboarding")
        }
    }
}
