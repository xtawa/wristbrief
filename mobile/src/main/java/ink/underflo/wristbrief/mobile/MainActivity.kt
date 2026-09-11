package ink.underflo.wristbrief.mobile

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { WristBriefMobileApp() } }
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

        if (!onboardingComplete) {
            WristBriefOnboarding { action ->
                onboarding.complete()
                onboardingAction = action.name
                if (action == OnboardingAction.AddFeed || action == OnboardingAction.ImportOpml) {
                    showSettings = true
                }
                onboardingComplete = true
            }
            return@WristBriefMobileTheme
        }

        val feedManager = remember(context) {
            MobileFeedManager(
                SharedPreferencesMobileFeedStore(context),
                HttpFeedProbe(),
                GoogleWearFeedSyncPublisher(context),
            )
        }
        val syncManager = remember(context) { PhoneItemStateSyncManager(context) }
        val inboxRepository = remember(context) {
            MobileInboxRepository(
                feedManager = feedManager,
                store = SharedPreferencesMobileInboxStore(context),
                stateAdapter = SyncManagerItemStateAdapter(syncManager),
                fetcher = HttpFeedItemFetcher(),
            )
        }

        var name by rememberSaveable { mutableStateOf(initialMobileDestination().name) }
        var selectedArticle by remember { mutableStateOf<MobileFeedItem?>(null) }
        var aiPrefilledTitle by rememberSaveable { mutableStateOf("") }
        var aiPrefilledContent by rememberSaveable { mutableStateOf("") }
        var showExpandedPlayer by rememberSaveable { mutableStateOf(false) }

        val playerController = remember(context) { MobilePodcastPlayerController(context) }
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

        if (selectedArticle != null) {
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
                aiPrefilledTitle = aiPrefilledTitle,
                aiPrefilledContent = aiPrefilledContent,
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
    aiPrefilledTitle: String,
    aiPrefilledContent: String,
) {
    BoxWithConstraints {
        val useRail = maxWidth >= 600.dp
        Row(Modifier.fillMaxSize()) {
            if (useRail) NavigationRail(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                MobileDestination.entries.forEach { item ->
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
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                topBar = {
                    LargeTopAppBar(
                        colors = TopAppBarDefaults.largeTopAppBarColors(
                            containerColor = Color.Transparent,
                            titleContentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                        title = {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    "WristBrief",
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    destination.localizedLabel(),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
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
                            NavigationBar(
                                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                tonalElevation = 0.dp,
                            ) {
                                MobileDestination.entries.forEach { item ->
                                    val selected = item == destination
                                    NavigationBarItem(
                                        selected = selected,
                                        onClick = { select(item) },
                                        icon = {
                                            Surface(
                                                shape = CircleShape,
                                                color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                                                contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                            ) {
                                                DestinationIcon(item, Modifier.padding(8.dp))
                                            }
                                        },
                                        label = { Text(item.localizedLabel()) },
                                    )
                                }
                            }
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
                                )
                                MobileDestination.Library -> LibraryDestination(
                                    padding = PaddingValues(0.dp),
                                    inboxRepository = inboxRepository,
                                    feedManager = feedManager,
                                    onManageSources = onOpenSettings,
                                    onOpenArticle = onOpenArticle,
                                    onPlayPodcast = onPlayPodcast,
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
private fun MobileDestination.localizedLabel(): String = stringResource(
    when (this) {
        MobileDestination.Today -> R.string.nav_today
        MobileDestination.Library -> R.string.nav_library
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
            MobileDestination.Library -> {
                drawCircle(color, 2.5.dp.toPx(), androidx.compose.ui.geometry.Offset(5.dp.toPx(), 19.dp.toPx()))
                drawArc(color, 270f, 90f, false, androidx.compose.ui.geometry.Offset(4.dp.toPx(), 9.dp.toPx()), androidx.compose.ui.geometry.Size(11.dp.toPx(), 11.dp.toPx()), style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
                drawArc(color, 270f, 90f, false, androidx.compose.ui.geometry.Offset(4.dp.toPx(), 4.dp.toPx()), androidx.compose.ui.geometry.Size(16.dp.toPx(), 16.dp.toPx()), style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
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
