package ink.underflo.wristbrief.mobile

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Deterministic Compose coverage: no network, account, billing or Wear dependencies. */
class LibraryDestinationTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val feed = MobileFeedSubscription(
        id = "library-test-feed",
        title = "Test source with a long name for narrow displays",
        url = "https://example.com/rss",
        category = "Technology",
    )
    private val article = MobileFeedItem(
        id = "library-test-article",
        feedId = feed.id,
        feedTitle = feed.title,
        title = "A searchable article",
        link = "https://example.com/article",
        description = "<p>Readable preview</p>",
        published = "2026-09-30T10:00:00Z",
        audioUrl = null,
        cachedAtEpochMs = 100L,
    )
    private val states = object : ItemStateReaderAndWriter {
        val read = mutableSetOf<String>()
        val saved = mutableSetOf<String>()
        override fun isRead(itemId: String) = itemId in read
        override fun isSaved(itemId: String) = itemId in saved
        override fun setRead(itemId: String, isRead: Boolean) {
            if (isRead) read.add(itemId) else read.remove(itemId)
        }
        override fun setSaved(itemId: String, isSaved: Boolean) {
            if (isSaved) saved.add(itemId) else saved.remove(itemId)
        }
    }
    private val manager = MobileFeedManager(
        store = object : MobileFeedStore {
            override fun load() = listOf(feed)
            override fun save(feeds: List<MobileFeedSubscription>) = Unit
        },
        probe = object : FeedProbe {
            override suspend fun validate(url: String): String? = null
        },
        publisher = object : FeedSyncPublisher {
            override fun publish(feeds: List<MobileFeedSubscription>) = Unit
        },
    )
    private val repository = MobileInboxRepository(
        feedManager = manager,
        store = object : MobileInboxStore {
            // Intentionally the same list contents after state writes: this was
            // the regression that prevented Compose from updating toggle state.
            override fun load() = listOf(article)
            override fun save(items: List<MobileFeedItem>) = Unit
        },
        stateAdapter = states,
        fetcher = object : FeedItemFetcher {
            override suspend fun fetch(url: String): List<ParsedFeedItem> = emptyList()
        },
    )

    private fun showLibrary(darkTheme: Boolean = false) {
        composeRule.setContent {
            WristBriefMobileTheme {
                LibraryDestination(
                    padding = PaddingValues(0.dp),
                    inboxRepository = repository,
                    feedManager = manager,
                    onManageSources = {},
                    darkTheme = darkTheme,
                )
            }
        }
    }

    @Test
    fun saveToggleUpdatesImmediatelyAndRemovingLastSavedItemShowsRecovery() {
        showLibrary()
        composeRule.onNodeWithTag(LibraryTestTags.saveToggle(article.id)).performScrollTo().performClick()
        composeRule.runOnIdle { assertTrue(states.isSaved(article.id)) }

        composeRule.onNodeWithTag(LibraryTestTags.filterChip(LibraryFilter.Saved)).performScrollTo().performClick()
        composeRule.onNodeWithTag(LibraryTestTags.filterChip(LibraryFilter.Saved)).assertIsSelected()
        composeRule.onNodeWithTag(LibraryTestTags.saveToggle(article.id)).performScrollTo().performClick()
        composeRule.runOnIdle { assertFalse(states.isSaved(article.id)) }
        composeRule.onNodeWithTag(LibraryTestTags.row(article.id)).assertDoesNotExist()
        composeRule.onNodeWithTag(LibraryTestTags.EMPTY_ACTION).performScrollTo().performClick()
        composeRule.onNodeWithTag(LibraryTestTags.row(article.id)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun markingLastUnreadItemReadRemovesItFromUnreadResultsInDarkTheme() {
        showLibrary(darkTheme = true)
        composeRule.onNodeWithTag(LibraryTestTags.filterChip(LibraryFilter.Unread)).performScrollTo().performClick()
        composeRule.onNodeWithTag(LibraryTestTags.readToggle(article.id)).performScrollTo().performClick()
        composeRule.runOnIdle { assertTrue(states.isRead(article.id)) }
        composeRule.onNodeWithTag(LibraryTestTags.row(article.id)).assertDoesNotExist()
        composeRule.onNodeWithTag(LibraryTestTags.EMPTY_ACTION).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun searchMatchesVisibleTextAndClearRestoresResults() {
        showLibrary()
        composeRule.onNodeWithTag(LibraryTestTags.SEARCH_FIELD).performTextInput("  searchable  ")
        composeRule.onNodeWithTag(LibraryTestTags.row(article.id)).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(LibraryTestTags.SEARCH_CLEAR).performScrollTo().performClick()
        composeRule.onNodeWithTag(LibraryTestTags.SEARCH_FIELD).performTextInput("no such result")
        composeRule.onNodeWithTag(LibraryTestTags.row(article.id)).assertDoesNotExist()
        composeRule.onNodeWithTag(LibraryTestTags.EMPTY_ACTION).performScrollTo().performClick()
        composeRule.onNodeWithTag(LibraryTestTags.row(article.id)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun queryAndFilterSurviveOpeningAndReturningFromArticle() {
        var articleOpen by mutableStateOf(false)
        composeRule.setContent {
            WristBriefMobileTheme {
                val holder = rememberSaveableStateHolder()
                if (articleOpen) {
                    Text("Article destination")
                } else {
                    holder.SaveableStateProvider("Library") {
                        LibraryDestination(
                            padding = PaddingValues(0.dp),
                            inboxRepository = repository,
                            feedManager = manager,
                            onManageSources = {},
                            onOpenArticle = { articleOpen = true },
                        )
                    }
                }
            }
        }
        composeRule.onNodeWithTag(LibraryTestTags.SEARCH_FIELD).performTextInput("searchable")
        composeRule.onNodeWithTag(LibraryTestTags.filterChip(LibraryFilter.Unread)).performScrollTo().performClick()
        composeRule.onNodeWithTag(LibraryTestTags.row(article.id)).performScrollTo().performClick()
        composeRule.runOnIdle { assertTrue(articleOpen) }
        composeRule.runOnIdle { articleOpen = false }
        composeRule.onNodeWithTag(LibraryTestTags.SEARCH_FIELD).performScrollTo().assertTextContains("searchable")
        composeRule.onNodeWithTag(LibraryTestTags.filterChip(LibraryFilter.Unread)).performScrollTo().assertIsSelected()
    }
}
