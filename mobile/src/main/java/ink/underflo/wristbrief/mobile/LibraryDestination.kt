package ink.underflo.wristbrief.mobile

import android.content.Intent
import android.net.Uri
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private enum class LibraryFilter { All, Unread, Saved, Articles, Podcasts }

@Composable
internal fun LibraryDestination(
    padding: PaddingValues,
    inboxRepository: MobileInboxRepository,
    feedManager: MobileFeedManager,
    onManageSources: () -> Unit,
    onOpenArticle: (MobileFeedItem) -> Unit = {},
    onPlayPodcast: (MobileFeedItem) -> Unit = {},
) {
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }
    var currentFilter by remember { mutableStateOf(LibraryFilter.All) }
    var items by remember { mutableStateOf(inboxRepository.items()) }

    val filteredItems = items.filter { item ->
        val matchesSearch = if (searchQuery.isBlank()) true else {
            item.title.contains(searchQuery, ignoreCase = true) ||
                item.feedTitle.contains(searchQuery, ignoreCase = true) ||
                (item.description?.contains(searchQuery, ignoreCase = true) == true)
        }
        val matchesCategory = when (currentFilter) {
            LibraryFilter.All -> true
            LibraryFilter.Unread -> !inboxRepository.isRead(item.id)
            LibraryFilter.Saved -> inboxRepository.isSaved(item.id)
            LibraryFilter.Articles -> item.audioUrl == null
            LibraryFilter.Podcasts -> item.audioUrl != null
        }
        matchesSearch && matchesCategory
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
            ) {
                items(LibraryFilter.entries) { filter ->
                    val label = when (filter) {
                        LibraryFilter.All -> stringResource(R.string.library_filter_all)
                        LibraryFilter.Unread -> stringResource(R.string.library_filter_unread)
                        LibraryFilter.Saved -> stringResource(R.string.library_filter_saved)
                        LibraryFilter.Articles -> stringResource(R.string.library_filter_articles)
                        LibraryFilter.Podcasts -> stringResource(R.string.library_filter_podcasts)
                    }
                    FilterChip(
                        selected = currentFilter == filter,
                        onClick = { currentFilter = filter },
                        label = { Text(label) },
                    )
                }
            }
        }

        if (filteredItems.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.extraLarge,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.library_empty),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (feedManager.feeds().isEmpty()) {
                            Button(onClick = onManageSources) {
                                Text(stringResource(R.string.today_add_feed))
                            }
                        }
                    }
                }
            }
        } else {
            items(filteredItems, key = { it.id }) { item ->
                val isRead = inboxRepository.isRead(item.id)
                val isSaved = inboxRepository.isSaved(item.id)

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.extraLarge,
                    colors = CardDefaults.cardColors(
                        containerColor = if (isRead) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainer,
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = if (isRead) FontWeight.Normal else FontWeight.SemiBold,
                        )
                        Text(
                            text = "${item.feedTitle}${item.published?.let { " · $it" }.orEmpty()}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        item.description?.let { desc ->
                            val clean = desc.replace(Regex("<[^>]+>"), "").trim()
                            if (clean.isNotBlank()) {
                                Text(
                                    text = clean,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
