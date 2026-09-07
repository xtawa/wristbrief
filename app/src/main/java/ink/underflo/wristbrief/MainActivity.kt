package ink.underflo.wristbrief

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnState
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TitleCard
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import ink.underflo.wristbrief.media.PodcastPlaybackConnection
import ink.underflo.wristbrief.media.PodcastPlaybackRequest
import ink.underflo.wristbrief.ui.ArticleDetailUi
import ink.underflo.wristbrief.ui.FeedManagementItemUi
import ink.underflo.wristbrief.ui.InboxItemUi
import ink.underflo.wristbrief.ui.InboxUiState
import ink.underflo.wristbrief.ui.InboxViewModel
import ink.underflo.wristbrief.ui.toArticleDetailUi
import ink.underflo.wristbrief.ui.wearEmptyDetail
import ink.underflo.wristbrief.ui.wearStatusLine

class MainActivity : ComponentActivity() {
    private lateinit var playbackConnection: PodcastPlaybackConnection

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        playbackConnection = PodcastPlaybackConnection(this)
        setContent { WristBriefApp(playbackConnection = playbackConnection) }
    }

    override fun onStart() {
        super.onStart()
        playbackConnection.connect()
    }

    override fun onStop() {
        playbackConnection.disconnect()
        super.onStop()
    }
}

private enum class AppDestination { Inbox, Saved, Feeds, Article }

@Composable
private fun WristBriefApp(
    playbackConnection: PodcastPlaybackConnection,
    viewModel: InboxViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsState()
    var destinationName by rememberSaveable { mutableStateOf(AppDestination.Inbox.name) }
    var articleReturnDestinationName by rememberSaveable { mutableStateOf(AppDestination.Inbox.name) }
    var selectedArticleId by rememberSaveable { mutableStateOf<String?>(null) }
    val destination = runCatching { AppDestination.valueOf(destinationName) }
        .getOrDefault(AppDestination.Inbox)

    val inboxListState = rememberTransformingLazyColumnState()
    val savedListState = rememberTransformingLazyColumnState()
    val feedsListState = rememberTransformingLazyColumnState()

    fun openArticle(item: InboxItemUi, returnDestination: AppDestination) {
        selectedArticleId = item.id
        articleReturnDestinationName = returnDestination.name
        destinationName = AppDestination.Article.name
    }

    MaterialTheme {
        AppScaffold {
            when (destination) {
                AppDestination.Inbox -> InboxScreen(
                    state = state,
                    listState = inboxListState,
                    onItemClick = { openArticle(it, AppDestination.Inbox) },
                    onRefresh = viewModel::refresh,
                    onOpenSaved = { destinationName = AppDestination.Saved.name },
                    onOpenFeeds = { destinationName = AppDestination.Feeds.name }
                )

                AppDestination.Saved -> SavedScreen(
                    items = state.savedItems,
                    listState = savedListState,
                    onItemClick = { openArticle(it, AppDestination.Saved) },
                    onBack = { destinationName = AppDestination.Inbox.name }
                )

                AppDestination.Feeds -> FeedManagementScreen(
                    feeds = state.feeds,
                    listState = feedsListState,
                    onToggleFeed = { feed -> viewModel.setFeedEnabled(feed.id, !feed.enabled) },
                    onRemoveFeed = { feed -> viewModel.removeFeed(feed.id) },
                    onBack = { destinationName = AppDestination.Inbox.name }
                )

                AppDestination.Article -> {
                    val selectedItem = (state.items + state.savedItems)
                        .distinctBy { it.id }
                        .firstOrNull { it.id == selectedArticleId }
                    ArticleDetailScreen(
                        article = selectedItem?.toArticleDetailUi(isOffline = state.isOfflineFallback),
                        isRead = selectedItem?.isRead ?: false,
                        isSaved = selectedItem?.isSaved ?: false,
                        isOfflineFallback = state.isOfflineFallback,
                        playbackConnection = playbackConnection,
                        onPlayPodcast = { article ->
                            article.audioUrl?.let { audioUrl ->
                                playbackConnection.play(
                                    PodcastPlaybackRequest(
                                        id = article.id,
                                        title = article.title,
                                        audioUrl = audioUrl
                                    )
                                )
                            }
                        },
                        onToggleRead = {
                            selectedItem?.let { viewModel.setItemRead(it.id, !it.isRead) }
                        },
                        onToggleSaved = {
                            selectedItem?.let { viewModel.setItemSaved(it.id, !it.isSaved) }
                        },
                        onBack = {
                            selectedArticleId = null
                            destinationName = runCatching {
                                AppDestination.valueOf(articleReturnDestinationName)
                            }.getOrDefault(AppDestination.Inbox).name
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun CompactText(text: String, maxLines: Int = 2) {
    Text(text, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

@Composable
internal fun InboxScreen(
    state: InboxUiState,
    listState: TransformingLazyColumnState,
    onItemClick: (InboxItemUi) -> Unit,
    onRefresh: () -> Unit,
    onOpenSaved: () -> Unit,
    onOpenFeeds: () -> Unit
) {
    val transformationSpec = rememberTransformationSpec()
    val statusLine = state.wearStatusLine()

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize()
        ) {
            item { ListHeader { CompactText("WristBrief", maxLines = 1) } }
            if (statusLine.isNotBlank()) {
                item { ListHeader { CompactText(statusLine, maxLines = 1) } }
            }

            if (state.items.isEmpty()) {
                item {
                    val label = when {
                        state.isLoading -> "Refreshing…"
                        state.hasSubscriptions -> "No briefs yet"
                        else -> "No feeds yet"
                    }
                    Button(
                        onClick = onRefresh,
                        enabled = state.hasSubscriptions && !state.isLoading,
                        label = { CompactText(label, maxLines = 1) },
                        secondaryLabel = { CompactText(state.wearEmptyDetail()) },
                        transformation = SurfaceTransformation(transformationSpec),
                        modifier = Modifier.transformedHeight(this, transformationSpec).fillMaxWidth()
                    )
                }
            } else {
                items(count = state.items.size) { index ->
                    val item = state.items[index]
                    InboxItemCard(item, transformationSpec) { onItemClick(item) }
                }
                if (state.hasSubscriptions) {
                    item {
                        Button(
                            onClick = onRefresh,
                            enabled = !state.isLoading,
                            label = { CompactText(if (state.isLoading) "Refreshing…" else "Refresh", maxLines = 1) },
                            secondaryLabel = if (state.errorMessage != null) {
                                { CompactText(state.errorMessage) }
                            } else null,
                            transformation = SurfaceTransformation(transformationSpec),
                            modifier = Modifier.transformedHeight(this, transformationSpec).fillMaxWidth()
                        )
                    }
                }
            }

            item {
                Button(
                    onClick = onOpenSaved,
                    label = { CompactText("Saved · ${state.savedItems.size}", maxLines = 1) },
                    secondaryLabel = { CompactText("Offline-ready bookmarks") },
                    transformation = SurfaceTransformation(transformationSpec),
                    modifier = Modifier.transformedHeight(this, transformationSpec).fillMaxWidth()
                )
            }
            item {
                Button(
                    onClick = onOpenFeeds,
                    label = { CompactText("Feeds", maxLines = 1) },
                    secondaryLabel = { CompactText("Manage subscriptions") },
                    transformation = SurfaceTransformation(transformationSpec),
                    modifier = Modifier.transformedHeight(this, transformationSpec).fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun androidx.wear.compose.foundation.lazy.TransformingLazyColumnItemScope.InboxItemCard(
    item: InboxItemUi,
    transformationSpec: androidx.wear.compose.material3.lazy.TransformationSpec,
    onClick: () -> Unit
) {
    TitleCard(
        onClick = onClick,
        title = { CompactText(item.title) },
        subtitle = {
            val kindAndSource = if (item.isPodcast) "Podcast · ${item.source}" else item.source
            val readPrefix = if (item.isRead) "" else "Unread · "
            val savedPrefix = if (item.isSaved) "Saved · " else ""
            CompactText("$savedPrefix$readPrefix$kindAndSource", maxLines = 1)
        },
        time = if (item.timeLabel.isBlank()) null else {
            { CompactText(item.timeLabel, maxLines = 1) }
        },
        transformation = SurfaceTransformation(transformationSpec),
        modifier = Modifier.transformedHeight(this, transformationSpec).fillMaxWidth()
    ) { CompactText(item.summary) }
}

@Composable
internal fun SavedScreen(
    items: List<InboxItemUi>,
    listState: TransformingLazyColumnState,
    onItemClick: (InboxItemUi) -> Unit,
    onBack: () -> Unit
) {
    val transformationSpec = rememberTransformationSpec()
    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize()
        ) {
            item { ListHeader { CompactText("Saved", maxLines = 1) } }
            if (items.isEmpty()) {
                item {
                    Button(
                        onClick = {},
                        enabled = false,
                        label = { CompactText("Nothing saved", maxLines = 1) },
                        secondaryLabel = { CompactText("Save a brief from its detail screen") },
                        transformation = SurfaceTransformation(transformationSpec),
                        modifier = Modifier.transformedHeight(this, transformationSpec).fillMaxWidth()
                    )
                }
            } else {
                items(count = items.size) { index ->
                    val item = items[index]
                    InboxItemCard(item, transformationSpec) { onItemClick(item) }
                }
            }
            item {
                Button(
                    onClick = onBack,
                    label = { CompactText("Back to Inbox", maxLines = 1) },
                    transformation = SurfaceTransformation(transformationSpec),
                    modifier = Modifier.transformedHeight(this, transformationSpec).fillMaxWidth()
                )
            }
        }
    }
}

@Composable
internal fun ArticleDetailScreen(
    article: ArticleDetailUi?,
    isRead: Boolean,
    isSaved: Boolean,
    isOfflineFallback: Boolean,
    playbackConnection: PodcastPlaybackConnection,
    onPlayPodcast: (ArticleDetailUi) -> Unit,
    onToggleRead: () -> Unit,
    onToggleSaved: () -> Unit,
    onBack: () -> Unit
) {
    val listState = rememberTransformingLazyColumnState()
    val transformationSpec = rememberTransformationSpec()
    val playbackState by playbackConnection.state.collectAsState()
    val isCurrentPodcast = article != null && playbackState.mediaId == article.id

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize()
        ) {
            item { ListHeader { CompactText(article?.source ?: "WristBrief") } }
            if (article?.isOffline == true || isOfflineFallback) {
                item { ListHeader { CompactText("Offline · cached preview", maxLines = 1) } }
            }

            if (article == null) {
                item {
                    TitleCard(
                        onClick = {},
                        title = { CompactText("Brief unavailable", maxLines = 1) },
                        subtitle = { CompactText("The cached item may have been removed") },
                        transformation = SurfaceTransformation(transformationSpec),
                        modifier = Modifier.transformedHeight(this, transformationSpec).fillMaxWidth()
                    ) { CompactText("Return to choose an available item.") }
                }
            } else {
                item {
                    TitleCard(
                        onClick = {},
                        title = { CompactText(article.title, maxLines = 4) },
                        subtitle = {
                            CompactText(if (article.isPodcast) "Podcast · ${article.source}" else article.source)
                        },
                        time = if (article.timeLabel.isBlank()) null else {
                            { CompactText(article.timeLabel) }
                        },
                        transformation = SurfaceTransformation(transformationSpec),
                        modifier = Modifier.transformedHeight(this, transformationSpec).fillMaxWidth()
                    ) { Text(article.body) }
                }
                if (article.audioUrl != null) {
                    if (!isCurrentPodcast) {
                        item {
                            Button(
                                onClick = { onPlayPodcast(article) },
                                label = { CompactText("Play podcast", maxLines = 1) },
                                secondaryLabel = { CompactText("Resumes from your saved position") },
                                transformation = SurfaceTransformation(transformationSpec),
                                modifier = Modifier.transformedHeight(this, transformationSpec).fillMaxWidth()
                            )
                        }
                    } else {
                        item {
                            Button(
                                onClick = {},
                                enabled = false,
                                label = { CompactText(playbackState.progressLabel, maxLines = 1) },
                                secondaryLabel = {
                                    CompactText("Playing at ${playbackState.playbackSpeed}×", maxLines = 1)
                                },
                                transformation = SurfaceTransformation(transformationSpec),
                                modifier = Modifier.transformedHeight(this, transformationSpec).fillMaxWidth()
                            )
                        }
                        item {
                            Button(
                                onClick = playbackConnection::togglePlayPause,
                                label = { CompactText(if (playbackState.isPlaying) "Pause" else "Play", maxLines = 1) },
                                secondaryLabel = { CompactText("Background playback stays active") },
                                transformation = SurfaceTransformation(transformationSpec),
                                modifier = Modifier.transformedHeight(this, transformationSpec).fillMaxWidth()
                            )
                        }
                        item {
                            Button(
                                onClick = { playbackConnection.seekBy(-15_000L) },
                                label = { CompactText("Back 15s", maxLines = 1) },
                                transformation = SurfaceTransformation(transformationSpec),
                                modifier = Modifier.transformedHeight(this, transformationSpec).fillMaxWidth()
                            )
                        }
                        item {
                            Button(
                                onClick = { playbackConnection.seekBy(30_000L) },
                                label = { CompactText("Forward 30s", maxLines = 1) },
                                transformation = SurfaceTransformation(transformationSpec),
                                modifier = Modifier.transformedHeight(this, transformationSpec).fillMaxWidth()
                            )
                        }
                        item {
                            Button(
                                onClick = playbackConnection::cyclePlaybackSpeed,
                                label = { CompactText("Speed ${playbackState.playbackSpeed}×", maxLines = 1) },
                                secondaryLabel = { CompactText("Tap to cycle 1×–2×") },
                                transformation = SurfaceTransformation(transformationSpec),
                                modifier = Modifier.transformedHeight(this, transformationSpec).fillMaxWidth()
                            )
                        }
                    }
                }
                item {
                    Button(
                        onClick = onToggleSaved,
                        label = { CompactText(if (isSaved) "Remove from Saved" else "Save", maxLines = 1) },
                        secondaryLabel = { CompactText("Keeps this cached brief easy to find offline") },
                        transformation = SurfaceTransformation(transformationSpec),
                        modifier = Modifier.transformedHeight(this, transformationSpec).fillMaxWidth()
                    )
                }
                item {
                    Button(
                        onClick = onToggleRead,
                        label = { CompactText(if (isRead) "Mark unread" else "Mark read", maxLines = 1) },
                        secondaryLabel = { CompactText("Opening alone does not change read state") },
                        transformation = SurfaceTransformation(transformationSpec),
                        modifier = Modifier.transformedHeight(this, transformationSpec).fillMaxWidth()
                    )
                }
            }

            item {
                Button(
                    onClick = onBack,
                    label = { CompactText("Back", maxLines = 1) },
                    transformation = SurfaceTransformation(transformationSpec),
                    modifier = Modifier.transformedHeight(this, transformationSpec).fillMaxWidth()
                )
            }
        }
    }
}

@Composable
internal fun FeedManagementScreen(
    feeds: List<FeedManagementItemUi>,
    listState: TransformingLazyColumnState,
    onToggleFeed: (FeedManagementItemUi) -> Unit,
    onRemoveFeed: (FeedManagementItemUi) -> Unit,
    onBack: () -> Unit
) {
    val transformationSpec = rememberTransformationSpec()

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize()
        ) {
            item { ListHeader { CompactText("Feeds", maxLines = 1) } }
            item {
                Button(
                    onClick = onBack,
                    label = { CompactText("Back to Inbox", maxLines = 1) },
                    transformation = SurfaceTransformation(transformationSpec),
                    modifier = Modifier.transformedHeight(this, transformationSpec).fillMaxWidth()
                )
            }

            if (feeds.isEmpty()) {
                item {
                    Button(
                        onClick = {},
                        enabled = false,
                        label = { CompactText("No subscriptions", maxLines = 1) },
                        secondaryLabel = { CompactText("Add feeds on the phone companion") },
                        transformation = SurfaceTransformation(transformationSpec),
                        modifier = Modifier.transformedHeight(this, transformationSpec).fillMaxWidth()
                    )
                }
            } else {
                items(count = feeds.size) { index ->
                    val feed = feeds[index]
                    Button(
                        onClick = { onToggleFeed(feed) },
                        label = { CompactText(feed.title) },
                        secondaryLabel = { CompactText("${feed.statusLabel} · ${feed.toggleLabel}", maxLines = 1) },
                        transformation = SurfaceTransformation(transformationSpec),
                        modifier = Modifier.transformedHeight(this, transformationSpec).fillMaxWidth()
                    )
                    Button(
                        onClick = { onRemoveFeed(feed) },
                        label = { CompactText("Remove ${feed.title}") },
                        secondaryLabel = { CompactText(feed.url, maxLines = 1) },
                        transformation = SurfaceTransformation(transformationSpec),
                        modifier = Modifier.transformedHeight(this, transformationSpec).fillMaxWidth()
                    )
                }
            }

            item {
                Button(
                    onClick = {},
                    enabled = false,
                    label = { CompactText("Add/manage on phone", maxLines = 1) },
                    secondaryLabel = { CompactText("Phone sync arrives in a later slot") },
                    transformation = SurfaceTransformation(transformationSpec),
                    modifier = Modifier.transformedHeight(this, transformationSpec).fillMaxWidth()
                )
            }
        }
    }
}
