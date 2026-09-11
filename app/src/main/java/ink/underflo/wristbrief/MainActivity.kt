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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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
import ink.underflo.wristbrief.ai.AiBriefUiState
import ink.underflo.wristbrief.ai.AiSummaryClient
import ink.underflo.wristbrief.ai.AiSummaryException
import ink.underflo.wristbrief.ai.aiFailureState
import ink.underflo.wristbrief.ai.isAiGatewayConfigured
import ink.underflo.wristbrief.ai.toWearPresentation
import ink.underflo.wristbrief.media.PodcastPlaybackConnection
import ink.underflo.wristbrief.media.PodcastPlaybackRequest
import ink.underflo.wristbrief.sync.ContinueOnPhoneLauncher
import ink.underflo.wristbrief.ui.ArticleDetailUi
import ink.underflo.wristbrief.ui.FeedManagementItemUi
import ink.underflo.wristbrief.ui.InboxItemUi
import ink.underflo.wristbrief.ui.InboxUiState
import ink.underflo.wristbrief.ui.InboxViewModel
import ink.underflo.wristbrief.ui.toArticleDetailUi
import ink.underflo.wristbrief.ui.wearEmptyDetail
import ink.underflo.wristbrief.ui.wearStatusLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private lateinit var playbackConnection: PodcastPlaybackConnection
    private lateinit var continueOnPhoneLauncher: ContinueOnPhoneLauncher
    private val aiSummaryClient = AiSummaryClient()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        playbackConnection = PodcastPlaybackConnection(this)
        continueOnPhoneLauncher = ContinueOnPhoneLauncher(this)
        setContent {
            WristBriefApp(
                playbackConnection = playbackConnection,
                aiSummaryClient = aiSummaryClient,
                onContinueOnPhone = { url -> continueOnPhoneLauncher.open(url) }
            )
        }
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
    aiSummaryClient: AiSummaryClient,
    onContinueOnPhone: (String) -> Unit,
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
                        aiSummaryClient = aiSummaryClient,
                        gatewayUrl = BuildConfig.AI_GATEWAY_URL,
                        gatewayToken = BuildConfig.AI_GATEWAY_TOKEN,
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
                        onContinueOnPhone = onContinueOnPhone,
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
            item { ListHeader { CompactText(stringResource(R.string.app_name), maxLines = 1) } }
            if (statusLine.isNotBlank()) {
                item { ListHeader { CompactText(statusLine, maxLines = 1) } }
            }

            if (state.items.isEmpty()) {
                item {
                    val label = when {
                        state.isLoading -> stringResource(R.string.wear_status_refreshing)
                        state.hasSubscriptions -> stringResource(R.string.wear_status_no_briefs)
                        else -> stringResource(R.string.wear_status_no_feeds)
                    }
                    val emptyDetail = when {
                        state.errorMessage != null -> state.errorMessage
                        state.hasSubscriptions -> stringResource(R.string.wear_empty_detail_refresh)
                        else -> stringResource(R.string.wear_empty_detail_add)
                    }
                    Button(
                        onClick = onRefresh,
                        enabled = state.hasSubscriptions && !state.isLoading,
                        label = { CompactText(label, maxLines = 1) },
                        secondaryLabel = { CompactText(emptyDetail) },
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
                            label = { CompactText(if (state.isLoading) stringResource(R.string.wear_status_refreshing) else stringResource(R.string.wear_action_refresh), maxLines = 1) },
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
                    label = { CompactText(stringResource(R.string.wear_saved_count, state.savedItems.size), maxLines = 1) },
                    secondaryLabel = { CompactText(stringResource(R.string.wear_saved_subtitle)) },
                    transformation = SurfaceTransformation(transformationSpec),
                    modifier = Modifier.transformedHeight(this, transformationSpec).fillMaxWidth()
                )
            }
            item {
                Button(
                    onClick = onOpenFeeds,
                    label = { CompactText(stringResource(R.string.wear_feeds_title), maxLines = 1) },
                    secondaryLabel = { CompactText(stringResource(R.string.wear_feeds_subtitle)) },
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
            val kindAndSource = if (item.isPodcast) stringResource(R.string.wear_podcast_prefix, item.source) else item.source
            val readPrefix = if (item.isRead) "" else stringResource(R.string.wear_unread_prefix)
            val savedPrefix = if (item.isSaved) stringResource(R.string.wear_saved_prefix) else ""
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
            item { ListHeader { CompactText(stringResource(R.string.wear_saved_title), maxLines = 1) } }
            if (items.isEmpty()) {
                item {
                    Button(
                        onClick = {},
                        enabled = false,
                        label = { CompactText(stringResource(R.string.wear_nothing_saved), maxLines = 1) },
                        secondaryLabel = { CompactText(stringResource(R.string.wear_save_hint)) },
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
                    label = { CompactText(stringResource(R.string.wear_back_to_inbox), maxLines = 1) },
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
    aiSummaryClient: AiSummaryClient,
    gatewayUrl: String,
    gatewayToken: String,
    onPlayPodcast: (ArticleDetailUi) -> Unit,
    onContinueOnPhone: (String) -> Unit,
    onToggleRead: () -> Unit,
    onToggleSaved: () -> Unit,
    onBack: () -> Unit
) {
    val listState = rememberTransformingLazyColumnState()
    val transformationSpec = rememberTransformationSpec()
    val playbackState by playbackConnection.state.collectAsState()
    val isCurrentPodcast = article != null && playbackState.mediaId == article.id
    val gatewayConfigured = remember(gatewayUrl, gatewayToken) {
        isAiGatewayConfigured(gatewayUrl, gatewayToken)
    }
    var aiState by remember(article?.id) { mutableStateOf<AiBriefUiState>(AiBriefUiState.Idle) }
    val aiPresentation = aiState.toWearPresentation(gatewayConfigured)
    val coroutineScope = rememberCoroutineScope()

    fun requestAiBrief() {
        val currentArticle = article ?: return
        if (!gatewayConfigured || aiState is AiBriefUiState.Loading) return
        aiState = AiBriefUiState.Loading
        coroutineScope.launch {
            aiState = try {
                val result = withContext(Dispatchers.IO) {
                    aiSummaryClient.summarize(
                        gatewayUrl = gatewayUrl,
                        gatewayToken = gatewayToken,
                        title = currentArticle.title,
                        content = currentArticle.body
                    )
                }
                AiBriefUiState.Ready(result)
            } catch (error: AiSummaryException) {
                aiFailureState(error.failure)
            } catch (_: IllegalArgumentException) {
                AiBriefUiState.Error("AI configuration unavailable")
            }
        }
    }

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize()
        ) {
            item { ListHeader { CompactText(article?.source ?: stringResource(R.string.app_name)) } }
            if (article?.isOffline == true || isOfflineFallback) {
                item { ListHeader { CompactText(stringResource(R.string.wear_offline_cached), maxLines = 1) } }
            }

            if (article == null) {
                item {
                    TitleCard(
                        onClick = {},
                        title = { CompactText(stringResource(R.string.wear_brief_unavailable), maxLines = 1) },
                        subtitle = { CompactText(stringResource(R.string.wear_brief_unavailable_sub)) },
                        transformation = SurfaceTransformation(transformationSpec),
                        modifier = Modifier.transformedHeight(this, transformationSpec).fillMaxWidth()
                    ) { CompactText(stringResource(R.string.wear_brief_unavailable_body)) }
                }
            } else {
                item {
                    TitleCard(
                        onClick = {},
                        title = { CompactText(article.title, maxLines = 4) },
                        subtitle = {
                            CompactText(if (article.isPodcast) stringResource(R.string.wear_podcast_prefix, article.source) else article.source)
                        },
                        time = if (article.timeLabel.isBlank()) null else {
                            { CompactText(article.timeLabel) }
                        },
                        transformation = SurfaceTransformation(transformationSpec),
                        modifier = Modifier.transformedHeight(this, transformationSpec).fillMaxWidth()
                    ) { Text(article.body) }
                }
                if (article.articleUrl != null) {
                    item {
                        Button(
                            onClick = { onContinueOnPhone(article.articleUrl) },
                            label = { CompactText(stringResource(R.string.wear_continue_phone), maxLines = 1) },
                            secondaryLabel = { CompactText(stringResource(R.string.wear_continue_phone_sub), maxLines = 1) },
                            transformation = SurfaceTransformation(transformationSpec),
                            modifier = Modifier.transformedHeight(this, transformationSpec).fillMaxWidth()
                        )
                    }
                }
                if (aiPresentation.showReadyBrief) {
                    item {
                        TitleCard(
                            onClick = {},
                            title = { CompactText(aiPresentation.label, maxLines = 3) },
                            subtitle = { CompactText(stringResource(R.string.wear_ai_brief), maxLines = 1) },
                            transformation = SurfaceTransformation(transformationSpec),
                            modifier = Modifier.transformedHeight(this, transformationSpec).fillMaxWidth()
                        ) { Text(aiPresentation.detail) }
                    }
                } else {
                    item {
                        Button(
                            onClick = ::requestAiBrief,
                            enabled = aiPresentation.actionEnabled,
                            label = { CompactText(aiPresentation.label, maxLines = 2) },
                            secondaryLabel = { CompactText(aiPresentation.detail, maxLines = 2) },
                            transformation = SurfaceTransformation(transformationSpec),
                            modifier = Modifier.transformedHeight(this, transformationSpec).fillMaxWidth()
                        )
                    }
                }
                if (article.audioUrl != null) {
                    if (!isCurrentPodcast) {
                        item {
                            Button(
                                onClick = { onPlayPodcast(article) },
                                label = { CompactText(stringResource(R.string.wear_play_podcast), maxLines = 1) },
                                secondaryLabel = { CompactText(stringResource(R.string.wear_podcast_resume_sub)) },
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
                                    CompactText(stringResource(R.string.wear_playing_speed, playbackState.playbackSpeed.toString()), maxLines = 1)
                                },
                                transformation = SurfaceTransformation(transformationSpec),
                                modifier = Modifier.transformedHeight(this, transformationSpec).fillMaxWidth()
                            )
                        }
                        item {
                            Button(
                                onClick = playbackConnection::togglePlayPause,
                                label = { CompactText(if (playbackState.isPlaying) stringResource(R.string.wear_pause) else stringResource(R.string.wear_play), maxLines = 1) },
                                secondaryLabel = { CompactText(stringResource(R.string.wear_background_playback_sub)) },
                                transformation = SurfaceTransformation(transformationSpec),
                                modifier = Modifier.transformedHeight(this, transformationSpec).fillMaxWidth()
                            )
                        }
                        item {
                            Button(
                                onClick = { playbackConnection.seekBy(-15_000L) },
                                label = { CompactText(stringResource(R.string.wear_back_15s), maxLines = 1) },
                                transformation = SurfaceTransformation(transformationSpec),
                                modifier = Modifier.transformedHeight(this, transformationSpec).fillMaxWidth()
                            )
                        }
                        item {
                            Button(
                                onClick = { playbackConnection.seekBy(30_000L) },
                                label = { CompactText(stringResource(R.string.wear_forward_30s), maxLines = 1) },
                                transformation = SurfaceTransformation(transformationSpec),
                                modifier = Modifier.transformedHeight(this, transformationSpec).fillMaxWidth()
                            )
                        }
                        item {
                            Button(
                                onClick = playbackConnection::cyclePlaybackSpeed,
                                label = { CompactText(stringResource(R.string.wear_speed_label, playbackState.playbackSpeed.toString()), maxLines = 1) },
                                secondaryLabel = { CompactText(stringResource(R.string.wear_speed_sub)) },
                                transformation = SurfaceTransformation(transformationSpec),
                                modifier = Modifier.transformedHeight(this, transformationSpec).fillMaxWidth()
                            )
                        }
                    }
                }
                item {
                    Button(
                        onClick = onToggleSaved,
                        label = { CompactText(if (isSaved) stringResource(R.string.wear_remove_saved) else stringResource(R.string.wear_save), maxLines = 1) },
                        secondaryLabel = { CompactText(stringResource(R.string.wear_save_sub)) },
                        transformation = SurfaceTransformation(transformationSpec),
                        modifier = Modifier.transformedHeight(this, transformationSpec).fillMaxWidth()
                    )
                }
                item {
                    Button(
                        onClick = onToggleRead,
                        label = { CompactText(if (isRead) stringResource(R.string.wear_mark_unread) else stringResource(R.string.wear_mark_read), maxLines = 1) },
                        secondaryLabel = { CompactText(stringResource(R.string.wear_read_sub)) },
                        transformation = SurfaceTransformation(transformationSpec),
                        modifier = Modifier.transformedHeight(this, transformationSpec).fillMaxWidth()
                    )
                }
            }

            item {
                Button(
                    onClick = onBack,
                    label = { CompactText(stringResource(R.string.wear_back), maxLines = 1) },
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
            item { ListHeader { CompactText(stringResource(R.string.wear_feeds_title), maxLines = 1) } }
            item {
                Button(
                    onClick = onBack,
                    label = { CompactText(stringResource(R.string.wear_back_to_inbox), maxLines = 1) },
                    transformation = SurfaceTransformation(transformationSpec),
                    modifier = Modifier.transformedHeight(this, transformationSpec).fillMaxWidth()
                )
            }

            if (feeds.isEmpty()) {
                item {
                    Button(
                        onClick = {},
                        enabled = false,
                        label = { CompactText(stringResource(R.string.wear_no_subscriptions), maxLines = 1) },
                        secondaryLabel = { CompactText(stringResource(R.string.wear_add_on_phone_subtitle)) },
                        transformation = SurfaceTransformation(transformationSpec),
                        modifier = Modifier.transformedHeight(this, transformationSpec).fillMaxWidth()
                    )
                }
            } else {
                items(count = feeds.size) { index ->
                    val feed = feeds[index]
                    val statusText = if (feed.enabled) stringResource(R.string.wear_status_enabled) else stringResource(R.string.wear_status_paused)
                    val toggleText = if (feed.enabled) stringResource(R.string.wear_action_pause) else stringResource(R.string.wear_action_enable)
                    Button(
                        onClick = { onToggleFeed(feed) },
                        label = { CompactText(feed.title) },
                        secondaryLabel = { CompactText("$statusText · $toggleText", maxLines = 1) },
                        transformation = SurfaceTransformation(transformationSpec),
                        modifier = Modifier.transformedHeight(this, transformationSpec).fillMaxWidth()
                    )
                    Button(
                        onClick = { onRemoveFeed(feed) },
                        label = { CompactText(stringResource(R.string.wear_remove_feed, feed.title)) },
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
                    label = { CompactText(stringResource(R.string.wear_add_on_phone_title), maxLines = 1) },
                    secondaryLabel = { CompactText(stringResource(R.string.wear_phone_sync_notice)) },
                    transformation = SurfaceTransformation(transformationSpec),
                    modifier = Modifier.transformedHeight(this, transformationSpec).fillMaxWidth()
                )
            }
        }
    }
}
