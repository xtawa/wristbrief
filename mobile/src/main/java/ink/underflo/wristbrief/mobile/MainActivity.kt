package ink.underflo.wristbrief.mobile

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { WristBriefMobileApp() } }
}

@Composable fun WristBriefMobileApp() {
    val context = LocalContext.current
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val colors = when { Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(context); Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(context); dark -> androidx.compose.material3.darkColorScheme(); else -> androidx.compose.material3.lightColorScheme() }
    MaterialTheme(colorScheme = colors) {
        var name by rememberSaveable { mutableStateOf(initialMobileDestination().name) }
        MobileShell(MobileDestination.valueOf(name), { name = it.name })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun MobileShell(destination: MobileDestination, select: (MobileDestination) -> Unit) {
    Scaffold(topBar = { LargeTopAppBar(title = { Column { Text("WristBrief", fontWeight = FontWeight.SemiBold); Text(destination.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) } }) }, bottomBar = { NavigationBar { MobileDestination.entries.forEach { item -> NavigationBarItem(selected = item == destination, onClick = { select(item) }, icon = { Surface(shape = MaterialTheme.shapes.large, color = if (item == destination) MaterialTheme.colorScheme.primaryContainer else Color.Transparent) { Text(item.shortLabel, Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium) } }, label = { Text(item.label) }) } } }) { padding ->
        when (destination) {
            MobileDestination.Feeds -> FeedManagementDestination(padding)
            MobileDestination.AiProvider -> FoundationDestination("AI & provider settings", "Provider preferences stay separate from server-held secrets.", listOf("Managed providers remain server-controlled", "Gateway credentials are not embedded here"), padding)
            MobileDestination.Membership -> FoundationDestination("Membership", "Play Billing and entitlement state will live here.", listOf("No hardcoded prices", "No fake production entitlement"), padding)
        }
    }
}

@Composable private fun FeedManagementDestination(padding: PaddingValues) {
    val context = LocalContext.current
    val manager = remember { MobileFeedManager(SharedPreferencesMobileFeedStore(context), HttpFeedProbe(), GoogleWearFeedSyncPublisher(context)) }
    val scope = rememberCoroutineScope()
    var feeds by remember { mutableStateOf(manager.feeds()) }
    var editing by remember { mutableStateOf<MobileFeedSubscription?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Add feeds here; validated changes sync to paired Wear devices.") }
    var busy by remember { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("Feed management", style = MaterialTheme.typography.headlineMedium) }
        item { Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { Button(onClick = { editing = null; showEditor = true }, enabled = !busy) { Text("Add feed") } }
        if (feeds.isEmpty()) item { Text("No phone-managed feeds yet.", style = MaterialTheme.typography.bodyLarge) }
        items(feeds, key = { it.id }) { feed -> Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(feed.title, style = MaterialTheme.typography.titleLarge); Text(feed.url, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text(if (feed.enabled) "Included on Wear" else "Paused"); Switch(checked = feed.enabled, onCheckedChange = { result -> val r = manager.setEnabled(feed.id, result); if (r is FeedMutationResult.Success) feeds = r.feeds }) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedButton(onClick = { editing = feed; showEditor = true }) { Text("Edit") }; TextButton(onClick = { val r = manager.remove(feed.id); if (r is FeedMutationResult.Success) feeds = r.feeds }) { Text("Remove") } }
        } } }
    }

    if (showEditor) FeedEditorDialog(editing, busy, onDismiss = { if (!busy) showEditor = false }, onSave = { url, title -> scope.launch { busy = true; status = "Validating feed…"; val r = if (editing == null) manager.add(url, title) else manager.update(editing!!.id, url, title); busy = false; when (r) { is FeedMutationResult.Success -> { feeds = r.feeds; status = "Saved and queued for Wear sync."; showEditor = false }; is FeedMutationResult.Error -> status = r.message } } })
}

@Composable private fun FeedEditorDialog(feed: MobileFeedSubscription?, busy: Boolean, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var url by remember(feed?.id) { mutableStateOf(feed?.url.orEmpty()) }; var title by remember(feed?.id) { mutableStateOf(feed?.title.orEmpty()) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (feed == null) "Add feed" else "Edit feed") }, text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), label = { Text("HTTPS feed URL") }, singleLine = true); OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text("Name (optional)") }, singleLine = true) } }, confirmButton = { Button(onClick = { onSave(url, title) }, enabled = !busy) { Text(if (busy) "Validating…" else "Save") } }, dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } })
}

@Composable private fun FoundationDestination(title: String, description: String, highlights: List<String>, padding: PaddingValues) { LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) { item { Text(title, style = MaterialTheme.typography.headlineMedium) }; item { Text(description, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }; items(highlights) { Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) { Text(it, Modifier.padding(20.dp), style = MaterialTheme.typography.titleMedium) } } } }
