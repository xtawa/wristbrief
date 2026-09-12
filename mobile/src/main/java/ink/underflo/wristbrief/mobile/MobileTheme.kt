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

internal val NeutralGlassDarkColorScheme: ColorScheme = darkColorScheme(
    surface = Color(0xFF111316),
    surfaceDim = Color(0xFF111316),
    surfaceBright = Color(0xFF37393D),
    surfaceContainerLowest = Color(0xFF0C0E11),
    surfaceContainerLow = Color(0xFF1A1C1F),
    surfaceContainer = Color(0xFF1E2023),
    surfaceContainerHigh = Color(0xFF282A2D),
    surfaceContainerHighest = Color(0xFF333538),
    onSurface = Color(0xFFE2E2E6),
    onSurfaceVariant = Color(0xFFC4C6CD),
    inverseSurface = Color(0xFFE2E2E6),
    inverseOnSurface = Color(0xFF2F3034),
    outline = Color(0xFF8E9197),
    outlineVariant = Color(0xFF43474C),
    primary = Color(0xFFB5C8DF),
    onPrimary = Color(0xFF203243),
    primaryContainer = Color(0xFF2C3E50),
    onPrimaryContainer = Color(0xFF96A9BE),
    inversePrimary = Color(0xFF4E6073),
    secondary = Color(0xFFADC6FF),
    onSecondary = Color(0xFF002E6A),
    secondaryContainer = Color(0xFF0566D9),
    onSecondaryContainer = Color(0xFFE6ECFF),
    tertiary = Color(0xFF6BD8CB),
    onTertiary = Color(0xFF003732),
    tertiaryContainer = Color(0xFF00453F),
    onTertiaryContainer = Color(0xFF48B8AB),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF111316),
    onBackground = Color(0xFFE2E2E6),
    surfaceVariant = Color(0xFF333538),
)

internal val EditorialLightGlassColorScheme: ColorScheme = lightColorScheme(
    surface = Color(0xFFF8F9FA),
    surfaceDim = Color(0xFFD9DADB),
    surfaceBright = Color(0xFFF8F9FA),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF5F6F8),
    surfaceContainer = Color(0xFFF1F3F5),
    surfaceContainerHigh = Color(0xFFE9ECEF),
    surfaceContainerHighest = Color(0xFFDEE2E6),
    onSurface = Color(0xFF191C1E),
    onSurfaceVariant = Color(0xFF43474E),
    inverseSurface = Color(0xFF2E3132),
    inverseOnSurface = Color(0xFFF0F1F2),
    outline = Color(0xFF73777F),
    outlineVariant = Color(0x0F000000),
    primary = Color(0xFF004C6E),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFC8E6FF),
    onPrimaryContainer = Color(0xFF001E2E),
    inversePrimary = Color(0xFF88CEFF),
    secondary = Color(0xFF016874),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFF97F0FF),
    onSecondaryContainer = Color(0xFF001F24),
    tertiary = Color(0xFF42474C),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFF5A5F63),
    onTertiaryContainer = Color(0xFFD5D9DE),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF93000A),
    background = Color(0xFFF8F9FA),
    onBackground = Color(0xFF191C1D),
    surfaceVariant = Color(0xFFE1E3E4),
)

internal fun wristBriefFallbackColorScheme(darkTheme: Boolean): ColorScheme =
    if (darkTheme) NeutralGlassDarkColorScheme else EditorialLightGlassColorScheme

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
    val colorScheme = wristBriefFallbackColorScheme(darkTheme)

    MaterialTheme(
        colorScheme = colorScheme,
        shapes = materialYouExpressiveShapes(),
        typography = materialYouExpressiveTypography(),
        content = content,
    )
}
