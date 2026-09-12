package ink.underflo.wristbrief.mobile.ui.glass

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Reusable Liquid Glass primitives for Android Phone companion specified in uidocs/PHONE_LIQUID_GLASS_SPEC.md.
 *
 * Rules:
 * - No decorative gradients (linearGradient, radialGradient, etc. prohibited).
 * - Surfaces are neutral softly translucent planes with 1dp hairline border and diffuse monochrome shadow.
 * - Flat neutral control selection.
 */

@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    strong: Boolean = false,
    cornerRadius: Dp = GlassTokens.CardRadius,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(cornerRadius)
    val fill = if (strong) GlassTokens.surfaceGlassStrong(darkTheme) else GlassTokens.surfaceGlass(darkTheme)
    val hairline = GlassTokens.hairline(darkTheme)
    val shadowColor = if (darkTheme) GlassTokens.ShadowDark else GlassTokens.ShadowLight

    Box(
        modifier = modifier
            .shadow(
                elevation = if (strong) 12.dp else 6.dp,
                shape = shape,
                ambientColor = shadowColor,
                spotColor = shadowColor,
            )
            .clip(shape)
            .background(fill)
            .border(
                border = BorderStroke(1.dp, hairline),
                shape = shape,
            ),
    ) {
        content()
    }
}

@Composable
fun NeutralFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    darkTheme: Boolean = isSystemInDarkTheme(),
) {
    val shape = RoundedCornerShape(GlassTokens.ControlRadius)
    val background = if (selected) {
        GlassTokens.controlSelected(darkTheme)
    } else {
        if (darkTheme) Color.White.copy(alpha = 0.08f) else Color.White.copy(alpha = 0.56f)
    }
    val foreground = if (selected) {
        GlassTokens.onControlSelected(darkTheme)
    } else {
        GlassTokens.textPrimary(darkTheme)
    }
    val borderStroke = if (selected) {
        BorderStroke(1.dp, Color.Transparent)
    } else {
        BorderStroke(1.dp, GlassTokens.hairline(darkTheme))
    }

    Box(
        modifier = modifier
            .clip(shape)
            .background(background)
            .border(borderStroke, shape)
            .clickable(onClick = onClick)
            .semantics { role = Role.Tab }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = foreground,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
fun DailyBriefCard(
    title: String = "Today's Brief",
    subtitle: String,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
    darkTheme: Boolean = isSystemInDarkTheme(),
) {
    GlassSurface(
        modifier = modifier.fillMaxWidth(),
        strong = true,
        cornerRadius = GlassTokens.HeroRadius,
        darkTheme = darkTheme,
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = GlassTokens.textPrimary(darkTheme),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    color = GlassTokens.textSecondary(darkTheme),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(GlassTokens.controlSelected(darkTheme))
                    .clickable(onClick = onPlay)
                    .semantics { role = Role.Button },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "▶",
                    color = GlassTokens.onControlSelected(darkTheme),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}

@Composable
fun ArticleRow(
    source: String,
    title: String,
    meta: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    darkTheme: Boolean = isSystemInDarkTheme(),
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.size(48.dp),
            shape = RoundedCornerShape(14.dp),
            color = if (darkTheme) Color(0xFF2A2E35) else Color(0xFFE4E7EB),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = source.take(2).uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = GlassTokens.textSecondary(darkTheme),
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = source,
                color = GlassTokens.textSecondary(darkTheme),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = title,
                color = GlassTokens.textPrimary(darkTheme),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = meta,
                color = GlassTokens.textSecondary(darkTheme),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
            )
        }
    }
}

@Composable
fun GlassBottomBar(
    items: List<Pair<String, @Composable (Boolean) -> Unit>>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    darkTheme: Boolean = isSystemInDarkTheme(),
) {
    GlassSurface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        strong = true,
        cornerRadius = GlassTokens.HeroRadius,
        darkTheme = darkTheme,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEachIndexed { index, item ->
                val selected = selectedIndex == index
                val label = item.first
                val iconComposable = item.second

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(GlassTokens.ControlRadius))
                        .background(
                            if (selected) GlassTokens.controlSelected(darkTheme)
                            else Color.Transparent,
                        )
                        .clickable { onSelect(index) }
                        .semantics { role = Role.Tab }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        iconComposable(selected)
                        Text(
                            text = label,
                            color = if (selected) GlassTokens.onControlSelected(darkTheme) else GlassTokens.textSecondary(darkTheme),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MiniPlayerGlass(
    title: String,
    feedTitle: String,
    isPlaying: Boolean,
    onExpand: () -> Unit,
    onPlayPause: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    darkTheme: Boolean = isSystemInDarkTheme(),
) {
    GlassSurface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onExpand),
        strong = true,
        cornerRadius = GlassTokens.CardRadius,
        darkTheme = darkTheme,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(38.dp),
                shape = RoundedCornerShape(10.dp),
                color = if (darkTheme) Color(0xFF2A2E35) else Color(0xFFE4E7EB),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = "♪",
                        color = GlassTokens.textSecondary(darkTheme),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = GlassTokens.textPrimary(darkTheme),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = feedTitle,
                    color = GlassTokens.textSecondary(darkTheme),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.width(8.dp))

            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(GlassTokens.controlSelected(darkTheme))
                    .clickable(onClick = onPlayPause)
                    .semantics { role = Role.Button },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (isPlaying) "❚❚" else "▶",
                    color = GlassTokens.onControlSelected(darkTheme),
                    style = MaterialTheme.typography.labelSmall,
                )
            }

            Spacer(Modifier.width(8.dp))

            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onDismiss)
                    .semantics { role = Role.Button },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "✕",
                    color = GlassTokens.textSecondary(darkTheme),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

