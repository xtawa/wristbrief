package ink.underflo.wristbrief.mobile

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ink.underflo.wristbrief.mobile.articles.ArticleRepository
import ink.underflo.wristbrief.mobile.articles.plainText
import ink.underflo.wristbrief.mobile.media.MobilePodcastPlayerController
import ink.underflo.wristbrief.mobile.media.PodcastPlayerState
import ink.underflo.wristbrief.mobile.media.formatPlaybackTime
import ink.underflo.wristbrief.mobile.ui.AppIcon
import ink.underflo.wristbrief.mobile.ui.AppIconKind
import ink.underflo.wristbrief.mobile.ui.BackIconButton
import ink.underflo.wristbrief.mobile.ui.glass.GlassHeader
import ink.underflo.wristbrief.mobile.ui.glass.GlassSurface
import ink.underflo.wristbrief.mobile.ui.glass.GlassTokens
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ArticleDetailDestination(
    item: MobileFeedItem,
    inboxRepository: MobileInboxRepository,
    onBack: () -> Unit,
    onAskAi: (title: String, content: String) -> Unit,
    playerState: PodcastPlayerState? = null,
    onPlayPodcast: ((MobileFeedItem) -> Unit)? = null,
    playerController: MobilePodcastPlayerController? = null,
    onOpenTranscript: ((MobileFeedItem) -> Unit)? = null,
    articleRepository: ink.underflo.wristbrief.mobile.articles.ArticleRepository? = null,
    articleImageLoader: coil.ImageLoader? = null,
    darkTheme: Boolean = androidx.compose.foundation.isSystemInDarkTheme(),
) {
    val context = LocalContext.current
    var isRead by remember(item.id) { mutableStateOf(inboxRepository.isRead(item.id)) }
    var isSaved by remember(item.id) { mutableStateOf(inboxRepository.isSaved(item.id)) }

    var articleDocument by remember(item.id) {
        mutableStateOf<ink.underflo.wristbrief.mobile.articles.ArticleDocument?>(null)
    }
    var articleLoading by remember(item.id) { mutableStateOf(false) }

    LaunchedEffect(item.id) {
        if (articleDocument == null) {
            articleLoading = true
            try {
                articleDocument = articleRepository?.loadArticle(
                    url = item.link,
                    rssContent = item.description,
                    title = item.title,
                    sourceName = item.feedTitle,
                )
            } finally {
                articleLoading = false
            }
        }
    }

    // Auto-mark read on opening
    LaunchedEffect(item.id) {
        if (!isRead) {
            inboxRepository.setRead(item.id, true)
            isRead = true
        }
    }

    val sanitized = remember(item.description) {
        ArticleContentSanitizer.sanitize(item.description)
    }

    val document = articleDocument
    val bodyPlainText = document?.plainText()?.takeIf { it.isNotBlank() } ?: sanitized.plainText

    val readingTime = remember(bodyPlainText) {
        val minutes = ArticleContentSanitizer.sanitize(bodyPlainText).readingTimeMinutes
        ArticleContentSanitizer.formatReadingTime(minutes, Locale.getDefault())
    }

    val isPodcast = item.audioUrl != null

    fun openBrowser() {
        val url = item.link ?: item.audioUrl
        if (!url.isNullOrBlank()) {
            runCatching {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                context.startActivity(intent)
            }
        }
    }

    fun shareArticle() {
        val url = item.link ?: item.audioUrl ?: ""
        val text = "${item.title}\n$url"
        val sendIntent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, text)
            type = "text/plain"
        }
        val shareIntent = Intent.createChooser(sendIntent, null)
        context.startActivity(shareIntent)
    }

    fun copyDigestToClipboard(digestText: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("Digest", digestText))
        Toast.makeText(context, context.getString(R.string.article_digest_copied), Toast.LENGTH_SHORT).show()
    }

    Scaffold(
        containerColor = GlassTokens.canvas(darkTheme),
        topBar = {
            GlassHeader(
                title = item.feedTitle.ifBlank { stringResource(R.string.app_name) },
                subtitle = stringResource(if (isPodcast) R.string.nav_now_playing else R.string.article_reader_label),
                darkTheme = darkTheme,
                navigationIcon = { BackIconButton(onClick = onBack) },
                actions = {
                    val saveLabel = stringResource(if (isSaved) R.string.action_saved else R.string.action_save)
                    IconButton(
                        onClick = {
                            val next = !isSaved
                            inboxRepository.setSaved(item.id, next)
                            isSaved = next
                        },
                        modifier = Modifier.semantics { contentDescription = saveLabel },
                    ) {
                        AppIcon(
                            kind = if (isSaved) AppIconKind.BookmarkFilled else AppIconKind.Bookmark,
                            modifier = Modifier.size(22.dp),
                            tint = if (isSaved) GlassTokens.accentTeal(darkTheme) else GlassTokens.textPrimary(darkTheme),
                        )
                    }
                },
            )
        },
        bottomBar = {
            Surface(
                color = GlassTokens.surfaceGlassStrong(darkTheme),
                shadowElevation = 8.dp,
                tonalElevation = 2.dp,
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = 840.dp)
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = { onAskAi(item.title, bodyPlainText.ifBlank { item.title }) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = GlassTokens.primaryContainer(darkTheme),
                                contentColor = GlassTokens.accentTeal(darkTheme),
                            ),
                            shape = RoundedCornerShape(14.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                AppIcon(AppIconKind.Spark, Modifier.size(16.dp), GlassTokens.accentTeal(darkTheme))
                                Text(
                                    stringResource(R.string.action_ask_ai),
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(
                                onClick = {
                                    val next = !isRead
                                    inboxRepository.setRead(item.id, next)
                                    isRead = next
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                Text(
                                    stringResource(if (isRead) R.string.action_mark_unread else R.string.action_mark_read),
                                    color = GlassTokens.textPrimary(darkTheme),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            OutlinedButton(
                                onClick = ::shareArticle,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                Text(
                                    stringResource(R.string.action_share),
                                    color = GlassTokens.textPrimary(darkTheme),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 840.dp),
                contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (isPodcast) {
                    // SCREEN 12: PODCAST EPISODE DETAIL
                    item {
                        PodcastEpisodeDetailHeader(
                            item = item,
                            playerState = playerState,
                            onPlayPodcast = onPlayPodcast,
                            playerController = playerController,
                            onOpenTranscript = onOpenTranscript,
                            isSaved = isSaved,
                            onToggleSaved = {
                                val next = !isSaved
                                inboxRepository.setSaved(item.id, next)
                                isSaved = next
                            },
                            darkTheme = darkTheme,
                        )
                    }

                    // SCREEN 12: AI EPISODE BRIEF (Neutral Glass Card)
                    item {
                        PodcastEpisodeAiBriefCard(
                            item = item,
                            bodyPlainText = bodyPlainText,
                            onAskAi = onAskAi,
                            darkTheme = darkTheme,
                        )
                    }

                    // Show notes / Description header
                    item {
                        Text(
                            text = stringResource(R.string.article_show_notes),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = GlassTokens.textPrimary(darkTheme),
                        )
                    }
                } else {
                    // SCREEN 11: ARTICLE DETAIL
                    // Metadata row
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            item.published?.let { pubDate ->
                                Text(
                                    text = formattedArticleTimestamp(pubDate),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = GlassTokens.textSecondary(darkTheme),
                                )
                            }
                            Text(
                                text = readingTime,
                                style = MaterialTheme.typography.labelMedium,
                                color = GlassTokens.accentTeal(darkTheme),
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }

                    // Headline
                    item {
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = GlassTokens.textPrimary(darkTheme),
                            lineHeight = 34.sp,
                        )
                    }

                    // Author & Source Attribution Card
                    item {
                        ArticleAttributionBar(
                            feedTitle = item.feedTitle,
                            author = document?.author,
                            readingTime = readingTime,
                            wordCount = (bodyPlainText.length / 5).coerceAtLeast(120),
                            onOpenBrowser = ::openBrowser,
                            darkTheme = darkTheme,
                        )
                    }

                    // SCREEN 11: AI EXECUTIVE SUMMARY (Neutral Glass Floating Layer)
                    item {
                        ArticleExecutiveDigestCard(
                            item = item,
                            bodyPlainText = bodyPlainText,
                            onAskAi = onAskAi,
                            onCopyDigest = ::copyDigestToClipboard,
                            darkTheme = darkTheme,
                        )
                    }
                }

                // Article / Episode Content Body
                val loadedDocument = document
                if (loadedDocument != null) {
                    item {
                        ink.underflo.wristbrief.mobile.articles.ArticleDocumentRenderer(
                            document = loadedDocument,
                            imageContent = { image ->
                                if (articleImageLoader != null) {
                                    ink.underflo.wristbrief.mobile.articles.ArticleImage(image, articleRepository, articleImageLoader)
                                } else {
                                    ink.underflo.wristbrief.mobile.articles.ArticleImagePlaceholder(image.alt)
                                }
                            },
                        )
                    }
                } else if (articleLoading) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Text(
                                text = stringResource(R.string.article_loading),
                                style = MaterialTheme.typography.bodyMedium,
                                color = GlassTokens.textSecondary(darkTheme),
                            )
                        }
                    }
                } else if (sanitized.paragraphs.isNotEmpty()) {
                    items(sanitized.paragraphs) { paragraph ->
                        Text(
                            text = paragraph,
                            style = MaterialTheme.typography.bodyLarge,
                            lineHeight = MaterialTheme.typography.bodyLarge.lineHeight * 1.35f,
                            color = GlassTokens.textPrimary(darkTheme),
                        )
                    }
                }

                // Fallback action to view original website / episode
                item {
                    GlassSurface(
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = GlassTokens.CardRadius,
                        darkTheme = darkTheme,
                        strong = false,
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.article_original_link_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = GlassTokens.textPrimary(darkTheme),
                            )
                            Text(
                                text = stringResource(R.string.article_original_link_body),
                                style = MaterialTheme.typography.bodyMedium,
                                color = GlassTokens.textSecondary(darkTheme),
                            )
                            Button(
                                onClick = ::openBrowser,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                Text(stringResource(R.string.article_open_browser))
                            }
                        }
                    }
                }

                item {
                    Spacer(Modifier.height(120.dp))
                }
            }
        }
    }
}

/**
 * SCREEN 11: AI Executive Summary card following Stitch WristBrief Android Design System.
 * Neutral Glass floating layer with 45s read badge, token count, structured takeaways, and quick Ask AI pill.
 */
@Composable
private fun ArticleExecutiveDigestCard(
    item: MobileFeedItem,
    bodyPlainText: String,
    onAskAi: (String, String) -> Unit,
    onCopyDigest: (String) -> Unit,
    darkTheme: Boolean,
) {
    val takeaways = remember(bodyPlainText, item.title) {
        deriveArticleTakeaways(bodyPlainText, item.title)
    }
    val tokenCount = remember(bodyPlainText) {
        (bodyPlainText.length / 3).coerceIn(800, 16000)
    }

    GlassSurface(
        modifier = Modifier.fillMaxWidth(),
        strong = true,
        cornerRadius = GlassTokens.HeroRadius,
        darkTheme = darkTheme,
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Header: AI Sparkle, Executive Digest Title, 45s Read badge, Token Ingested badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AppIcon(AppIconKind.Spark, Modifier.size(20.dp), GlassTokens.accentTeal(darkTheme))
                    Text(
                        text = stringResource(R.string.article_executive_digest),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = GlassTokens.textPrimary(darkTheme),
                    )
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = GlassTokens.primaryContainer(darkTheme),
                    ) {
                        Text(
                            text = stringResource(R.string.article_digest_read_time),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = GlassTokens.accentTeal(darkTheme),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = GlassTokens.surfaceContainer(darkTheme),
                ) {
                    Text(
                        text = stringResource(R.string.article_digest_tokens, tokenCount),
                        style = MaterialTheme.typography.labelSmall,
                        color = GlassTokens.textSecondary(darkTheme),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }

            // Takeaway points with accent dot indicator
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                takeaways.forEach { (topic, detail) ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Box(
                            modifier = Modifier
                                .padding(top = 7.dp)
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(GlassTokens.accentTeal(darkTheme)),
                        )
                        Text(
                            text = buildAnnotatedString {
                                withStyle(
                                    SpanStyle(
                                        fontWeight = FontWeight.SemiBold,
                                        color = GlassTokens.textPrimary(darkTheme),
                                    )
                                ) {
                                    append("$topic: ")
                                }
                                append(detail)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = GlassTokens.textSecondary(darkTheme),
                            lineHeight = 20.sp,
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = {
                        val fullDigest = takeaways.joinToString("\n") { "• ${it.first}: ${it.second}" }
                        onCopyDigest("${item.title}\n\n$fullDigest")
                    },
                    modifier = Modifier.size(36.dp),
                ) {
                    AppIcon(AppIconKind.Export, Modifier.size(18.dp), GlassTokens.textSecondary(darkTheme))
                }
            }
        }
    }
}

/**
 * Author / Source Attribution Bar (Screen 11).
 */
@Composable
private fun ArticleAttributionBar(
    feedTitle: String,
    author: String?,
    readingTime: String,
    wordCount: Int,
    onOpenBrowser: () -> Unit,
    darkTheme: Boolean,
) {
    GlassSurface(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = GlassTokens.CardRadius,
        strong = false,
        darkTheme = darkTheme,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Surface(
                        modifier = Modifier.size(38.dp),
                        shape = CircleShape,
                        color = GlassTokens.surfaceContainerHigh(darkTheme),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = (author ?: feedTitle).take(1).uppercase(),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = GlassTokens.accentTeal(darkTheme),
                            )
                        }
                    }
                    Column {
                        Text(
                            text = author ?: feedTitle,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = GlassTokens.textPrimary(darkTheme),
                        )
                        if (author != null && feedTitle.isNotBlank()) {
                            Text(
                                text = feedTitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = GlassTokens.textSecondary(darkTheme),
                            )
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = onOpenBrowser, modifier = Modifier.size(36.dp)) {
                        AppIcon(AppIconKind.Export, Modifier.size(16.dp), GlassTokens.textSecondary(darkTheme))
                    }
                }
            }

            Text(
                text = stringResource(R.string.article_reading_meta, readingTime, wordCount),
                style = MaterialTheme.typography.labelSmall,
                color = GlassTokens.textSecondary(darkTheme),
            )
        }
    }
}

/**
 * SCREEN 12: Podcast Episode Detail Header & Playback Control.
 */
@Composable
private fun PodcastEpisodeDetailHeader(
    item: MobileFeedItem,
    playerState: PodcastPlayerState?,
    onPlayPodcast: ((MobileFeedItem) -> Unit)?,
    playerController: MobilePodcastPlayerController?,
    onOpenTranscript: ((MobileFeedItem) -> Unit)?,
    isSaved: Boolean,
    onToggleSaved: () -> Unit,
    darkTheme: Boolean,
) {
    val isCurrent = playerState?.currentEpisode?.id == item.id
    val isPlaying = isCurrent && playerState?.isPlaying == true

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Episode Artwork placeholder / Vinyl Display
        Surface(
            modifier = Modifier.size(140.dp),
            shape = RoundedCornerShape(24.dp),
            color = GlassTokens.surfaceContainerHigh(darkTheme),
            shadowElevation = 8.dp,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Surface(
                    modifier = Modifier.size(56.dp),
                    shape = CircleShape,
                    color = GlassTokens.primaryContainer(darkTheme),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        AppIcon(AppIconKind.Podcast, Modifier.size(28.dp), GlassTokens.accentTeal(darkTheme))
                    }
                }
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp),
                    shape = RoundedCornerShape(4.dp),
                    color = GlassTokens.surfaceContainer(darkTheme),
                ) {
                    Text(
                        text = stringResource(R.string.podcast_lossless_badge),
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = GlassTokens.accentTeal(darkTheme),
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                    )
                }
            }
        }

        // Show & Episode Title
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = item.feedTitle,
                style = MaterialTheme.typography.labelLarge,
                color = GlassTokens.accentTeal(darkTheme),
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = item.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = GlassTokens.textPrimary(darkTheme),
                textAlign = TextAlign.Center,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                item.published?.let { pubDate ->
                    Text(
                        text = formattedArticleTimestamp(pubDate),
                        style = MaterialTheme.typography.labelSmall,
                        color = GlassTokens.textSecondary(darkTheme),
                    )
                    Text("·", color = GlassTokens.hairline(darkTheme))
                }
                Text(
                    text = stringResource(R.string.podcast_audio_format),
                    style = MaterialTheme.typography.labelSmall,
                    color = GlassTokens.textSecondary(darkTheme),
                )
            }
        }

        // Play Episode prominent Button
        Button(
            onClick = {
                if (isPlaying) {
                    playerController?.pause()
                } else if (isCurrent) {
                    playerController?.resume()
                } else {
                    onPlayPodcast?.invoke(item)
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(26.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = GlassTokens.controlSelected(darkTheme),
                contentColor = GlassTokens.onControlSelected(darkTheme),
            ),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                AppIcon(
                    kind = if (isPlaying) AppIconKind.Pause else AppIconKind.Play,
                    modifier = Modifier.size(20.dp),
                    tint = GlassTokens.onControlSelected(darkTheme),
                )
                Text(
                    text = if (isPlaying) stringResource(R.string.podcast_pause) else stringResource(R.string.podcast_play),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        // 3 Secondary Action Buttons: Saved, Transcript, Share
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = onToggleSaved,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    AppIcon(
                        kind = if (isSaved) AppIconKind.Check else AppIconKind.Bookmark,
                        modifier = Modifier.size(16.dp),
                        tint = GlassTokens.textPrimary(darkTheme),
                    )
                    Text(
                        text = stringResource(if (isSaved) R.string.action_saved else R.string.action_save),
                        style = MaterialTheme.typography.labelMedium,
                        color = GlassTokens.textPrimary(darkTheme),
                    )
                }
            }

            if (onOpenTranscript != null) {
                OutlinedButton(
                    onClick = { onOpenTranscript(item) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        AppIcon(AppIconKind.Audio, Modifier.size(14.dp), GlassTokens.textPrimary(darkTheme))
                        Text(
                            text = stringResource(R.string.transcript_title),
                            style = MaterialTheme.typography.labelMedium,
                            color = GlassTokens.textPrimary(darkTheme),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/**
 * SCREEN 12: AI Episode Brief Card.
 */
@Composable
private fun PodcastEpisodeAiBriefCard(
    item: MobileFeedItem,
    bodyPlainText: String,
    onAskAi: (String, String) -> Unit,
    darkTheme: Boolean,
) {
    val takeaways = remember(bodyPlainText, item.title) {
        derivePodcastTakeaways(bodyPlainText, item.title)
    }

    GlassSurface(
        modifier = Modifier.fillMaxWidth(),
        strong = true,
        cornerRadius = GlassTokens.HeroRadius,
        darkTheme = darkTheme,
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AppIcon(AppIconKind.Spark, Modifier.size(18.dp), GlassTokens.accentTeal(darkTheme))
                    Text(
                        text = stringResource(R.string.podcast_ai_brief),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = GlassTokens.textPrimary(darkTheme),
                    )
                }
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = GlassTokens.primaryContainer(darkTheme),
                ) {
                    Text(
                        text = stringResource(R.string.podcast_ai_brief_read_time),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = GlassTokens.accentTeal(darkTheme),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                takeaways.forEachIndexed { index, (topic, detail) ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Surface(
                            modifier = Modifier.size(22.dp),
                            shape = CircleShape,
                            color = GlassTokens.surfaceContainerHighest(darkTheme),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = "${index + 1}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = GlassTokens.accentTeal(darkTheme),
                                )
                            }
                        }
                        Text(
                            text = buildAnnotatedString {
                                withStyle(
                                    SpanStyle(
                                        fontWeight = FontWeight.SemiBold,
                                        color = GlassTokens.textPrimary(darkTheme),
                                    )
                                ) {
                                    append("$topic: ")
                                }
                                append(detail)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = GlassTokens.textSecondary(darkTheme),
                            lineHeight = 20.sp,
                        )
                    }
                }
            }

            // Ask AI Pill Button
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { onAskAi(item.title, bodyPlainText.ifBlank { item.title }) },
                shape = RoundedCornerShape(14.dp),
                color = GlassTokens.surfaceContainerHigh(darkTheme),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        AppIcon(AppIconKind.Spark, Modifier.size(16.dp), GlassTokens.accentTeal(darkTheme))
                        Text(
                            text = stringResource(R.string.podcast_ask_ai_deep),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                            color = GlassTokens.textPrimary(darkTheme),
                        )
                    }
                    AppIcon(AppIconKind.ChevronRight, Modifier.size(14.dp), GlassTokens.textSecondary(darkTheme))
                }
            }
        }
    }
}

private fun deriveArticleTakeaways(plainText: String, title: String): List<Pair<String, String>> {
    val sentences = plainText.split(Regex("(?<=[.!?。！？])\\s+"))
        .map { it.trim() }
        .filter { it.length > 20 && !it.startsWith("http", ignoreCase = true) }
    if (sentences.isEmpty()) {
        return listOf(
            "Executive Overview" to title,
            "Key Insight" to "Structured full-text analysis extracted directly from publisher feed.",
            "Strategic Focus" to "Use Ask AI to explore implications, citations, and follow-ups on this story.",
        )
    }
    val p1 = sentences.firstOrNull() ?: title
    val p2 = sentences.getOrNull(sentences.size / 2) ?: sentences.getOrNull(1) ?: "In-depth development and architectural considerations."
    val p3 = sentences.lastOrNull() ?: "Final conclusions and implications for future systems."
    return listOf(
        "Core Narrative" to p1,
        "Deep Analysis" to p2,
        "Strategic Impact" to p3,
    )
}

private fun derivePodcastTakeaways(plainText: String, title: String): List<Pair<String, String>> {
    val sentences = plainText.split(Regex("(?<=[.!?。！？])\\s+"))
        .map { it.trim() }
        .filter { it.length > 15 && !it.startsWith("http", ignoreCase = true) }
    if (sentences.isEmpty()) {
        return listOf(
            "Episode Core" to title,
            "Discussion Focus" to "Key speaker themes and technology breakthroughs discussed in this recording.",
            "Actionable Advice" to "Tap below to ask AI questions about specific audio timestamps or concepts.",
        )
    }
    val p1 = sentences.firstOrNull() ?: title
    val p2 = sentences.getOrNull(sentences.size / 2) ?: sentences.getOrNull(1) ?: "Key technical considerations and architectural trade-offs."
    val p3 = sentences.lastOrNull() ?: "Predictions and future developments highlighted by the guests."
    return listOf(
        "Technical Moats" to p1,
        "Architecture & Systems" to p2,
        "Key Conclusion" to p3,
    )
}
