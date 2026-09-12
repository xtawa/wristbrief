package ink.underflo.wristbrief.mobile

import android.app.Activity
import android.content.Intent
import android.net.Uri
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.unit.dp
import com.android.billingclient.api.BillingClient.BillingResponseCode
import kotlinx.coroutines.launch
import ink.underflo.wristbrief.mobile.auth.EmailAuthClient
import ink.underflo.wristbrief.mobile.auth.EmailFlowResult
import ink.underflo.wristbrief.mobile.auth.EmailSignInSection
import ink.underflo.wristbrief.mobile.auth.LinkEmailIdentitySection
import ink.underflo.wristbrief.mobile.auth.PasswordResetDialog
import ink.underflo.wristbrief.mobile.auth.emailAuthErrorMessageRes
import ink.underflo.wristbrief.mobile.artifacts.TranscriptCacheStore
import ink.underflo.wristbrief.mobile.db.WristBriefDatabaseHelper
import ink.underflo.wristbrief.mobile.sync.CloudSyncOutboxStore
import ink.underflo.wristbrief.mobile.sync.SharedPreferencesCloudSyncPreferences

@Composable
internal fun MembershipDestination(padding: PaddingValues) {
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()
    val sessionPreferences = remember(context) { AccountSessionPreferences(context) }
    val dbHelper = remember(context) { WristBriefDatabaseHelper(context) }
    val localDataCleaner = remember(context, dbHelper) {
        AccountLocalDataCleaner(
            transcriptCache = TranscriptCacheStore(dbHelper),
            cloudSyncOutbox = CloudSyncOutboxStore(dbHelper),
            cloudSyncPreferences = SharedPreferencesCloudSyncPreferences(context),
        )
    }
    val accountClient = remember(context, localDataCleaner) {
        GoogleAccountAuthClient(
            context,
            sessionPreferences = sessionPreferences,
            localDataCleaner = localDataCleaner,
        )
    }
    val emailAuthClient = remember(context, localDataCleaner, sessionPreferences) {
        EmailAuthClient(
            sessionPreferences = sessionPreferences,
            sessionBridge = GoogleWearAccountSessionBridge(context),
            localDataCleaner = localDataCleaner,
        )
    }
    var emailBusy by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }
    var resetDialogMessage by remember { mutableStateOf<String?>(null) }
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
    var showDeleteDialog by remember { mutableStateOf(false) }
    var membershipBusy by remember { mutableStateOf(false) }
    var serverSnapshot by remember { mutableStateOf<ServerMembershipSnapshot?>(null) }
    var snapshotLoading by remember { mutableStateOf(false) }

    val repository = remember(context) {
        GooglePlayBillingRepository(
            context = context,
            productIds = configuredBillingProductIds(BuildConfig.BILLING_SUBSCRIPTION_PRODUCT_IDS),
        )
    }
    var billingState by remember { mutableStateOf<BillingState>(BillingState.Loading) }
    var actionMessage by remember { mutableStateOf<String?>(null) }

    fun refreshServerSnapshot() {
        if (accountSession == null) {
            serverSnapshot = null
            return
        }
        scope.launch {
            snapshotLoading = true
            when (val result = membershipApi.loadMembership()) {
                is MembershipSnapshotResult.Success -> {
                    serverSnapshot = result.snapshot
                }
                MembershipSnapshotResult.SignedOut -> {
                    sessionPreferences.clear()
                    accountSession = null
                    serverSnapshot = null
                }
                is MembershipSnapshotResult.Failure -> {
                    // Fail closed gracefully without wiping local session display
                }
            }
            snapshotLoading = false
        }
    }

    LaunchedEffect(accountSession) {
        refreshServerSnapshot()
    }

    LaunchedEffect(billingState, accountSession) {
        val ready = billingState as? BillingState.Ready ?: return@LaunchedEffect
        if (accountSession == null) return@LaunchedEffect
        val unacknowledged = ready.purchases.filter { !it.pending && !it.acknowledged }
        if (unacknowledged.isEmpty()) return@LaunchedEffect

        actionMessage = context.getString(R.string.membership_auto_acknowledging)
        when (val result = membershipApi.restorePurchases(unacknowledged)) {
            is MembershipRestoreResult.Success -> {
                for (purchase in unacknowledged) {
                    repository.acknowledgePurchase(purchase.purchaseToken) { _ -> }
                }
                actionMessage = context.getString(
                    R.string.membership_verified_success,
                    result.restoredCount,
                    result.plan ?: "PRO",
                )
                refreshServerSnapshot()
            }
            MembershipRestoreResult.SignedOut -> {
                sessionPreferences.clear()
                accountSession = null
                serverSnapshot = null
            }
            is MembershipRestoreResult.Failure -> {
                // Pending purchases can be verified later or via manual restore
            }
        }
    }

    DisposableEffect(repository) {
        repository.connect { billingState = it }
        onDispose { repository.close() }
    }

    val account = accountPresentation(accountConfigured, accountSession)
    val presentation = billingState.toMembershipPresentation()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text(
                text = stringResource(R.string.settings_account_title),
                style = MaterialTheme.typography.headlineMedium,
            )
        }

        // 1. Account Identity & Sign In Card
        item {
            when (account) {
                AccountPresentation.NotConfigured -> MembershipStatusCard(
                    title = stringResource(R.string.account_preview_title),
                    description = stringResource(R.string.account_preview_body),
                )
                AccountPresentation.SignedOut -> Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.extraLarge,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.membership_account_title),
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            text = stringResource(R.string.membership_account_sign_in_prompt),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Button(
                            enabled = !accountBusy,
                            onClick = {
                                scope.launch {
                                    accountBusy = true
                                    accountMessage = null
                                    when (val result = accountClient.signIn()) {
                                        is AccountAuthResult.Success -> {
                                            accountSession = result.session
                                            accountMessage = context.getString(R.string.membership_signed_in)
                                        }
                                        is AccountAuthResult.Failure -> {
                                            accountMessage = context.getString(R.string.membership_sign_in_failed)
                                        }
                                        AccountAuthResult.SignedOut -> accountSession = null
                                    }
                                    accountBusy = false
                                }
                            },
                        ) {
                            Text(
                                if (accountBusy) stringResource(R.string.membership_signing_in)
                                else stringResource(R.string.membership_sign_in_google),
                            )
                        }

                        // Email/password sign-in against the same account model.
                        EmailSignInSection(
                            busy = emailBusy,
                            onSignIn = { email, password ->
                                scope.launch {
                                    emailBusy = true
                                    accountMessage = null
                                    when (val result = emailAuthClient.login(email, password)) {
                                        is AccountAuthResult.Success -> {
                                            accountSession = result.session
                                            accountMessage = context.getString(R.string.membership_signed_in)
                                        }
                                        is AccountAuthResult.Failure -> {
                                            accountMessage = context.getString(emailAuthErrorMessageRes(result.code))
                                        }
                                        AccountAuthResult.SignedOut -> accountSession = null
                                    }
                                    emailBusy = false
                                }
                            },
                            onRegister = { email, password ->
                                scope.launch {
                                    emailBusy = true
                                    accountMessage = null
                                    when (val result = emailAuthClient.register(email, password)) {
                                        is AccountAuthResult.Success -> {
                                            accountSession = result.session
                                            accountMessage = context.getString(R.string.auth_account_created)
                                        }
                                        is AccountAuthResult.Failure -> {
                                            accountMessage = context.getString(emailAuthErrorMessageRes(result.code))
                                        }
                                        AccountAuthResult.SignedOut -> accountSession = null
                                    }
                                    emailBusy = false
                                }
                            },
                            onForgotPassword = {
                                resetDialogMessage = null
                                showResetDialog = true
                            },
                        )
                    }
                }
                is AccountPresentation.SignedIn -> Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.extraLarge,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.membership_signed_in),
                                    style = MaterialTheme.typography.titleLarge,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                                Text(
                                    text = stringResource(R.string.membership_user_label, account.userId),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                                )
                            }
                            val isPro = serverSnapshot?.plan == "PRO"
                            SuggestionChip(
                                onClick = {},
                                label = {
                                    Text(
                                        text = if (isPro) stringResource(R.string.membership_plan_tier_pro)
                                        else stringResource(R.string.membership_plan_tier_free),
                                        fontWeight = FontWeight.Bold,
                                    )
                                },
                            )
                        }

                        // AI Quota Usage
                        val currentSnapshot = serverSnapshot
                        if (currentSnapshot != null) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                val limit = currentSnapshot.managedAiLimit
                                if (limit != null && limit > 0) {
                                    val used = currentSnapshot.managedAiUsed
                                    val remaining = currentSnapshot.managedAiRemaining ?: (limit - used).coerceAtLeast(0)
                                    Text(
                                        text = stringResource(R.string.membership_quota_usage, used, remaining),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    )
                                    LinearProgressIndicator(
                                        progress = { (used.toFloat() / limit.toFloat()).coerceIn(0f, 1f) },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                } else {
                                    Text(
                                        text = stringResource(R.string.membership_quota_unlimited, currentSnapshot.managedAiUsed),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    )
                                }
                            }
                        } else if (snapshotLoading) {
                            Text(
                                text = stringResource(R.string.membership_loading_snapshot),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                            )
                        }

                        Text(
                            text = stringResource(R.string.membership_session_expires, account.expiresAt),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                        )

                        // Scenario A: attach email/password sign-in to this account.
                        LinkEmailIdentitySection(
                            busy = emailBusy,
                            onLink = { email, password ->
                                scope.launch {
                                    emailBusy = true
                                    accountMessage = when (val result = emailAuthClient.linkEmailIdentity(email, password)) {
                                        EmailFlowResult.Success -> context.getString(R.string.auth_link_email_done)
                                        is EmailFlowResult.Failure -> context.getString(emailAuthErrorMessageRes(result.code))
                                    }
                                    emailBusy = false
                                }
                            },
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(
                                modifier = Modifier.weight(1f),
                                enabled = !accountBusy,
                                onClick = {
                                    scope.launch {
                                        accountBusy = true
                                        accountClient.signOut()
                                        accountSession = null
                                        serverSnapshot = null
                                        accountMessage = context.getString(R.string.membership_signed_out_device)
                                        accountBusy = false
                                    }
                                },
                            ) {
                                Text(
                                    if (accountBusy) stringResource(R.string.membership_signing_out)
                                    else stringResource(R.string.membership_sign_out),
                                )
                            }
                            OutlinedButton(
                                modifier = Modifier.weight(1f),
                                enabled = !accountBusy,
                                onClick = { showDeleteDialog = true },
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error,
                                ),
                            ) {
                                Text(stringResource(R.string.membership_delete_account))
                            }
                        }
                    }
                }
            }
            if (showResetDialog) {
                PasswordResetDialog(
                    busy = emailBusy,
                    message = resetDialogMessage,
                    onDismissRequest = { showResetDialog = false },
                    onRequestReset = { email ->
                        emailBusy = true
                        val result = emailAuthClient.forgotPassword(email)
                        emailBusy = false
                        resetDialogMessage = when (result) {
                            EmailFlowResult.Success -> context.getString(R.string.auth_reset_request_hint)
                            is EmailFlowResult.Failure -> context.getString(emailAuthErrorMessageRes(result.code))
                        }
                        EmailFlowResult.Success
                    },
                    onResetPassword = { token, newPassword ->
                        emailBusy = true
                        val result = emailAuthClient.resetPassword(token, newPassword)
                        emailBusy = false
                        when (result) {
                            EmailFlowResult.Success -> {
                                resetDialogMessage = null
                                showResetDialog = false
                                accountMessage = context.getString(R.string.auth_reset_done)
                            }
                            is EmailFlowResult.Failure -> {
                                resetDialogMessage = context.getString(emailAuthErrorMessageRes(result.code))
                            }
                        }
                        result
                    },
                )
            }
            if (showDeleteDialog) {
                AlertDialog(
                    onDismissRequest = { if (!accountBusy) showDeleteDialog = false },
                    title = { Text(stringResource(R.string.membership_delete_account_dialog_title)) },
                    text = { Text(stringResource(R.string.membership_delete_account_dialog_body)) },
                    confirmButton = {
                        Button(
                            enabled = !accountBusy,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError,
                            ),
                            onClick = {
                                scope.launch {
                                    accountBusy = true
                                    try {
                                        when (accountClient.deleteAccount()) {
                                            AccountAuthResult.SignedOut -> {
                                                accountSession = null
                                                serverSnapshot = null
                                                accountMessage = context.getString(R.string.membership_account_deleted)
                                            }
                                            else -> accountMessage = context.getString(R.string.membership_delete_failed)
                                        }
                                    } catch (_: Exception) {
                                        accountMessage = context.getString(R.string.membership_delete_failed)
                                    } finally {
                                        accountBusy = false
                                        showDeleteDialog = false
                                    }
                                }
                            },
                        ) {
                            Text(
                                if (accountBusy) stringResource(R.string.membership_deleting_account)
                                else stringResource(R.string.membership_delete_account_confirm),
                            )
                        }
                    },
                    dismissButton = {
                        TextButton(
                            enabled = !accountBusy,
                            onClick = { showDeleteDialog = false },
                        ) {
                            Text(stringResource(R.string.action_cancel))
                        }
                    },
                )
            }
        }

        accountMessage?.let { msg ->
            item { Text(msg, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }

        // 2. Plan Features Comparison Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = stringResource(R.string.membership_plans_title),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.membership_plan_tier_free),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = stringResource(R.string.membership_free_features),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.membership_plan_tier_pro),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = stringResource(R.string.membership_pro_features),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        // 3. Play Subscriptions & Purchases Section
        item {
            Text(
                text = stringResource(R.string.membership_play_disclaimer),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        actionMessage?.let { msg ->
            item { Text(msg, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }

        when (presentation) {
            MembershipPresentation.Loading -> item {
                MembershipStatusCard(
                    title = stringResource(R.string.membership_loading_products),
                    description = stringResource(R.string.membership_checking_purchases),
                )
            }
            is MembershipPresentation.Unavailable -> item {
                val notConfigured = configuredBillingProductIds(BuildConfig.BILLING_SUBSCRIPTION_PRODUCT_IDS).isEmpty()
                MembershipStatusCard(
                    title = if (notConfigured) stringResource(R.string.membership_billing_not_configured_title)
                    else stringResource(R.string.plans_unavailable_title),
                    description = if (notConfigured) stringResource(R.string.membership_billing_not_configured_body)
                    else stringResource(R.string.plans_unavailable_body),
                )
                if (!notConfigured) {
                    OutlinedButton(onClick = repository::refresh) {
                        Text(stringResource(R.string.membership_try_again))
                    }
                }
            }
            is MembershipPresentation.Error -> item {
                MembershipStatusCard(
                    title = stringResource(R.string.membership_could_not_load),
                    description = presentation.message,
                )
                OutlinedButton(onClick = repository::refresh) {
                    Text(stringResource(R.string.membership_try_again))
                }
            }
            is MembershipPresentation.Ready -> {
                if (presentation.restoredPurchaseCount > 0 || presentation.pendingPurchaseCount > 0) {
                    item {
                        MembershipStatusCard(
                            title = stringResource(R.string.membership_purchase_status),
                            description = buildString {
                                if (presentation.restoredPurchaseCount > 0) {
                                    append(context.getString(R.string.membership_purchases_found, presentation.restoredPurchaseCount))
                                }
                                if (presentation.pendingPurchaseCount > 0) {
                                    if (isNotEmpty()) append(" ")
                                    append(context.getString(R.string.membership_purchases_pending, presentation.pendingPurchaseCount))
                                }
                            },
                        )
                    }
                }
                if (presentation.products.isEmpty()) {
                    item {
                        MembershipStatusCard(
                            title = stringResource(R.string.membership_no_plans),
                            description = stringResource(R.string.membership_no_plans_body),
                        )
                    }
                }
                items(presentation.products, key = { it.productId }) { product ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.extraLarge,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(product.title, style = MaterialTheme.typography.titleLarge)
                            if (product.description.isNotBlank()) {
                                Text(product.description, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(
                                text = product.formattedPrice,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Button(
                                onClick = {
                                    if (activity == null) {
                                        actionMessage = context.getString(R.string.membership_flow_failed)
                                    } else {
                                        val result = repository.launchPurchase(activity, product.productId)
                                        actionMessage = when (result.responseCode) {
                                            BillingResponseCode.OK -> context.getString(R.string.membership_flow_opened)
                                            BillingResponseCode.USER_CANCELED -> context.getString(R.string.membership_flow_canceled)
                                            BillingResponseCode.ITEM_ALREADY_OWNED -> context.getString(R.string.membership_already_owned)
                                            else -> context.getString(R.string.membership_flow_failed)
                                        }
                                        if (result.responseCode == BillingResponseCode.ITEM_ALREADY_OWNED) {
                                            repository.refresh()
                                        }
                                    }
                                },
                                enabled = account is AccountPresentation.SignedIn && !product.alreadyPurchased && activity != null,
                            ) {
                                Text(
                                    when {
                                        product.alreadyPurchased -> stringResource(R.string.membership_already_purchased)
                                        account is AccountPresentation.SignedIn -> stringResource(R.string.membership_subscribe)
                                        else -> stringResource(R.string.membership_sign_in_to_subscribe)
                                    },
                                )
                            }
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
                                actionMessage = context.getString(R.string.membership_verifying)
                                when (val result = membershipApi.restorePurchases(purchases)) {
                                    is MembershipRestoreResult.Success -> {
                                        for (purchase in purchases.filter { !it.acknowledged && !it.pending }) {
                                            repository.acknowledgePurchase(purchase.purchaseToken) { _ -> }
                                        }
                                        actionMessage = context.getString(
                                            R.string.membership_verified_success,
                                            result.restoredCount,
                                            result.plan ?: "PRO",
                                        )
                                        refreshServerSnapshot()
                                    }
                                    MembershipRestoreResult.SignedOut -> {
                                        sessionPreferences.clear()
                                        accountSession = null
                                        serverSnapshot = null
                                        actionMessage = context.getString(R.string.membership_session_expired)
                                    }
                                    is MembershipRestoreResult.Failure -> {
                                        actionMessage = context.getString(R.string.membership_restore_failed)
                                    }
                                }
                                membershipBusy = false
                            }
                        },
                    ) {
                        Text(
                            if (membershipBusy) stringResource(R.string.membership_verifying)
                            else stringResource(R.string.membership_restore_purchases),
                        )
                    }
                }

                item {
                    TextButton(
                        onClick = {
                            actionMessage = context.getString(R.string.membership_refreshing_play)
                            repository.refresh()
                        },
                        enabled = !membershipBusy,
                    ) {
                        Text(stringResource(R.string.membership_refresh_play))
                    }
                }

                item {
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            val intent = Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://play.google.com/store/account/subscriptions")
                            )
                            runCatching { context.startActivity(intent) }
                        },
                    ) {
                        Text(stringResource(R.string.membership_manage_subscriptions))
                    }
                }
            }
        }

        // 4. Privacy & Terms Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = stringResource(R.string.membership_privacy_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(R.string.membership_privacy_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun MembershipStatusCard(title: String, description: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
