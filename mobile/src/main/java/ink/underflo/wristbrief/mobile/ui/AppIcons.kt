package ink.underflo.wristbrief.mobile.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Circle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Podcasts
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

/** Standard Material Design icons mapped to AppIconKind. */
@Composable
fun AppIcon(
    kind: AppIconKind,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    val imageVector: ImageVector = when (kind) {
        AppIconKind.Play -> Icons.Rounded.PlayArrow
        AppIconKind.Pause -> Icons.Rounded.Pause
        AppIconKind.Close -> Icons.Rounded.Close
        AppIconKind.Check -> Icons.Rounded.Check
        AppIconKind.Add -> Icons.Rounded.Add
        AppIconKind.Bookmark -> Icons.Outlined.BookmarkBorder
        AppIconKind.BookmarkFilled -> Icons.Rounded.Bookmark
        AppIconKind.Audio -> Icons.Rounded.GraphicEq
        AppIconKind.Bullet -> Icons.Rounded.Circle
        AppIconKind.Spark -> Icons.Rounded.AutoAwesome
        AppIconKind.Microphone -> Icons.Rounded.Mic
        AppIconKind.Radio -> Icons.Rounded.Radio
        AppIconKind.Import -> Icons.Rounded.FileDownload
        AppIconKind.Export -> Icons.Rounded.FileUpload
        AppIconKind.Refresh -> Icons.Rounded.Refresh
        AppIconKind.Search -> Icons.Rounded.Search
        AppIconKind.Settings -> Icons.Rounded.Settings
        AppIconKind.Back -> Icons.AutoMirrored.Rounded.ArrowBack
        AppIconKind.ChevronRight -> Icons.AutoMirrored.Rounded.KeyboardArrowRight
        AppIconKind.Podcast -> Icons.Rounded.Podcasts
    }

    Icon(
        imageVector = imageVector,
        contentDescription = null,
        modifier = modifier,
        tint = tint,
    )
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
    Search,
    Settings,
    Back,
    ChevronRight,
    Podcast,
}
