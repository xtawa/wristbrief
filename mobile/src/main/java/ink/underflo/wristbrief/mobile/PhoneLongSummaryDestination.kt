package ink.underflo.wristbrief.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun PhoneLongSummaryDestination(
    padding: PaddingValues,
) {
    val client = remember { PhoneLongSummaryClient() }
    val scope = rememberCoroutineScope()
    var gatewayUrl by rememberSaveable { mutableStateOf("") }
    var gatewayToken by remember { mutableStateOf("") }
    var title by rememberSaveable { mutableStateOf("") }
    var content by rememberSaveable { mutableStateOf("") }
    var state by remember { mutableStateOf<PhoneLongSummaryUiState>(PhoneLongSummaryUiState.AwaitingAuthenticatedGateway) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { Text("AI brief", style = MaterialTheme.typography.headlineMedium) }
        item {
            Text(
                "Generate a phone-length summary through an authenticated WristBrief Gateway session. The access token stays in memory and is not saved to app preferences.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            OutlinedTextField(
                value = gatewayUrl,
                onValueChange = { gatewayUrl = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("HTTPS Gateway URL") },
                singleLine = true,
                enabled = state != PhoneLongSummaryUiState.Loading,
            )
        }
        item {
            OutlinedTextField(
                value = gatewayToken,
                onValueChange = { gatewayToken = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Gateway access token") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                enabled = state != PhoneLongSummaryUiState.Loading,
            )
        }
        item {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Title (optional)") },
                singleLine = true,
                enabled = state != PhoneLongSummaryUiState.Loading,
            )
        }
        item {
            OutlinedTextField(
                value = content,
                onValueChange = { content = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Article or transcript text") },
                minLines = 6,
                enabled = state != PhoneLongSummaryUiState.Loading,
            )
        }
        item {
            Button(
                onClick = {
                    state = PhoneLongSummaryUiState.Loading
                    scope.launch {
                        state = try {
                            val summary = withContext(Dispatchers.IO) {
                                client.summarize(
                                    gatewayUrl = gatewayUrl,
                                    gatewayToken = gatewayToken,
                                    title = title.trim().takeIf { it.isNotEmpty() },
                                    content = content,
                                )
                            }
                            phoneLongSummaryReadyState(summary)
                        } catch (error: PhoneSummaryRequestException) {
                            phoneLongSummaryFailureState(error.failure)
                        } catch (_: IllegalArgumentException) {
                            PhoneLongSummaryUiState.Error("Enter a valid HTTPS Gateway URL, access token, and source text.")
                        }
                    }
                },
                enabled = state != PhoneLongSummaryUiState.Loading,
            ) {
                Text(if (state == PhoneLongSummaryUiState.Loading) "Generating…" else "Generate long summary")
            }
        }

        when (state) {
            PhoneLongSummaryUiState.AwaitingAuthenticatedGateway -> item {
                SummaryStateCard(
                    title = "Authenticated Gateway required",
                    description = "Enter your WristBrief Gateway session details and source text to request a validated structured.long response.",
                )
            }
            PhoneLongSummaryUiState.Loading -> item {
                SummaryStateCard(
                    title = "Generating long summary…",
                    description = "The phone can continue showing this screen while the bounded Gateway request runs.",
                )
            }
            is PhoneLongSummaryUiState.Error -> item {
                SummaryStateCard(
                    title = "Summary unavailable",
                    description = (state as PhoneLongSummaryUiState.Error).message,
                )
            }
            is PhoneLongSummaryUiState.Ready -> {
                val ready = state as PhoneLongSummaryUiState.Ready
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
                            Text(ready.text, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                buildString {
                                    append(ready.languageLabel)
                                    ready.modelLabel?.let { append(" · "); append(it) }
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
