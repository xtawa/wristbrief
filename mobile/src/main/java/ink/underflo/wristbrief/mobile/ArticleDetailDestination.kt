package ink.underflo.wristbrief.mobile

import android.content.Intent
import android.net.Uri
import ink.underflo.wristbrief.mobile.ui.BackIconButton
import ink.underflo.wristbrief.mobile.ui.AppIcon
import ink.underflo.wristbrief.mobile.ui.AppIconKind
import ink.underflo.wristbrief.mobile.ui.glass.GlassHeader
import ink.underflo.wristbrief.mobile.ui.glass.GlassTokens
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ink.underflo.wristbrief.mobile.articles.ArticleRepository
import ink.underflo.wristbrief.mobile.articles.plainText
import ink.underflo.wristbrief.mobile.media.MobilePodcastPlayerController
import ink.underflo.wristbrief.mobile.media.PodcastPlayerState
import ink.underflo.wristbrief.mobile.media.formatPlaybackTime
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

    // Full reader: resolve a structured ArticleDocument via the gateway (RSS
    // full-content first, server extraction as fallback). When unavailable —
    // offline without cache, signed out, or extraction failed — the legacy
    // sanitized-description rendering stays as the in-app fallback; the browser
    // is only ever opened by explicit user action.
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

    // Auto-mark read on opening the article
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

    Scaffold(
        containerColor = GlassTokens.canvas(darkTheme),
        topBar = {
            GlassHeader(
                title = item.feedTitle,
                subtitle = stringResource(R.string.article_reader_label),
                navigationIcon = { BackIconButton(onClick = onBack) },
                actions = {
                    val saveLabel = stringResource(if (isSaved) R.string.action_saved else R.string.action_save)
                    androidx.compose.material3.IconButton(
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
                            tint = GlassTokens.textPrimary(darkTheme),
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
                        ) {
                            Text(stringResource(R.string.action_ask_ai))
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
                            ) {
                                Text(stringResource(if (isRead) R.string.action_mark_unread else R.string.action_mark_read))
                            }
                            OutlinedButton(
                                onClick = ::shareArticle,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(stringResource(R.string.action_share))
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
            // Meta info: Date & reading time
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    item.published?.let { pubDate ->
                        Text(
                            text = pubDate,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = readingTime,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            // Article Title
            item {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            // Audio Podcast Episode Card if audioUrl is present
            if (item.audioUrl != null) {
                item {
                    val isCurrentEpisode = playerState?.currentEpisode?.id == item.id
                    val isPlayingThis = isCurrentEpisode && playerState?.isPlaying == true
                    val isBufferingThis = isCurrentEpisode && playerState?.isBuffering == true

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.large,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = if (isPlayingThis) stringResource(R.string.podcast_playing_episode)
                                    else stringResource(R.string.podcast_listen_episode),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                if (isCurrentEpisode) {
                                    Text(
                                        text = if (isBufferingThis) stringResource(R.string.podcast_buffering)
                                        else formatPlaybackTime(playerState?.currentPositionMs ?: 0L, playerState?.durationMs ?: 0L),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (onOpenTranscript != null) {
                                    OutlinedButton(onClick = { onOpenTranscript(item) }) {
                                        Text(stringResource(R.string.transcript_title))
                                    }
                                }
                                Button(
                                    onClick = {
                                        if (isPlayingThis) {
                                            playerController?.pause()
                                        } else if (isCurrentEpisode) {
                                            playerController?.resume()
                                        } else {
                                            onPlayPodcast?.invoke(item)
                                        }
                                    },
                                ) {
                                    Text(stringResource(if (isPlayingThis) R.string.action_pause else R.string.action_listen))
                                }
                            }
                        }
                    }
                }
            }

            // Article Body: structured full-text reader when a document was
            // resolved, otherwise the sanitized-description fallback.
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
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else if (sanitized.paragraphs.isNotEmpty()) {
                items(sanitized.paragraphs) { paragraph ->
                    Text(
                        text = paragraph,
                        style = MaterialTheme.typography.bodyLarge,
                        lineHeight = MaterialTheme.typography.bodyLarge.lineHeight * 1.35f,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            // Fallback action to view original website
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.extraLarge,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.article_original_link_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = stringResource(R.string.article_original_link_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(
                            onClick = ::openBrowser,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.article_open_browser))
                        }
                    }
                }
            }

            item {
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}
}
