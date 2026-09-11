package ink.underflo.wristbrief.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme

object WearSpacing {
    val none: Dp = 0.dp
    val small: Dp = 4.dp
    val medium: Dp = 8.dp
    val large: Dp = 12.dp
    val edge: Dp = 16.dp
}

object WearTouchTarget {
    val minTouchTarget: Dp = 48.dp
}

@Composable
fun WristBriefWearTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        content = content,
    )
}
