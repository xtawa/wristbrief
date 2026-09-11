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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
    WristBriefMobileTheme {
        val context = LocalContext.current
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
                            androidx.compose.material3.IconButton(onClick = onOpenSettings) {
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
                            padding = padding,
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
                            padding = padding,
                            inboxRepository = inboxRepository,
                            feedManager = feedManager,
                            onManageSources = onOpenSettings,
                            onOpenArticle = onOpenArticle,
                            onPlayPodcast = onPlayPodcast,
                        )
                        MobileDestination.AiProvider -> PhoneLongSummaryDestination(
                            padding = padding,
                            initialTitle = aiPrefilledTitle,
                            initialContent = aiPrefilledContent,
                        )
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

@Composable internal fun MembershipDestination(padding: PaddingValues) {
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()
    val sessionPreferences = remember(context) { AccountSessionPreferences(context) }
    val accountClient = remember(context) { GoogleAccountAuthClient(context, sessionPreferences = sessionPreferences) }
    val membershipApi = remember(context) {
        MembershipApiClient(
            sessionProvider = sessionPreferences::read,
            gatewayBaseUrl = BuildConfig.GATEWAY_BASE_URL,
            packageName = BuildConfig.APPLICATION_ID,
        )
    }
    val accountConfigured = remember { accountAuthConfig(BuildConfig.GOOGLE_WEB_CLIENT_ID, BuildConfig.GATEWAY_BASE_URL) != null }
    var accountSession by remember { mutableStateOf(sessionPreferences.read()) }
    var accountBusy by remember { mutableStateOf(false) }
    var accountMessage by remember { mutableStateOf<String?>(null) }
    var membershipBusy by remember { mutableStateOf(false) }
    val repository = remember(context) {
        GooglePlayBillingRepository(
            context = context,
            productIds = configuredBillingProductIds(BuildConfig.BILLING_SUBSCRIPTION_PRODUCT_IDS),
        )
    }
    var billingState by remember { mutableStateOf<BillingState>(BillingState.Loading) }
    var actionMessage by remember { mutableStateOf<String?>(null) }

    DisposableEffect(repository) {
        repository.connect { billingState = it }
        onDispose { repository.close() }
    }

    val account = accountPresentation(accountConfigured, accountSession)
    val presentation = billingState.toMembershipPresentation()
    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("Membership", style = MaterialTheme.typography.headlineMedium) }
        item {
            when (account) {
                AccountPresentation.NotConfigured -> MembershipStatusCard(
                    stringResource(R.string.account_preview_title),
                    stringResource(R.string.account_preview_body),
                )
                AccountPresentation.SignedOut -> Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("WristBrief account", style = MaterialTheme.typography.titleLarge)
                        Text("Sign in with Google to bind membership and managed AI quota to your WristBrief account across devices.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(
                            enabled = !accountBusy,
                            onClick = {
                                scope.launch {
                                    accountBusy = true
                                    accountMessage = null
                                    when (val result = accountClient.signIn()) {
                                        is AccountAuthResult.Success -> {
                                            accountSession = result.session
                                            accountMessage = "Signed in."
                                        }
                                        is AccountAuthResult.Failure -> accountMessage = "Sign-in failed: ${result.code}"
                                        AccountAuthResult.SignedOut -> accountSession = null
                                    }
                                    accountBusy = false
                                }
                            },
                        ) { Text(if (accountBusy) "Signing in…" else "Sign in with Google") }
                    }
                }
                is AccountPresentation.SignedIn -> Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Signed in", style = MaterialTheme.typography.titleLarge)
                        Text("WristBrief user ${account.userId}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Session expires ${account.expiresAt}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedButton(
                            enabled = !accountBusy,
                            onClick = {
                                scope.launch {
                                    accountBusy = true
                                    accountClient.signOut()
                                    accountSession = null
                                    accountMessage = "Signed out on this device."
                                    accountBusy = false
                                }
                            },
                        ) { Text(if (accountBusy) "Signing out…" else "Sign out") }
                    }
                }
            }
        }
        accountMessage?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        item { Text("Plans and prices below come from Google Play. Final membership remains server-owned and is associated with the signed-in WristBrief account.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        actionMessage?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        when (presentation) {
            MembershipPresentation.Loading -> item { MembershipStatusCard("Loading Play products…", "Checking products and existing purchases.") }
            is MembershipPresentation.Unavailable -> item {
                MembershipStatusCard(stringResource(R.string.plans_unavailable_title), stringResource(R.string.plans_unavailable_body))
                OutlinedButton(onClick = repository::refresh) { Text("Try again") }
            }
            is MembershipPresentation.Error -> item {
                MembershipStatusCard("Could not load membership", presentation.message)
                OutlinedButton(onClick = repository::refresh) { Text("Retry") }
            }
            is MembershipPresentation.Ready -> {
                if (presentation.restoredPurchaseCount > 0 || presentation.pendingPurchaseCount > 0) item {
                    MembershipStatusCard(
                        title = "Purchase status",
                        description = buildString {
                            if (presentation.restoredPurchaseCount > 0) append("${presentation.restoredPurchaseCount} existing purchase(s) found.")
                            if (presentation.pendingPurchaseCount > 0) {
                                if (isNotEmpty()) append(" ")
                                append("${presentation.pendingPurchaseCount} pending purchase(s).")
                            }
                        },
                    )
                }
                if (presentation.products.isEmpty()) item { MembershipStatusCard("No eligible plans", "Google Play returned no purchasable subscription offers for this account/build.") }
                items(presentation.products, key = { it.productId }) { product ->
                    Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(product.title, style = MaterialTheme.typography.titleLarge)
                            if (product.description.isNotBlank()) Text(product.description, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(product.formattedPrice, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Button(
                                onClick = {
                                    if (activity == null) {
                                        actionMessage = "Purchase flow is unavailable from this context."
                                    } else {
                                        val result = repository.launchPurchase(activity, product.productId)
                                        actionMessage = when (result.responseCode) {
                                            BillingResponseCode.OK -> "Google Play purchase flow opened. After it completes, verify the purchase with WristBrief below."
                                            BillingResponseCode.USER_CANCELED -> "Purchase canceled."
                                            BillingResponseCode.ITEM_ALREADY_OWNED -> "This plan is already owned; verify it with WristBrief below."
                                            else -> "Google Play could not start the purchase flow (code ${result.responseCode})."
                                        }
                                        if (result.responseCode == BillingResponseCode.ITEM_ALREADY_OWNED) repository.refresh()
                                    }
                                },
                                enabled = account is AccountPresentation.SignedIn && !product.alreadyPurchased && activity != null,
                            ) { Text(if (product.alreadyPurchased) "Already purchased" else if (account is AccountPresentation.SignedIn) "Subscribe" else "Sign in to subscribe") }
                        }
                    }
                }
                item {
                    OutlinedButton(
                        enabled = account is AccountPresentation.SignedIn && presentation.restoredPurchaseCount > 0 && !membershipBusy,
                        onClick = {
                            val purchases = (billingState as? BillingState.Ready)?.purchases.orEmpty()
                            scope.launch {
                                membershipBusy = true
                                actionMessage = "Verifying Play purchases with WristBrief…"
                                when (val result = membershipApi.restorePurchases(purchases)) {
                                    is MembershipRestoreResult.Success -> actionMessage = "Verified ${result.restoredCount} purchase(s). Membership: ${result.plan ?: "updated"}."
                                    MembershipRestoreResult.SignedOut -> {
                                        sessionPreferences.clear()
                                        accountSession = null
                                        actionMessage = "Your WristBrief session expired. Sign in again to restore membership."
                                    }
                                    is MembershipRestoreResult.Failure -> actionMessage = "Membership restore failed: ${result.code}"
                                }
                                membershipBusy = false
                            }
                        },
                    ) { Text(if (membershipBusy) "Verifying…" else "Restore & verify membership") }
                }
                item { TextButton(onClick = { actionMessage = "Refreshing Play purchases…"; repository.refresh() }, enabled = !membershipBusy) { Text("Refresh Play purchases") } }
            }
        }
    }
}

@Composable private fun MembershipStatusCard(title: String, description: String) {
    Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun FoundationDestination(title: String, description: String, highlights: List<String>, padding: PaddingValues) { LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) { item { Text(title, style = MaterialTheme.typography.headlineMedium) }; item { Text(description, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }; items(highlights) { Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) { Text(it, Modifier.padding(20.dp), style = MaterialTheme.typography.titleMedium) } } } }
