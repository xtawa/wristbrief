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
import ink.underflo.wristbrief.ui.ArticleDetailUi
import ink.underflo.wristbrief.ui.FeedManagementItemUi
import ink.underflo.wristbrief.ui.InboxItemUi
import ink.underflo.wristbrief.ui.InboxUiState
import ink.underflo.wristbrief.ui.InboxViewModel
import ink.underflo.wristbrief.ui.toArticleDetailUi

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { WristBriefApp() }
    }
}

private enum class AppDestination { Inbox, Saved, Feeds, Article }

@Composable
private fun WristBriefApp(viewModel: InboxViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsState()
    var destinationName by rememberSaveable { mutableStateOf(AppDestination.Inbox.name) }
    var articleReturnDestinationName by rememberSaveable { mutableStateOf(AppDestination.Inbox.name) }
    var selectedArticleId by rememberSaveable { mutableStateOf<String?>(null) }
    val destination = runCatching { AppDestination.valueOf(destinationName) }
        .getOrDefault(AppDestination.Inbox)

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
                    onItemClick = { openArticle(it, AppDestination.Inbox) },
                    onRefresh = viewModel::refresh,
                    onOpenSaved = { destinationName = AppDestination.Saved.name },
                    onOpenFeeds = { destinationName = AppDestination.Feeds.name }
                )

                AppDestination.Saved -> SavedScreen(
                    items = state.savedItems,
                    onItemClick = { openArticle(it, AppDestination.Saved) },
                    onBack = { destinationName = AppDestination.Inbox.name }
                )

                AppDestination.Feeds -> FeedManagementScreen(
                    feeds = state.feeds,
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

/** Wear-first inbox shell following the Material 3 Expressive scrolling model. */
@Composable
internal fun InboxScreen(
    state: InboxUiState,
    onItemClick: (InboxItemUi) -> Unit,
    onRefresh: () -> Unit,
    onOpenSaved: () -> Unit,
    onOpenFeeds: () -> Unit
) {
    val listState = rememberTransformingLazyColumnState()
    val transformationSpec = rememberTransformationSpec()

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize()
        ) {
            item { ListHeader { Text("WristBrief · ${state.unreadCount} unread") } }
            if (state.isOfflineFallback) item { ListHeader { Text("Offline · showing cached briefs") } }

            if (state.items.isEmpty()) {
                item {
                    val label = when {
                        state.isLoading -> "Refreshing…"
                        state.hasSubscriptions -> "No briefs yet"
                        else -> "No feeds yet"
                    }
                    val detail = when {
                        state.errorMessage != null -> state.errorMessage
                        state.hasSubscriptions -> "Refresh to fetch your latest briefs"
                        else -> "Add feeds from the phone companion"
                    }
                    Button(
                        onClick = onRefresh,
                        enabled = state.hasSubscriptions && !state.isLoading,
                        label = { Text(label) },
                        secondaryLabel = { Text(detail, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        transformation = SurfaceTransformation(transformationSpec),
                        modifier = Modifier.transformedHeight(this, transformationSpec).fillMaxWidth()
                    )
                }
            } else {
                items(count = state.items.size) { index ->
                    val item = state.items[index]
                    InboxItemCard(item, transformationSpec) { onItemClick(item) }
                }
                if (state.errorMessage != null) item { ListHeader { Text(state.errorMessage) } }
                if (state.hasSubscriptions) {
                    item {
                        Button(
                            onClick = onRefresh,
                            enabled = !state.isLoading,
                            label = { Text(if (state.isLoading) "Refreshing…" else "Refresh") },
                            transformation = SurfaceTransformation(transformationSpec),
                            modifier = Modifier.transformedHeight(this, transformationSpec).fillMaxWidth()
                        )
                    }
                }
            }

            item {
                Button(
                    onClick = onOpenSaved,
                    label = { Text("Saved · ${state.savedItems.size}") },
                    secondaryLabel = { Text("Offline-ready bookmarks") },
                    transformation = SurfaceTransformation(transformationSpec),
                    modifier = Modifier.transformedHeight(this, transformationSpec).fillMaxWidth()
                )
            }
            item {
                Button(
                    onClick = onOpenFeeds,
                    label = { Text("Feeds") },
                    secondaryLabel = { Text("Manage subscriptions") },
                    transformation = SurfaceTransformation(transformationSpec),
                    modifier = Modifier.transformedHeight(this, transformationSpec).fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope.InboxItemCard(
    item: InboxItemUi,
    transformationSpec: androidx.wear.compose.material3.lazy.TransformationSpec,
    onClick: () -> Unit
) {
    TitleCard(
        onClick = onClick,
        title = { Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        subtitle = {
            val kindAndSource = if (item.isPodcast) "Podcast · ${item.source}" else item.source
            val readPrefix = if (item.isRead) "" else "Unread · "
            val savedPrefix = if (item.isSaved) "Saved · " else ""
            Text("$savedPrefix$readPrefix$kindAndSource", maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        time = if (item.timeLabel.isBlank()) null else {
            { Text(item.timeLabel, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        },
        transformation = SurfaceTransformation(transformationSpec),
        modifier = Modifier.transformedHeight(this, transformationSpec).fillMaxWidth()
    ) { Text(item.summary, maxLines = 2, overflow = TextOverflow.Ellipsis) }
}

@Composable
internal fun SavedScreen(
    items: List<InboxItemUi>,
    onItemClick: (InboxItemUi) -> Unit,
    onBack: () -> Unit
) {
    val listState = rememberTransformingLazyColumnState()
    val transformationSpec = rememberTransformationSpec()
    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize()
        ) {
            item { ListHeader { Text("Saved") } }
            if (items.isEmpty()) {
                item {
                    Button(
                        onClick = {}, enabled = false,
                        label = { Text("Nothing saved") },
                        secondaryLabel = { Text("Save a brief from its detail screen") },
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
                    label = { Text("Back to Inbox") },
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
    onToggleRead: () -> Unit,
    onToggleSaved: () -> Unit,
    onBack: () -> Unit
) {
    val listState = rememberTransformingLazyColumnState()
    val transformationSpec = rememberTransformationSpec()

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize()
        ) {
            item { ListHeader { Text(article?.source ?: "WristBrief") } }
            if (article?.isOffline == true || isOfflineFallback) item { ListHeader { Text("Offline · cached preview") } }

            if (article == null) {
                item {
                    TitleCard(
                        onClick = {},
                        title = { Text("Brief unavailable") },
                        subtitle = { Text("The cached item may have been removed") },
                        transformation = SurfaceTransformation(transformationSpec),
                        modifier = Modifier.transformedHeight(this, transformationSpec).fillMaxWidth()
                    ) { Text("Return to choose an available item.") }
                }
            } else {
                item {
                    TitleCard(
                        onClick = {},
                        title = { Text(article.title, maxLines = 4, overflow = TextOverflow.Ellipsis) },
                        subtitle = {
                            Text(
                                if (article.isPodcast) "Podcast · ${article.source}" else article.source,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        time = if (article.timeLabel.isBlank()) null else {
                            { Text(article.timeLabel, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                        },
                        transformation = SurfaceTransformation(transformationSpec),
                        modifier = Modifier.transformedHeight(this, transformationSpec).fillMaxWidth()
                    ) { Text(article.body) }
                }
                item {
                    Button(
                        onClick = onToggleSaved,
                        label = { Text(if (isSaved) "Remove from Saved" else "Save") },
                        secondaryLabel = { Text("Keeps this cached brief easy to find offline") },
                        transformation = SurfaceTransformation(transformationSpec),
                        modifier = Modifier.transformedHeight(this, transformationSpec).fillMaxWidth()
                    )
                }
                item {
                    Button(
                        onClick = onToggleRead,
                        label = { Text(if (isRead) "Mark unread" else "Mark read") },
                        secondaryLabel = { Text("Opening alone does not change read state") },
                        transformation = SurfaceTransformation(transformationSpec),
                        modifier = Modifier.transformedHeight(this, transformationSpec).fillMaxWidth()
                    )
                }
            }

            item {
                Button(
                    onClick = onBack,
                    label = { Text("Back") },
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
    onToggleFeed: (FeedManagementItemUi) -> Unit,
    onRemoveFeed: (FeedManagementItemUi) -> Unit,
    onBack: () -> Unit
) {
    val listState = rememberTransformingLazyColumnState()
    val transformationSpec = rememberTransformationSpec()

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize()
        ) {
            item { ListHeader { Text("Feeds") } }
            item {
                Button(
                    onClick = onBack,
                    label = { Text("Back to Inbox") },
                    transformation = SurfaceTransformation(transformationSpec),
                    modifier = Modifier.transformedHeight(this, transformationSpec).fillMaxWidth()
                )
            }

            if (feeds.isEmpty()) {
                item {
                    Button(
                        onClick = {}, enabled = false,
                        label = { Text("No subscriptions") },
                        secondaryLabel = { Text("Add feeds on the phone companion") },
                        transformation = SurfaceTransformation(transformationSpec),
                        modifier = Modifier.transformedHeight(this, transformationSpec).fillMaxWidth()
                    )
                }
            } else {
                items(count = feeds.size) { index ->
                    val feed = feeds[index]
                    Button(
                        onClick = { onToggleFeed(feed) },
                        label = { Text(feed.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        secondaryLabel = { Text("${feed.statusLabel} · ${feed.toggleLabel}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        transformation = SurfaceTransformation(transformationSpec),
                        modifier = Modifier.transformedHeight(this, transformationSpec).fillMaxWidth()
                    )
                    Button(
                        onClick = { onRemoveFeed(feed) },
                        label = { Text("Remove ${feed.title}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        secondaryLabel = { Text(feed.url, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        transformation = SurfaceTransformation(transformationSpec),
                        modifier = Modifier.transformedHeight(this, transformationSpec).fillMaxWidth()
                    )
                }
            }

            item {
                Button(
                    onClick = {}, enabled = false,
                    label = { Text("Add/manage on phone") },
                    secondaryLabel = { Text("Phone sync arrives in a later slot") },
                    transformation = SurfaceTransformation(transformationSpec),
                    modifier = Modifier.transformedHeight(this, transformationSpec).fillMaxWidth()
                )
            }
        }
    }
}
