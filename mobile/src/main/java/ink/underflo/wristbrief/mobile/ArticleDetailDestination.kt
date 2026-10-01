package ink.underflo.wristbrief.mobile

import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
    speechPreviewState: ink.underflo.wristbrief.mobile.audio.SpeechPreviewUiState? = null,
    onSpeechPlayPause: ((ink.underflo.wristbrief.mobile.audio.SpeechPreviewRequest) -> Unit)? = null,
    onSpeechRetry: ((ink.underflo.wristbrief.mobile.audio.SpeechPreviewRequest) -> Unit)? = null,
    onSpeechStop: (() -> Unit)? = null,
    darkTheme: Boolean = androidx.compose.foundation.isSystemInDarkTheme(),
) {
    val context = LocalContext.current
    var isRead by remember(item.id) { mutableStateOf(inboxRepository.isRead(item.id)) }
    var isSaved by remember(item.id) { mutableStateOf(inboxRepository.isSaved(item.id)) }
    val readerPreferences = remember(context) { context.getSharedPreferences("reader_appearance", Context.MODE_PRIVATE) }
    var readerScale by remember { mutableStateOf(readerPreferences.getFloat("text_scale", 1f)) }
    var showReaderSettings by remember { mutableStateOf(false) }

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

    val originalUrl = item.link ?: item.audioUrl

    fun openBrowser() {
        val url = originalUrl
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

    if (showReaderSettings) {
        AlertDialog(
            onDismissRequest = { showReaderSettings = false },
            title = { Text(stringResource(R.string.reader_text_size)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0.9f, 1f, 1.15f, 1.3f).forEach { scale ->
                        TextButton(
                            onClick = {
                                readerScale = scale
                                readerPreferences.edit().putFloat("text_scale", scale).apply()
                                showReaderSettings = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.reader_text_size_option, (scale * 100).toInt()))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showReaderSettings = false }) { Text(stringResource(R.string.reader_done)) } },
        )
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
                    if (item.audioUrl == null) {
                        TextButton(onClick = { showReaderSettings = true }) {
                            Text(stringResource(R.string.reader_appearance_action), color = GlassTokens.textPrimary(darkTheme))
                        }
                    }
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
                            if (document != null && !document.isExcerpt && bodyPlainText.isNotBlank()) {
                                Text(
                                    text = readingTime,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = GlassTokens.accentTeal(darkTheme),
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
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
                            onOpenBrowser = ::openBrowser,
                            hasOriginalUrl = !originalUrl.isNullOrBlank(),
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
                            textScale = readerScale,
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
                            fontSize = MaterialTheme.typography.bodyLarge.fontSize * readerScale,
                            lineHeight = MaterialTheme.typography.bodyLarge.lineHeight * readerScale * 1.35f,
                            color = GlassTokens.textPrimary(darkTheme),
                        )
                    }
                } else {
                    item {
                        Text(
                            text = stringResource(R.string.article_no_content),
                            style = MaterialTheme.typography.bodyMedium,
                            color = GlassTokens.textSecondary(darkTheme),
                        )
                    }
                }

                if (((loadedDocument == null && !articleLoading && sanitized.paragraphs.isNotEmpty()) || loadedDocument?.isExcerpt == true) && !isPodcast) {
                    item {
                        Text(
                            text = stringResource(R.string.article_excerpt_notice),
                            style = MaterialTheme.typography.bodySmall,
                            color = GlassTokens.textSecondary(darkTheme),
                        )
                    }
                }

                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!articleLoading && bodyPlainText.isNotBlank()) {
                            Button(onClick = { onAskAi(item.title, bodyPlainText) }, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.action_ask_ai))
                            }
                            if (isPodcast || loadedDocument?.isExcerpt == true || loadedDocument == null) {
                                Text(
                                    text = stringResource(R.string.article_ai_scope_note),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = GlassTokens.textSecondary(darkTheme),
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                val next = !isRead
                                inboxRepository.setRead(item.id, next)
                                isRead = next
                            }, modifier = Modifier.weight(1f)) {
                                Text(stringResource(if (isRead) R.string.action_mark_unread else R.string.action_mark_read), maxLines = 1)
                            }
                            OutlinedButton(onClick = ::shareArticle, modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.action_share))
                            }
                        }
                    }
                }

                // Short-text speech preview. Only a bounded sample can be read aloud, so
                // this card is explicit about the 2000-character bound and about the voice
                // being AI-generated; it never claims to read the whole piece.
                if (speechPreviewState != null && onSpeechPlayPause != null) {
                    item {
                        ink.underflo.wristbrief.mobile.audio.SpeechPreviewCard(
                            state = speechPreviewState,
                            onPlayPause = onSpeechPlayPause,
                            onRetry = onSpeechRetry ?: onSpeechPlayPause,
                            onStop = onSpeechStop ?: {},
                            darkTheme = darkTheme,
                        )
                    }
                }

                // Fallback action to view original website / episode
                if (!originalUrl.isNullOrBlank()) item { GlassSurface(
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
    onOpenBrowser: () -> Unit,
    hasOriginalUrl: Boolean,
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

                if (hasOriginalUrl) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = onOpenBrowser, modifier = Modifier.size(36.dp)) {
                        AppIcon(AppIconKind.Export, Modifier.size(16.dp), GlassTokens.textSecondary(darkTheme))
                    }
                }
            }

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
                // Only observable facts here. The previous "128kbps AAC" label was a
                // hard-coded claim the client cannot verify (the gateway transcodes audio
                // to 16 kHz mono MP3), so it was removed rather than replaced.
                item.published?.let { pubDate ->
                    Text(
                        text = formattedArticleTimestamp(pubDate),
                        style = MaterialTheme.typography.labelSmall,
                        color = GlassTokens.textSecondary(darkTheme),
                    )
                }
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
