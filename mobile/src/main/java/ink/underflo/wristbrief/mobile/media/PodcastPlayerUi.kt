package ink.underflo.wristbrief.mobile.media

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ink.underflo.wristbrief.mobile.R
import ink.underflo.wristbrief.mobile.ui.AppIcon
import ink.underflo.wristbrief.mobile.ui.AppIconKind
import ink.underflo.wristbrief.mobile.ui.glass.GlassSurface
import ink.underflo.wristbrief.mobile.ui.glass.GlassTokens

@Composable
fun PodcastMiniPlayer(
    state: PodcastPlayerState,
    onExpand: () -> Unit,
    onPlayPause: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    darkTheme: Boolean = isSystemInDarkTheme(),
) {
    val episode = state.currentEpisode ?: return
    if (!state.isVisible) return

    val progress = if (state.durationMs > 0L) {
        (state.currentPositionMs.toFloat() / state.durationMs.toFloat()).coerceIn(0f, 1f)
    } else 0f

    val shape = RoundedCornerShape(GlassTokens.CardRadius)
    val shadowColor = if (darkTheme) GlassTokens.ShadowDark else GlassTokens.ShadowLight

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .shadow(
                elevation = 8.dp,
                shape = shape,
                ambientColor = shadowColor,
                spotColor = shadowColor,
            )
            .clip(shape)
            .background(GlassTokens.surfaceGlassStrong(darkTheme))
            .border(BorderStroke(1.dp, GlassTokens.hairline(darkTheme)), shape),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onExpand),
        ) {
            if (state.durationMs > 0L) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp),
                    color = GlassTokens.controlSelected(darkTheme),
                    trackColor = GlassTokens.hairline(darkTheme),
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = episode.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = GlassTokens.textPrimary(darkTheme),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = episode.feedTitle.ifBlank {
                            if (state.isBuffering) stringResource(R.string.podcast_buffering)
                            else formatPlaybackTime(state.currentPositionMs, state.durationMs)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = GlassTokens.textSecondary(darkTheme),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    val playPauseDesc = stringResource(if (state.isPlaying) R.string.action_pause else R.string.action_play)
                    FilledIconButton(
                        onClick = onPlayPause,
                        modifier = Modifier
                            .size(48.dp)
                            .semantics { contentDescription = playPauseDesc },
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = GlassTokens.controlSelected(darkTheme),
                            contentColor = GlassTokens.onControlSelected(darkTheme),
                        ),
                    ) {
                        AppIcon(
                            kind = if (state.isPlaying) AppIconKind.Pause else AppIconKind.Play,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    val dismissDesc = stringResource(R.string.action_dismiss)
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(48.dp)
                            .semantics { contentDescription = dismissDesc },
                    ) {
                        AppIcon(AppIconKind.Close, Modifier.size(18.dp), GlassTokens.textSecondary(darkTheme))
                    }
                }
            }
        }
    }
}

/**
 * SCREEN 15: Expanded Now Playing Player following Stitch WristBrief Android Design System.
 * Features tactile waveform scrubber, lossless profile badge, and Live Key Insights dock.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PodcastExpandedSheet(
    state: PodcastPlayerState,
    onDismissRequest: () -> Unit,
    onPlayPause: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onSeekBy: (Long) -> Unit,
    onCycleSpeed: () -> Unit,
    onOpenTranscript: (() -> Unit)? = null,
    onAskAiClip: ((title: String, clipContext: String) -> Unit)? = null,
    darkTheme: Boolean = isSystemInDarkTheme(),
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = GlassTokens.SheetRadius, topEnd = GlassTokens.SheetRadius),
        containerColor = GlassTokens.surfaceGlassStrong(darkTheme),
    ) {
        PodcastExpandedContent(
            state = state,
            onPlayPause = onPlayPause,
            onSeekTo = onSeekTo,
            onSeekBy = onSeekBy,
            onCycleSpeed = onCycleSpeed,
            onOpenTranscript = onOpenTranscript,
            onAskAiClip = onAskAiClip,
            onDismiss = onDismissRequest,
            darkTheme = darkTheme,
        )
    }
}

/**
 * Reusable full-screen / expanded player content (Screen 15).
 */
@Composable
fun PodcastExpandedContent(
    state: PodcastPlayerState,
    onPlayPause: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onSeekBy: (Long) -> Unit,
    onCycleSpeed: () -> Unit,
    onOpenTranscript: (() -> Unit)? = null,
    onAskAiClip: ((title: String, clipContext: String) -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
    darkTheme: Boolean = isSystemInDarkTheme(),
) {
    val context = LocalContext.current
    val episode = state.currentEpisode ?: return
    val duration = state.durationMs.coerceAtLeast(1L)
    val progress = (state.currentPositionMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentWidth(Alignment.CenterHorizontally)
            .widthIn(max = 640.dp)
            .padding(horizontal = 24.dp)
            .padding(bottom = 36.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Top Navigation & Route Status Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onDismiss != null) {
                IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp)) {
                    AppIcon(AppIconKind.Close, Modifier.size(20.dp), GlassTokens.textSecondary(darkTheme))
                }
            } else {
                Spacer(Modifier.size(40.dp))
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = stringResource(R.string.nav_now_playing).uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = GlassTokens.accentTeal(darkTheme),
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                )
                Text(
                    text = episode.feedTitle.ifBlank { stringResource(R.string.app_name) },
                    style = MaterialTheme.typography.titleSmall,
                    color = GlassTokens.textPrimary(darkTheme),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Surface(
                shape = RoundedCornerShape(999.dp),
                color = GlassTokens.surfaceContainerHigh(darkTheme),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(GlassTokens.accentTeal(darkTheme)),
                    )
                    Text(
                        text = "HD",
                        style = MaterialTheme.typography.labelSmall,
                        color = GlassTokens.accentTeal(darkTheme),
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }

        // Episode Artwork / Silicon Wafer Graphic
        Surface(
            modifier = Modifier.size(180.dp),
            shape = RoundedCornerShape(28.dp),
            color = GlassTokens.surfaceContainerHigh(darkTheme),
            shadowElevation = 12.dp,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Surface(
                    modifier = Modifier.size(72.dp),
                    shape = CircleShape,
                    color = GlassTokens.primaryContainer(darkTheme),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        AppIcon(AppIconKind.Podcast, Modifier.size(36.dp), GlassTokens.accentTeal(darkTheme))
                    }
                }
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(10.dp),
                    shape = RoundedCornerShape(6.dp),
                    color = GlassTokens.surfaceContainer(darkTheme),
                ) {
                    Text(
                        text = stringResource(R.string.podcast_lossless_badge),
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = GlassTokens.accentTeal(darkTheme),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }

        // Episode Title & Metadata
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = episode.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = GlassTokens.textPrimary(darkTheme),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (episode.feedTitle.isNotBlank()) {
                Text(
                    text = episode.feedTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = GlassTokens.textSecondary(darkTheme),
                    textAlign = TextAlign.Center,
                )
            }
        }

        // Tactile Waveform Scrubber
        val waveformHeights = remember {
            listOf(14, 22, 34, 26, 42, 30, 46, 22, 34, 18, 38, 26, 38, 18, 42, 30, 50, 22, 34, 14, 26, 18, 30, 22)
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Interactive waveform bars
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            val ratio = (offset.x / size.width).coerceIn(0f, 1f)
                            onSeekTo((ratio * duration).toLong())
                        }
                    },
                contentAlignment = Alignment.BottomCenter,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom,
                ) {
                    waveformHeights.forEachIndexed { index, h ->
                        val barFraction = index.toFloat() / waveformHeights.size.toFloat()
                        val isActive = barFraction <= progress
                        val barColor = if (isActive) GlassTokens.accentTeal(darkTheme) else GlassTokens.surfaceContainerHighest(darkTheme)
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 1.5.dp)
                                .height(h.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(barColor),
                        )
                    }
                }
            }

            // Timestamps & Chapter
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = formatPlaybackTime(state.currentPositionMs, 0L),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = GlassTokens.accentTeal(darkTheme),
                )
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = GlassTokens.surfaceContainerHigh(darkTheme),
                ) {
                    Text(
                        text = stringResource(R.string.podcast_chapter_indicator, 1, 4),
                        style = MaterialTheme.typography.labelSmall,
                        color = GlassTokens.textSecondary(darkTheme),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
                val remainingMs = (state.durationMs - state.currentPositionMs).coerceAtLeast(0L)
                Text(
                    text = if (state.durationMs > 0L) "-${formatPlaybackTime(remainingMs, 0L)}" else "--:--",
                    style = MaterialTheme.typography.labelMedium,
                    color = GlassTokens.textSecondary(darkTheme),
                )
            }
        }

        // Primary Playback Controls: -15s, Play/Pause, +30s
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val rewindDesc = stringResource(R.string.action_rewind_15)
            IconButton(
                onClick = { onSeekBy(-15_000L) },
                modifier = Modifier
                    .size(52.dp)
                    .semantics { contentDescription = rewindDesc },
            ) {
                Text(
                    text = "-15s",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = GlassTokens.textPrimary(darkTheme),
                )
            }

            val playPauseDesc = stringResource(if (state.isPlaying) R.string.action_pause else R.string.action_play)
            FilledIconButton(
                onClick = onPlayPause,
                modifier = Modifier
                    .size(68.dp)
                    .semantics { contentDescription = playPauseDesc },
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = GlassTokens.controlSelected(darkTheme),
                    contentColor = GlassTokens.onControlSelected(darkTheme),
                ),
            ) {
                AppIcon(
                    kind = if (state.isPlaying) AppIconKind.Pause else AppIconKind.Play,
                    modifier = Modifier.size(30.dp),
                )
            }

            val ffDesc = stringResource(R.string.action_fast_forward_30)
            IconButton(
                onClick = { onSeekBy(30_000L) },
                modifier = Modifier
                    .size(52.dp)
                    .semantics { contentDescription = ffDesc },
            ) {
                Text(
                    text = "+30s",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = GlassTokens.textPrimary(darkTheme),
                )
            }
        }

        // Secondary Row: Speed, Voice+, Live Transcript
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = onCycleSpeed,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text(
                    text = "${state.playbackSpeed}x",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = GlassTokens.accentTeal(darkTheme),
                )
            }

            Surface(
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp),
                shape = RoundedCornerShape(14.dp),
                color = GlassTokens.surfaceContainerHigh(darkTheme),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = "Voice+",
                        style = MaterialTheme.typography.labelMedium,
                        color = GlassTokens.textSecondary(darkTheme),
                    )
                }
            }

            if (onOpenTranscript != null) {
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .clickable(onClick = onOpenTranscript),
                    shape = RoundedCornerShape(14.dp),
                    color = GlassTokens.primaryContainer(darkTheme),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        AppIcon(AppIconKind.Audio, Modifier.size(14.dp), GlassTokens.accentTeal(darkTheme))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "Live",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = GlassTokens.accentTeal(darkTheme),
                        )
                    }
                }
            }
        }

        // SCREEN 15: AI Live Key Insights (Neutral Glass Dock)
        GlassSurface(
            modifier = Modifier.fillMaxWidth(),
            strong = true,
            cornerRadius = GlassTokens.HeroRadius,
            darkTheme = darkTheme,
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        AppIcon(AppIconKind.Spark, Modifier.size(18.dp), GlassTokens.accentTeal(darkTheme))
                        Text(
                            text = stringResource(R.string.podcast_live_insights),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = GlassTokens.textPrimary(darkTheme),
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = GlassTokens.primaryContainer(darkTheme),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(5.dp)
                                    .clip(CircleShape)
                                    .background(GlassTokens.accentTeal(darkTheme)),
                            )
                            Text(
                                text = stringResource(R.string.podcast_live_syncing),
                                style = MaterialTheme.typography.labelSmall,
                                color = GlassTokens.accentTeal(darkTheme),
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }

                // Active insight takeaways
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Box(
                            modifier = Modifier
                                .padding(top = 6.dp)
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(GlassTokens.accentTeal(darkTheme)),
                        )
                        Text(
                            text = "Edge Silicon Optimization: Speculative token offloading drops packet dependency and delivers microsecond response times.",
                            style = MaterialTheme.typography.bodySmall,
                            color = GlassTokens.textSecondary(darkTheme),
                            lineHeight = 18.sp,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Box(
                            modifier = Modifier
                                .padding(top = 6.dp)
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(GlassTokens.accentTeal(darkTheme)),
                        )
                        Text(
                            text = "Local Vector Retrieval: Quantized embeddings enable fast similarity queries with minimal memory footprint.",
                            style = MaterialTheme.typography.bodySmall,
                            color = GlassTokens.textSecondary(darkTheme),
                            lineHeight = 18.sp,
                        )
                    }
                }

                // Dynamic Exploration Chips
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(999.dp))
                            .clickable {
                                onAskAiClip?.invoke(
                                    episode.title,
                                    "Clip from ${episode.title} (${formatPlaybackTime(state.currentPositionMs, 0L)}): Edge compute and localized AI models."
                                )
                            },
                        shape = RoundedCornerShape(999.dp),
                        color = GlassTokens.primaryContainer(darkTheme),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            AppIcon(AppIconKind.Spark, Modifier.size(12.dp), GlassTokens.accentTeal(darkTheme))
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = stringResource(R.string.podcast_ask_ai_clip),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = GlassTokens.accentTeal(darkTheme),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(999.dp))
                            .clickable {
                                Toast.makeText(context, context.getString(R.string.podcast_highlight_saved), Toast.LENGTH_SHORT).show()
                            },
                        shape = RoundedCornerShape(999.dp),
                        color = GlassTokens.surfaceContainerHigh(darkTheme),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            AppIcon(AppIconKind.Bookmark, Modifier.size(12.dp), GlassTokens.textSecondary(darkTheme))
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = stringResource(R.string.podcast_save_highlight),
                                style = MaterialTheme.typography.labelSmall,
                                color = GlassTokens.textSecondary(darkTheme),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}
