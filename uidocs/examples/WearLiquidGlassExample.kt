package ink.underflo.wristbrief.uidocs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text

/**
 * Documentation example for the approved WristBrief Wear OS direction.
 *
 * Key behavior:
 * 1. First viewport = AI key-point summary.
 * 2. Crown/scroll down = latest library content.
 * 3. Continue down = Library / Now Playing / Settings entry buttons.
 *
 * This intentionally uses Wear Compose Material 3, not phone Material 3.
 */

private object WearGlassTokens {
    val Canvas = Color.Black
    val SurfaceSubtle = Color.White.copy(alpha = 0.09f)
    val SurfaceStrong = Color.White.copy(alpha = 0.13f)
    val Hairline = Color.White.copy(alpha = 0.16f)
    val TextPrimary = Color(0xFFF5F7FA)
    val TextSecondary = Color(0xFFA7AFBA)

    val MajorShape = RoundedCornerShape(30.dp)
    val RowShape = RoundedCornerShape(24.dp)
}

data class BriefPoint(val text: String)

data class LibraryPreviewItem(
    val title: String,
    val meta: String,
)

@Composable
fun WristBriefWearHomeExample(
    points: List<BriefPoint> = listOf(
        BriefPoint("Markets rise as tech leads gains"),
        BriefPoint("New AI tool boosts productivity"),
        BriefPoint("Global climate summit sets bolder targets"),
    ),
    latest: List<LibraryPreviewItem> = listOf(
        LibraryPreviewItem("The Next Climate Frontier", "Article · 5 min"),
        LibraryPreviewItem("AI at Work: What's Changing", "Podcast · 28 min"),
    ),
    onListen: () -> Unit = {},
    onOpenLibrary: () -> Unit = {},
    onOpenNowPlaying: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    AppScaffold(modifier = Modifier.background(WearGlassTokens.Canvas)) {
        val state = rememberTransformingLazyColumnState()

        ScreenScaffold(scrollState = state) { contentPadding ->
            TransformingLazyColumn(
                state = state,
                modifier = Modifier
                    .fillMaxSize()
                    .background(WearGlassTokens.Canvas),
                contentPadding = contentPadding,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Stage 1 — the opening viewport stays focused on the AI summary.
                item {
                    WearPageHeading(
                        eyebrow = "WristBrief",
                        title = "Today's Brief",
                    )
                }

                item {
                    BriefGlassCard(
                        points = points.take(3),
                        onListen = onListen,
                    )
                }

                item { SectionGap() }

                // Stage 2 — latest library preview appears only after scrolling down.
                item {
                    WearPageHeading(
                        eyebrow = null,
                        title = "Latest in Library",
                    )
                }

                latest.take(2).forEach { item ->
                    item {
                        LibraryPreviewRow(item = item)
                    }
                }

                item { SectionGap() }

                // Stage 3 — navigation hub for secondary destinations.
                item {
                    WearPageHeading(
                        eyebrow = null,
                        title = "More",
                    )
                }

                item {
                    SecondaryEntryButton(
                        label = "Library",
                        icon = Icons.Outlined.Book,
                        onClick = onOpenLibrary,
                    )
                }
                item {
                    SecondaryEntryButton(
                        label = "Now Playing",
                        icon = Icons.Outlined.GraphicEq,
                        onClick = onOpenNowPlaying,
                    )
                }
                item {
                    SecondaryEntryButton(
                        label = "Settings",
                        icon = Icons.Outlined.Settings,
                        onClick = onOpenSettings,
                    )
                }

                item { Spacer(Modifier.height(20.dp)) }
            }
        }
    }
}

@Composable
private fun WearPageHeading(
    eyebrow: String?,
    title: String,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (eyebrow != null) {
            Text(
                text = eyebrow,
                color = WearGlassTokens.TextSecondary,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(4.dp))
        }
        Text(
            text = title,
            color = WearGlassTokens.TextPrimary,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun BriefGlassCard(
    points: List<BriefPoint>,
    onListen: () -> Unit,
) {
    val shape = WearGlassTokens.MajorShape

    Card(
        onClick = {},
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .border(1.dp, WearGlassTokens.Hairline, shape),
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = WearGlassTokens.SurfaceSubtle,
        ),
        contentPadding = PaddingValues(14.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                points.forEach { point ->
                    Text(
                        text = "• ${point.text}",
                        color = WearGlassTokens.TextPrimary,
                        fontSize = 14.sp,
                        lineHeight = 18.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            IconButton(
                onClick = onListen,
                modifier = Modifier
                    .size(44.dp)
                    .background(WearGlassTokens.SurfaceStrong, CircleShape)
                    .border(1.dp, WearGlassTokens.Hairline, CircleShape),
            ) {
                Icon(
                    imageVector = Icons.Outlined.PlayArrow,
                    contentDescription = "Listen to today's brief",
                    tint = WearGlassTokens.TextPrimary,
                )
            }
        }
    }
}

@Composable
private fun LibraryPreviewRow(item: LibraryPreviewItem) {
    val shape = WearGlassTokens.RowShape

    Card(
        onClick = {},
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 5.dp)
            .border(1.dp, WearGlassTokens.Hairline, shape),
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = WearGlassTokens.SurfaceSubtle,
        ),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = item.title,
                color = WearGlassTokens.TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = item.meta,
                color = WearGlassTokens.TextSecondary,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun SecondaryEntryButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    val shape = WearGlassTokens.RowShape

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 5.dp)
            .border(1.dp, WearGlassTokens.Hairline, shape),
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = WearGlassTokens.SurfaceStrong,
        ),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = WearGlassTokens.TextPrimary,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = label,
                color = WearGlassTokens.TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun SectionGap() {
    Spacer(Modifier.height(24.dp))
}