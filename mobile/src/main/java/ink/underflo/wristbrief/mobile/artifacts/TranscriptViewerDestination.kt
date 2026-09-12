package ink.underflo.wristbrief.mobile.artifacts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ink.underflo.wristbrief.mobile.R
import ink.underflo.wristbrief.mobile.media.formatPlaybackTime
import ink.underflo.wristbrief.mobile.ui.BackIconButton
import ink.underflo.wristbrief.mobile.ui.MobileSpacing
import ink.underflo.wristbrief.mobile.ui.TouchTargetTokens
import ink.underflo.wristbrief.mobile.ui.glass.GlassHeader
import ink.underflo.wristbrief.mobile.ui.glass.GlassTokens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranscriptViewerDestination(
    state: TranscriptFetchResult,
    onBack: () -> Unit,
    onSeekToMs: (Long) -> Unit,
    modifier: Modifier = Modifier,
    darkTheme: Boolean = androidx.compose.foundation.isSystemInDarkTheme(),
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            GlassHeader(
                title = stringResource(R.string.transcript_title),
                subtitle = stringResource(R.string.podcast_player_title),
                darkTheme = darkTheme,
                navigationIcon = { BackIconButton(onClick = onBack) },
            )
        },
        containerColor = GlassTokens.canvas(darkTheme),
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = MobileSpacing.medium),
        ) {
            when (state) {
                is TranscriptFetchResult.Processing -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("transcript_loading_state"),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(modifier = Modifier.height(MobileSpacing.medium))
                        Text(
                            text = stringResource(R.string.transcript_loading),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                is TranscriptFetchResult.Failure -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("transcript_error_state"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                is TranscriptFetchResult.Ready -> {
                    val payload = state.payload
                    if (payload == null || payload.segments.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("transcript_empty_state"),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = stringResource(R.string.transcript_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("transcript_segments_list"),
                            verticalArrangement = Arrangement.spacedBy(MobileSpacing.small),
                        ) {
                            item {
                                QuotaBadge(
                                    source = state.source,
                                    multiplier = state.quota.multiplier,
                                )
                                Spacer(modifier = Modifier.height(MobileSpacing.small))
                            }
                            items(payload.segments, key = { it.id }) { segment ->
                                TranscriptSegmentRow(
                                    segment = segment,
                                    onClick = { onSeekToMs(segment.startMs) },
                                )
                            }
                            item {
                                Spacer(modifier = Modifier.height(MobileSpacing.large))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuotaBadge(
    source: String,
    multiplier: Float,
    modifier: Modifier = Modifier,
) {
    val text = if (multiplier == 0f || source == "existing_access") {
        stringResource(R.string.transcript_quota_free)
    } else if (multiplier <= 0.25f) {
        stringResource(R.string.transcript_quota_cached)
    } else {
        null
    }

    if (text != null) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = modifier.padding(vertical = MobileSpacing.xsmall),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(horizontal = MobileSpacing.small, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun TranscriptSegmentRow(
    segment: TranscriptSegment,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .defaultMinSize(minHeight = TouchTargetTokens.minTouchTarget),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MobileSpacing.medium),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                text = formatPlaybackTime(segment.startMs, 0L),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.width(48.dp),
            )
            Spacer(modifier = Modifier.width(MobileSpacing.small))
            Text(
                text = segment.text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
