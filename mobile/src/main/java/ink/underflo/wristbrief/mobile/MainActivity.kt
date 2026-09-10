package ink.underflo.wristbrief.mobile

import android.app.Activity
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.android.billingclient.api.BillingClient.BillingResponseCode
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { WristBriefMobileApp() } }
}

@Composable fun WristBriefMobileApp() {
    val context = LocalContext.current
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val colors = when { Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(context); Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(context); dark -> androidx.compose.material3.darkColorScheme(); else -> androidx.compose.material3.lightColorScheme() }
    MaterialTheme(colorScheme = colors) {
        var name by rememberSaveable { mutableStateOf(initialMobileDestination().name) }
        MobileShell(MobileDestination.valueOf(name), { name = it.name })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun MobileShell(destination: MobileDestination, select: (MobileDestination) -> Unit) {
    Scaffold(topBar = { LargeTopAppBar(title = { Column { Text("WristBrief", fontWeight = FontWeight.SemiBold); Text(destination.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) } }) }, bottomBar = { NavigationBar { MobileDestination.entries.forEach { item -> NavigationBarItem(selected = item == destination, onClick = { select(item) }, icon = { Surface(shape = MaterialTheme.shapes.large, color = if (item == destination) MaterialTheme.colorScheme.primaryContainer else Color.Transparent) { Text(item.shortLabel, Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium) } }, label = { Text(item.label) }) } } }) { padding ->
        when (destination) {
            MobileDestination.Feeds -> CategorizedFeedManagementDestination(padding)
            MobileDestination.AiProvider -> PhoneLongSummaryDestination(padding)
            MobileDestination.Membership -> MembershipDestination(padding)
        }
    }
}

@Composable private fun FeedManagementDestination(padding: PaddingValues) {
    val context = LocalContext.current
    val manager = remember { MobileFeedManager(SharedPreferencesMobileFeedStore(context), HttpFeedProbe(), GoogleWearFeedSyncPublisher(context)) }
    val scope = rememberCoroutineScope()
    var feeds by remember { mutableStateOf(manager.feeds()) }
    var editing by remember { mutableStateOf<MobileFeedSubscription?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Add feeds here; choose separately which subscriptions are active and sent to Wear.") }
    var busy by remember { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("Feed management", style = MaterialTheme.typography.headlineMedium) }
        item { Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { Button(onClick = { editing = null; showEditor = true }, enabled = !busy) { Text("Add feed") } }
        item {
            OpmlManagementActions(
                manager = manager,
                busy = busy,
                onBusyChange = { busy = it },
                onFeedsChanged = { feeds = it },
                onStatus = { status = it },
            )
        }
        if (feeds.isEmpty()) item { Text("No phone-managed feeds yet.", style = MaterialTheme.typography.bodyLarge) }
        items(feeds, key = { it.id }) { feed ->
            Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(feed.title, style = MaterialTheme.typography.titleLarge)
                    Text(feed.url, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Subscription active")
                            Text(if (feed.enabled) "Included in refreshes" else "Paused on synced devices", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = feed.enabled, onCheckedChange = { enabled ->
                            val r = manager.setEnabled(feed.id, enabled)
                            if (r is FeedMutationResult.Success) feeds = r.feeds
                        })
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Send to watch")
                            Text(if (feed.sendToWatch) "Available on paired Wear devices" else "Keep on phone only", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = feed.sendToWatch, onCheckedChange = { sendToWatch ->
                            val r = manager.setSendToWatch(feed.id, sendToWatch)
                            if (r is FeedMutationResult.Success) {
                                feeds = r.feeds
                                status = if (sendToWatch) "Feed queued for Wear sync." else "Feed kept on phone only."
                            }
                        })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { editing = feed; showEditor = true }) { Text("Edit") }
                        TextButton(onClick = { val r = manager.remove(feed.id); if (r is FeedMutationResult.Success) feeds = r.feeds }) { Text("Remove") }
                    }
                }
            }
        }
    }

    if (showEditor) FeedEditorDialog(editing, busy, onDismiss = { if (!busy) showEditor = false }, onSave = { url, title -> scope.launch { busy = true; status = "Validating feed…"; val r = if (editing == null) manager.add(url, title) else manager.update(editing!!.id, url, title); busy = false; when (r) { is FeedMutationResult.Success -> { feeds = r.feeds; status = "Saved and queued for Wear sync."; showEditor = false }; is FeedMutationResult.Error -> status = r.message } } })
}

@Composable private fun FeedEditorDialog(feed: MobileFeedSubscription?, busy: Boolean, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var url by remember(feed?.id) { mutableStateOf(feed?.url.orEmpty()) }; var title by remember(feed?.id) { mutableStateOf(feed?.title.orEmpty()) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (feed == null) "Add feed" else "Edit feed") }, text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), label = { Text("HTTPS feed URL") }, singleLine = true); OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text("Name (optional)") }, singleLine = true) } }, confirmButton = { Button(onClick = { onSave(url, title) }, enabled = !busy) { Text(if (busy) "Validating…" else "Save") } }, dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } })
}

@Composable private fun MembershipDestination(padding: PaddingValues) {
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
                    "Google account not configured",
                    "Set the production Google web client ID and HTTPS Gateway origin for this build before account sign-in is available.",
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
                MembershipStatusCard("Google Play Billing unavailable", presentation.message)
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
