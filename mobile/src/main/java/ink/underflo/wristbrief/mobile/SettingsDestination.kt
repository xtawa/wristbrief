package ink.underflo.wristbrief.mobile

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import ink.underflo.wristbrief.mobile.ui.BackIconButton
import ink.underflo.wristbrief.mobile.ui.glass.GlassHeader
import ink.underflo.wristbrief.mobile.ui.glass.GlassTokens
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
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
import ink.underflo.wristbrief.mobile.ui.TouchTargetTokens
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

internal enum class SettingsTab { Sources, Preferences, Account, About }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun SettingsDestination(
    onBack: () -> Unit,
    onboardingAction: OnboardingAction? = null,
    onOnboardingActionConsumed: () -> Unit = {},
    appPreferences: AppPreferences? = null,
    onThemeChanged: (AppThemeMode) -> Unit = {},
    feedManager: MobileFeedManager,
    /** Called after the Wear toggle is switched back on; the shell resends the current snapshot. */
    onWearSyncReenabled: () -> Unit = { runCatching { feedManager.republishToWatch() } },
    darkTheme: Boolean = androidx.compose.foundation.isSystemInDarkTheme(),
    initialTab: SettingsTab = SettingsTab.Sources,
) {
    val context = LocalContext.current
    val preferences = remember(context, appPreferences) {
        appPreferences ?: AppPreferences(context)
    }

    var selectedTab by rememberSaveable(initialTab) { mutableStateOf(initialTab) }
    var currentTheme by rememberSaveable { mutableStateOf(preferences.getThemeMode()) }
    var currentInterval by rememberSaveable { mutableStateOf(preferences.getRefreshInterval()) }
    var wifiOnly by rememberSaveable { mutableStateOf(preferences.isWifiOnly()) }
    var wearSync by rememberSaveable { mutableStateOf(preferences.isWearSyncEnabled()) }
    // Observed value only: null until a refresh has actually completed.
    val lastRefreshEpochMs = remember(preferences) { preferences.getLastInboxRefreshEpochMs() }

    var showLicensesDialog by rememberSaveable { mutableStateOf(false) }

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
        containerColor = GlassTokens.canvas(darkTheme),
        contentColor = GlassTokens.textPrimary(darkTheme),
        topBar = {
            GlassHeader(
                title = stringResource(R.string.settings_title),
                subtitle = when (selectedTab) {
                    SettingsTab.Sources -> stringResource(R.string.settings_sources_subtitle)
                    SettingsTab.Preferences -> stringResource(R.string.settings_preferences_title)
                    SettingsTab.Account -> stringResource(R.string.settings_account_title)
                    SettingsTab.About -> stringResource(R.string.settings_about_title)
                },
                darkTheme = darkTheme,
                navigationIcon = { BackIconButton(onClick = onBack) },
            )
        },
    ) { padding ->
        CompositionLocalProvider(
            LocalContentColor provides GlassTokens.textPrimary(darkTheme)
        ) {
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
                    ScrollableTabRow(
                        selectedTabIndex = selectedTab.ordinal,
                        edgePadding = 16.dp,
                        containerColor = GlassTokens.surfaceGlassStrong(darkTheme),
                        contentColor = GlassTokens.textPrimary(darkTheme),
                    ) {
                        Tab(
                            selected = selectedTab == SettingsTab.Sources,
                            onClick = { selectedTab = SettingsTab.Sources },
                            text = {
                                Text(
                                    stringResource(R.string.settings_sources_title),
                                    color = if (selectedTab == SettingsTab.Sources) MaterialTheme.colorScheme.primary else GlassTokens.textSecondary(darkTheme),
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                        )
                        Tab(
                            selected = selectedTab == SettingsTab.Preferences,
                            onClick = { selectedTab = SettingsTab.Preferences },
                            text = {
                                Text(
                                    stringResource(R.string.settings_preferences_title),
                                    color = if (selectedTab == SettingsTab.Preferences) MaterialTheme.colorScheme.primary else GlassTokens.textSecondary(darkTheme),
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                        )
                        Tab(
                            selected = selectedTab == SettingsTab.Account,
                            onClick = { selectedTab = SettingsTab.Account },
                            text = {
                                Text(
                                    stringResource(R.string.settings_account_title),
                                    color = if (selectedTab == SettingsTab.Account) MaterialTheme.colorScheme.primary else GlassTokens.textSecondary(darkTheme),
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                        )
                        Tab(
                            selected = selectedTab == SettingsTab.About,
                            onClick = { selectedTab = SettingsTab.About },
                            text = {
                                Text(
                                    stringResource(R.string.settings_about_title),
                                    color = if (selectedTab == SettingsTab.About) MaterialTheme.colorScheme.primary else GlassTokens.textSecondary(darkTheme),
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
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
                                        // Wrapping options: at 320 dp with large fonts a fixed Row squeezed
                                        // the last chip until its label broke into two lines.
                                        FlowRow(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalArrangement = Arrangement.spacedBy(4.dp),
                                        ) {
                                            PreferenceOptionChip(
                                                label = stringResource(R.string.settings_theme_system),
                                                selected = currentTheme == AppThemeMode.SYSTEM,
                                                onClick = {
                                                    currentTheme = AppThemeMode.SYSTEM
                                                    preferences.setThemeMode(AppThemeMode.SYSTEM)
                                                    onThemeChanged(AppThemeMode.SYSTEM)
                                                },
                                            )
                                            PreferenceOptionChip(
                                                label = stringResource(R.string.settings_theme_light),
                                                selected = currentTheme == AppThemeMode.LIGHT,
                                                onClick = {
                                                    currentTheme = AppThemeMode.LIGHT
                                                    preferences.setThemeMode(AppThemeMode.LIGHT)
                                                    onThemeChanged(AppThemeMode.LIGHT)
                                                },
                                            )
                                            PreferenceOptionChip(
                                                label = stringResource(R.string.settings_theme_dark),
                                                selected = currentTheme == AppThemeMode.DARK,
                                                onClick = {
                                                    currentTheme = AppThemeMode.DARK
                                                    preferences.setThemeMode(AppThemeMode.DARK)
                                                    onThemeChanged(AppThemeMode.DARK)
                                                },
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
                                        Text(
                                            text = stringResource(R.string.settings_refresh_summary),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Text(
                                            text = lastRefreshEpochMs?.let { epochMs ->
                                                val formatted = java.text.DateFormat.getDateTimeInstance(
                                                    java.text.DateFormat.MEDIUM,
                                                    java.text.DateFormat.SHORT,
                                                ).format(java.util.Date(epochMs))
                                                stringResource(R.string.settings_refresh_last_format, formatted)
                                            } ?: stringResource(R.string.settings_refresh_never),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        // Wrapping instead of a horizontally scrolling row: the fourth
                                        // option used to clip past the right edge with no scroll affordance.
                                        FlowRow(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalArrangement = Arrangement.spacedBy(4.dp),
                                        ) {
                                            listOf(
                                                RefreshInterval.ONE_HOUR to R.string.settings_refresh_1h,
                                                RefreshInterval.THREE_HOURS to R.string.settings_refresh_3h,
                                                RefreshInterval.SIX_HOURS to R.string.settings_refresh_6h,
                                                RefreshInterval.MANUAL to R.string.settings_refresh_manual,
                                            ).forEach { (interval, labelRes) ->
                                                PreferenceOptionChip(
                                                    label = stringResource(labelRes),
                                                    selected = currentInterval == interval,
                                                    onClick = {
                                                        currentInterval = interval
                                                        preferences.setRefreshInterval(interval)
                                                    },
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
                                                    text = stringResource(
                                                        if (wearSync) R.string.settings_wear_sync_summary else R.string.settings_wear_sync_off_summary,
                                                    ),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                            // Every phone→watch publisher checks this preference through
                                            // WearSyncGate; re-enabling resends the subscription list so the
                                            // watch catches up on changes made while it was off.
                                            Switch(
                                                checked = wearSync,
                                                onCheckedChange = { enabled ->
                                                    wearSync = enabled
                                                    preferences.setWearSyncEnabled(enabled)
                                                    if (enabled) onWearSyncReenabled()
                                                },
                                            )
                                        }
                                        // The former "Notifications" switch was removed: the only notification
                                        // this app posts is the mandatory media-playback notification, which a
                                        // preference cannot disable, and there is no background sync to notify about.
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
                                            text = stringResource(R.string.settings_version_format, BuildConfig.VERSION_NAME),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
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
                                                onClick = {
                                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://wb.underflo.ink"))
                                                    context.startActivity(intent)
                                                },
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

                                            val wearStatus = if (wearSync) {
                                                stringResource(R.string.settings_diagnostics_status_ready)
                                            } else {
                                                stringResource(R.string.settings_diagnostics_status_disabled)
                                            }
                                            Text(
                                                text = "${stringResource(R.string.settings_diagnostics_wear_sync)}: $wearStatus (ink.underflo.wristbrief)",
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
}

/**
 * One option in a single-choice preference group. Labels never wrap (the chip grows
 * instead and the FlowRow moves it to the next line) and the touch target meets the
 * 48 dp minimum.
 */
@Composable
private fun PreferenceOptionChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                text = label,
                maxLines = 1,
                softWrap = false,
            )
        },
        // FilterChip already exposes selectable semantics (selected state + role).
        modifier = Modifier.defaultMinSize(minHeight = TouchTargetTokens.minTouchTarget),
    )
}
