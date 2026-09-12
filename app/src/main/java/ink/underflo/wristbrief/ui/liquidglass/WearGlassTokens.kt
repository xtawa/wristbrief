package ink.underflo.wristbrief.ui.liquidglass

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Design tokens for Wear OS Liquid Glass direction specified in uidocs/WEAR_LIQUID_GLASS_SPEC.md.
 *
 * Rules:
 * - Canvas is black-first (#000000).
 * - Surfaces are neutral subtle translucent planes (no decorative gradients).
 * - Hairline borders (#FFFFFF @ 16%).
 * - Major card shape is 30dp, row shape is 24dp.
 */
object WearGlassTokens {
    val Canvas: Color = Color(0xFF000000)
    val SurfaceSubtle: Color = Color.White.copy(alpha = 0.09f)
    val SurfaceStrong: Color = Color.White.copy(alpha = 0.13f)
    val Hairline: Color = Color.White.copy(alpha = 0.16f)
    val TextPrimary: Color = Color(0xFFF5F7FA)
    val TextSecondary: Color = Color(0xFFA7AFBA)
    val Shadow: Color = Color.Black.copy(alpha = 0.35f)

    val MajorShape: RoundedCornerShape = RoundedCornerShape(30.dp)
    val RowShape: RoundedCornerShape = RoundedCornerShape(24.dp)
    val PillShape: RoundedCornerShape = RoundedCornerShape(999.dp)
    val Circle: RoundedCornerShape = RoundedCornerShape(percent = 50)
}
