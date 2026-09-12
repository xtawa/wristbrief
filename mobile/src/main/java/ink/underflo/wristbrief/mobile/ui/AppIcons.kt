package ink.underflo.wristbrief.mobile.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.unit.dp

/** Small, type-safe line icons used instead of Unicode glyphs in the UI. */
@Composable
fun AppIcon(
    kind: AppIconKind,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    val iconTint = tint
    Canvas(modifier) {
        val stroke = 2.1.dp.toPx()
        val centerX = size.width / 2f
        val centerY = size.height / 2f
        when (kind) {
            AppIconKind.Play -> {
                val path = Path().apply {
                    moveTo(size.width * 0.38f, size.height * 0.25f)
                    lineTo(size.width * 0.72f, centerY)
                    lineTo(size.width * 0.38f, size.height * 0.75f)
                    close()
                }
                drawPath(path, iconTint)
            }
            AppIconKind.Pause -> {
                drawRoundRect(iconTint, topLeft = androidx.compose.ui.geometry.Offset(size.width * 0.30f, size.height * 0.24f), size = androidx.compose.ui.geometry.Size(size.width * 0.14f, size.height * 0.52f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.5.dp.toPx()))
                drawRoundRect(iconTint, topLeft = androidx.compose.ui.geometry.Offset(size.width * 0.56f, size.height * 0.24f), size = androidx.compose.ui.geometry.Size(size.width * 0.14f, size.height * 0.52f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.5.dp.toPx()))
            }
            AppIconKind.Close -> {
                drawLine(iconTint, androidx.compose.ui.geometry.Offset(size.width * 0.28f, size.height * 0.28f), androidx.compose.ui.geometry.Offset(size.width * 0.72f, size.height * 0.72f), stroke, androidx.compose.ui.graphics.StrokeCap.Round)
                drawLine(iconTint, androidx.compose.ui.geometry.Offset(size.width * 0.72f, size.height * 0.28f), androidx.compose.ui.geometry.Offset(size.width * 0.28f, size.height * 0.72f), stroke, androidx.compose.ui.graphics.StrokeCap.Round)
            }
            AppIconKind.Check -> {
                drawLine(iconTint, androidx.compose.ui.geometry.Offset(size.width * 0.22f, centerY), androidx.compose.ui.geometry.Offset(size.width * 0.43f, size.height * 0.70f), stroke, androidx.compose.ui.graphics.StrokeCap.Round)
                drawLine(iconTint, androidx.compose.ui.geometry.Offset(size.width * 0.43f, size.height * 0.70f), androidx.compose.ui.geometry.Offset(size.width * 0.78f, size.height * 0.30f), stroke, androidx.compose.ui.graphics.StrokeCap.Round)
            }
            AppIconKind.Add -> {
                drawLine(iconTint, androidx.compose.ui.geometry.Offset(centerX, size.height * 0.24f), androidx.compose.ui.geometry.Offset(centerX, size.height * 0.76f), stroke, androidx.compose.ui.graphics.StrokeCap.Round)
                drawLine(iconTint, androidx.compose.ui.geometry.Offset(size.width * 0.24f, centerY), androidx.compose.ui.geometry.Offset(size.width * 0.76f, centerY), stroke, androidx.compose.ui.graphics.StrokeCap.Round)
            }
            AppIconKind.Bookmark -> {
                val path = Path().apply {
                    moveTo(size.width * 0.30f, size.height * 0.20f)
                    lineTo(size.width * 0.70f, size.height * 0.20f)
                    lineTo(size.width * 0.70f, size.height * 0.80f)
                    lineTo(centerX, size.height * 0.62f)
                    lineTo(size.width * 0.30f, size.height * 0.80f)
                    close()
                }
                drawPath(path, iconTint, style = Stroke(stroke))
            }
            AppIconKind.BookmarkFilled -> {
                val path = Path().apply {
                    moveTo(size.width * 0.30f, size.height * 0.20f)
                    lineTo(size.width * 0.70f, size.height * 0.20f)
                    lineTo(size.width * 0.70f, size.height * 0.80f)
                    lineTo(centerX, size.height * 0.62f)
                    lineTo(size.width * 0.30f, size.height * 0.80f)
                    close()
                }
                drawPath(path, iconTint)
            }
            AppIconKind.Audio -> {
                drawRoundRect(iconTint, topLeft = androidx.compose.ui.geometry.Offset(size.width * 0.18f, size.height * 0.43f), size = androidx.compose.ui.geometry.Size(size.width * 0.10f, size.height * 0.14f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()))
                drawRoundRect(iconTint, topLeft = androidx.compose.ui.geometry.Offset(size.width * 0.36f, size.height * 0.28f), size = androidx.compose.ui.geometry.Size(size.width * 0.10f, size.height * 0.44f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()))
                drawRoundRect(iconTint, topLeft = androidx.compose.ui.geometry.Offset(size.width * 0.54f, size.height * 0.18f), size = androidx.compose.ui.geometry.Size(size.width * 0.10f, size.height * 0.64f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()))
                drawRoundRect(iconTint, topLeft = androidx.compose.ui.geometry.Offset(size.width * 0.72f, size.height * 0.36f), size = androidx.compose.ui.geometry.Size(size.width * 0.10f, size.height * 0.28f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()))
            }
            AppIconKind.Bullet -> drawCircle(iconTint, radius = size.minDimension * 0.12f)
            AppIconKind.Spark -> {
                drawLine(iconTint, androidx.compose.ui.geometry.Offset(centerX, size.height * 0.16f), androidx.compose.ui.geometry.Offset(centerX, size.height * 0.84f), stroke, androidx.compose.ui.graphics.StrokeCap.Round)
                drawLine(iconTint, androidx.compose.ui.geometry.Offset(size.width * 0.16f, centerY), androidx.compose.ui.geometry.Offset(size.width * 0.84f, centerY), stroke, androidx.compose.ui.graphics.StrokeCap.Round)
                drawLine(iconTint, androidx.compose.ui.geometry.Offset(size.width * 0.30f, size.height * 0.30f), androidx.compose.ui.geometry.Offset(size.width * 0.70f, size.height * 0.70f), stroke, androidx.compose.ui.graphics.StrokeCap.Round)
                drawLine(iconTint, androidx.compose.ui.geometry.Offset(size.width * 0.70f, size.height * 0.30f), androidx.compose.ui.geometry.Offset(size.width * 0.30f, size.height * 0.70f), stroke, androidx.compose.ui.graphics.StrokeCap.Round)
            }
            AppIconKind.Microphone -> {
                drawRoundRect(iconTint, topLeft = androidx.compose.ui.geometry.Offset(size.width * 0.38f, size.height * 0.18f), size = androidx.compose.ui.geometry.Size(size.width * 0.24f, size.height * 0.42f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()), style = Stroke(stroke))
                drawArc(iconTint, 0f, 180f, false, androidx.compose.ui.geometry.Offset(size.width * 0.26f, size.height * 0.38f), androidx.compose.ui.geometry.Size(size.width * 0.48f, size.height * 0.42f), style = Stroke(stroke))
                drawLine(iconTint, androidx.compose.ui.geometry.Offset(centerX, size.height * 0.80f), androidx.compose.ui.geometry.Offset(centerX, size.height * 0.65f), stroke, androidx.compose.ui.graphics.StrokeCap.Round)
                drawLine(iconTint, androidx.compose.ui.geometry.Offset(size.width * 0.36f, size.height * 0.82f), androidx.compose.ui.geometry.Offset(size.width * 0.64f, size.height * 0.82f), stroke, androidx.compose.ui.graphics.StrokeCap.Round)
            }
            AppIconKind.Radio -> {
                drawCircle(iconTint, radius = size.minDimension * 0.10f, center = androidx.compose.ui.geometry.Offset(centerX, centerY))
                drawArc(iconTint, 210f, 120f, false, androidx.compose.ui.geometry.Offset(size.width * 0.25f, size.height * 0.25f), androidx.compose.ui.geometry.Size(size.width * 0.50f, size.height * 0.50f), style = Stroke(stroke))
                drawArc(iconTint, 210f, 120f, false, androidx.compose.ui.geometry.Offset(size.width * 0.10f, size.height * 0.10f), androidx.compose.ui.geometry.Size(size.width * 0.80f, size.height * 0.80f), style = Stroke(stroke))
            }
            AppIconKind.Import -> {
                drawLine(iconTint, androidx.compose.ui.geometry.Offset(centerX, size.height * 0.18f), androidx.compose.ui.geometry.Offset(centerX, size.height * 0.62f), stroke, androidx.compose.ui.graphics.StrokeCap.Round)
                drawLine(iconTint, androidx.compose.ui.geometry.Offset(size.width * 0.30f, size.height * 0.48f), androidx.compose.ui.geometry.Offset(centerX, size.height * 0.68f), stroke, androidx.compose.ui.graphics.StrokeCap.Round)
                drawLine(iconTint, androidx.compose.ui.geometry.Offset(size.width * 0.70f, size.height * 0.48f), androidx.compose.ui.geometry.Offset(centerX, size.height * 0.68f), stroke, androidx.compose.ui.graphics.StrokeCap.Round)
                drawLine(iconTint, androidx.compose.ui.geometry.Offset(size.width * 0.22f, size.height * 0.82f), androidx.compose.ui.geometry.Offset(size.width * 0.78f, size.height * 0.82f), stroke, androidx.compose.ui.graphics.StrokeCap.Round)
            }
            AppIconKind.Export -> {
                drawLine(iconTint, androidx.compose.ui.geometry.Offset(centerX, size.height * 0.82f), androidx.compose.ui.geometry.Offset(centerX, size.height * 0.38f), stroke, androidx.compose.ui.graphics.StrokeCap.Round)
                drawLine(iconTint, androidx.compose.ui.geometry.Offset(size.width * 0.30f, size.height * 0.52f), androidx.compose.ui.geometry.Offset(centerX, size.height * 0.32f), stroke, androidx.compose.ui.graphics.StrokeCap.Round)
                drawLine(iconTint, androidx.compose.ui.geometry.Offset(size.width * 0.70f, size.height * 0.52f), androidx.compose.ui.geometry.Offset(centerX, size.height * 0.32f), stroke, androidx.compose.ui.graphics.StrokeCap.Round)
                drawLine(iconTint, androidx.compose.ui.geometry.Offset(size.width * 0.22f, size.height * 0.18f), androidx.compose.ui.geometry.Offset(size.width * 0.78f, size.height * 0.18f), stroke, androidx.compose.ui.graphics.StrokeCap.Round)
            }
            AppIconKind.Refresh -> {
                drawArc(iconTint, -55f, 285f, false, androidx.compose.ui.geometry.Offset(size.width * 0.18f, size.height * 0.18f), androidx.compose.ui.geometry.Size(size.width * 0.64f, size.height * 0.64f), style = Stroke(stroke))
                drawLine(iconTint, androidx.compose.ui.geometry.Offset(size.width * 0.75f, size.height * 0.20f), androidx.compose.ui.geometry.Offset(size.width * 0.75f, size.height * 0.42f), stroke, androidx.compose.ui.graphics.StrokeCap.Round)
                drawLine(iconTint, androidx.compose.ui.geometry.Offset(size.width * 0.75f, size.height * 0.20f), androidx.compose.ui.geometry.Offset(size.width * 0.55f, size.height * 0.20f), stroke, androidx.compose.ui.graphics.StrokeCap.Round)
            }
        }
    }
}

enum class AppIconKind {
    Play,
    Pause,
    Close,
    Check,
    Add,
    Bookmark,
    BookmarkFilled,
    Audio,
    Bullet,
    Spark,
    Microphone,
    Radio,
    Import,
    Export,
    Refresh,
}
