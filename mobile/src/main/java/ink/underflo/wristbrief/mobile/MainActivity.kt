package ink.underflo.wristbrief.mobile

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { WristBriefMobileApp() }
    }
}

@Composable
fun WristBriefMobileApp() {
    val context = LocalContext.current
    val darkTheme = androidx.compose.foundation.isSystemInDarkTheme()
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && darkTheme -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
        darkTheme -> androidx.compose.material3.darkColorScheme()
        else -> androidx.compose.material3.lightColorScheme()
    }

    MaterialTheme(colorScheme = colorScheme) {
        var destinationName by rememberSaveable { mutableStateOf(initialMobileDestination().name) }
        val destination = MobileDestination.valueOf(destinationName)
        MobileShell(
            destination = destination,
            onDestinationSelected = { destinationName = it.name },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MobileShell(
    destination: MobileDestination,
    onDestinationSelected: (MobileDestination) -> Unit,
) {
    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = {
                    Column {
                        Text("WristBrief", fontWeight = FontWeight.SemiBold)
                        Text(
                            text = destination.label,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                MobileDestination.entries.forEach { item ->
                    NavigationBarItem(
                        selected = item == destination,
                        onClick = { onDestinationSelected(item) },
                        icon = {
                            Surface(
                                shape = MaterialTheme.shapes.large,
                                color = if (item == destination) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    Color.Transparent
                                },
                            ) {
                                Text(
                                    text = item.shortLabel,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        },
                        label = { Text(item.label) },
                    )
                }
            }
        },
    ) { innerPadding ->
        when (destination) {
            MobileDestination.Feeds -> FoundationDestination(
                title = "Feed management",
                description = "Add, edit, pause, and remove subscriptions comfortably from the phone.",
                highlights = listOf(
                    "Phone-first feed entry",
                    "Wear remains usable with its existing local data",
                    "Data Layer sync arrives in the next slot",
                ),
                innerPadding = innerPadding,
            )
            MobileDestination.AiProvider -> FoundationDestination(
                title = "AI & provider settings",
                description = "A dedicated home for provider preferences without exposing gateway secrets in the app.",
                highlights = listOf(
                    "Managed providers remain server-controlled",
                    "Gateway credentials are not embedded here",
                    "Provider controls will grow behind explicit contracts",
                ),
                innerPadding = innerPadding,
            )
            MobileDestination.Membership -> FoundationDestination(
                title = "Membership",
                description = "Subscription and entitlement status will live here once Play Billing foundations are added.",
                highlights = listOf(
                    "No hardcoded prices",
                    "No fake production entitlement",
                    "Billing client foundation is scheduled after phone sync",
                ),
                innerPadding = innerPadding,
            )
        }
    }
}

@Composable
private fun FoundationDestination(
    title: String,
    description: String,
    highlights: List<String>,
    innerPadding: PaddingValues,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text(title, style = MaterialTheme.typography.headlineMedium)
        }
        item {
            Text(
                description,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(highlights) { highlight ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    horizontalAlignment = Alignment.Start,
                ) {
                    Text(highlight, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}
