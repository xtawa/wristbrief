package ink.underflo.wristbrief

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TitleCard
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import ink.underflo.wristbrief.ui.InboxItemUi

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { WristBriefApp() }
    }
}

@Composable
private fun WristBriefApp() {
    MaterialTheme {
        AppScaffold {
            InboxScreen(
                items = emptyList(),
                onItemClick = {}
            )
        }
    }
}

/**
 * Wear-first inbox shell built on the Material 3 Expressive scrolling model.
 * Data is intentionally injected so feed persistence/networking can evolve
 * independently from the round-screen UI.
 */
@Composable
internal fun InboxScreen(
    items: List<InboxItemUi>,
    onItemClick: (InboxItemUi) -> Unit
) {
    val listState = rememberTransformingLazyColumnState()
    val transformationSpec = rememberTransformationSpec()

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize()
        ) {
            item {
                ListHeader {
                    Text("WristBrief")
                }
            }

            if (items.isEmpty()) {
                item {
                    Button(
                        onClick = {},
                        enabled = false,
                        label = { Text("No briefs yet") },
                        secondaryLabel = {
                            Text(
                                "Your RSS and podcast inbox will appear here",
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        transformation = SurfaceTransformation(transformationSpec),
                        modifier = Modifier
                            .transformedHeight(this, transformationSpec)
                            .fillMaxWidth()
                    )
                }
            } else {
                items(count = items.size) { index ->
                    val item = items[index]
                    TitleCard(
                        onClick = { onItemClick(item) },
                        title = {
                            Text(
                                item.title,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        subtitle = {
                            Text(
                                if (item.isPodcast) "Podcast · ${item.source}" else item.source,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        time = if (item.timeLabel.isBlank()) null else {
                            { Text(item.timeLabel, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        },
                        transformation = SurfaceTransformation(transformationSpec),
                        modifier = Modifier
                            .transformedHeight(this, transformationSpec)
                            .fillMaxWidth()
                    ) {
                        Text(
                            item.summary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
