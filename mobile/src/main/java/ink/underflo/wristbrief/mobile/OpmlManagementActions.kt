package ink.underflo.wristbrief.mobile

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun OpmlManagementActions(
    manager: MobileFeedManager,
    busy: Boolean,
    onBusyChange: (Boolean) -> Unit,
    onFeedsChanged: (List<MobileFeedSubscription>) -> Unit,
    onStatus: (String) -> Unit,
    launchImport: Boolean = false,
    onImportLaunchConsumed: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var urlInput by remember { mutableStateOf("") }
    var pendingPreview by remember { mutableStateOf<OpmlImportPreview?>(null) }
    val previewApi = remember(context) {
        HttpOpmlPreviewApi(BuildConfig.GATEWAY_BASE_URL) {
            AccountSessionPreferences(context).read()?.sessionToken
        }
    }

    fun applyPreview(preview: OpmlImportPreview) {
        scope.launch {
            onBusyChange(true)
            onStatus(context.getString(R.string.opml_importing))
            try {
                val result = withContext(Dispatchers.IO) { manager.applyOpmlPreview(preview) }
                when (result) {
                    is OpmlImportResult.Success -> {
                        onFeedsChanged(result.feeds)
                        onStatus(opmlImportStatus(result))
                    }
                    is OpmlImportResult.Error -> onStatus(result.message)
                }
            } catch (_: Exception) {
                onStatus(context.getString(R.string.opml_import_read_error))
            } finally {
                onBusyChange(false)
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            onBusyChange(true)
            onStatus(context.getString(R.string.opml_importing))
            try {
                val raw = withContext(Dispatchers.IO) { readOpmlDocument(context.contentResolver, uri) }
                when (val outcome = withContext(Dispatchers.Default) { previewLocalOpml(OpmlImportSource.LocalFile(uri.toString()), raw) }) {
                    is OpmlPreviewOutcome.Ready -> pendingPreview = outcome.preview
                    is OpmlPreviewOutcome.Error -> onStatus(context.getString(R.string.opml_import_format_error))
                }
            } catch (_: OpmlFormatException) {
                onStatus(context.getString(R.string.opml_import_format_error))
            } catch (_: Exception) {
                onStatus(context.getString(R.string.opml_import_read_error))
            } finally {
                onBusyChange(false)
            }
        }
    }

    LaunchedEffect(launchImport) {
        if (launchImport) {
            onImportLaunchConsumed()
            importLauncher.launch(OPML_IMPORT_MIME_TYPES)
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(OPML_EXPORT_MIME_TYPE),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            onBusyChange(true)
            onStatus(context.getString(R.string.opml_exporting))
            try {
                val feedCount = manager.feeds().size
                val content = withContext(Dispatchers.Default) { manager.exportOpml() }
                withContext(Dispatchers.IO) {
                    writeOpmlDocument(context.contentResolver, uri, content)
                }
                onStatus(opmlExportStatus(feedCount))
            } catch (_: Exception) {
                onStatus(context.getString(R.string.opml_export_write_error))
            } finally {
                onBusyChange(false)
            }
        }
    }

    pendingPreview?.let { preview ->
        AlertDialog(
            onDismissRequest = { if (!busy) pendingPreview = null },
            title = { Text(stringResource(R.string.opml_preview_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        stringResource(
                            R.string.opml_preview_summary,
                            preview.feeds.size,
                            preview.duplicateCount,
                            preview.rejectedCount,
                        )
                    )
                    preview.warnings.forEach { warning ->
                        Text(warning, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = !busy && preview.feeds.isNotEmpty(),
                    onClick = {
                        pendingPreview = null
                        applyPreview(preview)
                    },
                ) {
                    Text(stringResource(R.string.opml_preview_confirm))
                }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { pendingPreview = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.opml_management_title), style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(R.string.opml_management_body),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = { importLauncher.launch(OPML_IMPORT_MIME_TYPES) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
            ) {
                Text(stringResource(R.string.opml_import_button))
            }

            // URL import: the gateway fetches and previews the document (SSRF
            // policy server-side); the phone only shows the confirmation.
            OutlinedTextField(
                value = urlInput,
                onValueChange = { urlInput = it },
                label = { Text(stringResource(R.string.opml_import_from_url_hint)) },
                singleLine = true,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(
                onClick = {
                    val url = urlInput.trim()
                    if (url.isEmpty()) return@OutlinedButton
                    scope.launch {
                        onBusyChange(true)
                        when (val outcome = previewApi.preview(url)) {
                            is OpmlPreviewOutcome.Ready -> pendingPreview = outcome.preview
                            is OpmlPreviewOutcome.Error -> onStatus(context.getString(opmlPreviewErrorMessageRes(outcome.code)))
                        }
                        onBusyChange(false)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy && urlInput.isNotBlank(),
            ) {
                Text(stringResource(R.string.opml_preview_from_url))
            }

            OutlinedButton(
                onClick = { exportLauncher.launch(OPML_EXPORT_FILE_NAME) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
            ) {
                Text(stringResource(R.string.opml_export_button))
            }
        }
    }
}

internal fun opmlPreviewErrorMessageRes(code: String): Int = when (code) {
    "INVALID_URL" -> R.string.opml_preview_error_invalid_url
    "FETCH_BLOCKED_HOST", "REDIRECT_BLOCKED" -> R.string.opml_preview_error_blocked
    "FETCH_TIMEOUT", "network_error" -> R.string.opml_preview_error_timeout
    "HTTP_ERROR" -> R.string.opml_preview_error_http
    "TOO_LARGE" -> R.string.opml_preview_error_too_large
    "MISSING_ROOT", "UNSUPPORTED_ENTITY", "MALFORMED_OUTLINE", "TOO_MANY_FEEDS", "invalid_opml" -> R.string.opml_preview_error_format
    "unauthorized" -> R.string.opml_preview_error_unauthorized
    else -> R.string.opml_preview_error_generic
}
