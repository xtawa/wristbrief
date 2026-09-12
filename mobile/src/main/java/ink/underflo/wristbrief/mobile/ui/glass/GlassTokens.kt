package ink.underflo.wristbrief.mobile.ui.glass

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Design tokens for Phone Liquid Glass direction specified in uidocs/PHONE_LIQUID_GLASS_SPEC.md.
 *
 * Constraints:
 * - No decorative gradients (Brush.linearGradient etc. are strictly prohibited).
 * - Surfaces are neutral softly translucent planes.
 * - Hierarchy comes from opacity, border contrast, and diffuse monochrome elevation.
 */
object GlassTokens {
    // Canvas backgrounds
    val CanvasLight: Color = Color(0xFFF4F5F7)
    val CanvasDark: Color = Color(0xFF111315)

    // Glass planes
    val SurfaceGlassLight: Color = Color.White.copy(alpha = 0.62f)
    val SurfaceGlassDark: Color = Color(0xFF1B1E22).copy(alpha = 0.72f)

    val SurfaceGlassStrongLight: Color = Color.White.copy(alpha = 0.84f)
    val SurfaceGlassStrongDark: Color = Color(0xFF22262B).copy(alpha = 0.86f)

    val SurfaceSolidLight: Color = Color(0xFFFFFFFF)
    val SurfaceSolidDark: Color = Color(0xFF1B1E22)

    // Hairline borders
    val HairlineLight: Color = Color(0xC6D9DEE6)
    val HairlineDark: Color = Color.White.copy(alpha = 0.12f)

    // Typography colors
    val TextPrimaryLight: Color = Color(0xFF111317)
    val TextPrimaryDark: Color = Color(0xFFF5F7FA)

    val TextSecondaryLight: Color = Color(0xFF646B75)
    val TextSecondaryDark: Color = Color(0xFFA7AFBA)

    // Neutral control selection
    val ControlSelectedLight: Color = Color(0xFF17191D)
    val ControlSelectedDark: Color = Color(0xFFF4F5F7)

    val OnControlSelectedLight: Color = Color(0xFFFFFFFF)
    val OnControlSelectedDark: Color = Color(0xFF111317)

    // Diffuse monochrome shadows
    val ShadowLight: Color = Color.Black.copy(alpha = 0.10f)
    val ShadowDark: Color = Color.Black.copy(alpha = 0.28f)

    // Radii vocabulary
    val SheetRadius: Dp = 32.dp
    val HeroRadius: Dp = 28.dp
    val CardRadius: Dp = 24.dp
    val RowRadius: Dp = 18.dp
    val CompactRadius: Dp = 14.dp
    val ControlRadius: Dp = 999.dp

    // Theme-sensitive accessors
    fun canvas(darkTheme: Boolean): Color = if (darkTheme) CanvasDark else CanvasLight
    fun surfaceGlass(darkTheme: Boolean): Color = if (darkTheme) SurfaceGlassDark else SurfaceGlassLight
    fun surfaceGlassStrong(darkTheme: Boolean): Color = if (darkTheme) SurfaceGlassStrongDark else SurfaceGlassStrongLight
    fun surfaceSolid(darkTheme: Boolean): Color = if (darkTheme) SurfaceSolidDark else SurfaceSolidLight
    fun hairline(darkTheme: Boolean): Color = if (darkTheme) HairlineDark else HairlineLight
    fun textPrimary(darkTheme: Boolean): Color = if (darkTheme) TextPrimaryDark else TextPrimaryLight
    fun textSecondary(darkTheme: Boolean): Color = if (darkTheme) TextSecondaryDark else TextSecondaryLight
    fun controlSelected(darkTheme: Boolean): Color = if (darkTheme) ControlSelectedDark else ControlSelectedLight
    fun onControlSelected(darkTheme: Boolean): Color = if (darkTheme) OnControlSelectedDark else OnControlSelectedLight
}
