package ink.underflo.wristbrief.mobile.articles

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest

/**
 * Article image loaded through the gateway media proxy with the account
 * session attached (the proxy is authenticated). A failed load degrades to the
 * fixed-ratio placeholder — one bad image never breaks the article.
 */
@Composable
fun ArticleImage(
    image: ArticleBlock.Image,
    repository: ArticleRepository?,
    imageLoader: coil.ImageLoader,
) {
    val context = LocalContext.current
    val url = repository?.mediaUrl(image.mediaId)
    if (url == null) {
        ArticleImagePlaceholder(image.alt)
        return
    }
    AsyncImage(
        model = ImageRequest.Builder(context)
            .data(url)
            .crossfade(true)
            .build(),
        imageLoader = imageLoader,
        contentDescription = image.alt.ifBlank { null },
        contentScale = ContentScale.FillWidth,
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .clip(RoundedCornerShape(12.dp)),
    )
}
