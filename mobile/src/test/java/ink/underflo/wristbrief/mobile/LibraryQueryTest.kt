package ink.underflo.wristbrief.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryQueryTest {

    private val feeds = listOf(
        MobileFeedSubscription("f-tech", "Tech Daily", "https://tech.example/feed", category = "Tech"),
        MobileFeedSubscription("f-tech2", "Dev Weekly", "https://dev.example/feed", category = " tech "),
        MobileFeedSubscription("f-audio", "Audio Hour", "https://audio.example/feed", category = "Audio"),
        MobileFeedSubscription("f-empty", "Quiet Source", "https://quiet.example/feed", category = "Ghost"),
        MobileFeedSubscription("f-none", "No Category", "https://none.example/feed", category = null),
    )

    private fun item(
        id: String,
        feedId: String,
        title: String,
        published: String? = null,
        description: String? = null,
        audioUrl: String? = null,
        cachedAt: Long = 1_000L,
    ) = MobileFeedItem(
        id = id,
        feedId = feedId,
        feedTitle = feeds.first { it.id == feedId }.title,
        title = title,
        link = "https://example.com/$id",
        description = description,
        published = published,
        audioUrl = audioUrl,
        cachedAtEpochMs = cachedAt,
    )

    private val items = listOf(
        item("a", "f-tech", "Kotlin 2.1 released", published = "2026-09-30T10:00:00Z", description = "<p>Compiler <b>speedups</b> and K2 <img src=\"x.png\"> defaults.</p>"),
        item("b", "f-tech2", "Compose tips", published = "Mon, 28 Sep 2026 08:00:00 +0000", description = "<div class=\"lead\">Layout tricks for AI assistants</div>"),
        item("c", "f-audio", "Episode 12: 中文播客 roundup", published = "2026-09-29T12:00:00Z", audioUrl = "https://audio.example/12.mp3"),
        item("d", "f-none", "Undated note", published = null, cachedAt = 5_000L),
        item("e", "f-none", "Broken date", published = "not a date", cachedAt = 4_000L),
    )

    private fun entries(
        readIds: Set<String> = emptySet(),
        savedIds: Set<String> = emptySet(),
    ) = buildLibraryEntries(items, feeds, isRead = { it in readIds }, isSaved = { it in savedIds })

    @Test
    fun `newest first sorts by parsed publish date and keeps undated rows last`() {
        val snapshot = queryLibrary(entries(), LibraryQuery(sortOrder = LibrarySortOrder.NewestFirst), hasSources = true)
        assertEquals(listOf("a", "c", "b", "d", "e"), snapshot.entries.map { it.item.id })
    }

    @Test
    fun `oldest first reverses dated rows but still keeps undated rows last`() {
        val snapshot = queryLibrary(entries(), LibraryQuery(sortOrder = LibrarySortOrder.OldestFirst), hasSources = true)
        assertEquals(listOf("b", "c", "a", "d", "e"), snapshot.entries.map { it.item.id })
    }

    @Test
    fun `sort ignores cache order when publish dates are available`() {
        val sameCache = items.map { it.copy(cachedAtEpochMs = 1L) }
        val built = buildLibraryEntries(sameCache, feeds, { false }, { false })
        val newest = queryLibrary(built, LibraryQuery(), hasSources = true).entries.map { it.item.id }
        assertEquals(listOf("a", "c", "b", "d", "e"), newest)
    }

    @Test
    fun `search trims, ignores case and matches title source and sanitized description`() {
        val result = queryLibrary(entries(), LibraryQuery(searchQuery = "  KOTLIN "), hasSources = true)
        assertEquals(listOf("a"), result.entries.map { it.item.id })

        val bySource = queryLibrary(entries(), LibraryQuery(searchQuery = "audio hour"), hasSources = true)
        assertEquals(listOf("c"), bySource.entries.map { it.item.id })

        val byDescription = queryLibrary(entries(), LibraryQuery(searchQuery = "speedups"), hasSources = true)
        assertEquals(listOf("a"), byDescription.entries.map { it.item.id })
    }

    @Test
    fun `search does not match html markup in descriptions`() {
        for (markup in listOf("img", "src", "div", "class", "lead", "<p>")) {
            val result = queryLibrary(entries(), LibraryQuery(searchQuery = markup), hasSources = true)
            assertTrue("'$markup' should not match markup", result.entries.isEmpty())
        }
    }

    @Test
    fun `multi word search requires every token and supports CJK`() {
        val both = queryLibrary(entries(), LibraryQuery(searchQuery = "compose layout"), hasSources = true)
        assertEquals(listOf("b"), both.entries.map { it.item.id })

        val miss = queryLibrary(entries(), LibraryQuery(searchQuery = "compose kotlin"), hasSources = true)
        assertTrue(miss.entries.isEmpty())

        val cjk = queryLibrary(entries(), LibraryQuery(searchQuery = "中文"), hasSources = true)
        assertEquals(listOf("c"), cjk.entries.map { it.item.id })
    }

    @Test
    fun `filters combine with category and search as AND`() {
        val built = entries(readIds = setOf("a"), savedIds = setOf("b", "c"))

        val unread = queryLibrary(built, LibraryQuery(filter = LibraryFilter.Unread), hasSources = true)
        assertEquals(setOf("b", "c", "d", "e"), unread.entries.map { it.item.id }.toSet())
        assertEquals(4, unread.unreadCount)
        assertEquals(2, unread.savedCount)

        val savedTech = queryLibrary(built, LibraryQuery(filter = LibraryFilter.Saved, category = "tech"), hasSources = true)
        assertEquals(listOf("b"), savedTech.entries.map { it.item.id })
        assertEquals("Tech", savedTech.effectiveCategory)

        val podcasts = queryLibrary(built, LibraryQuery(filter = LibraryFilter.Podcasts), hasSources = true)
        assertEquals(listOf("c"), podcasts.entries.map { it.item.id })

        val articlesSearch = queryLibrary(built, LibraryQuery(filter = LibraryFilter.Articles, searchQuery = "roundup"), hasSources = true)
        assertTrue(articlesSearch.entries.isEmpty())
    }

    @Test
    fun `categories are normalized, deduplicated and limited to feeds with cached items`() {
        val snapshot = queryLibrary(entries(), LibraryQuery(), hasSources = true)
        assertEquals(listOf("Audio", "Tech"), snapshot.categories.map { it.name })
        assertEquals(listOf(1, 2), snapshot.categories.map { it.itemCount })
    }

    @Test
    fun `selected category that disappeared falls back to All`() {
        val categories = libraryCategories(entries())
        assertNull(resolveLibraryCategory("Ghost", categories))
        assertEquals("Tech", resolveLibraryCategory("TECH", categories))
        assertNull(resolveLibraryCategory("   ", categories))

        val snapshot = queryLibrary(entries(), LibraryQuery(category = "Ghost"), hasSources = true)
        assertNull(snapshot.effectiveCategory)
        assertEquals(5, snapshot.entries.size)
        assertNull(snapshot.emptyState)
    }

    @Test
    fun `empty state distinguishes no sources from nothing cached`() {
        assertEquals(
            LibraryEmptyState.NoSources,
            queryLibrary(emptyList(), LibraryQuery(searchQuery = "anything"), hasSources = false).emptyState,
        )
        assertEquals(
            LibraryEmptyState.NoCachedItems,
            queryLibrary(emptyList(), LibraryQuery(), hasSources = true).emptyState,
        )
        assertEquals(
            LibraryEmptyState.NoActiveSources,
            queryLibrary(emptyList(), LibraryQuery(), hasSources = true, hasActiveSources = false).emptyState,
        )
        // Cached items from paused feeds still show; the paused state only applies to an empty library.
        assertNull(queryLibrary(entries(), LibraryQuery(), hasSources = true, hasActiveSources = false).emptyState)
    }

    @Test
    fun `category test tags are stable for names with spaces and mixed case`() {
        assertEquals("library_category_name:tech news", LibraryTestTags.categoryChip("Tech News"))
        assertEquals("library_category_all", LibraryTestTags.categoryChip(null))
        // A real category named "All" must never collide with the All chip.
        assertEquals("library_category_name:all", LibraryTestTags.categoryChip("All"))
        assertEquals("library_filter_unread", LibraryTestTags.filterChip(LibraryFilter.Unread))
    }

    @Test
    fun `entries are built from injected state lookups only`() {
        var reads = 0
        var saves = 0
        val built = buildLibraryEntries(items, feeds, isRead = { reads++; false }, isSaved = { saves++; false })
        assertEquals(items.size, reads)
        assertEquals(items.size, saves)
        val states = mapOf("a" to MobileLibraryItemState(isRead = true), "b" to MobileLibraryItemState(isSaved = true))
        val applied = built.withItemState(
            isRead = { states[it]?.isRead == true },
            isSaved = { states[it]?.isSaved == true },
        )
        assertTrue(applied.first { it.item.id == "a" }.isRead)
        assertTrue(applied.first { it.item.id == "b" }.isSaved)
        assertFalse(applied.first { it.item.id == "d" }.isRead)
    }

    @Test
    fun `search empty state reports matches hidden by filters`() {
        val built = entries(readIds = setOf("a"))
        val hidden = queryLibrary(built, LibraryQuery(searchQuery = "kotlin", filter = LibraryFilter.Unread), hasSources = true)
        assertEquals(LibraryEmptyState.SearchNoMatch(query = "kotlin", matchesWithoutFilters = 1), hidden.emptyState)

        val none = queryLibrary(built, LibraryQuery(searchQuery = " zzz "), hasSources = true)
        assertEquals(LibraryEmptyState.SearchNoMatch(query = "zzz", matchesWithoutFilters = 0), none.emptyState)
    }

    @Test
    fun `filter and category empty states are reported separately`() {
        val allRead = entries(readIds = items.map { it.id }.toSet())
        assertEquals(
            LibraryEmptyState.FilterExcludesAll(LibraryFilter.Unread, null),
            queryLibrary(allRead, LibraryQuery(filter = LibraryFilter.Unread), hasSources = true).emptyState,
        )
        assertEquals(
            LibraryEmptyState.FilterExcludesAll(LibraryFilter.Podcasts, "Tech"),
            queryLibrary(entries(), LibraryQuery(filter = LibraryFilter.Podcasts, category = "Tech"), hasSources = true).emptyState,
        )
        assertEquals(
            LibraryEmptyState.FilterExcludesAll(LibraryFilter.Saved, null),
            queryLibrary(entries(), LibraryQuery(filter = LibraryFilter.Saved), hasSources = true).emptyState,
        )
    }

    @Test
    fun `withItemState refreshes read and saved flags without rebuilding text`() {
        val before = entries()
        val after = before.withItemState(isRead = { it == "a" }, isSaved = { it == "b" })
        assertTrue(after.first { it.item.id == "a" }.isRead)
        assertTrue(after.first { it.item.id == "b" }.isSaved)
        assertFalse(after.first { it.item.id == "c" }.isRead)
        assertEquals(before.map { it.searchText }, after.map { it.searchText })
        assertTrue(after.first { it.item.id == "c" } === before.first { it.item.id == "c" })
    }

    @Test
    fun `preview text is sanitized and search tokens normalize`() {
        val built = entries()
        assertEquals("Compiler speedups and K2 defaults.", built.first { it.item.id == "a" }.previewText)
        assertEquals(listOf("hello", "world"), normalizeLibrarySearchTokens("  Hello   WORLD hello "))
        assertTrue(normalizeLibrarySearchTokens("   ").isEmpty())
        assertFalse(LibraryQuery(searchQuery = "   ").hasSearch)
        assertTrue(LibraryQuery(category = "Tech").hasFilters)
    }

    @Test
    fun `snapshot counts reflect the whole library not the filtered rows`() {
        val built = entries(readIds = setOf("a", "b"), savedIds = setOf("c"))
        val snapshot = queryLibrary(built, LibraryQuery(filter = LibraryFilter.Saved), hasSources = true)
        assertEquals(1, snapshot.entries.size)
        assertEquals(5, snapshot.totalCount)
        assertEquals(3, snapshot.unreadCount)
        assertEquals(1, snapshot.savedCount)
        assertTrue(snapshot.isNarrowed)
    }
}
