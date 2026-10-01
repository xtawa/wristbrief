package ink.underflo.wristbrief.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import ink.underflo.wristbrief.mobile.ui.AppIcon
import ink.underflo.wristbrief.mobile.ui.AppIconKind
import ink.underflo.wristbrief.mobile.ui.glass.GlassSurface
import ink.underflo.wristbrief.mobile.ui.glass.GlassTokens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.Locale

private val MinTouchTarget = 48.dp

private sealed interface LibraryRefreshOutcome {
    data class FailedSources(val count: Int) : LibraryRefreshOutcome
    data object Failed : LibraryRefreshOutcome
    data object ReturnedNothing : LibraryRefreshOutcome
}

/**
 * Library Destination (Screen 10)
 *
 * Visual vocabulary follows 10_library (dual-row filters, neutral glass search, quiet rows).
 * All derived state (counts, categories, filtering, sort, empty-state reason) comes from
 * [queryLibrary] so the behaviour is unit tested in LibraryQueryTest.
 */
@Composable
internal fun LibraryDestination(
    padding: PaddingValues,
    inboxRepository: MobileInboxRepository,
    feedManager: MobileFeedManager,
    onManageSources: () -> Unit,
    onOpenArticle: (MobileFeedItem) -> Unit = {},
    onPlayPodcast: (MobileFeedItem) -> Unit = {},
    darkTheme: Boolean = isSystemInDarkTheme(),
) {
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var currentFilter by rememberSaveable { mutableStateOf(LibraryFilter.All) }
    var sortNewestFirst by rememberSaveable { mutableStateOf(true) }
    var selectedCategory by rememberSaveable { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    var items by remember { mutableStateOf(inboxRepository.items()) }
    var feeds by remember { mutableStateOf(feedManager.feeds()) }
    // Read/saved state lives outside the item list; bump this to re-read it after a toggle.
    var stateRevision by remember { mutableIntStateOf(0) }
    var isRefreshing by remember { mutableStateOf(false) }
    var refreshOutcome by remember { mutableStateOf<LibraryRefreshOutcome?>(null) }
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    fun reload() {
        items = inboxRepository.items()
        feeds = feedManager.feeds()
        stateRevision++
    }

    // Pick up Today refreshes, Settings edits and cloud-merged read state when the app returns.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) reload()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // One state snapshot per revision: avoids a full state-store decode per item per lookup.
    val itemStates = remember(items, stateRevision) {
        inboxRepository.itemStates(items.map { it.id })
    }
    val baseEntries = remember(items, feeds) {
        buildLibraryEntries(items, feeds, isRead = { false }, isSaved = { false })
    }
    val entries = remember(baseEntries, itemStates) {
        baseEntries.withItemState(
            isRead = { itemStates[it]?.isRead == true },
            isSaved = { itemStates[it]?.isSaved == true },
        )
    }
    val query = LibraryQuery(
        searchQuery = searchQuery,
        filter = currentFilter,
        category = selectedCategory,
        sortOrder = if (sortNewestFirst) LibrarySortOrder.NewestFirst else LibrarySortOrder.OldestFirst,
    )
    val snapshot = remember(entries, query, feeds) {
        queryLibrary(
            entries,
            query,
            hasSources = feeds.isNotEmpty(),
            hasActiveSources = feeds.any { it.enabled },
        )
    }
    // A selected category whose feeds vanished falls back to All so the list never silently empties.
    LaunchedEffect(snapshot.effectiveCategory, selectedCategory) {
        if (selectedCategory != null && snapshot.effectiveCategory == null) selectedCategory = null
    }

    fun refreshNow() {
        if (isRefreshing) return
        isRefreshing = true
        refreshOutcome = null
        scope.launch {
            val result = runCatching { inboxRepository.refresh() }
            isRefreshing = false
            result.onSuccess { refreshed ->
                reload()
                refreshOutcome = when {
                    refreshed.failedFeedTitles.isNotEmpty() -> LibraryRefreshOutcome.FailedSources(refreshed.failedFeedTitles.size)
                    refreshed.totalCount == 0 -> LibraryRefreshOutcome.ReturnedNothing
                    else -> null
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                refreshOutcome = LibraryRefreshOutcome.Failed
            }
        }
    }

    fun toggleSaved(entry: LibraryEntry) {
        inboxRepository.setSaved(entry.item.id, !entry.isSaved)
        stateRevision++
    }

    fun toggleRead(entry: LibraryEntry) {
        inboxRepository.setRead(entry.item.id, !entry.isRead)
        stateRevision++
    }

    fun open(entry: LibraryEntry) {
        if (entry.isPodcast) onPlayPodcast(entry.item) else onOpenArticle(entry.item)
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .testTag(LibraryTestTags.SCREEN),
        contentPadding = PaddingValues(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // 1. Heading and truthful totals
        item(key = "header") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            ) {
                Text(
                    text = stringResource(R.string.nav_library),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = GlassTokens.textPrimary(darkTheme),
                    modifier = Modifier
                        .semantics { heading() }
                        .testTag(LibraryTestTags.HEADING),
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(
                        R.string.library_header_summary,
                        pluralStringResource(R.plurals.library_items_count, snapshot.totalCount, snapshot.totalCount),
                        stringResource(R.string.library_unread_count, snapshot.unreadCount),
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = GlassTokens.textSecondary(darkTheme),
                    modifier = Modifier.testTag(LibraryTestTags.SUMMARY),
                )
            }
        }

        // 2. Neutral glass search
        item(key = "search") {
            Box(Modifier.padding(horizontal = 20.dp)) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(LibraryTestTags.SEARCH_FIELD),
                    label = {
                        Text(
                            text = stringResource(R.string.library_search_field_label),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    placeholder = {
                        Text(
                            text = stringResource(R.string.library_search_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = GlassTokens.textSecondary(darkTheme),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    leadingIcon = {
                        AppIcon(
                            kind = AppIconKind.Search,
                            modifier = Modifier.size(18.dp),
                            tint = GlassTokens.textSecondary(darkTheme),
                        )
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            val clearLabel = stringResource(R.string.library_clear_search)
                            Box(
                                modifier = Modifier
                                    .size(MinTouchTarget)
                                    .clip(CircleShape)
                                    .clickable(role = Role.Button, onClickLabel = clearLabel) { searchQuery = "" }
                                    .semantics { contentDescription = clearLabel }
                                    .testTag(LibraryTestTags.SEARCH_CLEAR),
                                contentAlignment = Alignment.Center,
                            ) {
                                Surface(
                                    modifier = Modifier.size(28.dp),
                                    shape = CircleShape,
                                    color = GlassTokens.surfaceContainerHighest(darkTheme),
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        AppIcon(
                                            kind = AppIconKind.Close,
                                            modifier = Modifier.size(14.dp),
                                            tint = GlassTokens.textPrimary(darkTheme),
                                        )
                                    }
                                }
                            }
                        }
                    },
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = GlassTokens.surfaceContainerHigh(darkTheme),
                        unfocusedContainerColor = GlassTokens.surfaceContainer(darkTheme),
                        focusedBorderColor = GlassTokens.accentTeal(darkTheme),
                        unfocusedBorderColor = GlassTokens.hairline(darkTheme),
                        focusedLabelColor = GlassTokens.accentTeal(darkTheme),
                        unfocusedLabelColor = GlassTokens.textSecondary(darkTheme),
                        focusedTextColor = GlassTokens.textPrimary(darkTheme),
                        unfocusedTextColor = GlassTokens.textPrimary(darkTheme),
                    ),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                )
            }
        }

        // 3. Filter controls: sort + content type, then source categories
        item(key = "filters") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                LazyRow(
                    modifier = Modifier.semantics { isTraversalGroup = true },
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    item(key = "sort") {
                        LibrarySortToggle(
                            newestFirst = sortNewestFirst,
                            onToggle = { sortNewestFirst = !sortNewestFirst },
                            darkTheme = darkTheme,
                        )
                    }
                    items(LibraryFilter.entries, key = { it.name }) { filter ->
                        val baseLabel = stringResource(libraryFilterLabel(filter))
                        val label = when (filter) {
                            LibraryFilter.Unread -> stringResource(R.string.library_filter_with_count, baseLabel, snapshot.unreadCount)
                            LibraryFilter.Saved -> stringResource(R.string.library_filter_with_count, baseLabel, snapshot.savedCount)
                            else -> baseLabel
                        }
                        LibraryFilterChip(
                            label = label,
                            selected = currentFilter == filter,
                            onClick = { currentFilter = filter },
                            darkTheme = darkTheme,
                            leading = if (filter == LibraryFilter.Unread) {
                                {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .background(GlassTokens.accentTeal(darkTheme)),
                                    )
                                }
                            } else {
                                null
                            },
                            modifier = Modifier.testTag(LibraryTestTags.filterChip(filter)),
                        )
                    }
                }

                if (snapshot.categories.isNotEmpty()) {
                    LazyRow(
                        modifier = Modifier
                            .semantics { isTraversalGroup = true }
                            .testTag(LibraryTestTags.CATEGORY_ROW),
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        item(key = "category:all") {
                            LibraryCategoryChip(
                                label = stringResource(R.string.library_category_all),
                                selected = snapshot.effectiveCategory == null,
                                onClick = { selectedCategory = null },
                                darkTheme = darkTheme,
                                modifier = Modifier.testTag(LibraryTestTags.categoryChip(null)),
                            )
                        }
                        items(snapshot.categories, key = { "category:name:${it.name.lowercase(Locale.ROOT)}" }) { option ->
                            val isSelected = snapshot.effectiveCategory.equals(option.name, ignoreCase = true)
                            LibraryCategoryChip(
                                label = stringResource(R.string.library_filter_with_count, option.name, option.itemCount),
                                selected = isSelected,
                                onClick = { selectedCategory = if (isSelected) null else option.name },
                                darkTheme = darkTheme,
                                modifier = Modifier.testTag(LibraryTestTags.categoryChip(option.name)),
                            )
                        }
                    }
                }
            }
        }

        // 4. Result summary (announced politely so search/filter changes are audible)
        if (snapshot.totalCount > 0 && (query.isNarrowed || snapshot.isNarrowed)) {
            item(key = "results-summary") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .heightIn(min = MinTouchTarget),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = pluralStringResource(R.plurals.library_results_summary, snapshot.totalCount, snapshot.entries.size, snapshot.totalCount),
                        style = MaterialTheme.typography.labelMedium,
                        color = GlassTokens.textSecondary(darkTheme),
                        modifier = Modifier
                            .weight(1f)
                            .semantics { liveRegion = LiveRegionMode.Polite }
                            .testTag(LibraryTestTags.RESULTS_SUMMARY),
                    )
                    if (query.hasFilters) {
                        TextButton(
                            onClick = {
                                currentFilter = LibraryFilter.All
                                selectedCategory = null
                            },
                            modifier = Modifier
                                .heightIn(min = MinTouchTarget)
                                .testTag(LibraryTestTags.RESET_FILTERS),
                        ) {
                            Text(
                                text = stringResource(R.string.library_reset_filters),
                                style = MaterialTheme.typography.labelMedium,
                                color = GlassTokens.accentTeal(darkTheme),
                            )
                        }
                    }
                }
            }
        }

        // 5. Content or a specific recoverable empty state
        val emptyState = snapshot.emptyState
        if (emptyState != null) {
            item(key = "empty") {
                Box(Modifier.padding(horizontal = 20.dp)) {
                    LibraryEmptyCard(
                        state = emptyState,
                        isRefreshing = isRefreshing,
                        refreshOutcome = refreshOutcome,
                        onManageSources = onManageSources,
                        onRefresh = ::refreshNow,
                        onClearSearch = { searchQuery = "" },
                        onSearchEverywhere = {
                            currentFilter = LibraryFilter.All
                            selectedCategory = null
                        },
                        onShowAll = {
                            currentFilter = LibraryFilter.All
                            selectedCategory = null
                        },
                        onAllCategories = { selectedCategory = null },
                        darkTheme = darkTheme,
                    )
                }
            }
        } else {
            items(snapshot.entries, key = { it.item.id }) { entry ->
                Box(Modifier.padding(horizontal = 20.dp)) {
                    LibraryRow(
                        entry = entry,
                        onOpen = { open(entry) },
                        onToggleSaved = { toggleSaved(entry) },
                        onToggleRead = { toggleRead(entry) },
                        darkTheme = darkTheme,
                    )
                }
            }
        }
    }
}

private fun libraryFilterLabel(filter: LibraryFilter): Int = when (filter) {
    LibraryFilter.All -> R.string.library_filter_all
    LibraryFilter.Unread -> R.string.library_filter_unread
    LibraryFilter.Saved -> R.string.library_filter_saved
    LibraryFilter.Articles -> R.string.library_filter_articles
    LibraryFilter.Podcasts -> R.string.library_filter_podcasts
}

@Composable
private fun LibrarySortToggle(
    newestFirst: Boolean,
    onToggle: () -> Unit,
    darkTheme: Boolean,
) {
    val actionLabel = stringResource(R.string.library_sort_action)
    val stateLabel = stringResource(if (newestFirst) R.string.library_sort_state_newest else R.string.library_sort_state_oldest)
    Box(
        modifier = Modifier
            .heightIn(min = MinTouchTarget)
            .clip(RoundedCornerShape(18.dp))
            .clickable(role = Role.Button, onClickLabel = actionLabel, onClick = onToggle)
            .semantics { stateDescription = stateLabel }
            .testTag(LibraryTestTags.SORT_TOGGLE),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.height(36.dp),
            shape = RoundedCornerShape(18.dp),
            color = GlassTokens.surfaceContainerHigh(darkTheme),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                AppIcon(
                    kind = AppIconKind.Sort,
                    modifier = Modifier.size(16.dp),
                    tint = GlassTokens.accentTeal(darkTheme),
                )
                Text(
                    text = stringResource(if (newestFirst) R.string.library_sort_newest else R.string.library_sort_oldest),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = GlassTokens.textPrimary(darkTheme),
                )
            }
        }
    }
}

@Composable
private fun LibraryFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    darkTheme: Boolean,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
) {
    val chipBg = if (selected) GlassTokens.primaryContainer(darkTheme) else GlassTokens.surfaceContainer(darkTheme)
    val chipText = if (selected) GlassTokens.accentTeal(darkTheme) else GlassTokens.textSecondary(darkTheme)
    Box(
        modifier = modifier
            .heightIn(min = MinTouchTarget)
            .clip(RoundedCornerShape(18.dp))
            .semantics { this.selected = selected }
            .clickable(role = Role.Tab, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.height(36.dp),
            shape = RoundedCornerShape(18.dp),
            color = chipBg,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (selected) {
                    AppIcon(
                        kind = AppIconKind.Check,
                        modifier = Modifier.size(14.dp),
                        tint = GlassTokens.accentTeal(darkTheme),
                    )
                } else {
                    leading?.invoke()
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = chipText,
                )
            }
        }
    }
}

@Composable
private fun LibraryCategoryChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    darkTheme: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .heightIn(min = MinTouchTarget)
            .clip(RoundedCornerShape(15.dp))
            .semantics { this.selected = selected }
            .clickable(role = Role.Tab, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.height(30.dp),
            shape = RoundedCornerShape(15.dp),
            color = if (selected) GlassTokens.surfaceContainerHighest(darkTheme) else GlassTokens.surfaceContainerLow(darkTheme),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (selected) {
                    AppIcon(
                        kind = AppIconKind.Check,
                        modifier = Modifier.size(12.dp),
                        tint = GlassTokens.accentTeal(darkTheme),
                    )
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) GlassTokens.textPrimary(darkTheme) else GlassTokens.textSecondary(darkTheme),
                )
            }
        }
    }
}

@Composable
private fun LibraryEmptyCard(
    state: LibraryEmptyState,
    isRefreshing: Boolean,
    refreshOutcome: LibraryRefreshOutcome?,
    onManageSources: () -> Unit,
    onRefresh: () -> Unit,
    onClearSearch: () -> Unit,
    onSearchEverywhere: () -> Unit,
    onShowAll: () -> Unit,
    onAllCategories: () -> Unit,
    darkTheme: Boolean,
) {
    val title: String
    val body: String
    val actionLabel: String
    val onAction: () -> Unit
    var actionEnabled = true
    var icon: AppIconKind = AppIconKind.Search
    var secondaryLabel: String? = null
    var onSecondary: (() -> Unit)? = null
    var note: String? = null

    when (state) {
        LibraryEmptyState.NoSources -> {
            icon = AppIconKind.Add
            title = stringResource(R.string.library_empty_no_sources_title)
            body = stringResource(R.string.library_empty_no_sources_body)
            actionLabel = stringResource(R.string.library_manage_sources)
            onAction = onManageSources
        }
        LibraryEmptyState.NoActiveSources -> {
            icon = AppIconKind.Settings
            title = stringResource(R.string.library_empty_paused_title)
            body = stringResource(R.string.library_empty_paused_body)
            actionLabel = stringResource(R.string.library_manage_sources)
            onAction = onManageSources
        }
        LibraryEmptyState.NoCachedItems -> {
            icon = AppIconKind.Refresh
            title = stringResource(R.string.library_empty_no_items_title)
            body = stringResource(R.string.library_empty_no_items_body)
            actionLabel = stringResource(if (isRefreshing) R.string.library_refreshing else R.string.library_refresh_now)
            actionEnabled = !isRefreshing
            onAction = onRefresh
            secondaryLabel = stringResource(R.string.library_manage_sources)
            onSecondary = onManageSources
            note = when (refreshOutcome) {
                is LibraryRefreshOutcome.FailedSources -> pluralStringResource(R.plurals.library_refresh_failed_count, refreshOutcome.count, refreshOutcome.count)
                LibraryRefreshOutcome.Failed -> stringResource(R.string.library_refresh_failed)
                LibraryRefreshOutcome.ReturnedNothing -> stringResource(R.string.library_refresh_returned_nothing)
                null -> null
            }
        }
        is LibraryEmptyState.SearchNoMatch -> {
            icon = AppIconKind.Search
            title = stringResource(R.string.library_search_no_match_title, state.query)
            if (state.matchesWithoutFilters > 0) {
                body = pluralStringResource(R.plurals.library_search_matches_outside_filters, state.matchesWithoutFilters, state.matchesWithoutFilters)
                actionLabel = stringResource(R.string.library_search_everywhere)
                onAction = onSearchEverywhere
                secondaryLabel = stringResource(R.string.library_clear_search)
                onSecondary = onClearSearch
            } else {
                body = stringResource(R.string.library_search_no_match_body)
                actionLabel = stringResource(R.string.library_clear_search)
                onAction = onClearSearch
            }
        }
        is LibraryEmptyState.FilterExcludesAll -> {
            icon = when (state.filter) {
                LibraryFilter.Unread -> AppIconKind.Check
                LibraryFilter.Saved -> AppIconKind.Bookmark
                LibraryFilter.Podcasts -> AppIconKind.Podcast
                else -> AppIconKind.Search
            }
            title = when (state.filter) {
                LibraryFilter.Unread -> stringResource(R.string.library_empty_unread_title)
                LibraryFilter.Saved -> stringResource(R.string.library_empty_saved_title)
                LibraryFilter.Podcasts -> stringResource(R.string.library_empty_podcasts_title)
                else -> stringResource(R.string.library_empty_articles_title)
            }
            body = when {
                state.category != null -> stringResource(R.string.library_empty_type_body)
                state.filter == LibraryFilter.Unread -> stringResource(R.string.library_empty_unread_body)
                state.filter == LibraryFilter.Saved -> stringResource(R.string.library_empty_saved_body)
                else -> stringResource(R.string.library_empty_type_body)
            }
            actionLabel = stringResource(R.string.library_show_all)
            onAction = onShowAll
        }
        is LibraryEmptyState.CategoryEmpty -> {
            icon = AppIconKind.Search
            title = stringResource(R.string.library_empty_category_title, state.category)
            body = stringResource(R.string.library_empty_category_body)
            actionLabel = stringResource(R.string.library_category_all)
            onAction = onAllCategories
        }
    }

    GlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(LibraryTestTags.EMPTY_STATE),
        strong = false,
        cornerRadius = GlassTokens.CardRadius,
        darkTheme = darkTheme,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Surface(
                modifier = Modifier.size(52.dp),
                shape = CircleShape,
                color = GlassTokens.surfaceContainerHigh(darkTheme),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    AppIcon(icon, Modifier.size(22.dp), GlassTokens.textSecondary(darkTheme))
                }
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = GlassTokens.textPrimary(darkTheme),
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = GlassTokens.textSecondary(darkTheme),
                textAlign = TextAlign.Center,
            )
            note?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = GlassTokens.textPrimary(darkTheme),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            Button(
                onClick = onAction,
                enabled = actionEnabled,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .heightIn(min = MinTouchTarget)
                    .testTag(LibraryTestTags.EMPTY_ACTION),
            ) {
                Text(actionLabel)
            }
            if (secondaryLabel != null && onSecondary != null) {
                TextButton(
                    onClick = onSecondary,
                    modifier = Modifier.heightIn(min = MinTouchTarget),
                ) {
                    Text(
                        text = secondaryLabel,
                        color = GlassTokens.accentTeal(darkTheme),
                    )
                }
            }
        }
    }
}

@Composable
private fun LibraryRow(
    entry: LibraryEntry,
    onOpen: () -> Unit,
    onToggleSaved: () -> Unit,
    onToggleRead: () -> Unit,
    darkTheme: Boolean,
) {
    val item = entry.item
    val isPodcast = entry.isPodcast
    val openLabel = stringResource(if (isPodcast) R.string.action_listen else R.string.action_read)
    val readState = stringResource(if (entry.isRead) R.string.library_item_state_read else R.string.library_item_state_unread)
    val savedState = stringResource(R.string.library_item_state_saved)
    val rowState = if (entry.isSaved) "$readState, $savedState" else readState

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable(role = Role.Button, onClickLabel = openLabel, onClick = onOpen)
            .semantics { stateDescription = rowState }
            .testTag(LibraryTestTags.row(item.id)),
        shape = RoundedCornerShape(18.dp),
        color = GlassTokens.surfaceContainer(darkTheme),
    ) {
        Column(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Source, time, unread dot, save toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Surface(
                        modifier = Modifier.size(24.dp),
                        shape = RoundedCornerShape(6.dp),
                        color = GlassTokens.surfaceContainerHighest(darkTheme),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            if (isPodcast) {
                                AppIcon(
                                    kind = AppIconKind.Podcast,
                                    modifier = Modifier.size(14.dp),
                                    tint = GlassTokens.accentTeal(darkTheme),
                                )
                            } else {
                                Text(
                                    text = item.feedTitle.take(1).uppercase(Locale.getDefault()),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                    Text(
                        text = item.feedTitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = GlassTokens.textSecondary(darkTheme),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    item.published?.let {
                        val stamp = formattedArticleTimestamp(it)
                        val published = stringResource(R.string.library_item_published, stamp)
                        Text(
                            text = "· $stamp",
                            style = MaterialTheme.typography.labelSmall,
                            color = GlassTokens.textSecondary(darkTheme),
                            maxLines = 1,
                            modifier = Modifier.semantics { contentDescription = published },
                        )
                    }
                }

                if (!entry.isRead) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(GlassTokens.accentTeal(darkTheme)),
                    )
                }
                val saveLabel = stringResource(if (entry.isSaved) R.string.library_action_unsave else R.string.library_action_save)
                Box(
                    modifier = Modifier
                        .size(MinTouchTarget)
                        .clip(CircleShape)
                        .clickable(role = Role.Button, onClickLabel = saveLabel, onClick = onToggleSaved)
                        .semantics {
                            contentDescription = saveLabel
                            if (entry.isSaved) stateDescription = savedState
                        }
                        .testTag(LibraryTestTags.saveToggle(item.id)),
                    contentAlignment = Alignment.Center,
                ) {
                    AppIcon(
                        kind = if (entry.isSaved) AppIconKind.BookmarkFilled else AppIconKind.Bookmark,
                        modifier = Modifier.size(20.dp),
                        tint = if (entry.isSaved) GlassTokens.accentTeal(darkTheme) else GlassTokens.textSecondary(darkTheme),
                    )
                }
            }

            Column(
                modifier = Modifier.padding(end = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (entry.isRead) FontWeight.Normal else FontWeight.Bold,
                    color = GlassTokens.textPrimary(darkTheme),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                if (entry.previewText.isNotBlank()) {
                    Text(
                        text = entry.previewText,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = GlassTokens.textSecondary(darkTheme),
                        lineHeight = 18.sp,
                    )
                }
            }

            // Bottom strip: the pill is a visual affordance for the row action; the toggle is separate.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    modifier = Modifier
                        .height(34.dp)
                        .clearAndSetSemantics {},
                    shape = RoundedCornerShape(17.dp),
                    color = GlassTokens.primaryContainer(darkTheme),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        AppIcon(
                            kind = if (isPodcast) AppIconKind.Play else AppIconKind.ChevronRight,
                            modifier = Modifier.size(16.dp),
                            tint = GlassTokens.accentTeal(darkTheme),
                        )
                        Text(
                            text = openLabel,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = GlassTokens.accentTeal(darkTheme),
                        )
                    }
                }

                val readLabel = stringResource(if (entry.isRead) R.string.library_action_mark_unread else R.string.library_action_mark_read)
                Box(
                    modifier = Modifier
                        .size(MinTouchTarget)
                        .clip(CircleShape)
                        .clickable(role = Role.Button, onClickLabel = readLabel, onClick = onToggleRead)
                        .semantics {
                            contentDescription = readLabel
                            stateDescription = readState
                        }
                        .testTag(LibraryTestTags.readToggle(item.id)),
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(
                        modifier = Modifier.size(34.dp),
                        shape = CircleShape,
                        color = if (entry.isRead) GlassTokens.primaryContainer(darkTheme) else GlassTokens.surfaceContainerHigh(darkTheme),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            AppIcon(
                                kind = AppIconKind.Check,
                                modifier = Modifier.size(16.dp),
                                tint = if (entry.isRead) GlassTokens.accentTeal(darkTheme) else GlassTokens.textSecondary(darkTheme),
                            )
                        }
                    }
                }
            }
        }
    }
}
