package ink.underflo.wristbrief.mobile

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun TodayDestination(
    padding: PaddingValues,
    inboxRepository: MobileInboxRepository,
    feedManager: MobileFeedManager,
    onAddFeed: () -> Unit,
    onImportOpml: () -> Unit,
    onOpenAskAi: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenArticle: (MobileFeedItem) -> Unit = {},
    onPlayPodcast: (MobileFeedItem) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf(inboxRepository.items()) }
    var feeds by remember { mutableStateOf(feedManager.feeds()) }
    var isRefreshing by remember { mutableStateOf(false) }
    var refreshStatus by remember { mutableStateOf<String?>(null) }

    val unread = items.filterNot { inboxRepository.isRead(it.id) }
    val saved = items.filter { inboxRepository.isSaved(it.id) }
    val continueReading = unread.firstOrNull { it.audioUrl == null }
    val continueListening = items.firstOrNull { it.audioUrl != null }

    val todayDateFormatted = remember {
        val format = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault())
        format.format(Date())
    }

    LaunchedEffect(Unit) {
        if (feeds.isNotEmpty() && items.isEmpty()) {
            isRefreshing = true
            val res = inboxRepository.refresh()
            items = inboxRepository.items()
            isRefreshing = false
            refreshStatus = if (res.isOfflineFallback) {
                context.getString(R.string.today_offline_fallback)
            } else {
                context.getString(R.string.today_refreshed)
            }
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
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Date and Greeting Header
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = todayDateFormatted,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = stringResource(R.string.today_greeting),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        // Empty state: No feeds subscribed
        if (feeds.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.extraLarge,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.today_empty_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = stringResource(R.string.today_empty_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        Button(
                            onClick = onAddFeed,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.today_add_feed))
                        }
                        OutlinedButton(
                            onClick = onImportOpml,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.today_import_opml))
                        }
                    }
                }
            }
        } else if (items.isEmpty()) {
            // Feeds exist, but items not yet loaded / empty
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.extraLarge,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.today_syncing_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = stringResource(R.string.today_syncing_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(
                            enabled = !isRefreshing,
                            onClick = {
                                scope.launch {
                                    isRefreshing = true
                                    val res = inboxRepository.refresh()
                                    items = inboxRepository.items()
                                    isRefreshing = false
                                    refreshStatus = if (res.isOfflineFallback) {
                                        context.getString(R.string.today_offline_fallback)
                                    } else {
                                        context.getString(R.string.today_refreshed)
                                    }
                                }
                            },
                        ) {
                            Text(if (isRefreshing) stringResource(R.string.today_refreshing) else stringResource(R.string.today_refresh))
                        }
                    }
                }
            }
        } else {
            // Daily Brief card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.extraLarge,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.today_brief_title),
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = stringResource(R.string.today_brief_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                        )
                        Button(
                            onClick = onOpenAskAi,
                            modifier = Modifier.align(Alignment.End),
                        ) {
                            Text(stringResource(R.string.today_brief_action))
                        }
                    }
                }
            }

            // Continue reading card
            continueReading?.let { reading ->
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.extraLarge,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.today_continue_reading),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = reading.title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = reading.feedTitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Button(onClick = { onOpenArticle(reading) }) {
                                    Text(stringResource(R.string.action_read))
                                }
                                TextButton(
                                    onClick = {
                                        inboxRepository.setRead(reading.id, true)
                                        items = inboxRepository.items()
                                    },
                                ) {
                                    Text(stringResource(R.string.action_mark_read))
                                }
                            }
                        }
                    }
                }
            }

            // Continue listening card
            continueListening?.let { podcast ->
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.extraLarge,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.today_continue_listening),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.tertiary,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = podcast.title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = podcast.feedTitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Button(onClick = { onPlayPodcast(podcast) }) {
                                Text(stringResource(R.string.action_listen))
                            }
                        }
                    }
                }
            }

            // Latest unread section
            if (unread.isNotEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.today_latest),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
                items(unread.take(6), key = { it.id }) { item ->
                    val isSaved = inboxRepository.isSaved(item.id)
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.extraLarge,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    ) {
                        Column(
                            modifier = Modifier.padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = item.title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
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
                                        maxLines = 3,
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
                                            inboxRepository.setRead(item.id, true)
                                            items = inboxRepository.items()
                                        },
                                    ) {
                                        Text(stringResource(R.string.action_mark_read))
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

            // Saved section
            if (saved.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.today_saved),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        TextButton(onClick = onOpenLibrary) {
                            Text(stringResource(R.string.today_view_all))
                        }
                    }
                }
                items(saved.take(3), key = { "saved:${it.id}" }) { item ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (item.audioUrl != null) onPlayPodcast(item) else onOpenArticle(item)
                            },
                        shape = MaterialTheme.shapes.extraLarge,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = item.title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = item.feedTitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            // Bottom Refresh status & action
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    refreshStatus?.let { status ->
                        Text(
                            text = status,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    TextButton(
                        enabled = !isRefreshing,
                        onClick = {
                            scope.launch {
                                isRefreshing = true
                                val res = inboxRepository.refresh()
                                items = inboxRepository.items()
                                isRefreshing = false
                                refreshStatus = if (res.isOfflineFallback) {
                                    context.getString(R.string.today_offline_fallback)
                                } else {
                                    context.getString(R.string.today_refreshed)
                                }
                            }
                        },
                    ) {
                        Text(if (isRefreshing) stringResource(R.string.today_refreshing) else stringResource(R.string.today_refresh))
                    }
                }
            }
        }
    }
}
