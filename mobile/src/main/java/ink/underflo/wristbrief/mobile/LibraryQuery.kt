package ink.underflo.wristbrief.mobile

import java.util.Locale

/**
 * Pure, UI-free query model for the Library destination.
 *
 * The composable only owns transient input (query text, selected chips); every derived value
 * (counts, available categories, filtered/sorted rows, empty-state reason) comes from here so it
 * can be unit tested without Compose.
 */
enum class LibraryFilter { All, Unread, Saved, Articles, Podcasts }

enum class LibrarySortOrder { NewestFirst, OldestFirst }

/** Semantic test tags shared with instrumentation tests. */
object LibraryTestTags {
    const val SCREEN = "library_screen"
    const val HEADING = "library_heading"
    const val SUMMARY = "library_summary"
    const val SEARCH_FIELD = "library_search_field"
    const val SEARCH_CLEAR = "library_search_clear"
    const val SORT_TOGGLE = "library_sort_toggle"
    const val RESULTS_SUMMARY = "library_results_summary"
    const val RESET_FILTERS = "library_reset_filters"
    const val EMPTY_STATE = "library_empty_state"
    const val EMPTY_ACTION = "library_empty_action"
    const val CATEGORY_ROW = "library_category_row"
    fun filterChip(filter: LibraryFilter): String = "library_filter_${filter.name.lowercase(Locale.ROOT)}"
    fun categoryChip(category: String?): String =
        if (category == null) "library_category_all" else "library_category_name:${category.lowercase(Locale.ROOT)}"
    fun row(itemId: String): String = "library_row_$itemId"
    fun saveToggle(itemId: String): String = "library_save_$itemId"
    fun readToggle(itemId: String): String = "library_read_$itemId"
}

data class LibraryEntry(
    val item: MobileFeedItem,
    val isRead: Boolean,
    val isSaved: Boolean,
    /** Normalized feed category, or null when the feed is uncategorized or unknown. */
    val category: String?,
    /** Lower-cased, markup-free text used for search matching. */
    val searchText: String,
    /** Parsed publish instant in epoch millis; null when the feed gave no parseable date. */
    val publishedEpochMs: Long?,
    /** Plain-text preview derived from the description, blank when there is none. */
    val previewText: String,
) {
    val isPodcast: Boolean get() = item.audioUrl != null
}

data class LibraryCategoryOption(
    val name: String,
    val itemCount: Int,
)

data class LibraryQuery(
    val searchQuery: String = "",
    val filter: LibraryFilter = LibraryFilter.All,
    val category: String? = null,
    val sortOrder: LibrarySortOrder = LibrarySortOrder.NewestFirst,
) {
    val hasSearch: Boolean get() = normalizeLibrarySearchTokens(searchQuery).isNotEmpty()
    val hasFilters: Boolean get() = filter != LibraryFilter.All || category != null
    val isNarrowed: Boolean get() = hasSearch || hasFilters

    fun clearedFilters(): LibraryQuery = copy(filter = LibraryFilter.All, category = null)
    fun clearedSearch(): LibraryQuery = copy(searchQuery = "")
    fun cleared(): LibraryQuery = copy(searchQuery = "", filter = LibraryFilter.All, category = null)
}

sealed interface LibraryEmptyState {
    /** No subscriptions and nothing cached: the only useful action is adding a source. */
    data object NoSources : LibraryEmptyState

    /** Sources exist but nothing has been fetched on this device yet. */
    data object NoCachedItems : LibraryEmptyState

    /** Sources exist but every one is paused, so a refresh cannot produce items. */
    data object NoActiveSources : LibraryEmptyState

    /** Search is active and nothing matches; [matchesWithoutFilters] counts hits ignoring filter/category. */
    data class SearchNoMatch(val query: String, val matchesWithoutFilters: Int) : LibraryEmptyState

    /** A non-All filter excludes everything (optionally combined with a category). */
    data class FilterExcludesAll(val filter: LibraryFilter, val category: String?) : LibraryEmptyState

    /** Only a category is selected and it currently holds no items. */
    data class CategoryEmpty(val category: String) : LibraryEmptyState
}

data class LibrarySnapshot(
    val entries: List<LibraryEntry>,
    val totalCount: Int,
    val unreadCount: Int,
    val savedCount: Int,
    val categories: List<LibraryCategoryOption>,
    /** Category actually applied after falling back to All when the selection disappeared. */
    val effectiveCategory: String?,
    val emptyState: LibraryEmptyState?,
) {
    val isNarrowed: Boolean get() = entries.size != totalCount
}

/** Splits a raw query into lower-cased, de-duplicated tokens; blank input yields no tokens. */
fun normalizeLibrarySearchTokens(raw: String): List<String> =
    raw.trim()
        .split(Regex("\\s+"))
        .map { it.lowercase(Locale.ROOT) }
        .filter { it.isNotBlank() }
        .distinct()

fun buildLibraryEntries(
    items: List<MobileFeedItem>,
    feeds: List<MobileFeedSubscription>,
    isRead: (String) -> Boolean,
    isSaved: (String) -> Boolean,
    descriptionToPlainText: (String) -> String = { ArticleContentSanitizer.sanitize(it).plainText },
): List<LibraryEntry> {
    val categoryByFeedId = feeds.associate { it.id to normalizeFeedCategory(it.category) }
    return items.map { item ->
        val preview = item.description?.let(descriptionToPlainText)?.trim().orEmpty()
        val searchText = buildString {
            append(item.title.lowercase(Locale.ROOT))
            append('\n')
            append(item.feedTitle.lowercase(Locale.ROOT))
            if (preview.isNotEmpty()) {
                append('\n')
                append(preview.lowercase(Locale.ROOT))
            }
        }
        LibraryEntry(
            item = item,
            isRead = isRead(item.id),
            isSaved = isSaved(item.id),
            category = categoryByFeedId[item.feedId],
            searchText = searchText,
            publishedEpochMs = item.published?.let { parseArticleInstant(it)?.toEpochMilli() },
            previewText = preview,
        )
    }
}

/** Re-applies read/saved state without recomputing sanitized text or dates. */
fun List<LibraryEntry>.withItemState(
    isRead: (String) -> Boolean,
    isSaved: (String) -> Boolean,
): List<LibraryEntry> = map { entry ->
    val read = isRead(entry.item.id)
    val saved = isSaved(entry.item.id)
    if (read == entry.isRead && saved == entry.isSaved) entry else entry.copy(isRead = read, isSaved = saved)
}

/**
 * Categories that currently hold at least one cached item, de-duplicated case-insensitively and
 * sorted for stable chip order. Categories that only exist on item-less feeds are not offered.
 */
fun libraryCategories(entries: List<LibraryEntry>): List<LibraryCategoryOption> {
    val counts = linkedMapOf<String, Pair<String, Int>>()
    entries.forEach { entry ->
        val category = entry.category ?: return@forEach
        val key = category.lowercase(Locale.ROOT)
        val existing = counts[key]
        counts[key] = if (existing == null) category to 1 else existing.first to existing.second + 1
    }
    return counts.values
        .map { (name, count) -> LibraryCategoryOption(name, count) }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
}

/** Returns the canonical category name for [selected], or null (All) when it is no longer available. */
fun resolveLibraryCategory(selected: String?, categories: List<LibraryCategoryOption>): String? {
    val wanted = normalizeFeedCategory(selected) ?: return null
    return categories.firstOrNull { it.name.equals(wanted, ignoreCase = true) }?.name
}

fun LibraryEntry.matchesSearchTokens(tokens: List<String>): Boolean =
    tokens.all { token -> searchText.contains(token) }

fun LibraryEntry.matchesFilter(filter: LibraryFilter): Boolean = when (filter) {
    LibraryFilter.All -> true
    LibraryFilter.Unread -> !isRead
    LibraryFilter.Saved -> isSaved
    LibraryFilter.Articles -> !isPodcast
    LibraryFilter.Podcasts -> isPodcast
}

fun LibraryEntry.matchesCategory(category: String?): Boolean =
    category == null || (this.category?.equals(category, ignoreCase = true) == true)

/**
 * Orders by the parsed publish date. Items without a parseable date go last in both directions so
 * "Oldest" never promotes undated rows; ties keep cache recency and finally insertion order.
 */
fun libraryEntryComparator(sortOrder: LibrarySortOrder): Comparator<LibraryEntry> {
    val dated = when (sortOrder) {
        LibrarySortOrder.NewestFirst -> compareByDescending<LibraryEntry> { it.publishedEpochMs ?: Long.MIN_VALUE }
        LibrarySortOrder.OldestFirst -> compareBy<LibraryEntry> { it.publishedEpochMs ?: Long.MAX_VALUE }
    }
    return compareBy<LibraryEntry> { it.publishedEpochMs == null }
        .then(dated)
        .thenByDescending { it.item.cachedAtEpochMs }
}

fun queryLibrary(
    entries: List<LibraryEntry>,
    query: LibraryQuery,
    hasSources: Boolean,
    hasActiveSources: Boolean = hasSources,
): LibrarySnapshot {
    val categories = libraryCategories(entries)
    val category = resolveLibraryCategory(query.category, categories)
    val tokens = normalizeLibrarySearchTokens(query.searchQuery)

    val searchHits = if (tokens.isEmpty()) entries else entries.filter { it.matchesSearchTokens(tokens) }
    val filtered = searchHits
        .filter { it.matchesFilter(query.filter) && it.matchesCategory(category) }
        .sortedWith(libraryEntryComparator(query.sortOrder))

    val emptyState = when {
        filtered.isNotEmpty() -> null
        entries.isEmpty() && !hasSources -> LibraryEmptyState.NoSources
        entries.isEmpty() && !hasActiveSources -> LibraryEmptyState.NoActiveSources
        entries.isEmpty() -> LibraryEmptyState.NoCachedItems
        tokens.isNotEmpty() -> LibraryEmptyState.SearchNoMatch(
            query = query.searchQuery.trim(),
            matchesWithoutFilters = searchHits.size,
        )
        query.filter != LibraryFilter.All -> LibraryEmptyState.FilterExcludesAll(query.filter, category)
        category != null -> LibraryEmptyState.CategoryEmpty(category)
        else -> null
    }

    return LibrarySnapshot(
        entries = filtered,
        totalCount = entries.size,
        unreadCount = entries.count { !it.isRead },
        savedCount = entries.count { it.isSaved },
        categories = categories,
        effectiveCategory = category,
        emptyState = emptyState,
    )
}
