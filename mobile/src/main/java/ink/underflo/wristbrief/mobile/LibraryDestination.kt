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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ink.underflo.wristbrief.mobile.ui.AppIcon
import ink.underflo.wristbrief.mobile.ui.AppIconKind
import ink.underflo.wristbrief.mobile.ui.glass.GlassSurface
import ink.underflo.wristbrief.mobile.ui.glass.GlassTokens
import java.util.Locale

private enum class LibraryFilter { All, Unread, Saved, Articles, Podcasts }

/**
 * Library Destination (Screen 10)
 * Implements Stitch WristBrief Android Design System specifications:
 * - 10_library (Dual-row filter, unread indicators, neutral glass search, clean article/podcast rows)
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
    var items by remember { mutableStateOf(inboxRepository.items()) }
    val feeds = remember(feedManager) { feedManager.feeds() }
    val categories = remember(feeds) {
        feeds.mapNotNull { it.category }.filter { it.isNotBlank() }.distinct().sorted()
    }

    val totalCount = items.size
    val totalUnreadCount = remember(items) {
        items.count { !inboxRepository.isRead(it.id) }
    }

    val filteredItems = remember(items, searchQuery, currentFilter, selectedCategory, sortNewestFirst, feeds) {
        val categoryFeedIds = if (selectedCategory == null) null else {
            feeds.filter { it.category.equals(selectedCategory, ignoreCase = true) }.map { it.id }.toSet()
        }
        val list = items.filter { item ->
            val matchesCategory = categoryFeedIds == null || item.feedId in categoryFeedIds
            val matchesSearch = if (searchQuery.isBlank()) true else {
                item.title.contains(searchQuery, ignoreCase = true) ||
                    item.feedTitle.contains(searchQuery, ignoreCase = true) ||
                    (item.description?.contains(searchQuery, ignoreCase = true) == true)
            }
            val matchesFilter = when (currentFilter) {
                LibraryFilter.All -> true
                LibraryFilter.Unread -> !inboxRepository.isRead(item.id)
                LibraryFilter.Saved -> inboxRepository.isSaved(item.id)
                LibraryFilter.Articles -> item.audioUrl == null
                LibraryFilter.Podcasts -> item.audioUrl != null
            }
            matchesCategory && matchesSearch && matchesFilter
        }
        if (sortNewestFirst) list else list.reversed()
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentPadding = PaddingValues(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // 1. Library Header & Metrics
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.nav_library),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = GlassTokens.textPrimary(darkTheme),
                        )
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = GlassTokens.primaryContainer(darkTheme),
                        ) {
                            Text(
                                text = stringResource(R.string.library_count_badge),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = GlassTokens.accentTeal(darkTheme),
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.library_item_count, totalCount),
                            style = MaterialTheme.typography.labelMedium,
                            color = GlassTokens.textSecondary(darkTheme),
                        )
                        Text(
                            text = stringResource(R.string.library_unread_count, totalUnreadCount),
                            style = MaterialTheme.typography.labelMedium,
                            color = GlassTokens.accentTeal(darkTheme),
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }

        // 2. Neutral Glass Search Bar
        item {
            Box(Modifier.padding(horizontal = 20.dp)) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(
                            text = stringResource(R.string.library_search_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = GlassTokens.textSecondary(darkTheme),
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
                        if (searchQuery.isNotBlank()) {
                            Surface(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .clickable { searchQuery = "" },
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
                    },
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = GlassTokens.surfaceContainerHigh(darkTheme),
                        unfocusedContainerColor = GlassTokens.surfaceContainer(darkTheme),
                        focusedBorderColor = GlassTokens.accentTeal(darkTheme),
                        unfocusedBorderColor = GlassTokens.hairline(darkTheme),
                        focusedTextColor = GlassTokens.textPrimary(darkTheme),
                        unfocusedTextColor = GlassTokens.textPrimary(darkTheme),
                    ),
                    singleLine = true,
                )
            }
        }

        // 3. Filter Controls: Dual Row Layout
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Row 1: Sort + Media Type Filters
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Sort Trigger Button
                    item {
                        Surface(
                            modifier = Modifier
                                .height(36.dp)
                                .clip(RoundedCornerShape(18.dp))
                                .clickable { sortNewestFirst = !sortNewestFirst },
                            shape = RoundedCornerShape(18.dp),
                            color = GlassTokens.surfaceContainerHigh(darkTheme),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                AppIcon(
                                    kind = AppIconKind.Refresh,
                                    modifier = Modifier.size(14.dp),
                                    tint = GlassTokens.accentTeal(darkTheme),
                                )
                                Text(
                                    text = if (sortNewestFirst) stringResource(R.string.library_sort_newest) else stringResource(R.string.library_sort_oldest),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = GlassTokens.textPrimary(darkTheme),
                                )
                            }
                        }
                    }

                    items(LibraryFilter.entries) { filter ->
                        val isSelected = currentFilter == filter
                        val label = when (filter) {
                            LibraryFilter.All -> stringResource(R.string.library_filter_all)
                            LibraryFilter.Unread -> "${stringResource(R.string.library_filter_unread)} ($totalUnreadCount)"
                            LibraryFilter.Saved -> stringResource(R.string.library_filter_saved)
                            LibraryFilter.Articles -> stringResource(R.string.library_filter_articles)
                            LibraryFilter.Podcasts -> stringResource(R.string.library_filter_podcasts)
                        }

                        val chipBg = if (isSelected) GlassTokens.primaryContainer(darkTheme) else GlassTokens.surfaceContainer(darkTheme)
                        val chipText = if (isSelected) GlassTokens.accentTeal(darkTheme) else GlassTokens.textSecondary(darkTheme)

                        Surface(
                            modifier = Modifier
                                .height(36.dp)
                                .clip(RoundedCornerShape(18.dp))
                                .clickable { currentFilter = filter },
                            shape = RoundedCornerShape(18.dp),
                            color = chipBg,
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                if (filter == LibraryFilter.Unread) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .background(GlassTokens.accentTeal(darkTheme)),
                                    )
                                }
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                    color = chipText,
                                )
                            }
                        }
                    }
                }

                // Row 2: Editorial Topic Chips
                if (categories.isNotEmpty()) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        item {
                            val isAllSelected = selectedCategory == null
                            Surface(
                                modifier = Modifier
                                    .height(30.dp)
                                    .clip(RoundedCornerShape(15.dp))
                                    .clickable { selectedCategory = null },
                                shape = RoundedCornerShape(15.dp),
                                color = if (isAllSelected) GlassTokens.surfaceContainerHighest(darkTheme) else GlassTokens.surfaceContainerLow(darkTheme),
                            ) {
                                Box(
                                    modifier = Modifier.padding(horizontal = 12.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = stringResource(R.string.library_category_all),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = if (isAllSelected) FontWeight.SemiBold else FontWeight.Normal,
                                        color = GlassTokens.textPrimary(darkTheme),
                                    )
                                }
                            }
                        }

                        items(categories) { cat ->
                            val isCatSelected = selectedCategory.equals(cat, ignoreCase = true)
                            Surface(
                                modifier = Modifier
                                    .height(30.dp)
                                    .clip(RoundedCornerShape(15.dp))
                                    .clickable {
                                        selectedCategory = if (isCatSelected) null else cat
                                    },
                                shape = RoundedCornerShape(15.dp),
                                color = if (isCatSelected) GlassTokens.surfaceContainerHighest(darkTheme) else GlassTokens.surfaceContainerLow(darkTheme),
                            ) {
                                Box(
                                    modifier = Modifier.padding(horizontal = 12.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = cat,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = if (isCatSelected) FontWeight.SemiBold else FontWeight.Normal,
                                        color = if (isCatSelected) GlassTokens.textPrimary(darkTheme) else GlassTokens.textSecondary(darkTheme),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 4. Reading Queue & Media Feed
        if (filteredItems.isEmpty()) {
            item {
                Box(Modifier.padding(horizontal = 20.dp)) {
                    GlassSurface(
                        modifier = Modifier.fillMaxWidth(),
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
                            if (searchQuery.isNotBlank()) {
                                Surface(
                                    modifier = Modifier.size(52.dp),
                                    shape = CircleShape,
                                    color = GlassTokens.surfaceContainerHigh(darkTheme),
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        AppIcon(AppIconKind.Search, Modifier.size(22.dp), GlassTokens.textSecondary(darkTheme))
                                    }
                                }
                                Text(
                                    text = stringResource(R.string.search_empty_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = GlassTokens.textPrimary(darkTheme),
                                )
                                Text(
                                    text = stringResource(R.string.library_no_search_results, searchQuery),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = GlassTokens.textSecondary(darkTheme),
                                    textAlign = TextAlign.Center,
                                )
                                Button(
                                    onClick = { searchQuery = "" },
                                    shape = RoundedCornerShape(12.dp),
                                ) {
                                    Text(stringResource(R.string.library_clear_search))
                                }
                            } else {
                                Text(
                                    text = stringResource(R.string.library_empty),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = GlassTokens.textSecondary(darkTheme),
                                )
                                if (feeds.isEmpty()) {
                                    Button(onClick = onManageSources, shape = RoundedCornerShape(12.dp)) {
                                        Text(stringResource(R.string.today_add_feed))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else {
            items(filteredItems, key = { it.id }) { item ->
                val isRead = inboxRepository.isRead(item.id)
                val isSaved = inboxRepository.isSaved(item.id)
                val isPodcast = item.audioUrl != null

                Box(Modifier.padding(horizontal = 20.dp)) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .clickable {
                                if (isPodcast) onPlayPodcast(item) else onOpenArticle(item)
                            },
                        shape = RoundedCornerShape(18.dp),
                        color = GlassTokens.surfaceContainer(darkTheme),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            // Header Row: Source, Time, Unread Dot, Save Button
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(
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
                                    )
                                    item.published?.let {
                                        Text(
                                            text = "· ${formattedArticleTimestamp(it)}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = GlassTokens.textSecondary(darkTheme),
                                        )
                                    }
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    if (!isRead) {
                                        Box(
                                            modifier = Modifier
                                                .size(8.dp)
                                                .clip(CircleShape)
                                                .background(GlassTokens.accentTeal(darkTheme)),
                                        )
                                    }
                                    Surface(
                                        modifier = Modifier
                                            .size(32.dp)
                                            .clip(CircleShape)
                                            .clickable {
                                                inboxRepository.setSaved(item.id, !isSaved)
                                                items = inboxRepository.items()
                                            },
                                        shape = CircleShape,
                                        color = Color.Transparent,
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            AppIcon(
                                                kind = if (isSaved) AppIconKind.BookmarkFilled else AppIconKind.Bookmark,
                                                modifier = Modifier.size(18.dp),
                                                tint = if (isSaved) GlassTokens.accentTeal(darkTheme) else GlassTokens.textSecondary(darkTheme),
                                            )
                                        }
                                    }
                                }
                            }

                            // Headline
                            Text(
                                text = item.title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = if (isRead) FontWeight.Normal else FontWeight.Bold,
                                color = GlassTokens.textPrimary(darkTheme),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )

                            // Preview description
                            item.description?.let { desc ->
                                val clean = ArticleContentSanitizer.sanitize(desc).plainText
                                if (clean.isNotBlank()) {
                                    Text(
                                        text = clean,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        color = GlassTokens.textSecondary(darkTheme),
                                        lineHeight = 18.sp,
                                    )
                                }
                            }

                            // Bottom Interactive Strip
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Surface(
                                    modifier = Modifier
                                        .height(34.dp)
                                        .clip(RoundedCornerShape(17.dp))
                                        .clickable {
                                            if (isPodcast) onPlayPodcast(item) else onOpenArticle(item)
                                        },
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
                                            text = stringResource(if (isPodcast) R.string.action_listen else R.string.action_read),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = GlassTokens.accentTeal(darkTheme),
                                        )
                                    }
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Surface(
                                        modifier = Modifier
                                            .size(34.dp)
                                            .clip(CircleShape)
                                            .clickable {
                                                inboxRepository.setRead(item.id, !isRead)
                                                items = inboxRepository.items()
                                            },
                                        shape = CircleShape,
                                        color = GlassTokens.surfaceContainerHigh(darkTheme),
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            AppIcon(
                                                kind = AppIconKind.Check,
                                                modifier = Modifier.size(16.dp),
                                                tint = if (isRead) GlassTokens.textSecondary(darkTheme) else GlassTokens.accentTeal(darkTheme),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
