package ink.underflo.wristbrief.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun PhoneLongSummaryDestination(
    state: PhoneLongSummaryUiState,
    padding: PaddingValues,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { Text("AI brief", style = MaterialTheme.typography.headlineMedium) }
        when (state) {
            PhoneLongSummaryUiState.AwaitingAuthenticatedGateway -> item {
                SummaryStateCard(
                    title = "Phone-length summaries are ready",
                    description = "The phone UI can render validated structured.long responses once authenticated Gateway access is wired. No provider key is stored in the APK.",
                )
            }
            is PhoneLongSummaryUiState.Error -> item {
                SummaryStateCard(
                    title = "Summary unavailable",
                    description = state.message,
                )
            }
            is PhoneLongSummaryUiState.Ready -> {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.extraLarge,
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text("Long summary", style = MaterialTheme.typography.titleLarge)
                            Text(state.text, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                buildString {
                                    append(state.languageLabel)
                                    state.modelLabel?.let { append(" · "); append(it) }
                                },
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryStateCard(title: String, description: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(
                description,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
