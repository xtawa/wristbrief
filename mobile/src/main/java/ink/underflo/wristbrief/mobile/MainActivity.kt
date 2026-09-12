package ink.underflo.wristbrief.mobile

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import ink.underflo.wristbrief.mobile.db.LegacyDataMigration
import ink.underflo.wristbrief.mobile.db.SqliteMobileFeedStore
import ink.underflo.wristbrief.mobile.db.SqliteMobileInboxStore
import ink.underflo.wristbrief.mobile.db.SqlitePodcastProgressStore
import ink.underflo.wristbrief.mobile.db.WristBriefDatabaseHelper
import ink.underflo.wristbrief.mobile.artifacts.DefaultTranscriptRepository
import ink.underflo.wristbrief.mobile.artifacts.EpisodeTranscriptRequest
import ink.underflo.wristbrief.mobile.artifacts.HttpTranscriptGatewayApi
import ink.underflo.wristbrief.mobile.artifacts.TranscriptCacheStore
import ink.underflo.wristbrief.mobile.artifacts.TranscriptFetchResult
import ink.underflo.wristbrief.mobile.artifacts.TranscriptViewerDestination
import ink.underflo.wristbrief.mobile.media.PodcastProgressStore
import ink.underflo.wristbrief.mobile.navigation.MobileBackHandler
import ink.underflo.wristbrief.mobile.sync.CloudSyncCoordinator
import ink.underflo.wristbrief.mobile.sync.CloudSyncMerge
import ink.underflo.wristbrief.mobile.sync.CloudSyncOutboxStore
import ink.underflo.wristbrief.mobile.sync.CloudSyncRuntime
import ink.underflo.wristbrief.mobile.sync.HttpCloudSyncApi
import ink.underflo.wristbrief.mobile.sync.SharedPreferencesCloudSyncPreferences
import ink.underflo.wristbrief.mobile.sync.WearSyncNotifier
import ink.underflo.wristbrief.mobile.ui.glass.GlassSurface
import androidx.compose.ui.text.style.TextAlign
import android.content.Context
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.CompositionLocalProvider
import ink.underflo.wristbrief.mobile.ui.glass.GlassBottomBar
import ink.underflo.wristbrief.mobile.ui.glass.GlassTokens
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.android.billingclient.api.BillingClient.BillingResponseCode
import ink.underflo.wristbrief.mobile.media.MobilePodcastPlayerController
import ink.underflo.wristbrief.mobile.media.PodcastExpandedSheet
import ink.underflo.wristbrief.mobile.media.PodcastMiniPlayer
import ink.underflo.wristbrief.mobile.media.PodcastPlaybackRequest
import ink.underflo.wristbrief.mobile.media.PodcastPlayerState
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { WristBriefMobileApp() } }
}

/**
 * Late-bound subscription lifecycle hooks: the feed manager is constructed before
 * the inbox repository, so the bindings are assigned after both exist.
 */
private class MobileFeedListenerBindings : MobileFeedListener {
    var onAdded: (MobileFeedSubscription) -> Unit = {}
    var onUpdated: (MobileFeedSubscription) -> Unit = {}
    var onRemoved: (MobileFeedSubscription) -> Unit = {}
    override fun onSubscriptionAdded(feed: MobileFeedSubscription) = onAdded(feed)
    override fun onSubscriptionUpdated(feed: MobileFeedSubscription) = onUpdated(feed)
    override fun onSubscriptionRemoved(feed: MobileFeedSubscription) = onRemoved(feed)
}

@Composable fun WristBriefMobileApp() {
    val context = LocalContext.current
    val appPreferences = remember(context) { AppPreferences(context) }
    var themeMode by remember { mutableStateOf(appPreferences.getThemeMode()) }

    WristBriefMobileTheme(themeMode = themeMode) {
        val onboarding = remember(context) { OnboardingPreferences(context) }
        var onboardingComplete by rememberSaveable { mutableStateOf(onboarding.isComplete()) }
        var onboardingAction by rememberSaveable { mutableStateOf<String?>(null) }
        var showSettings by rememberSaveable { mutableStateOf(false) }

        val dbHelper = remember(context) { WristBriefDatabaseHelper(context) }
        val sqliteFeedStore = remember(dbHelper) { SqliteMobileFeedStore(dbHelper) }
        val sqliteInboxStore = remember(dbHelper) { SqliteMobileInboxStore(dbHelper) }
        val sqlitePodcastStore = remember(dbHelper) { SqlitePodcastProgressStore(dbHelper) }
        val accountSessionPreferences = remember(context) { AccountSessionPreferences(context) }

        LaunchedEffect(Unit) {
            LegacyDataMigration.performIfNeeded(context, sqliteFeedStore, sqliteInboxStore, sqlitePodcastStore)
        }

        val syncManager = remember(context) { PhoneItemStateSyncManager(context) }

        // Cloud sync runtime: the coordinator (push outbox + pull + merge) that
        // existed but was never wired into the app. Cycles start on app start,
        // ON_RESUME, and after every local mutation; signed-out users never
        // start a cycle. Failures leave mutations in the outbox with backoff.
        val cloudSyncOutboxStore = remember(dbHelper) { CloudSyncOutboxStore(dbHelper) }
        val cloudSyncRuntime = remember(context, dbHelper, sqliteFeedStore, cloudSyncOutboxStore, accountSessionPreferences) {
            val wearFeedPublisher = GoogleWearFeedSyncPublisher(context)
            CloudSyncRuntime(
                coordinator = CloudSyncCoordinator(
                    api = HttpCloudSyncApi(BuildConfig.GATEWAY_BASE_URL),
                    outbox = cloudSyncOutboxStore,
                    merge = CloudSyncMerge(dbHelper),
                    preferences = SharedPreferencesCloudSyncPreferences(context),
                    wearPublisher = WearSyncNotifier {
                        runCatching { wearFeedPublisher.publish(sqliteFeedStore.load()) }
                    },
                ),
                sessionTokenProvider = { accountSessionPreferences.read()?.sessionToken },
            )
        }
        DisposableEffect(cloudSyncRuntime) {
            onDispose { cloudSyncRuntime.dispose() }
        }

        // Subscription lifecycle: additions/removals enqueue cloud mutations and
        // refresh the inbox; deletions also clear the feed's cached items
        // (local cleanup only — the cloud tombstone never deletes shared content).
        val appScope = remember { kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default) }
        DisposableEffect(appScope) {
            onDispose { appScope.cancel() }
        }
        val feedListener = remember { MobileFeedListenerBindings() }
        val feedManager = remember(context, sqliteFeedStore, cloudSyncOutboxStore, feedListener) {
            MobileFeedManager(
                sqliteFeedStore,
                HttpFeedProbe(),
                GoogleWearFeedSyncPublisher(context),
                cloudOutbox = cloudSyncOutboxStore,
                listener = feedListener,
            )
        }
        val inboxRepository = remember(context, feedManager, sqliteInboxStore, cloudSyncOutboxStore, cloudSyncRuntime) {
            MobileInboxRepository(
                feedManager = feedManager,
                store = sqliteInboxStore,
                stateAdapter = SyncManagerItemStateAdapter(
                    syncManager,
                    cloudOutbox = cloudSyncOutboxStore,
                    onCloudMutation = { cloudSyncRuntime.requestSync() },
                ),
                fetcher = HttpFeedItemFetcher(),
            )
        }
        feedListener.onAdded = {
            cloudSyncRuntime.requestSync()
            appScope.launch { runCatching { inboxRepository.refresh() } }
        }
        feedListener.onUpdated = { cloudSyncRuntime.requestSync() }
        feedListener.onRemoved = { feed ->
            cloudSyncRuntime.requestSync()
            sqliteInboxStore.deleteItemsForFeed(feed.id)
            appScope.launch { runCatching { inboxRepository.refresh() } }
        }

        if (!onboardingComplete) {
            var feedCount by remember { mutableStateOf(feedManager.feeds().size) }
            var itemCount by remember { mutableStateOf(inboxRepository.items().size) }
            WristBriefOnboarding(
                feedCount = feedCount,
                itemCount = itemCount,
                onAddSampleFeed = { sample ->
                    val result = feedManager.add(sample.url, sample.title, sample.category)
                    if (result is FeedMutationResult.Success) {
                        feedCount = result.feeds.size
                    }
                },
                onComplete = { action ->
                    onboarding.complete()
                    onboardingAction = action.name
                    if (action == OnboardingAction.AddFeed || action == OnboardingAction.ImportOpml) {
                        showSettings = true
                    }
                    onboardingComplete = true
                },
            )
            return@WristBriefMobileTheme
        }

        val transcriptCache = remember(dbHelper) { TranscriptCacheStore(dbHelper) }
        val transcriptGateway = remember(context, accountSessionPreferences) {
            HttpTranscriptGatewayApi(BuildConfig.GATEWAY_BASE_URL) {
                accountSessionPreferences.read()?.sessionToken
            }
        }
        val transcriptRepo = remember(transcriptCache, transcriptGateway) {
            DefaultTranscriptRepository(transcriptCache, transcriptGateway)
        }

        // Full-text reader: the media proxy is authenticated, so article images
        // load through a dedicated Coil ImageLoader that attaches the session.
        val articleRepository = remember(context, accountSessionPreferences) {
            ink.underflo.wristbrief.mobile.articles.ArticleRepository(context, BuildConfig.GATEWAY_BASE_URL) {
                accountSessionPreferences.read()?.sessionToken
            }
        }
        val articleImageLoader = remember(context, accountSessionPreferences) {
            val authedClient = okhttp3.OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val token = accountSessionPreferences.read()?.sessionToken
                    val request = if (token != null) {
                        chain.request().newBuilder().header("Authorization", "Bearer $token").build()
                    } else {
                        chain.request()
                    }
                    chain.proceed(request)
                }
                .build()
            coil.ImageLoader.Builder(context)
                .okHttpClient(authedClient)
                .crossfade(true)
                .build()
        }

        // Cloud sync triggers: app start and every return to the foreground.
        val lifecycleOwner = LocalLifecycleOwner.current
        LaunchedEffect(cloudSyncRuntime) {
            cloudSyncRuntime.requestSync()
        }
        DisposableEffect(lifecycleOwner, cloudSyncRuntime) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) cloudSyncRuntime.requestSync()
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }

        var activeTranscriptRequest by remember { mutableStateOf<EpisodeTranscriptRequest?>(null) }
        var activeTranscriptState by remember { mutableStateOf<TranscriptFetchResult?>(null) }

        LaunchedEffect(activeTranscriptRequest) {
            val req = activeTranscriptRequest ?: return@LaunchedEffect
            val cached = transcriptRepo.getCached(req.audioUrl)
            if (cached != null) {
                activeTranscriptState = TranscriptFetchResult.Ready(
                    contentCode = cached.contentCode,
                    artifactId = "",
                    source = "cache",
                    quota = ink.underflo.wristbrief.mobile.artifacts.TranscriptQuotaInfo(0, 0f, 0f),
                    payload = cached,
                )
                return@LaunchedEffect
            }
            val initial = transcriptRepo.fetchTranscript(req)
            activeTranscriptState = initial
            if (initial is TranscriptFetchResult.Processing) {
                val polled = transcriptRepo.pollUntilReady(initial.jobId)
                activeTranscriptState = polled
            }
        }

        fun openTranscriptForEpisode(audioUrl: String, title: String?, feedUrl: String? = null, guid: String? = null) {
            activeTranscriptRequest = EpisodeTranscriptRequest(
                audioUrl = audioUrl,
                feedUrl = feedUrl,
                guid = guid,
                title = title,
            )
            activeTranscriptState = TranscriptFetchResult.Processing(contentCode = "", jobId = "")
        }

        var name by rememberSaveable { mutableStateOf(initialMobileDestination().name) }
        // Normalize stale saved selections (e.g. the retired Now Playing tab).
        if (MobileDestination.valueOf(name) == MobileDestination.NowPlaying) name = MobileDestination.Today.name
        var selectedArticle by remember { mutableStateOf<MobileFeedItem?>(null) }
        var aiPrefilledTitle by rememberSaveable { mutableStateOf("") }
        var aiPrefilledContent by rememberSaveable { mutableStateOf("") }
        var showExpandedPlayer by rememberSaveable { mutableStateOf(false) }

        val playerController = remember(context, sqlitePodcastStore, appPreferences) {
            MobilePodcastPlayerController(context, sqlitePodcastStore, appPreferences)
        }
        DisposableEffect(playerController) {
            onDispose { playerController.release() }
        }
        val playerState by playerController.state.collectAsState()

        fun playPodcast(item: MobileFeedItem) {
            val audioUrl = item.audioUrl ?: item.link ?: return
            if (!audioUrl.startsWith("https://", ignoreCase = true)) {
                runCatching {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(audioUrl))
                    context.startActivity(intent)
                }
                return
            }
            playerController.play(
                PodcastPlaybackRequest(
                    id = item.id,
                    title = item.title,
                    audioUrl = audioUrl,
                    feedTitle = item.feedTitle,
                )
            )
        }

        // System back / gesture back is arbitrated by MobileBackPolicy; the snapshot
        // below is derived from the same state that drives rendering (no duplicates).
        // Back never touches the account session, the playback service, or the
        // Wear sync paths — it only maps to the navigation states captured here.
        var exitHintShownAtMs by remember { mutableStateOf<Long?>(null) }
        MobileBackHandler(
            hasActiveDialog = false, // destination-owned dialogs install their own inner BackHandler
            expandedPlayerVisible = showExpandedPlayer,
            transcriptViewerVisible = activeTranscriptState != null,
            articleDetailVisible = selectedArticle != null,
            settingsVisible = showSettings,
            exitHintShownAtEpochMs = exitHintShownAtMs,
            onExitHintChanged = { exitHintShownAtMs = it },
            onDismissExpandedPlayer = { showExpandedPlayer = false },
            onCloseTranscriptViewer = {
                activeTranscriptRequest = null
                activeTranscriptState = null
            },
            onCloseArticleDetail = { selectedArticle = null },
            onCloseSettings = { showSettings = false },
        )

        if (activeTranscriptState != null) {
            TranscriptViewerDestination(
                state = activeTranscriptState!!,
                onBack = {
                    activeTranscriptRequest = null
                    activeTranscriptState = null
                },
                onSeekToMs = { ms ->
                    playerController.seekTo(ms)
                },
            )
        } else if (selectedArticle != null) {
            ArticleDetailDestination(
                item = selectedArticle!!,
                inboxRepository = inboxRepository,
                onBack = { selectedArticle = null },
                onAskAi = { title, content ->
                    selectedArticle = null
                    aiPrefilledTitle = title
                    aiPrefilledContent = content
                    name = MobileDestination.AiProvider.name
                },
                playerState = playerState,
                onPlayPodcast = ::playPodcast,
                playerController = playerController,
                onOpenTranscript = { item ->
                    openTranscriptForEpisode(
                        audioUrl = item.audioUrl ?: item.link ?: "",
                        title = item.title,
                        feedUrl = null,
                        guid = item.id,
                    )
                },
                articleRepository = articleRepository,
                articleImageLoader = articleImageLoader,
            )
        } else if (showSettings) {
            SettingsDestination(
                onBack = { showSettings = false },
                onReplayOnboarding = {
                    onboarding.reset()
                    showSettings = false
                    onboardingComplete = false
                },
                onboardingAction = onboardingAction?.let(OnboardingAction::valueOf),
                onOnboardingActionConsumed = { onboardingAction = null },
                appPreferences = appPreferences,
                onThemeChanged = { themeMode = it },
                feedManager = feedManager,
            )
        } else {
            MobileShell(
                destination = MobileDestination.valueOf(name),
                select = { name = it.name },
                inboxRepository = inboxRepository,
                feedManager = feedManager,
                onOpenSettings = { showSettings = true },
                onAddFeed = { showSettings = true },
                onImportOpml = { showSettings = true },
                onOpenArticle = { selectedArticle = it },
                onPlayPodcast = ::playPodcast,
                playerState = playerState,
                playerController = playerController,
                onExpandPlayer = { showExpandedPlayer = true },
                onOpenTranscript = { ep ->
                    openTranscriptForEpisode(
                        audioUrl = ep.audioUrl,
                        title = ep.title,
                    )
                },
                aiPrefilledTitle = aiPrefilledTitle,
                aiPrefilledContent = aiPrefilledContent,
                progressStore = sqlitePodcastStore,
                darkTheme = resolveDarkTheme(themeMode, isSystemInDarkTheme()),
            )
        }

        if (showExpandedPlayer) {
            PodcastExpandedSheet(
                state = playerState,
                onDismissRequest = { showExpandedPlayer = false },
                onPlayPause = {
                    if (playerState.isPlaying) playerController.pause()
                    else playerController.resume()
                },
                onSeekTo = { playerController.seekTo(it) },
                onSeekBy = { playerController.seekBy(it) },
                onCycleSpeed = { playerController.cycleSpeed() },
                onOpenTranscript = {
                    val ep = playerState.currentEpisode
                    if (ep != null) {
                        showExpandedPlayer = false
                        openTranscriptForEpisode(
                            audioUrl = ep.audioUrl,
                            title = ep.title,
                        )
                    }
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun MobileShell(
    destination: MobileDestination,
    select: (MobileDestination) -> Unit,
    inboxRepository: MobileInboxRepository,
    feedManager: MobileFeedManager,
    onOpenSettings: () -> Unit,
    onAddFeed: () -> Unit,
    onImportOpml: () -> Unit,
    onOpenArticle: (MobileFeedItem) -> Unit,
    onPlayPodcast: (MobileFeedItem) -> Unit,
    playerState: PodcastPlayerState,
    playerController: MobilePodcastPlayerController,
    onExpandPlayer: () -> Unit,
    onOpenTranscript: (PodcastPlaybackRequest) -> Unit = {},
    aiPrefilledTitle: String,
    aiPrefilledContent: String,
    progressStore: PodcastProgressStore? = null,
    darkTheme: Boolean = isSystemInDarkTheme(),
) {
    BoxWithConstraints {
        val useRail = maxWidth >= 600.dp
        // Bottom bar / rail show the four primary destinations per uidocs;
        // playback is reached through the mini/expanded player surfaces.
        val destinations = MobileDestination.entries.filter { it.showsInBottomBar }
        Row(Modifier.fillMaxSize()) {
            if (useRail) NavigationRail(
                containerColor = GlassTokens.surfaceGlass(darkTheme),
                contentColor = GlassTokens.textPrimary(darkTheme),
            ) {
                destinations.forEach { item ->
                    NavigationRailItem(
                        selected = item == destination,
                        onClick = { select(item) },
                        icon = { DestinationIcon(item) },
                        label = { Text(item.localizedLabel()) },
                    )
                }
            }
            Scaffold(
                modifier = Modifier.weight(1f),
                containerColor = GlassTokens.canvas(darkTheme),
                topBar = {
                    LargeTopAppBar(
                        colors = TopAppBarDefaults.largeTopAppBarColors(
                            containerColor = Color.Transparent,
                            titleContentColor = GlassTokens.textPrimary(darkTheme),
                        ),
                        title = {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    "WristBrief",
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = GlassTokens.textPrimary(darkTheme),
                                )
                                Text(
                                    destination.localizedLabel(),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = GlassTokens.textSecondary(darkTheme),
                                )
                            }
                        },
                        actions = {
                            val settingsDesc = stringResource(R.string.nav_settings)
                            androidx.compose.material3.IconButton(
                                onClick = onOpenSettings,
                                modifier = Modifier.semantics { contentDescription = settingsDesc },
                            ) {
                                SettingsIcon()
                            }
                        },
                    )
                },
                bottomBar = {
                    Column {
                        if (playerState.isVisible) {
                            PodcastMiniPlayer(
                                state = playerState,
                                onExpand = onExpandPlayer,
                                onPlayPause = {
                                    if (playerState.isPlaying) playerController.pause()
                                    else playerController.resume()
                                },
                                onDismiss = { playerController.dismiss() },
                            )
                        }
                        if (!useRail) {
                            val selectedIndex = destinations.indexOf(destination)
                            GlassBottomBar(
                                items = destinations.map { item ->
                                    Pair(item.localizedLabel()) { selected ->
                                        CompositionLocalProvider(
                                            androidx.compose.material3.LocalContentColor provides if (selected) GlassTokens.onControlSelected(darkTheme) else GlassTokens.textSecondary(darkTheme),
                                        ) {
                                            DestinationIcon(item)
                                        }
                                    }
                                },
                                selectedIndex = selectedIndex,
                                onSelect = { index -> select(destinations[index]) },
                                darkTheme = darkTheme,
                            )
                        }
                    }
                },
            ) { padding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .widthIn(max = 840.dp),
                    ) {
                        AnimatedContent(
                            targetState = destination,
                            transitionSpec = {
                                val motion = spring<Float>(stiffness = Spring.StiffnessMediumLow)
                                val slide = spring<IntOffset>(stiffness = Spring.StiffnessMediumLow)
                                (fadeIn(motion) + slideInHorizontally(slide) { it / 8 }) togetherWith fadeOut(motion)
                            },
                            label = "mobile-destination",
                        ) { currentDestination ->
                            when (currentDestination) {
                                MobileDestination.Today -> TodayDestination(
                                    padding = PaddingValues(0.dp),
                                    inboxRepository = inboxRepository,
                                    feedManager = feedManager,
                                    onAddFeed = onAddFeed,
                                    onImportOpml = onImportOpml,
                                    onOpenAskAi = { select(MobileDestination.AiProvider) },
                                    onOpenLibrary = { select(MobileDestination.Library) },
                                    onOpenSettings = onOpenSettings,
                                    onOpenArticle = onOpenArticle,
                                    onPlayPodcast = onPlayPodcast,
                                    progressStore = progressStore,
                                    darkTheme = darkTheme,
                                )
                                MobileDestination.Explore -> ExploreDestination(
                                    padding = PaddingValues(0.dp),
                                    feedManager = feedManager,
                                    darkTheme = darkTheme,
                                )
                                MobileDestination.Library -> LibraryDestination(
                                    padding = PaddingValues(0.dp),
                                    inboxRepository = inboxRepository,
                                    feedManager = feedManager,
                                    onManageSources = onOpenSettings,
                                    onOpenArticle = onOpenArticle,
                                    onPlayPodcast = onPlayPodcast,
                                    darkTheme = darkTheme,
                                )
                                MobileDestination.NowPlaying -> NowPlayingDestination(
                                    padding = PaddingValues(0.dp),
                                    playerState = playerState,
                                    playerController = playerController,
                                    onOpenTranscript = onOpenTranscript,
                                    onOpenLibrary = { select(MobileDestination.Library) },
                                    darkTheme = darkTheme,
                                )
                                MobileDestination.AiProvider -> PhoneLongSummaryDestination(
                                    padding = PaddingValues(0.dp),
                                    initialTitle = aiPrefilledTitle,
                                    initialContent = aiPrefilledContent,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExploreDestination(
    padding: PaddingValues,
    feedManager: MobileFeedManager,
    darkTheme: Boolean,
) {
    val scope = rememberCoroutineScope()
    var addedIds by remember { mutableStateOf(setOf<String>()) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text(
                text = stringResource(R.string.nav_explore),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = GlassTokens.textPrimary(darkTheme),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Discover high-signal sources curated for calm reading and listening.",
                style = MaterialTheme.typography.bodyMedium,
                color = GlassTokens.textSecondary(darkTheme),
            )
        }

        item {
            Text(
                text = stringResource(R.string.sample_feeds_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = GlassTokens.textPrimary(darkTheme),
            )
        }

        items(SampleFeeds.curatedFeeds) { feed ->
            val isAdded = feed.id in addedIds
            GlassSurface(
                modifier = Modifier.fillMaxWidth(),
                darkTheme = darkTheme,
                cornerRadius = GlassTokens.CardRadius,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = feed.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = GlassTokens.textPrimary(darkTheme),
                        )
                        Text(
                            text = feed.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = GlassTokens.textSecondary(darkTheme),
                        )
                        Text(
                            text = if (feed.isPodcast) "Podcast · ${feed.category}" else "RSS · ${feed.category}",
                            style = MaterialTheme.typography.labelSmall,
                            color = GlassTokens.textSecondary(darkTheme),
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val res = feedManager.add(feed.url, feed.title, feed.category)
                                if (res is FeedMutationResult.Success) {
                                    addedIds = addedIds + feed.id
                                }
                            }
                        },
                        enabled = !isAdded,
                    ) {
                        Text(if (isAdded) "✓ Subscribed" else "+ Subscribe")
                    }
                }
            }
        }
    }
}

@Composable
private fun NowPlayingDestination(
    padding: PaddingValues,
    playerState: PodcastPlayerState,
    playerController: MobilePodcastPlayerController,
    onOpenTranscript: (PodcastPlaybackRequest) -> Unit,
    onOpenLibrary: () -> Unit,
    darkTheme: Boolean,
) {
    val episode = playerState.currentEpisode
    if (episode == null) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(R.string.nav_now_playing),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = GlassTokens.textPrimary(darkTheme),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "No podcast episode is currently playing.",
                style = MaterialTheme.typography.bodyLarge,
                color = GlassTokens.textSecondary(darkTheme),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            Button(onClick = onOpenLibrary) {
                Text(stringResource(R.string.today_view_all))
            }
        }
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = episode.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = GlassTokens.textPrimary(darkTheme),
                textAlign = TextAlign.Center,
            )
            if (episode.feedTitle.isNotBlank()) {
                Text(
                    text = episode.feedTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = GlassTokens.textSecondary(darkTheme),
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = {
                        if (playerState.isPlaying) playerController.pause()
                        else playerController.resume()
                    }
                ) {
                    Text(if (playerState.isPlaying) stringResource(R.string.podcast_pause) else stringResource(R.string.podcast_play))
                }
                OutlinedButton(onClick = { onOpenTranscript(episode) }) {
                    Text(stringResource(R.string.transcript_title))
                }
            }
        }
    }
}

@Composable
private fun MobileDestination.localizedLabel(): String = stringResource(
    when (this) {
        MobileDestination.Today -> R.string.nav_today
        MobileDestination.Explore -> R.string.nav_explore
        MobileDestination.Library -> R.string.nav_library
        MobileDestination.NowPlaying -> R.string.nav_now_playing
        MobileDestination.AiProvider -> R.string.nav_ai
    },
)

@Composable
private fun DestinationIcon(destination: MobileDestination, modifier: Modifier = Modifier) {
    val color = androidx.compose.material3.LocalContentColor.current
    val cutout = MaterialTheme.colorScheme.surface
    Canvas(modifier.size(24.dp)) {
        val stroke = 2.2.dp.toPx()
        when (destination) {
            MobileDestination.Today -> {
                drawCircle(color, 5.dp.toPx())
                repeat(8) { i ->
                    val angle = Math.toRadians((i * 45).toDouble())
                    val inner = 7.5.dp.toPx()
                    val outer = 10.dp.toPx()
                    drawLine(
                        color,
                        androidx.compose.ui.geometry.Offset(center.x + kotlin.math.cos(angle).toFloat() * inner, center.y + kotlin.math.sin(angle).toFloat() * inner),
                        androidx.compose.ui.geometry.Offset(center.x + kotlin.math.cos(angle).toFloat() * outer, center.y + kotlin.math.sin(angle).toFloat() * outer),
                        stroke,
                    )
                }
            }
            MobileDestination.Explore -> {
                drawCircle(color, 9.dp.toPx(), style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
                drawLine(
                    color,
                    androidx.compose.ui.geometry.Offset(center.x - 4.dp.toPx(), center.y + 4.dp.toPx()),
                    androidx.compose.ui.geometry.Offset(center.x + 4.dp.toPx(), center.y - 4.dp.toPx()),
                    stroke,
                )
            }
            MobileDestination.Library -> {
                drawCircle(color, 2.5.dp.toPx(), androidx.compose.ui.geometry.Offset(5.dp.toPx(), 19.dp.toPx()))
                drawArc(color, 270f, 90f, false, androidx.compose.ui.geometry.Offset(4.dp.toPx(), 9.dp.toPx()), androidx.compose.ui.geometry.Size(11.dp.toPx(), 11.dp.toPx()), style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
                drawArc(color, 270f, 90f, false, androidx.compose.ui.geometry.Offset(4.dp.toPx(), 4.dp.toPx()), androidx.compose.ui.geometry.Size(16.dp.toPx(), 16.dp.toPx()), style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
            }
            MobileDestination.NowPlaying -> {
                drawLine(color, androidx.compose.ui.geometry.Offset(center.x - 7.dp.toPx(), center.y - 4.dp.toPx()), androidx.compose.ui.geometry.Offset(center.x - 7.dp.toPx(), center.y + 4.dp.toPx()), stroke)
                drawLine(color, androidx.compose.ui.geometry.Offset(center.x, center.y - 9.dp.toPx()), androidx.compose.ui.geometry.Offset(center.x, center.y + 9.dp.toPx()), stroke)
                drawLine(color, androidx.compose.ui.geometry.Offset(center.x + 7.dp.toPx(), center.y - 5.dp.toPx()), androidx.compose.ui.geometry.Offset(center.x + 7.dp.toPx(), center.y + 5.dp.toPx()), stroke)
            }
            MobileDestination.AiProvider -> {
                drawCircle(color, 8.dp.toPx())
                drawLine(cutout, androidx.compose.ui.geometry.Offset(9.dp.toPx(), 12.dp.toPx()), androidx.compose.ui.geometry.Offset(15.dp.toPx(), 12.dp.toPx()), stroke)
                drawLine(cutout, androidx.compose.ui.geometry.Offset(12.dp.toPx(), 9.dp.toPx()), androidx.compose.ui.geometry.Offset(12.dp.toPx(), 15.dp.toPx()), stroke)
            }
        }
    }
}

@Composable
private fun SettingsIcon(modifier: Modifier = Modifier) {
    val color = androidx.compose.material3.LocalContentColor.current
    Canvas(modifier.size(24.dp)) {
        val stroke = 2.dp.toPx()
        drawCircle(color, 4.dp.toPx(), style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
        repeat(6) { i ->
            val angle = Math.toRadians((i * 60).toDouble())
            val inner = 6.dp.toPx()
            val outer = 9.dp.toPx()
            drawLine(
                color,
                androidx.compose.ui.geometry.Offset(center.x + kotlin.math.cos(angle).toFloat() * inner, center.y + kotlin.math.sin(angle).toFloat() * inner),
                androidx.compose.ui.geometry.Offset(center.x + kotlin.math.cos(angle).toFloat() * outer, center.y + kotlin.math.sin(angle).toFloat() * outer),
                stroke,
            )
        }
    }
}



@Composable private fun FoundationDestination(title: String, description: String, highlights: List<String>, padding: PaddingValues) { LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) { item { Text(title, style = MaterialTheme.typography.headlineMedium) }; item { Text(description, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }; items(highlights) { Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) { Text(it, Modifier.padding(20.dp), style = MaterialTheme.typography.titleMedium) } } } }
