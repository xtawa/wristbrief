package ink.underflo.wristbrief.mobile

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun PhoneLongSummaryDestination(
    padding: PaddingValues,
    initialTitle: String = "",
    initialContent: String = "",
) {
    val context = LocalContext.current
    val session = remember(context) { AccountSessionPreferences(context) }.read()
    val gatewayConfigured = BuildConfig.GATEWAY_BASE_URL.startsWith("https://")
    val client = remember { PhoneLongSummaryClient() }
    val scope = rememberCoroutineScope()
    var title by rememberSaveable(initialTitle) { mutableStateOf(initialTitle) }
    var content by rememberSaveable(initialContent) { mutableStateOf(initialContent) }
    var state by remember { mutableStateOf<PhoneLongSummaryUiState>(PhoneLongSummaryUiState.AwaitingAuthenticatedGateway) }
    var copiedFeedback by remember { mutableStateOf(false) }
    val loading = state == PhoneLongSummaryUiState.Loading

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { Text(stringResource(R.string.ai_title), style = MaterialTheme.typography.headlineMedium) }
        item { Text(stringResource(R.string.ai_body), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }

        if (!gatewayConfigured) {
            item { SummaryStateCard(stringResource(R.string.ai_service_unavailable_title), stringResource(R.string.ai_service_unavailable_body)) }
            return@LazyColumn
        }
        if (session == null) {
            item { SummaryStateCard(stringResource(R.string.ai_sign_in_title), stringResource(R.string.ai_sign_in_body)) }
            return@LazyColumn
        }

        // Quick prompt suggestion chips
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    SuggestionChip(
                        onClick = {
                            val prompt = context.getString(R.string.ai_prompt_summarize)
                            content = if (content.isBlank()) "$prompt:\n" else "$prompt:\n$content"
                        },
                        label = { Text(stringResource(R.string.ai_prompt_summarize)) },
                    )
                }
                item {
                    SuggestionChip(
                        onClick = {
                            val prompt = context.getString(R.string.ai_prompt_takeaways)
                            content = if (content.isBlank()) "$prompt:\n" else "$prompt:\n$content"
                        },
                        label = { Text(stringResource(R.string.ai_prompt_takeaways)) },
                    )
                }
                item {
                    SuggestionChip(
                        onClick = {
                            val prompt = context.getString(R.string.ai_prompt_critique)
                            content = if (content.isBlank()) "$prompt:\n" else "$prompt:\n$content"
                        },
                        label = { Text(stringResource(R.string.ai_prompt_critique)) },
                    )
                }
            }
        }

        item { OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.ai_title_optional)) }, singleLine = true, enabled = !loading) }
        item { OutlinedTextField(content, { content = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.ai_source_text)) }, minLines = 7, enabled = !loading) }
        item {
            Button(
                enabled = content.isNotBlank() && !loading,
                onClick = {
                    state = PhoneLongSummaryUiState.Loading
                    copiedFeedback = false
                    scope.launch {
                        state = try {
                            val summary = withContext(Dispatchers.IO) {
                                client.summarize(BuildConfig.GATEWAY_BASE_URL, session.sessionToken, title.trim().takeIf { it.isNotEmpty() }, content)
                            }
                            phoneLongSummaryReadyState(summary)
                        } catch (error: PhoneSummaryRequestException) {
                            val msg = when (error.failure) {
                                PhoneSummaryFailure.Quota -> context.getString(R.string.ai_quota_exceeded)
                                PhoneSummaryFailure.ProviderUnavailable -> context.getString(R.string.ai_provider_unavailable)
                                PhoneSummaryFailure.Unauthorized -> context.getString(R.string.ai_sign_in_body)
                                else -> context.getString(R.string.ai_error)
                            }
                            PhoneLongSummaryUiState.Error(msg)
                        } catch (_: Exception) {
                            PhoneLongSummaryUiState.Error(context.getString(R.string.ai_error))
                        }
                    }
                },
            ) {
                Text(stringResource(if (loading) R.string.ai_generating else R.string.ai_generate))
            }
        }

        when (val current = state) {
            PhoneLongSummaryUiState.AwaitingAuthenticatedGateway -> item { SummaryStateCard("", stringResource(R.string.ai_ready_hint)) }
            PhoneLongSummaryUiState.Loading -> Unit
            is PhoneLongSummaryUiState.Error -> item { SummaryStateCard(stringResource(R.string.ai_error), current.message) }
            is PhoneLongSummaryUiState.Ready -> item {
                Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(stringResource(R.string.ai_result_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            OutlinedButton(onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("Brief", current.text))
                                copiedFeedback = true
                            }) {
                                Text(if (copiedFeedback) stringResource(R.string.ai_copied) else stringResource(R.string.ai_action_copy))
                            }
                        }

                        Text(current.text, style = MaterialTheme.typography.bodyLarge)

                        if (current.bullets.isNotEmpty()) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                current.bullets.forEach { bullet ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("•", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                        Text(bullet, style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                            }
                        }

                        if (current.topics.isNotEmpty()) {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(current.topics) { topic ->
                                    AssistChip(onClick = {}, label = { Text(topic) })
                                }
                            }
                        }

                        Text(current.languageLabel, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryStateCard(title: String, description: String) {
    Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (title.isNotBlank()) Text(title, style = MaterialTheme.typography.titleLarge)
            Text(description, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
