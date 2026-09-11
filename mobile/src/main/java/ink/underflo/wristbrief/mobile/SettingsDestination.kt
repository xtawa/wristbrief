package ink.underflo.wristbrief.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private enum class SettingsTab { Sources, Account, About }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsDestination(
    onBack: () -> Unit,
    onReplayOnboarding: () -> Unit,
    onboardingAction: OnboardingAction? = null,
    onOnboardingActionConsumed: () -> Unit = {},
) {
    var selectedTab by remember { mutableStateOf(SettingsTab.Sources) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Text("←", style = MaterialTheme.typography.titleLarge)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            PrimaryTabRow(selectedTabIndex = selectedTab.ordinal) {
                Tab(
                    selected = selectedTab == SettingsTab.Sources,
                    onClick = { selectedTab = SettingsTab.Sources },
                    text = { Text(stringResource(R.string.settings_sources_title)) },
                )
                Tab(
                    selected = selectedTab == SettingsTab.Account,
                    onClick = { selectedTab = SettingsTab.Account },
                    text = { Text(stringResource(R.string.settings_account_title)) },
                )
                Tab(
                    selected = selectedTab == SettingsTab.About,
                    onClick = { selectedTab = SettingsTab.About },
                    text = { Text(stringResource(R.string.settings_about_title)) },
                )
            }

            when (selectedTab) {
                SettingsTab.Sources -> {
                    CategorizedFeedManagementDestination(
                        padding = PaddingValues(0.dp),
                        onboardingAction = onboardingAction,
                        onOnboardingActionConsumed = onOnboardingActionConsumed,
                    )
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
                                        text = "Version 0.1.0",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }

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
                                        text = stringResource(R.string.settings_replay_onboarding),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(
                                        text = "View the introductory guide and setup options again.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    OutlinedButton(onClick = onReplayOnboarding) {
                                        Text(stringResource(R.string.settings_replay_onboarding))
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
