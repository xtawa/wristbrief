package ink.underflo.wristbrief.ui.liquidglass

import androidx.compose.foundation.shape.CornerSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests verifying that Wear OS Liquid Glass tokens adhere strictly to uidocs/WEAR_LIQUID_GLASS_SPEC.md.
 */
class WearLiquidGlassTest {

    @Test
    fun wearCanvasIsPureBlack() {
        assertEquals("Wear canvas must be black-first (#000000)", Color(0xFF000000), WearGlassTokens.Canvas)
    }

    @Test
    fun surfaceSubtleAlphaMatchesSpec() {
        // Spec: #FFFFFF @ 8–10%
        val alpha = WearGlassTokens.SurfaceSubtle.alpha
        assertTrue("SurfaceSubtle alpha should be between 0.08 and 0.10, actual: $alpha", alpha in 0.08f..0.10f)
    }

    @Test
    fun surfaceStrongAlphaMatchesSpec() {
        // Spec: #FFFFFF @ 12–14%
        val alpha = WearGlassTokens.SurfaceStrong.alpha
        assertTrue("SurfaceStrong alpha should be between 0.12 and 0.14, actual: $alpha", alpha in 0.12f..0.14f)
    }

    @Test
    fun hairlineAlphaMatchesSpec() {
        // Spec: #FFFFFF @ 14–18%
        val alpha = WearGlassTokens.Hairline.alpha
        assertTrue("Hairline alpha should be between 0.14 and 0.18, actual: $alpha", alpha in 0.14f..0.18f)
    }

    @Test
    fun textColorsMatchSpec() {
        assertEquals("TextPrimary must be #F5F7FA", Color(0xFFF5F7FA), WearGlassTokens.TextPrimary)
        assertEquals("TextSecondary must be #A7AFBA", Color(0xFFA7AFBA), WearGlassTokens.TextSecondary)
    }

    @Test
    fun radiiMatchSpec() {
        // Major content card: 26–32dp radius; list row: 20–24dp radius
        assertEquals(CornerSize(30.dp), WearGlassTokens.MajorShape.topStart)
        assertEquals(CornerSize(24.dp), WearGlassTokens.RowShape.topStart)
    }
}
