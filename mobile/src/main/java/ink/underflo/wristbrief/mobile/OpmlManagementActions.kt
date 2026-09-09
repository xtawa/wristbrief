package ink.underflo.wristbrief.mobile

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            onBusyChange(true)
            onStatus("Importing OPML and validating feeds…")
            try {
                val result = withContext(Dispatchers.IO) {
                    manager.importOpml(readOpmlDocument(context.contentResolver, uri))
                }
                when (result) {
                    is OpmlImportResult.Success -> {
                        onFeedsChanged(result.feeds)
                        onStatus(opmlImportStatus(result))
                    }
                    is OpmlImportResult.Error -> onStatus(result.message)
                }
            } catch (_: OpmlFormatException) {
                onStatus("Could not import OPML. Check the file format and size.")
            } catch (_: Exception) {
                onStatus("Could not read the selected OPML file.")
            } finally {
                onBusyChange(false)
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(OPML_EXPORT_MIME_TYPE),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            onBusyChange(true)
            onStatus("Exporting subscriptions…")
            try {
                val feedCount = manager.feeds().size
                val content = withContext(Dispatchers.Default) { manager.exportOpml() }
                withContext(Dispatchers.IO) {
                    writeOpmlDocument(context.contentResolver, uri, content)
                }
                onStatus(opmlExportStatus(feedCount))
            } catch (_: Exception) {
                onStatus("Could not write the OPML export.")
            } finally {
                onBusyChange(false)
            }
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Import or export OPML", style = MaterialTheme.typography.titleLarge)
            Text(
                "Move feed subscriptions between WristBrief and other RSS apps. Imported feeds are validated before they are saved or synced to Wear.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = { importLauncher.launch(OPML_IMPORT_MIME_TYPES) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
            ) {
                Text("Import OPML file")
            }
            OutlinedButton(
                onClick = { exportLauncher.launch(OPML_EXPORT_FILE_NAME) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
            ) {
                Text("Export OPML file")
            }
        }
    }
}
