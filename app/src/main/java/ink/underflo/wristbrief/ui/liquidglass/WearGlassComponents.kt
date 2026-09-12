package ink.underflo.wristbrief.ui.liquidglass

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.Text

@Composable
fun WearPageHeading(
    eyebrow: String?,
    title: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (!eyebrow.isNullOrBlank()) {
            Text(
                text = eyebrow,
                color = WearGlassTokens.TextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
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
fun BriefGlassCard(
    points: List<String>,
    onListen: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    listenContentDescription: String = "Listen to today's brief",
) {
    val shape = WearGlassTokens.MajorShape

    Card(
        onClick = {},
        modifier = modifier
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
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                points.forEach { point ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                        WearIconBullet(
                            tint = WearGlassTokens.TextSecondary,
                            modifier = Modifier.size(12.dp),
                        )
                        Text(
                            text = point,
                            color = WearGlassTokens.TextPrimary,
                            fontSize = 14.sp,
                            lineHeight = 18.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            if (onListen != null) {
                IconButton(
                    onClick = onListen,
                    modifier = Modifier
                        .size(44.dp)
                        .background(WearGlassTokens.SurfaceStrong, WearGlassTokens.Circle)
                        .border(1.dp, WearGlassTokens.Hairline, WearGlassTokens.Circle)
                        .semantics {
                            role = Role.Button
                            contentDescription = listenContentDescription
                        },
                ) {
                    WearIconPlay(
                        tint = WearGlassTokens.TextPrimary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun LibraryPreviewRow(
    title: String,
    meta: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = WearGlassTokens.RowShape

    Card(
        onClick = onClick,
        modifier = modifier
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
                text = title,
                color = WearGlassTokens.TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = meta,
                color = WearGlassTokens.TextSecondary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun SecondaryEntryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
) {
    val shape = WearGlassTokens.RowShape

    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 5.dp)
            .border(1.dp, WearGlassTokens.Hairline, shape),
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = WearGlassTokens.SurfaceStrong,
        ),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            icon()
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
fun SectionGap(height: Dp = 24.dp) {
    Spacer(Modifier.height(height))
}

// Minimal vector canvas icons matching liquid glass style
@Composable
fun WearIconPlay(tint: Color = WearGlassTokens.TextPrimary, modifier: Modifier = Modifier) {
    Canvas(modifier.size(20.dp)) {
        val path = Path().apply {
            moveTo(size.width * 0.25f, size.height * 0.15f)
            lineTo(size.width * 0.85f, size.height * 0.5f)
            lineTo(size.width * 0.25f, size.height * 0.85f)
            close()
        }
        drawPath(path, tint)
    }
}

@Composable
fun WearIconPause(tint: Color = WearGlassTokens.TextPrimary, modifier: Modifier = Modifier) {
    Canvas(modifier.size(20.dp)) {
        val barWidth = size.width * 0.22f
        val gap = size.width * 0.2f
        val left = (size.width - (barWidth * 2 + gap)) / 2f
        drawRect(tint, Offset(left, size.height * 0.18f), Size(barWidth, size.height * 0.64f))
        drawRect(tint, Offset(left + barWidth + gap, size.height * 0.18f), Size(barWidth, size.height * 0.64f))
    }
}

@Composable
fun WearIconBullet(tint: Color = WearGlassTokens.TextPrimary, modifier: Modifier = Modifier) {
    Canvas(modifier.size(12.dp)) {
        drawCircle(tint, radius = size.minDimension * 0.22f)
    }
}

@Composable
fun WearIconBook(tint: Color = WearGlassTokens.TextPrimary, modifier: Modifier = Modifier) {
    Canvas(modifier.size(20.dp)) {
        val stroke = 1.8.dp.toPx()
        val path = Path().apply {
            moveTo(size.width * 0.15f, size.height * 0.2f)
            lineTo(size.width * 0.85f, size.height * 0.2f)
            lineTo(size.width * 0.85f, size.height * 0.82f)
            lineTo(size.width * 0.15f, size.height * 0.82f)
            close()
        }
        drawPath(path, tint, style = Stroke(stroke))
        drawLine(tint, Offset(size.width * 0.35f, size.height * 0.2f), Offset(size.width * 0.35f, size.height * 0.82f), stroke)
    }
}

@Composable
fun WearIconWave(tint: Color = WearGlassTokens.TextPrimary, modifier: Modifier = Modifier) {
    Canvas(modifier.size(20.dp)) {
        val stroke = 2.dp.toPx()
        val xs = listOf(0.2f, 0.4f, 0.6f, 0.8f)
        val heights = listOf(0.4f, 0.7f, 0.5f, 0.85f)
        xs.forEachIndexed { index, xFactor ->
            val h = heights[index] * size.height
            val y = (size.height - h) / 2f
            drawLine(tint, Offset(xFactor * size.width, y), Offset(xFactor * size.width, y + h), stroke)
        }
    }
}

@Composable
fun WearIconSettings(tint: Color = WearGlassTokens.TextPrimary, modifier: Modifier = Modifier) {
    Canvas(modifier.size(20.dp)) {
        val stroke = 1.8.dp.toPx()
        drawCircle(tint, size.width * 0.22f, style = Stroke(stroke))
        repeat(6) { i ->
            val angle = Math.toRadians((i * 60).toDouble())
            val inner = size.width * 0.28f
            val outer = size.width * 0.44f
            drawLine(
                tint,
                Offset(center.x + kotlin.math.cos(angle).toFloat() * inner, center.y + kotlin.math.sin(angle).toFloat() * inner),
                Offset(center.x + kotlin.math.cos(angle).toFloat() * outer, center.y + kotlin.math.sin(angle).toFloat() * outer),
                stroke,
            )
        }
    }
}

@Composable
fun WearIconBack(tint: Color = WearGlassTokens.TextPrimary, modifier: Modifier = Modifier) {
    Canvas(modifier.size(20.dp)) {
        val stroke = 2.dp.toPx()
        val path = Path().apply {
            moveTo(size.width * 0.6f, size.height * 0.2f)
            lineTo(size.width * 0.3f, size.height * 0.5f)
            lineTo(size.width * 0.6f, size.height * 0.8f)
        }
        drawPath(path, tint, style = Stroke(stroke))
    }
}
