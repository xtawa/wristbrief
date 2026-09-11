package ink.underflo.wristbrief.mobile

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal fun materialYouExpressiveShapes(): Shapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

internal fun materialYouExpressiveTypography(): Typography = Typography().copy(
    headlineLarge = TextStyle(fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.SemiBold),
    headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold),
    headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
)

internal fun wristBriefFallbackColorScheme(darkTheme: Boolean): ColorScheme =
    if (darkTheme) {
        darkColorScheme(
            primary = Color(0xFF82D5D2),
            onPrimary = Color(0xFF003736),
            primaryContainer = Color(0xFF004F4F),
            onPrimaryContainer = Color(0xFF9CF1F0),
            secondary = Color(0xFFB1CCC8),
            onSecondary = Color(0xFF1C3531),
            secondaryContainer = Color(0xFF334B48),
            onSecondaryContainer = Color(0xFFCDE8E3),
            tertiary = Color(0xFFD0BBE6),
            onTertiary = Color(0xFF3A2942),
            tertiaryContainer = Color(0xFF513F5A),
            onTertiaryContainer = Color(0xFFF2DAFF),
            background = Color(0xFF191C1C),
            onBackground = Color(0xFFE0E3E2),
            surface = Color(0xFF191C1C),
            onSurface = Color(0xFFE0E3E2),
            surfaceVariant = Color(0xFF3F4847),
            onSurfaceVariant = Color(0xFFBEC8C7),
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF006A6A),
            onPrimary = Color(0xFFFFFFFF),
            primaryContainer = Color(0xFF9CF1F0),
            onPrimaryContainer = Color(0xFF002020),
            secondary = Color(0xFF4A635F),
            onSecondary = Color(0xFFFFFFFF),
            secondaryContainer = Color(0xFFCDE8E3),
            onSecondaryContainer = Color(0xFF05201C),
            tertiary = Color(0xFF6B5876),
            onTertiary = Color(0xFFFFFFFF),
            tertiaryContainer = Color(0xFFF2DAFF),
            onTertiaryContainer = Color(0xFF251431),
            background = Color(0xFFF4FBFA),
            onBackground = Color(0xFF161D1D),
            surface = Color(0xFFF4FBFA),
            onSurface = Color(0xFF161D1D),
            surfaceVariant = Color(0xFFDAE4E3),
            onSurfaceVariant = Color(0xFF3F4847),
        )
    }

internal fun resolveDarkTheme(themeMode: AppThemeMode, systemDark: Boolean): Boolean =
    when (themeMode) {
        AppThemeMode.SYSTEM -> systemDark
        AppThemeMode.LIGHT -> false
        AppThemeMode.DARK -> true
    }

@Composable
internal fun WristBriefMobileTheme(
    themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    darkTheme: Boolean = resolveDarkTheme(themeMode, isSystemInDarkTheme()),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && darkTheme -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
        else -> wristBriefFallbackColorScheme(darkTheme)
    }

    MaterialTheme(
        colorScheme = colorScheme,
        shapes = materialYouExpressiveShapes(),
        typography = materialYouExpressiveTypography(),
        content = content,
    )
}
