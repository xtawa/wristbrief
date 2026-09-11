package ink.underflo.wristbrief.mobile.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Design tokens for spacing, elevation, and touch targets in WristBrief mobile companion. */
object MobileSpacing {
    val none: Dp = 0.dp
    val xsmall: Dp = 4.dp
    val small: Dp = 8.dp
    val medium: Dp = 16.dp
    val cardPadding: Dp = 20.dp
    val large: Dp = 24.dp
    val xlarge: Dp = 32.dp
}

object ElevationTokens {
    val flat: Dp = 0.dp
    val card: Dp = 1.dp
    val raised: Dp = 3.dp
    val dialog: Dp = 6.dp
}

object TouchTargetTokens {
    val minTouchTarget: Dp = 48.dp
}
