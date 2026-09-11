package ink.underflo.wristbrief.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
internal fun CategorizedFeedManagementDestination(
    padding: PaddingValues,
    onboardingAction: OnboardingAction? = null,
    onOnboardingActionConsumed: () -> Unit = {},
) {
    val context = LocalContext.current
    val manager = remember {
        MobileFeedManager(
            SharedPreferencesMobileFeedStore(context),
            HttpFeedProbe(),
            GoogleWearFeedSyncPublisher(context),
        )
    }
    val scope = rememberCoroutineScope()
    var feeds by remember { mutableStateOf(manager.feeds()) }
    var editing by remember { mutableStateOf<MobileFeedSubscription?>(null) }
    var showEditor by rememberSaveable { mutableStateOf(false) }
    var launchOpmlImport by rememberSaveable { mutableStateOf(false) }
    var status by remember { mutableStateOf("Add feeds here; organize them into folders and choose separately what is active or sent to Wear.") }
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
        item { Text("Feed management", style = MaterialTheme.typography.headlineMedium) }
        item { Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item {
            Button(
                onClick = {
                    editing = null
                    showEditor = true
                },
                enabled = !busy,
            ) { Text("Add feed") }
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
            item { Text("No phone-managed feeds yet.", style = MaterialTheme.typography.bodyLarge) }
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
                        "${group.feeds.size} ${if (group.feeds.size == 1) "feed" else "feeds"}",
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
                            Text("Folder · $category", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("Watch filter · ${keywordState.summary}", style = MaterialTheme.typography.labelLarge)
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
                                Text("Subscription active")
                                Text(
                                    if (feed.enabled) "Included in refreshes" else "Paused on synced devices",
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
                                Text("Send to watch")
                                Text(
                                    if (feed.sendToWatch) "Available on paired Wear devices" else "Keep on phone only",
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
                                        status = if (sendToWatch) "Feed queued for Wear sync." else "Feed kept on phone only."
                                    }
                                },
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                editing = feed
                                showEditor = true
                            }) { Text("Edit") }
                            TextButton(onClick = {
                                val result = manager.remove(feed.id)
                                if (result is FeedMutationResult.Success) feeds = result.feeds
                            }) { Text("Remove") }
                        }
                    }
                }
            }
        }
    }

    if (showEditor) {
        CategoryFeedEditorDialog(
            feed = editing,
            busy = busy,
            onDismiss = { if (!busy) showEditor = false },
            onSave = { url, title, category, keywordText ->
                scope.launch {
                    busy = true
                    status = "Validating feed…"
                    val watchKeywords = normalizeWatchKeywords(keywordText)
                    val result = if (editing == null) {
                        manager.add(url, title, category, watchKeywords)
                    } else {
                        manager.update(editing!!.id, url, title, category, watchKeywords)
                    }
                    busy = false
                    when (result) {
                        is FeedMutationResult.Success -> {
                            feeds = result.feeds
                            val folderStatus = normalizeFeedCategory(category)?.let { " in $it" }.orEmpty()
                            val filterStatus = if (watchKeywords.isEmpty()) "all items sent to Wear" else "${watchKeywords.size} watch keyword${if (watchKeywords.size == 1) "" else "s"} active"
                            status = "Saved$folderStatus; $filterStatus."
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
    busy: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, String, String, String) -> Unit,
) {
    var url by remember(feed?.id) { mutableStateOf(feed?.url.orEmpty()) }
    var title by remember(feed?.id) { mutableStateOf(feed?.title.orEmpty()) }
    var category by remember(feed?.id) { mutableStateOf(feed?.category.orEmpty()) }
    var watchKeywords by remember(feed?.id) { mutableStateOf(keywordWatchEditorText(feed?.watchKeywords.orEmpty())) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (feed == null) "Add feed" else "Edit feed") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("HTTPS feed URL") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Name (optional)") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = category,
                    onValueChange = { category = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Folder (optional)") },
                    supportingText = { Text("Feeds with the same folder name are grouped together.") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = watchKeywords,
                    onValueChange = { watchKeywords = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Watch keywords (optional)") },
                    supportingText = {
                        Text("Comma or line separated. Wear shows items when title or description matches any keyword; leave empty for all items.")
                    },
                    minLines = 2,
                    maxLines = 4,
                )
            }
        },
        confirmButton = {
            Button(onClick = { onSave(url, title, category, watchKeywords) }, enabled = !busy) {
                Text(if (busy) "Validating…" else "Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") }
        },
    )
}
