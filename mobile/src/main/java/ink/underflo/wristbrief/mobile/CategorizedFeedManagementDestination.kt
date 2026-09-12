package ink.underflo.wristbrief.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
internal fun CategorizedFeedManagementDestination(
    padding: PaddingValues,
    feedManager: MobileFeedManager,
    onboardingAction: OnboardingAction? = null,
    onOnboardingActionConsumed: () -> Unit = {},
) {
    val context = LocalContext.current
    // Shares the app-wide manager (SQLite store, cloud outbox, Wear publisher).
    // Previously this screen built its own manager on SharedPreferences, which
    // diverged from the SQLite-backed list after the one-shot legacy migration.
    val manager = feedManager
    val scope = rememberCoroutineScope()
    var feeds by remember { mutableStateOf(manager.feeds()) }
    var editing by remember { mutableStateOf<MobileFeedSubscription?>(null) }
    var showEditor by rememberSaveable { mutableStateOf(false) }
    var launchOpmlImport by rememberSaveable { mutableStateOf(false) }
    var status by remember { mutableStateOf(context.getString(R.string.feed_management_status)) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(onboardingAction) {
        when (onboardingAction) {
            OnboardingAction.AddFeed -> showEditor = true
            OnboardingAction.ImportOpml -> launchOpmlImport = true
            OnboardingAction.Explore, null -> Unit
        }
        if (onboardingAction != null) onOnboardingActionConsumed()
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { Text(stringResource(R.string.feed_management_title), style = MaterialTheme.typography.headlineMedium) }
        item { Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item {
            Button(
                onClick = {
                    editing = null
                    showEditor = true
                },
                enabled = !busy,
            ) { Text(stringResource(R.string.feed_management_add)) }
        }
        item {
            OpmlManagementActions(
                manager = manager,
                busy = busy,
                onBusyChange = { busy = it },
                onFeedsChanged = { feeds = it },
                onStatus = { status = it },
                launchImport = launchOpmlImport,
                onImportLaunchConsumed = { launchOpmlImport = false },
            )
        }
        if (feeds.isEmpty()) {
            item { Text(stringResource(R.string.feed_management_empty), style = MaterialTheme.typography.bodyLarge) }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        stringResource(R.string.sample_feeds_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    SampleFeeds.curatedFeeds.forEach { sample ->
                        val isAdded = feeds.any { normalizeFeedUrl(it.url) == normalizeFeedUrl(sample.url) }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                                Text(sample.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                Text(sample.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (isAdded) {
                                Text(
                                    "✓",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold,
                                )
                            } else {
                                OutlinedButton(
                                    onClick = {
                                        scope.launch {
                                            busy = true
                                            status = context.getString(R.string.feed_validating)
                                            val result = manager.add(sample.url, sample.title, sample.category)
                                            busy = false
                                            if (result is FeedMutationResult.Success) {
                                                feeds = result.feeds
                                                status = context.getString(R.string.sample_feed_added, sample.title)
                                            } else if (result is FeedMutationResult.Error) {
                                                status = result.message
                                            }
                                        }
                                    },
                                    enabled = !busy,
                                ) {
                                    Text(stringResource(R.string.sample_feeds_quick_add))
                                }
                            }
                        }
                    }
                }
            }
        }

        groupMobileFeedsByCategory(feeds).forEach { group ->
            item(key = "category:${group.category ?: "uncategorized"}") {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        group.label,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        if (group.feeds.size == 1) stringResource(R.string.feed_count_single)
                        else stringResource(R.string.feed_count_multiple, group.feeds.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(group.feeds, key = { it.id }) { feed ->
                val keywordState = keywordWatchUiState(feed.watchKeywords)
                Card(
                    Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.extraLarge,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                ) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(feed.title, style = MaterialTheme.typography.titleLarge)
                        Text(feed.url, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        feed.category?.let { category ->
                            Text(stringResource(R.string.feed_folder_prefix, category), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(stringResource(R.string.feed_watch_filter_prefix, keywordState.summary), style = MaterialTheme.typography.labelLarge)
                            Text(
                                keywordState.supportingText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.feed_subscription_active))
                                Text(
                                    if (feed.enabled) stringResource(R.string.feed_included_refreshes) else stringResource(R.string.feed_paused_synced),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = feed.enabled,
                                onCheckedChange = { enabled ->
                                    val result = manager.setEnabled(feed.id, enabled)
                                    if (result is FeedMutationResult.Success) feeds = result.feeds
                                },
                            )
                        }
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.feed_send_to_watch))
                                Text(
                                    if (feed.sendToWatch) stringResource(R.string.feed_available_wear) else stringResource(R.string.feed_phone_only),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = feed.sendToWatch,
                                onCheckedChange = { sendToWatch ->
                                    val result = manager.setSendToWatch(feed.id, sendToWatch)
                                    if (result is FeedMutationResult.Success) {
                                        feeds = result.feeds
                                        status = if (sendToWatch) context.getString(R.string.feed_status_queued_wear) else context.getString(R.string.feed_status_phone_only)
                                    }
                                },
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                editing = feed
                                showEditor = true
                            }) { Text(stringResource(R.string.feed_edit)) }
                            TextButton(onClick = {
                                val result = manager.remove(feed.id)
                                if (result is FeedMutationResult.Success) feeds = result.feeds
                            }) { Text(stringResource(R.string.feed_remove)) }
                        }
                    }
                }
            }
        }
    }

    if (showEditor) {
        val existingUrls = remember(feeds) { feeds.map { it.url }.toSet() }
        CategoryFeedEditorDialog(
            feed = editing,
            existingUrls = existingUrls,
            busy = busy,
            onDismiss = { if (!busy) showEditor = false },
            onSave = { url, title, category, keywordText, sendToWatch ->
                scope.launch {
                    busy = true
                    status = context.getString(R.string.feed_validating)
                    val watchKeywords = normalizeWatchKeywords(keywordText)
                    val result = if (editing == null) {
                        manager.add(url, title, category, watchKeywords, sendToWatch)
                    } else {
                        manager.update(editing!!.id, url, title, category, watchKeywords, sendToWatch)
                    }
                    busy = false
                    when (result) {
                        is FeedMutationResult.Success -> {
                            feeds = result.feeds
                            val filterStatus = if (watchKeywords.isEmpty()) {
                                context.getString(R.string.feed_all_items_sent)
                            } else {
                                context.getString(R.string.feed_watch_keywords_active, watchKeywords.size)
                            }
                            status = normalizeFeedCategory(category)?.let {
                                context.getString(R.string.feed_saved_in_folder, it, filterStatus)
                            } ?: context.getString(R.string.feed_saved, filterStatus)
                            showEditor = false
                        }
                        is FeedMutationResult.Error -> status = result.message
                    }
                }
            },
        )
    }
}

@Composable
private fun CategoryFeedEditorDialog(
    feed: MobileFeedSubscription?,
    existingUrls: Set<String>,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, String, String, String, Boolean) -> Unit,
) {
    var url by remember(feed?.id) { mutableStateOf(feed?.url.orEmpty()) }
    var title by remember(feed?.id) { mutableStateOf(feed?.title.orEmpty()) }
    var category by remember(feed?.id) { mutableStateOf(feed?.category.orEmpty()) }
    var watchKeywords by remember(feed?.id) { mutableStateOf(keywordWatchEditorText(feed?.watchKeywords.orEmpty())) }
    var sendToWatch by remember(feed?.id) { mutableStateOf(feed?.sendToWatch ?: true) }

    val validation = remember(url, existingUrls, feed?.url) {
        validateFeedUrlInput(url, existingUrls, feed?.url)
    }
    val hasUrlInput = url.trim().isNotEmpty()
    val isUrlError = hasUrlInput && validation !is FeedUrlValidationResult.Valid
    val urlErrorText = when {
        !isUrlError -> null
        validation is FeedUrlValidationResult.NotHttps -> stringResource(R.string.feed_url_error_https)
        validation is FeedUrlValidationResult.InvalidFormat -> stringResource(R.string.feed_url_error_invalid)
        validation is FeedUrlValidationResult.Duplicate -> stringResource(R.string.feed_url_error_duplicate)
        else -> null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (feed == null) stringResource(R.string.feed_dialog_add) else stringResource(R.string.feed_dialog_edit)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.feed_url_label)) },
                    isError = isUrlError,
                    supportingText = urlErrorText?.let { err -> { Text(err, color = MaterialTheme.colorScheme.error) } },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.feed_name_optional)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = category,
                    onValueChange = { category = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.feed_folder_optional)) },
                    supportingText = { Text(stringResource(R.string.feed_folder_hint)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = watchKeywords,
                    onValueChange = { watchKeywords = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.feed_keywords_optional)) },
                    supportingText = {
                        Text(stringResource(R.string.feed_keywords_hint))
                    },
                    minLines = 2,
                    maxLines = 4,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.feed_send_to_watch), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (sendToWatch) stringResource(R.string.feed_available_wear) else stringResource(R.string.feed_phone_only),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = sendToWatch,
                        onCheckedChange = { sendToWatch = it },
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(url, title, category, watchKeywords, sendToWatch) },
                enabled = !busy && validation is FeedUrlValidationResult.Valid,
            ) {
                Text(if (busy) stringResource(R.string.feed_validating) else stringResource(R.string.feed_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
