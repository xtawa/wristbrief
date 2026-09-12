package ink.underflo.wristbrief.mobile

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ink.underflo.wristbrief.mobile.ui.glass.GlassSurface
import ink.underflo.wristbrief.mobile.ui.glass.GlassTokens
import ink.underflo.wristbrief.mobile.ui.glass.NeutralFilterChip

private enum class LibraryFilter { All, Unread, Saved, Articles, Podcasts }

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
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }
    var currentFilter by remember { mutableStateOf(LibraryFilter.All) }
    var sortNewestFirst by rememberSaveable { mutableStateOf(true) }
    var selectedCategory by rememberSaveable { mutableStateOf<String?>(null) }
    var items by remember { mutableStateOf(inboxRepository.items()) }
    val feeds = remember(feedManager) { feedManager.feeds() }
    val categories = remember(feeds) {
        feeds.mapNotNull { it.category }.distinct().sorted()
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
        if (sortNewestFirst) {
            list
        } else {
            list.reversed()
        }
    }

    fun openUrl(url: String?) {
        if (!url.isNullOrBlank()) {
            runCatching {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                context.startActivity(intent)
            }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.nav_library),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = GlassTokens.textPrimary(darkTheme),
                )
                TextButton(onClick = onManageSources) {
                    Text(stringResource(R.string.library_manage_sources))
                }
            }
        }

        item {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.library_search_hint)) },
                singleLine = true,
            )
        }

        item {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                item {
                    AssistChip(
                        onClick = { sortNewestFirst = !sortNewestFirst },
                        label = {
                            Text(
                                if (sortNewestFirst) stringResource(R.string.library_sort_newest)
                                else stringResource(R.string.library_sort_oldest)
                            )
                        },
                    )
                }
                items(LibraryFilter.entries) { filter ->
                    val label = when (filter) {
                        LibraryFilter.All -> stringResource(R.string.library_filter_all)
                        LibraryFilter.Unread -> stringResource(R.string.library_filter_unread)
                        LibraryFilter.Saved -> stringResource(R.string.library_filter_saved)
                        LibraryFilter.Articles -> stringResource(R.string.library_filter_articles)
                        LibraryFilter.Podcasts -> stringResource(R.string.library_filter_podcasts)
                    }
                    NeutralFilterChip(
                        label = label,
                        selected = currentFilter == filter,
                        onClick = { currentFilter = filter },
                        darkTheme = darkTheme,
                    )
                }
            }
        }

        if (categories.isNotEmpty()) {
            item {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    item {
                        NeutralFilterChip(
                            label = stringResource(R.string.today_filter_all),
                            selected = selectedCategory == null,
                            onClick = { selectedCategory = null },
                            darkTheme = darkTheme,
                        )
                    }
                    items(categories) { cat ->
                        NeutralFilterChip(
                            label = cat,
                            selected = selectedCategory.equals(cat, ignoreCase = true),
                            onClick = {
                                selectedCategory = if (selectedCategory.equals(cat, ignoreCase = true)) null else cat
                            },
                            darkTheme = darkTheme,
                        )
                    }
                }
            }
        }

        if (filteredItems.isEmpty()) {
            item {
                GlassSurface(
                    modifier = Modifier.fillMaxWidth(),
                    strong = false,
                    cornerRadius = GlassTokens.CardRadius,
                    darkTheme = darkTheme,
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (searchQuery.isNotBlank()) {
                            Text(
                                text = stringResource(R.string.library_no_search_results, searchQuery),
                                style = MaterialTheme.typography.bodyLarge,
                                color = GlassTokens.textSecondary(darkTheme),
                            )
                            Button(onClick = { searchQuery = "" }) {
                                Text(stringResource(R.string.library_clear_search))
                            }
                        } else {
                            Text(
                                text = stringResource(R.string.library_empty),
                                style = MaterialTheme.typography.bodyLarge,
                                color = GlassTokens.textSecondary(darkTheme),
                            )
                            if (feedManager.feeds().isEmpty()) {
                                Button(onClick = onManageSources) {
                                    Text(stringResource(R.string.today_add_feed))
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

                GlassSurface(
                    modifier = Modifier.fillMaxWidth(),
                    strong = !isRead,
                    cornerRadius = GlassTokens.CardRadius,
                    darkTheme = darkTheme,
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = if (isRead) FontWeight.Normal else FontWeight.SemiBold,
                            color = GlassTokens.textPrimary(darkTheme),
                        )
                        Text(
                            text = "${item.feedTitle}${item.published?.let { " · $it" }.orEmpty()}",
                            style = MaterialTheme.typography.bodySmall,
                            color = GlassTokens.textSecondary(darkTheme),
                        )
                        item.description?.let { desc ->
                            val clean = ArticleContentSanitizer.sanitize(desc).plainText
                            if (clean.isNotBlank()) {
                                Text(
                                    text = clean,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    color = GlassTokens.textSecondary(darkTheme),
                                )
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = {
                                    if (item.audioUrl != null) onPlayPodcast(item) else onOpenArticle(item)
                                }) {
                                    Text(stringResource(if (item.audioUrl != null) R.string.action_listen else R.string.action_read))
                                }
                                OutlinedButton(
                                    onClick = {
                                        inboxRepository.setRead(item.id, !isRead)
                                        items = inboxRepository.items()
                                    },
                                ) {
                                    Text(stringResource(if (isRead) R.string.action_mark_unread else R.string.action_mark_read))
                                }
                            }
                            TextButton(
                                onClick = {
                                    inboxRepository.setSaved(item.id, !isSaved)
                                    items = inboxRepository.items()
                                },
                            ) {
                                Text(stringResource(if (isSaved) R.string.action_saved else R.string.action_save))
                            }
                        }
                    }
                }
            }
        }
    }
}
