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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ink.underflo.wristbrief.mobile.R
import ink.underflo.wristbrief.mobile.media.formatPlaybackTime
import ink.underflo.wristbrief.mobile.ui.AppIcon
import ink.underflo.wristbrief.mobile.ui.AppIconKind
import ink.underflo.wristbrief.mobile.ui.BackIconButton
import ink.underflo.wristbrief.mobile.ui.MobileSpacing
import ink.underflo.wristbrief.mobile.ui.TouchTargetTokens
import ink.underflo.wristbrief.mobile.ui.glass.GlassHeader
import ink.underflo.wristbrief.mobile.ui.glass.GlassTokens

/**
 * Transcript reader.
 *
 * Only clip-level timings exist (the server splits audio into 4-minute clips and the
 * provider returns one timed segment per clip), so this screen positions by segment and
 * says so explicitly. It never offers word-level seeking or per-word highlighting.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranscriptViewerDestination(
    state: TranscriptUiState,
    onBack: () -> Unit,
    onSeekToMs: (Long) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    darkTheme: Boolean = androidx.compose.foundation.isSystemInDarkTheme(),
) {
    val subtitle = (state as? TranscriptUiState.Completed)?.title
        ?: stringResource(R.string.podcast_player_title)
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            GlassHeader(
                title = stringResource(R.string.transcript_title),
                subtitle = subtitle,
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
                is TranscriptUiState.Checking -> StatusPanel(
                    testTag = "transcript_checking_state",
                    title = stringResource(R.string.transcript_state_checking),
                    body = stringResource(R.string.transcript_state_checking_body),
                    darkTheme = darkTheme,
                    statusLabel = stringResource(R.string.transcript_state_checking),
                )

                is TranscriptUiState.Queued -> StatusPanel(
                    testTag = "transcript_queued_state",
                    title = stringResource(R.string.transcript_state_queued),
                    body = stringResource(R.string.transcript_state_queued_body),
                    darkTheme = darkTheme,
                    statusLabel = stringResource(R.string.transcript_state_queued),
                )

                is TranscriptUiState.Processing -> StatusPanel(
                    testTag = "transcript_processing_state",
                    title = stringResource(R.string.transcript_state_processing),
                    body = stringResource(R.string.transcript_state_processing_body),
                    darkTheme = darkTheme,
                    statusLabel = stringResource(R.string.transcript_state_processing),
                )

                is TranscriptUiState.Failed -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("transcript_error_state")
                        .semantics { liveRegion = LiveRegionMode.Polite },
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = stringResource(R.string.transcript_state_failed),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = GlassTokens.textPrimary(darkTheme),
                    )
                    Spacer(modifier = Modifier.height(MobileSpacing.small))
                    Text(
                        text = stringResource(state.messageRes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(modifier = Modifier.height(MobileSpacing.medium))
                    if (state.retryable) {
                        OutlinedButton(
                            onClick = onRetry,
                            modifier = Modifier
                                .defaultMinSize(minHeight = TouchTargetTokens.minTouchTarget)
                                .testTag("transcript_retry_button"),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.transcript_retry_action),
                                color = GlassTokens.textPrimary(darkTheme),
                            )
                        }
                        Spacer(modifier = Modifier.height(MobileSpacing.small))
                    }
                    Text(
                        text = stringResource(
                            if (state.retryable) {
                                R.string.transcript_retry_hint
                            } else {
                                R.string.transcript_no_retry_hint
                            },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = GlassTokens.textSecondary(darkTheme),
                    )
                }

                is TranscriptUiState.Completed -> {
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
                                TranscriptFactsHeader(
                                    sourceLabelRes = state.sourceLabelRes,
                                    durationMs = state.durationMs,
                                    segmentCount = payload.segments.size,
                                    darkTheme = darkTheme,
                                )
                            }
                            items(payload.segments, key = { it.id }) { segment ->
                                TranscriptSegmentRow(
                                    segment = segment,
                                    onClick = { onSeekToMs(segment.startMs) },
                                    darkTheme = darkTheme,
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
private fun StatusPanel(
    testTag: String,
    title: String,
    body: String,
    darkTheme: Boolean,
    statusLabel: String,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag(testTag)
            .semantics {
                liveRegion = LiveRegionMode.Polite
                stateDescription = statusLabel
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(color = GlassTokens.accentTeal(darkTheme))
        Spacer(modifier = Modifier.height(MobileSpacing.medium))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = GlassTokens.textPrimary(darkTheme),
            modifier = Modifier.semantics { heading() },
        )
        Spacer(modifier = Modifier.height(MobileSpacing.small))
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = GlassTokens.textSecondary(darkTheme),
        )
        Spacer(modifier = Modifier.height(MobileSpacing.medium))
        // Indeterminate only: the gateway does not report a percentage, and inventing
        // one would be a fabricated progress claim.
        LinearProgressIndicator(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            color = GlassTokens.accentTeal(darkTheme),
        )
    }
}

@Composable
private fun TranscriptFactsHeader(
    sourceLabelRes: Int,
    durationMs: Long,
    segmentCount: Int,
    darkTheme: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = MobileSpacing.small),
        verticalArrangement = Arrangement.spacedBy(MobileSpacing.small),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(MobileSpacing.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FactChip(
                text = stringResource(sourceLabelRes),
                darkTheme = darkTheme,
            )
            if (durationMs > 0L) {
                FactChip(
                    text = stringResource(
                        R.string.transcript_duration_label,
                        formatPlaybackTime(durationMs, 0L),
                    ),
                    darkTheme = darkTheme,
                )
            }
            FactChip(
                text = stringResource(R.string.transcript_segment_count, segmentCount),
                darkTheme = darkTheme,
            )
        }
        // Explicit about what a timestamp localizes: the passage, not each word.
        Text(
            text = stringResource(R.string.transcript_positioning_explainer),
            style = MaterialTheme.typography.bodySmall,
            color = GlassTokens.textSecondary(darkTheme),
            modifier = Modifier.testTag("transcript_positioning_explainer"),
        )
    }
}

@Composable
private fun FactChip(text: String, darkTheme: Boolean) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = GlassTokens.secondaryContainer(darkTheme),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = MobileSpacing.small, vertical = 4.dp),
        )
    }
}

@Composable
private fun TranscriptSegmentRow(
    segment: TranscriptSegment,
    onClick: () -> Unit,
    darkTheme: Boolean,
    modifier: Modifier = Modifier,
) {
    val rangeLabel = "${formatPlaybackTime(segment.startMs, 0L)} – ${formatPlaybackTime(segment.endMs, 0L)}"
    val seekLabel = stringResource(R.string.transcript_seek_to, rangeLabel)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .defaultMinSize(minHeight = TouchTargetTokens.minTouchTarget)
            .semantics {
                contentDescription = seekLabel
            },
        color = GlassTokens.surfaceContainer(darkTheme),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MobileSpacing.medium),
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.width(64.dp)) {
                Text(
                    text = formatPlaybackTime(segment.startMs, 0L),
                    style = MaterialTheme.typography.labelSmall,
                    color = GlassTokens.accentTeal(darkTheme),
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = formatPlaybackTime(segment.endMs, 0L),
                    style = MaterialTheme.typography.labelSmall,
                    color = GlassTokens.textSecondary(darkTheme),
                )
            }
            Spacer(modifier = Modifier.width(MobileSpacing.small))
            Column(modifier = Modifier.weight(1f)) {
                if (!segment.speaker.isNullOrBlank()) {
                    // Only shown when the provider actually reported a speaker label.
                    Text(
                        text = segment.speaker,
                        style = MaterialTheme.typography.labelSmall,
                        color = GlassTokens.textSecondary(darkTheme),
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                }
                Text(
                    text = segment.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = GlassTokens.textPrimary(darkTheme),
                )
            }
            Spacer(modifier = Modifier.width(MobileSpacing.small))
            AppIcon(
                kind = AppIconKind.Play,
                modifier = Modifier.size(16.dp),
                tint = GlassTokens.textSecondary(darkTheme),
            )
        }
    }
}
