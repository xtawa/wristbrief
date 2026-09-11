package ink.underflo.wristbrief.mobile

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Test

class MobileThemeTest {
    @Test
    fun expressiveShapesUseProgressivelyLargerRoundedCorners() {
        val shapes = materialYouExpressiveShapes()

        assertEquals(
            Shapes(
                extraSmall = RoundedCornerShape(8.dp),
                small = RoundedCornerShape(12.dp),
                medium = RoundedCornerShape(16.dp),
                large = RoundedCornerShape(24.dp),
                extraLarge = RoundedCornerShape(32.dp),
            ),
            shapes,
        )
    }

    @Test
    fun expressiveTypographyKeepsProminentTitlesAndReadableBodyText() {
        val typography = materialYouExpressiveTypography()

        assertEquals(28.sp, typography.headlineMedium.fontSize)
        assertEquals(36.sp, typography.headlineMedium.lineHeight)
        assertEquals(FontWeight.SemiBold, typography.headlineMedium.fontWeight)
        assertEquals(FontWeight.SemiBold, typography.titleLarge.fontWeight)
        assertEquals(16.sp, typography.bodyLarge.fontSize)
        assertEquals(24.sp, typography.bodyLarge.lineHeight)
    }

    @Test
    fun fallbackColorSchemesProvideDistinctLightAndDarkSurfaces() {
        val light = wristBriefFallbackColorScheme(darkTheme = false)
        val dark = wristBriefFallbackColorScheme(darkTheme = true)

        assertEquals(Color(0xFFF4FBFA), light.surface)
        assertEquals(Color(0xFF191C1C), dark.surface)
        assertEquals(Color(0xFF006A6A), light.primary)
        assertEquals(Color(0xFF82D5D2), dark.primary)
    }

    @Test
    fun resolveDarkThemeFollowsPreferenceAndSystemFallback() {
        assertEquals(true, resolveDarkTheme(AppThemeMode.DARK, systemDark = false))
        assertEquals(true, resolveDarkTheme(AppThemeMode.DARK, systemDark = true))
        assertEquals(false, resolveDarkTheme(AppThemeMode.LIGHT, systemDark = false))
        assertEquals(false, resolveDarkTheme(AppThemeMode.LIGHT, systemDark = true))
        assertEquals(false, resolveDarkTheme(AppThemeMode.SYSTEM, systemDark = false))
        assertEquals(true, resolveDarkTheme(AppThemeMode.SYSTEM, systemDark = true))
    }
}
