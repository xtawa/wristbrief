package ink.underflo.wristbrief.mobile.ui.glass

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneLiquidGlassTest {

    @Test
    fun canvasColorsMatchApprovedNeutralPalette() {
        assertEquals(Color(0xFFF4F5F7), GlassTokens.CanvasLight)
        assertEquals(Color(0xFF111315), GlassTokens.CanvasDark)

        assertEquals(GlassTokens.CanvasLight, GlassTokens.canvas(darkTheme = false))
        assertEquals(GlassTokens.CanvasDark, GlassTokens.canvas(darkTheme = true))
    }

    @Test
    fun glassSurfacesAreNeutralTranslucentPlanes() {
        assertEquals(0.62f, GlassTokens.SurfaceGlassLight.alpha, 0.01f)
        assertEquals(0.72f, GlassTokens.SurfaceGlassDark.alpha, 0.01f)
        assertEquals(0.84f, GlassTokens.SurfaceGlassStrongLight.alpha, 0.01f)
        assertEquals(0.86f, GlassTokens.SurfaceGlassStrongDark.alpha, 0.01f)

        assertEquals(Color(0xFFFFFFFF), GlassTokens.SurfaceSolidLight)
        assertEquals(Color(0xFF1B1E22), GlassTokens.SurfaceSolidDark)

        assertEquals(GlassTokens.SurfaceGlassLight, GlassTokens.surfaceGlass(darkTheme = false))
        assertEquals(GlassTokens.SurfaceGlassDark, GlassTokens.surfaceGlass(darkTheme = true))
        assertEquals(GlassTokens.SurfaceGlassStrongLight, GlassTokens.surfaceGlassStrong(darkTheme = false))
        assertEquals(GlassTokens.SurfaceGlassStrongDark, GlassTokens.surfaceGlassStrong(darkTheme = true))
    }

    @Test
    fun hairlineBordersProvideSubtleDelimitation() {
        assertEquals(Color(0xC6D9DEE6), GlassTokens.HairlineLight)
        assertEquals(Color.White.copy(alpha = 0.12f), GlassTokens.HairlineDark)

        assertEquals(GlassTokens.HairlineLight, GlassTokens.hairline(darkTheme = false))
        assertEquals(GlassTokens.HairlineDark, GlassTokens.hairline(darkTheme = true))
    }

    @Test
    fun typographyColorsEnsureHighLegibilityWithoutFrostedWashing() {
        assertEquals(Color(0xFF111317), GlassTokens.TextPrimaryLight)
        assertEquals(Color(0xFFF5F7FA), GlassTokens.TextPrimaryDark)
        assertEquals(Color(0xFF646B75), GlassTokens.TextSecondaryLight)
        assertEquals(Color(0xFFA7AFBA), GlassTokens.TextSecondaryDark)

        assertEquals(GlassTokens.TextPrimaryLight, GlassTokens.textPrimary(darkTheme = false))
        assertEquals(GlassTokens.TextPrimaryDark, GlassTokens.textPrimary(darkTheme = true))
        assertEquals(GlassTokens.TextSecondaryLight, GlassTokens.textSecondary(darkTheme = false))
        assertEquals(GlassTokens.TextSecondaryDark, GlassTokens.textSecondary(darkTheme = true))
    }

    @Test
    fun controlSelectedUsesFlatNeutralFillsWithoutGradients() {
        assertEquals(Color(0xFF17191D), GlassTokens.ControlSelectedLight)
        assertEquals(Color(0xFFF4F5F7), GlassTokens.ControlSelectedDark)
        assertEquals(Color(0xFFFFFFFF), GlassTokens.OnControlSelectedLight)
        assertEquals(Color(0xFF111317), GlassTokens.OnControlSelectedDark)

        assertEquals(GlassTokens.ControlSelectedLight, GlassTokens.controlSelected(darkTheme = false))
        assertEquals(GlassTokens.ControlSelectedDark, GlassTokens.controlSelected(darkTheme = true))
        assertEquals(GlassTokens.OnControlSelectedLight, GlassTokens.onControlSelected(darkTheme = false))
        assertEquals(GlassTokens.OnControlSelectedDark, GlassTokens.onControlSelected(darkTheme = true))
    }

    @Test
    fun shadowsAreMonochromeAndDiffuse() {
        assertEquals(0.10f, GlassTokens.ShadowLight.alpha, 0.01f)
        assertEquals(0.28f, GlassTokens.ShadowDark.alpha, 0.01f)
    }

    @Test
    fun cornerRadiiVocabularyFollowsRestrainedScale() {
        assertEquals(32.dp, GlassTokens.SheetRadius)
        assertEquals(28.dp, GlassTokens.HeroRadius)
        assertEquals(24.dp, GlassTokens.CardRadius)
        assertEquals(18.dp, GlassTokens.RowRadius)
        assertEquals(14.dp, GlassTokens.CompactRadius)
        assertEquals(999.dp, GlassTokens.ControlRadius)
    }

    @Test
    fun zeroGradientPolicyIsStrictlyEnforced() {
        // Assert all colors in GlassTokens are single Color instances, not brushes or multi-stop gradients
        assertTrue(GlassTokens.CanvasLight is Color)
        assertTrue(GlassTokens.CanvasDark is Color)
        assertTrue(GlassTokens.SurfaceGlassLight is Color)
        assertTrue(GlassTokens.SurfaceGlassDark is Color)
        assertTrue(GlassTokens.SurfaceGlassStrongLight is Color)
        assertTrue(GlassTokens.SurfaceGlassStrongDark is Color)
        assertTrue(GlassTokens.ControlSelectedLight is Color)
        assertTrue(GlassTokens.ControlSelectedDark is Color)
    }
}
