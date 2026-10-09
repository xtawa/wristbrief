package ink.underflo.wristbrief.mobile.articles

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import ink.underflo.wristbrief.mobile.ui.AppIcon
import ink.underflo.wristbrief.mobile.ui.AppIconKind

/**
 * Native Compose renderer for ArticleDocument. No WebView: every block type is
 * a Compose primitive. Failed images degrade to a fixed-ratio placeholder and
 * never break the rest of the article.
 */
@Composable
fun ArticleDocumentRenderer(
    document: ArticleDocument,
    imageContent: @Composable (ArticleBlock.Image) -> Unit,
    textScale: Float = 1f,
) {
    val bodyStyle = MaterialTheme.typography.bodyLarge.copy(
        fontSize = MaterialTheme.typography.bodyLarge.fontSize * textScale,
        lineHeight = MaterialTheme.typography.bodyLarge.lineHeight * textScale * 1.35f,
    )
    @Composable
    fun LinkedText(spans: List<ArticleInline>, modifier: Modifier = Modifier, style: androidx.compose.ui.text.TextStyle = bodyStyle) {
        val annotated = spans.toAnnotatedString()
        Text(
            text = annotated,
            style = style.copy(color = MaterialTheme.colorScheme.onSurface),
            modifier = modifier,
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        document.blocks.forEach { block ->
            when (block) {
                is ArticleBlock.Paragraph -> LinkedText(block.spans)
                is ArticleBlock.Heading -> LinkedText(
                    spans = block.spans,
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.headlineSmall
                        2 -> MaterialTheme.typography.titleLarge
                        else -> MaterialTheme.typography.titleMedium
                    }.copy(fontSize = when (block.level) {
                        1 -> MaterialTheme.typography.headlineSmall.fontSize * textScale
                        2 -> MaterialTheme.typography.titleLarge.fontSize * textScale
                        else -> MaterialTheme.typography.titleMedium.fontSize * textScale
                    }, fontWeight = FontWeight.SemiBold),
                )
                is ArticleBlock.UnorderedList -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    block.items.forEach { spans ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            AppIcon(AppIconKind.Bullet, Modifier.size(18.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                            LinkedText(spans)
                        }
                    }
                }
                is ArticleBlock.OrderedList -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    block.items.forEachIndexed { index, spans ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("${index + 1}.", style = bodyStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            LinkedText(spans)
                        }
                    }
                }
                is ArticleBlock.Quote -> Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Row {
                        Box(
                            modifier = Modifier
                                .padding(vertical = 12.dp)
                                .height(24.dp)
                                .fillMaxWidth(0.02f)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.6f), RoundedCornerShape(2.dp)),
                        )
                        LinkedText(
                            spans = block.spans,
                            style = bodyStyle.copy(fontStyle = FontStyle.Italic),
                            modifier = Modifier.padding(14.dp),
                        )
                    }
                }
                is ArticleBlock.CodeBlock -> Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Text(
                        text = block.text,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(14.dp),
                    )
                }
                is ArticleBlock.Image -> imageContent(block)
                ArticleBlock.Divider -> HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable
fun ArticleImagePlaceholder(alt: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .height(140.dp),
    ) {
        Box(contentAlignment = androidx.compose.ui.Alignment.Center) {
            Text(
                text = if (alt.isBlank()) androidx.compose.ui.res.stringResource(ink.underflo.wristbrief.mobile.R.string.article_image_unavailable)
                else alt,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun List<ArticleInline>.toAnnotatedString(): AnnotatedString = toAnnotatedString(MaterialTheme.colorScheme.primary)

fun List<ArticleInline>.toAnnotatedString(linkColor: androidx.compose.ui.graphics.Color): AnnotatedString = buildAnnotatedString {
    for (span in this@toAnnotatedString) {
        when (span) {
            is ArticleInline.Text -> append(span.text)
            is ArticleInline.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(span.text) }
            is ArticleInline.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(span.text) }
            is ArticleInline.Code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(span.text) }
            is ArticleInline.Link -> {
                if (span.url.startsWith("https://") || span.url.startsWith("http://")) {
                    // Native link semantics expose individual links to TalkBack.
                    pushStringAnnotation("URL", span.url)
                    withLink(LinkAnnotation.Url(span.url, TextLinkStyles(
                        style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
                    ))) { append(span.text) }
                    pop()
                } else {
                    append(span.text)
                }
            }
        }
    }
}
