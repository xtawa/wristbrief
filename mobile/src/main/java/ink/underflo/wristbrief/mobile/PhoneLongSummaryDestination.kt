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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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

enum class PhoneSummaryAccessMode {
    Managed,
    Byok,
}

data class PhoneByokEditorState(
    val provider: PhoneByokProvider = PhoneByokProvider.OpenRouter,
    val model: String = "",
    val apiKey: String = "",
)

internal fun PhoneSummaryAccessMode.toRequestConfig(editor: PhoneByokEditorState): PhoneByokConfig? =
    when (this) {
        PhoneSummaryAccessMode.Managed -> null
        PhoneSummaryAccessMode.Byok -> PhoneByokConfig(
            provider = editor.provider,
            model = editor.model,
            apiKey = editor.apiKey,
        )
    }

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
    var accessModeName by rememberSaveable { mutableStateOf(PhoneSummaryAccessMode.Managed.name) }
    var providerName by rememberSaveable { mutableStateOf(PhoneByokProvider.OpenRouter.name) }
    var byokModel by rememberSaveable { mutableStateOf("") }
    var byokApiKey by remember { mutableStateOf("") }
    var state by remember { mutableStateOf<PhoneLongSummaryUiState>(PhoneLongSummaryUiState.AwaitingAuthenticatedGateway) }

    val accessMode = PhoneSummaryAccessMode.valueOf(accessModeName)
    val provider = PhoneByokProvider.valueOf(providerName)
    val loading = state == PhoneLongSummaryUiState.Loading

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { Text("AI brief", style = MaterialTheme.typography.headlineMedium) }
        item {
            Text(
                "Use WristBrief managed AI or bring your own OpenRouter/Gemini key. Session credentials and provider keys stay in memory and are not saved to app preferences.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = { accessModeName = PhoneSummaryAccessMode.Managed.name },
                    enabled = !loading && accessMode != PhoneSummaryAccessMode.Managed,
                    modifier = Modifier.weight(1f),
                ) { Text("Managed") }
                OutlinedButton(
                    onClick = { accessModeName = PhoneSummaryAccessMode.Byok.name },
                    enabled = !loading && accessMode != PhoneSummaryAccessMode.Byok,
                    modifier = Modifier.weight(1f),
                ) { Text("BYOK") }
            }
        }
        if (accessMode == PhoneSummaryAccessMode.Byok) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.extraLarge,
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("Provider", style = MaterialTheme.typography.titleMedium)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Button(
                                onClick = { providerName = PhoneByokProvider.OpenRouter.name },
                                enabled = !loading && provider != PhoneByokProvider.OpenRouter,
                                modifier = Modifier.weight(1f),
                            ) { Text("OpenRouter") }
                            OutlinedButton(
                                onClick = { providerName = PhoneByokProvider.Gemini.name },
                                enabled = !loading && provider != PhoneByokProvider.Gemini,
                                modifier = Modifier.weight(1f),
                            ) { Text("Gemini") }
                        }
                        OutlinedTextField(
                            value = byokModel,
                            onValueChange = { byokModel = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Model") },
                            singleLine = true,
                            enabled = !loading,
                        )
                        OutlinedTextField(
                            value = byokApiKey,
                            onValueChange = { byokApiKey = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Provider API key") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            enabled = !loading,
                        )
                        Text(
                            "The provider key is sent only in the dedicated BYOK request header. It is not placed in the request body or saved locally.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        item {
            OutlinedTextField(
                value = gatewayUrl,
                onValueChange = { gatewayUrl = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("HTTPS Gateway URL") },
                singleLine = true,
                enabled = !loading,
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
                enabled = !loading,
            )
        }
        item {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Title (optional)") },
                singleLine = true,
                enabled = !loading,
            )
        }
        item {
            OutlinedTextField(
                value = content,
                onValueChange = { content = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Article or transcript text") },
                minLines = 6,
                enabled = !loading,
            )
        }
        item {
            Button(
                onClick = {
                    state = PhoneLongSummaryUiState.Loading
                    scope.launch {
                        state = try {
                            val editor = PhoneByokEditorState(
                                provider = provider,
                                model = byokModel,
                                apiKey = byokApiKey,
                            )
                            val byokConfig = accessMode.toRequestConfig(editor)
                            val summary = withContext(Dispatchers.IO) {
                                if (byokConfig == null) {
                                    client.summarize(
                                        gatewayUrl = gatewayUrl,
                                        gatewayToken = gatewayToken,
                                        title = title.trim().takeIf { it.isNotEmpty() },
                                        content = content,
                                    )
                                } else {
                                    client.summarizeByok(
                                        gatewayUrl = gatewayUrl,
                                        gatewayToken = gatewayToken,
                                        title = title.trim().takeIf { it.isNotEmpty() },
                                        content = content,
                                        config = byokConfig,
                                    )
                                }
                            }
                            phoneLongSummaryReadyState(summary)
                        } catch (error: PhoneSummaryRequestException) {
                            phoneLongSummaryFailureState(error.failure)
                        } catch (_: IllegalArgumentException) {
                            PhoneLongSummaryUiState.Error(
                                if (accessMode == PhoneSummaryAccessMode.Byok) {
                                    "Enter a valid HTTPS Gateway URL, access token, source text, BYOK model, and provider API key."
                                } else {
                                    "Enter a valid HTTPS Gateway URL, access token, and source text."
                                },
                            )
                        }
                    }
                },
                enabled = !loading,
            ) {
                Text(if (loading) "Generating…" else "Generate long summary")
            }
        }

        when (state) {
            PhoneLongSummaryUiState.AwaitingAuthenticatedGateway -> item {
                SummaryStateCard(
                    title = "Authenticated Gateway required",
                    description = "Enter your WristBrief Gateway session details and source text. BYOK additionally requires an OpenRouter or Gemini key and model.",
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
