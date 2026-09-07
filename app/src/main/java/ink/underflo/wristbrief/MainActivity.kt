package ink.underflo.wristbrief

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewmodel.compose.viewModel
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
import ink.underflo.wristbrief.ui.InboxUiState
import ink.underflo.wristbrief.ui.InboxViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { WristBriefApp() }
    }
}

@Composable
private fun WristBriefApp(viewModel: InboxViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsState()

    MaterialTheme {
        AppScaffold {
            InboxScreen(
                state = state,
                onItemClick = {},
                onRefresh = viewModel::refresh
            )
        }
    }
}

/** Wear-first inbox shell following the Material 3 Expressive scrolling model. */
@Composable
internal fun InboxScreen(
    state: InboxUiState,
    onItemClick: (InboxItemUi) -> Unit,
    onRefresh: () -> Unit
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

            if (state.isOfflineFallback) {
                item {
                    ListHeader {
                        Text("Offline · showing cached briefs")
                    }
                }
            }

            if (state.items.isEmpty()) {
                item {
                    val label = when {
                        state.isLoading -> "Refreshing…"
                        state.hasSubscriptions -> "No briefs yet"
                        else -> "No feeds yet"
                    }
                    val detail = when {
                        state.errorMessage != null -> state.errorMessage
                        state.hasSubscriptions -> "Refresh to fetch your latest briefs"
                        else -> "Add feeds from the phone companion"
                    }
                    Button(
                        onClick = onRefresh,
                        enabled = state.hasSubscriptions && !state.isLoading,
                        label = { Text(label) },
                        secondaryLabel = {
                            Text(
                                detail,
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
                items(count = state.items.size) { index ->
                    val item = state.items[index]
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

                if (state.errorMessage != null) {
                    item {
                        ListHeader {
                            Text(state.errorMessage)
                        }
                    }
                }

                if (state.hasSubscriptions) {
                    item {
                        Button(
                            onClick = onRefresh,
                            enabled = !state.isLoading,
                            label = { Text(if (state.isLoading) "Refreshing…" else "Refresh") },
                            transformation = SurfaceTransformation(transformationSpec),
                            modifier = Modifier
                                .transformedHeight(this, transformationSpec)
                                .fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}
