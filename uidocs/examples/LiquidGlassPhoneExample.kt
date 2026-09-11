@file:Suppress("UnusedPrivateMember")

package ink.underflo.wristbrief.uidocs

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Documentation-only sample for the approved WristBrief phone visual direction.
 *
 * Constraints demonstrated here:
 * - phone-side Compose only; do not copy this into Wear UI
 * - no gradient Brush usage
 * - translucent neutral surfaces
 * - thin neutral borders
 * - diffuse monochrome elevation
 * - selected controls use flat neutral fills
 *
 * This file is intentionally dependency-light so the patterns map onto the current
 * mobile module's Compose + Material 3 stack.
 */

private object WristBriefGlassTokens {
    val CanvasLight = Color(0xFFF4F5F7)
    val TextPrimaryLight = Color(0xFF111317)
    val TextSecondaryLight = Color(0xFF646B75)
    val HairlineLight = Color(0xC6D9DEE6)
    val SelectedLight = Color(0xFF17191D)

    val SurfaceGlassLight = Color.White.copy(alpha = 0.62f)
    val SurfaceGlassStrongLight = Color.White.copy(alpha = 0.84f)

    val CardRadius = 24.dp
    val ControlRadius = 999.dp
}

@Composable
private fun GlassSurface(
    modifier: Modifier = Modifier,
    strong: Boolean = false,
    cornerRadius: Dp = WristBriefGlassTokens.CardRadius,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(cornerRadius)
    val fill = if (strong) {
        WristBriefGlassTokens.SurfaceGlassStrongLight
    } else {
        WristBriefGlassTokens.SurfaceGlassLight
    }

    Box(
        modifier = modifier
            .shadow(
                elevation = if (strong) 12.dp else 6.dp,
                shape = shape,
                ambientColor = Color.Black.copy(alpha = 0.08f),
                spotColor = Color.Black.copy(alpha = 0.10f),
            )
            .clip(shape)
            .background(fill)
            .border(
                border = BorderStroke(1.dp, WristBriefGlassTokens.HairlineLight),
                shape = shape,
            ),
    ) {
        content()
    }
}

@Composable
private fun NeutralFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val background = if (selected) {
        WristBriefGlassTokens.SelectedLight
    } else {
        Color.White.copy(alpha = 0.56f)
    }
    val foreground = if (selected) Color.White else WristBriefGlassTokens.TextPrimaryLight

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(WristBriefGlassTokens.ControlRadius))
            .background(background)
            .border(
                1.dp,
                if (selected) Color.Transparent else WristBriefGlassTokens.HairlineLight,
                RoundedCornerShape(WristBriefGlassTokens.ControlRadius),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(
            text = label,
            color = foreground,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun DailyBriefCard(
    onPlay: () -> Unit,
) {
    GlassSurface(
        modifier = Modifier.fillMaxWidth(),
        strong = true,
        cornerRadius = 28.dp,
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Today's Brief",
                    color = WristBriefGlassTokens.TextPrimaryLight,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "8 key insights · 6 min",
                    color = WristBriefGlassTokens.TextSecondaryLight,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(WristBriefGlassTokens.SelectedLight)
                    .clickable(onClick = onPlay),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "▶",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}

@Composable
private fun ArticleRow(
    source: String,
    title: String,
    meta: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.size(56.dp),
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFFE4E7EB),
        ) {}

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = source,
                color = WristBriefGlassTokens.TextSecondaryLight,
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                text = title,
                color = WristBriefGlassTokens.TextPrimaryLight,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = meta,
                color = WristBriefGlassTokens.TextSecondaryLight,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun GlassBottomBar(
    items: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    GlassSurface(
        modifier = Modifier.fillMaxWidth(),
        strong = true,
        cornerRadius = 30.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            items.forEachIndexed { index, label ->
                val selected = selectedIndex == index
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(WristBriefGlassTokens.ControlRadius))
                        .background(
                            if (selected) WristBriefGlassTokens.SelectedLight
                            else Color.Transparent,
                        )
                        .clickable { onSelect(index) }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Text(
                        text = label,
                        color = if (selected) Color.White else WristBriefGlassTokens.TextSecondaryLight,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}

@Composable
fun PhoneLiquidGlassHomeExample(
    selectedTab: Int,
    onSelectTab: (Int) -> Unit,
    onPlayBrief: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(WristBriefGlassTokens.CanvasLight),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            Text(
                text = "Good morning,",
                color = WristBriefGlassTokens.TextPrimaryLight,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Here's your brief for today.",
                color = WristBriefGlassTokens.TextSecondaryLight,
                style = MaterialTheme.typography.bodyLarge,
            )

            Spacer(Modifier.height(20.dp))

            DailyBriefCard(onPlay = onPlayBrief)

            Spacer(Modifier.height(16.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NeutralFilterChip("For you", selected = true, onClick = {})
                NeutralFilterChip("Latest", selected = false, onClick = {})
                NeutralFilterChip("Podcasts", selected = false, onClick = {})
            }

            Spacer(Modifier.height(8.dp))

            ArticleRow(
                source = "The Verge",
                title = "The next wave of AI agents",
                meta = "4 min read",
            )
            ArticleRow(
                source = "The Atlantic",
                title = "A calmer internet is possible",
                meta = "6 min read",
            )

            Spacer(modifier = Modifier.weight(1f))

            GlassBottomBar(
                items = listOf("Home", "Explore", "Ask", "Library"),
                selectedIndex = selectedTab,
                onSelect = onSelectTab,
            )
        }
    }
}
