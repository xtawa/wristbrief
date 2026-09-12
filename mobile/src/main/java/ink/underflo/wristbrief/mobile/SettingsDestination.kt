package ink.underflo.wristbrief.mobile

import androidx.compose.foundation.layout.Arrangement
import ink.underflo.wristbrief.mobile.ui.BackIconButton
import ink.underflo.wristbrief.mobile.ui.glass.GlassHeader
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

internal enum class SettingsTab { Sources, Preferences, Account, About }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsDestination(
    onBack: () -> Unit,
    onReplayOnboarding: () -> Unit,
    onboardingAction: OnboardingAction? = null,
    onOnboardingActionConsumed: () -> Unit = {},
    appPreferences: AppPreferences? = null,
    onThemeChanged: (AppThemeMode) -> Unit = {},
    feedManager: MobileFeedManager,
    darkTheme: Boolean = androidx.compose.foundation.isSystemInDarkTheme(),
) {
    val context = LocalContext.current
    val preferences = remember(context, appPreferences) {
        appPreferences ?: AppPreferences(context)
    }

    var selectedTab by rememberSaveable { mutableStateOf(SettingsTab.Sources) }
    var currentTheme by rememberSaveable { mutableStateOf(preferences.getThemeMode()) }
    var currentInterval by rememberSaveable { mutableStateOf(preferences.getRefreshInterval()) }
    var wifiOnly by rememberSaveable { mutableStateOf(preferences.isWifiOnly()) }
    var wearSync by rememberSaveable { mutableStateOf(preferences.isWearSyncEnabled()) }
    var notifications by rememberSaveable { mutableStateOf(preferences.isNotificationsEnabled()) }

    var showPrivacyDialog by rememberSaveable { mutableStateOf(false) }
    var showLicensesDialog by rememberSaveable { mutableStateOf(false) }

    if (showPrivacyDialog) {
        AlertDialog(
            onDismissRequest = { showPrivacyDialog = false },
            title = { Text(stringResource(R.string.settings_privacy_policy_title)) },
            text = { Text(stringResource(R.string.settings_privacy_policy_body)) },
            confirmButton = {
                TextButton(onClick = { showPrivacyDialog = false }) {
                    Text(stringResource(R.string.dialog_ok))
                }
            },
        )
    }

    if (showLicensesDialog) {
        AlertDialog(
            onDismissRequest = { showLicensesDialog = false },
            title = { Text(stringResource(R.string.settings_licenses_title)) },
            text = { Text(stringResource(R.string.settings_licenses_body)) },
            confirmButton = {
                TextButton(onClick = { showLicensesDialog = false }) {
                    Text(stringResource(R.string.dialog_ok))
                }
            },
        )
    }

    Scaffold(
        containerColor = ink.underflo.wristbrief.mobile.ui.glass.GlassTokens.canvas(darkTheme),
        topBar = {
            GlassHeader(
                title = stringResource(R.string.settings_title),
                subtitle = stringResource(R.string.settings_sources_subtitle),
                darkTheme = darkTheme,
                navigationIcon = { BackIconButton(onClick = onBack) },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 840.dp),
            ) {
                PrimaryTabRow(selectedTabIndex = selectedTab.ordinal) {
                    Tab(
                        selected = selectedTab == SettingsTab.Sources,
                        onClick = { selectedTab = SettingsTab.Sources },
                        text = { Text(stringResource(R.string.settings_sources_title), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) },
                    )
                    Tab(
                        selected = selectedTab == SettingsTab.Preferences,
                        onClick = { selectedTab = SettingsTab.Preferences },
                        text = { Text(stringResource(R.string.settings_preferences_title), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) },
                    )
                    Tab(
                        selected = selectedTab == SettingsTab.Account,
                        onClick = { selectedTab = SettingsTab.Account },
                        text = { Text(stringResource(R.string.settings_account_title), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) },
                    )
                    Tab(
                        selected = selectedTab == SettingsTab.About,
                        onClick = { selectedTab = SettingsTab.About },
                        text = { Text(stringResource(R.string.settings_about_title), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) },
                    )
                }

                when (selectedTab) {
                    SettingsTab.Sources -> {
                        CategorizedFeedManagementDestination(
                            padding = PaddingValues(0.dp),
                            feedManager = feedManager,
                            onboardingAction = onboardingAction,
                            onOnboardingActionConsumed = onOnboardingActionConsumed,
                        )
                    }
                    SettingsTab.Preferences -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(20.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            // Theme Appearance Card
                            item {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = MaterialTheme.shapes.extraLarge,
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                                ) {
                                    Column(
                                        modifier = Modifier.padding(20.dp),
                                        verticalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        Text(
                                            text = stringResource(R.string.settings_theme_title),
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            FilterChip(
                                                selected = currentTheme == AppThemeMode.SYSTEM,
                                                onClick = {
                                                    currentTheme = AppThemeMode.SYSTEM
                                                    preferences.setThemeMode(AppThemeMode.SYSTEM)
                                                    onThemeChanged(AppThemeMode.SYSTEM)
                                                },
                                                label = { Text(stringResource(R.string.settings_theme_system)) },
                                            )
                                            FilterChip(
                                                selected = currentTheme == AppThemeMode.LIGHT,
                                                onClick = {
                                                    currentTheme = AppThemeMode.LIGHT
                                                    preferences.setThemeMode(AppThemeMode.LIGHT)
                                                    onThemeChanged(AppThemeMode.LIGHT)
                                                },
                                                label = { Text(stringResource(R.string.settings_theme_light)) },
                                            )
                                            FilterChip(
                                                selected = currentTheme == AppThemeMode.DARK,
                                                onClick = {
                                                    currentTheme = AppThemeMode.DARK
                                                    preferences.setThemeMode(AppThemeMode.DARK)
                                                    onThemeChanged(AppThemeMode.DARK)
                                                },
                                                label = { Text(stringResource(R.string.settings_theme_dark)) },
                                            )
                                        }
                                    }
                                }
                            }

                            // Refresh Frequency Card
                            item {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = MaterialTheme.shapes.extraLarge,
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                                ) {
                                    Column(
                                        modifier = Modifier.padding(20.dp),
                                        verticalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        Text(
                                            text = stringResource(R.string.settings_refresh_title),
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        LazyRow(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            item {
                                                FilterChip(
                                                    selected = currentInterval == RefreshInterval.ONE_HOUR,
                                                    onClick = {
                                                        currentInterval = RefreshInterval.ONE_HOUR
                                                        preferences.setRefreshInterval(RefreshInterval.ONE_HOUR)
                                                    },
                                                    label = { Text(stringResource(R.string.settings_refresh_1h)) },
                                                )
                                            }
                                            item {
                                                FilterChip(
                                                    selected = currentInterval == RefreshInterval.THREE_HOURS,
                                                    onClick = {
                                                        currentInterval = RefreshInterval.THREE_HOURS
                                                        preferences.setRefreshInterval(RefreshInterval.THREE_HOURS)
                                                    },
                                                    label = { Text(stringResource(R.string.settings_refresh_3h)) },
                                                )
                                            }
                                            item {
                                                FilterChip(
                                                    selected = currentInterval == RefreshInterval.SIX_HOURS,
                                                    onClick = {
                                                        currentInterval = RefreshInterval.SIX_HOURS
                                                        preferences.setRefreshInterval(RefreshInterval.SIX_HOURS)
                                                    },
                                                    label = { Text(stringResource(R.string.settings_refresh_6h)) },
                                                )
                                            }
                                            item {
                                                FilterChip(
                                                    selected = currentInterval == RefreshInterval.MANUAL,
                                                    onClick = {
                                                        currentInterval = RefreshInterval.MANUAL
                                                        preferences.setRefreshInterval(RefreshInterval.MANUAL)
                                                    },
                                                    label = { Text(stringResource(R.string.settings_refresh_manual)) },
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            // Network & Sync Card
                            item {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = MaterialTheme.shapes.extraLarge,
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                                ) {
                                    Column(
                                        modifier = Modifier.padding(20.dp),
                                        verticalArrangement = Arrangement.spacedBy(16.dp),
                                    ) {
                                        // Wi-Fi only toggle
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                                                Text(
                                                    text = stringResource(R.string.settings_wifi_only_title),
                                                    style = MaterialTheme.typography.titleSmall,
                                                    fontWeight = FontWeight.SemiBold,
                                                )
                                                Text(
                                                    text = stringResource(R.string.settings_wifi_only_summary),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                            Switch(
                                                checked = wifiOnly,
                                                onCheckedChange = {
                                                    wifiOnly = it
                                                    preferences.setWifiOnly(it)
                                                },
                                            )
                                        }

                                        // Wear OS sync toggle
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                                                Text(
                                                    text = stringResource(R.string.settings_wear_sync_title),
                                                    style = MaterialTheme.typography.titleSmall,
                                                    fontWeight = FontWeight.SemiBold,
                                                )
                                                Text(
                                                    text = stringResource(R.string.settings_wear_sync_summary),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                            Switch(
                                                checked = wearSync,
                                                onCheckedChange = {
                                                    wearSync = it
                                                    preferences.setWearSyncEnabled(it)
                                                },
                                            )
                                        }

                                        // Notifications toggle
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                                                Text(
                                                    text = stringResource(R.string.settings_notifications_title),
                                                    style = MaterialTheme.typography.titleSmall,
                                                    fontWeight = FontWeight.SemiBold,
                                                )
                                                Text(
                                                    text = stringResource(R.string.settings_notifications_summary),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                            Switch(
                                                checked = notifications,
                                                onCheckedChange = {
                                                    notifications = it
                                                    preferences.setNotificationsEnabled(it)
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    SettingsTab.Account -> {
                        MembershipDestination(padding = PaddingValues(0.dp))
                    }
                    SettingsTab.About -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(20.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            // Version & App Info Card
                            item {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = MaterialTheme.shapes.extraLarge,
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                                ) {
                                    Column(
                                        modifier = Modifier.padding(20.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Text(
                                            text = stringResource(R.string.settings_about_title),
                                            style = MaterialTheme.typography.titleLarge,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        Text(
                                            text = stringResource(R.string.settings_about_body),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Text(
                                            text = stringResource(R.string.settings_version_format, "0.1.0"),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                            }

                            // Replay Onboarding Card
                            item {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = MaterialTheme.shapes.extraLarge,
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                                ) {
                                    Column(
                                        modifier = Modifier.padding(20.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Text(
                                            text = stringResource(R.string.settings_replay_onboarding_title),
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        Text(
                                            text = stringResource(R.string.settings_replay_onboarding_desc),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        OutlinedButton(
                                            onClick = onReplayOnboarding,
                                            modifier = Modifier
                                                .semantics { role = Role.Button }
                                                .testTag("settings_replay_onboarding_button"),
                                        ) {
                                            Text(stringResource(R.string.settings_replay_onboarding))
                                        }
                                    }
                                }
                            }

                            // Privacy & Licenses Card
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
                                            text = stringResource(R.string.settings_privacy_policy_title),
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            OutlinedButton(
                                                onClick = { showPrivacyDialog = true },
                                                modifier = Modifier.weight(1f),
                                            ) {
                                                Text(stringResource(R.string.settings_privacy_policy))
                                            }
                                            OutlinedButton(
                                                onClick = { showLicensesDialog = true },
                                                modifier = Modifier.weight(1f),
                                            ) {
                                                Text(stringResource(R.string.settings_licenses))
                                            }
                                        }
                                    }
                                }
                            }

                            // Developer Diagnostics Card (Debug builds only)
                            if (BuildConfig.DEBUG) {
                                item {
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = MaterialTheme.shapes.extraLarge,
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                        ),
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(20.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            Text(
                                                text = stringResource(R.string.settings_diagnostics_title),
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.SemiBold,
                                            )
                                            val gatewayStatus = if (BuildConfig.GATEWAY_BASE_URL.isNotBlank()) {
                                                stringResource(R.string.settings_diagnostics_status_ready)
                                            } else {
                                                stringResource(R.string.settings_diagnostics_status_default)
                                            }
                                            Text(
                                                text = "${stringResource(R.string.settings_diagnostics_gateway)}: $gatewayStatus",
                                                style = MaterialTheme.typography.bodySmall,
                                            )

                                            val oauthStatus = if (BuildConfig.GOOGLE_WEB_CLIENT_ID.isNotBlank()) {
                                                stringResource(R.string.settings_diagnostics_status_ready)
                                            } else {
                                                stringResource(R.string.settings_diagnostics_status_default)
                                            }
                                            Text(
                                                text = "${stringResource(R.string.settings_diagnostics_oauth)}: $oauthStatus",
                                                style = MaterialTheme.typography.bodySmall,
                                            )

                                            val billingProducts = BuildConfig.BILLING_SUBSCRIPTION_PRODUCT_IDS.ifBlank { "pro_monthly, pro_yearly" }
                                            Text(
                                                text = "${stringResource(R.string.settings_diagnostics_billing)}: $billingProducts",
                                                style = MaterialTheme.typography.bodySmall,
                                            )

                                            Text(
                                                text = "${stringResource(R.string.settings_diagnostics_wear_sync)}: ${stringResource(R.string.settings_diagnostics_status_ready)} (ink.underflo.wristbrief)",
                                                style = MaterialTheme.typography.bodySmall,
                                            )

                                            Spacer(Modifier.height(4.dp))
                                            Text(
                                                text = stringResource(R.string.settings_diagnostics_safe_notice),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
