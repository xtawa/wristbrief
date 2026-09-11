package ink.underflo.wristbrief.mobile

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ArticleDetailDestination(
    item: MobileFeedItem,
    inboxRepository: MobileInboxRepository,
    onBack: () -> Unit,
    onAskAi: (title: String, content: String) -> Unit,
) {
    val context = LocalContext.current
    var isRead by remember(item.id) { mutableStateOf(inboxRepository.isRead(item.id)) }
    var isSaved by remember(item.id) { mutableStateOf(inboxRepository.isSaved(item.id)) }

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

    val readingTime = remember(sanitized.readingTimeMinutes) {
        ArticleContentSanitizer.formatReadingTime(sanitized.readingTimeMinutes, Locale.getDefault())
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
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = item.feedTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Text("←", style = MaterialTheme.typography.titleLarge)
                    }
                },
                actions = {
                    TextButton(onClick = {
                        val next = !isSaved
                        inboxRepository.setSaved(item.id, next)
                        isSaved = next
                    }) {
                        Text(stringResource(if (isSaved) R.string.action_saved else R.string.action_save))
                    }
                },
            )
        },
        bottomBar = {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                tonalElevation = 3.dp,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            val next = !isRead
                            inboxRepository.setRead(item.id, next)
                            isRead = next
                        }) {
                            Text(stringResource(if (isRead) R.string.action_mark_unread else R.string.action_mark_read))
                        }
                        OutlinedButton(onClick = ::shareArticle) {
                            Text(stringResource(R.string.action_share))
                        }
                    }
                    Button(onClick = { onAskAi(item.title, sanitized.plainText.ifBlank { item.title }) }) {
                        Text(stringResource(R.string.action_ask_ai))
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
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

            // Article Body Paragraphs
            if (sanitized.paragraphs.isNotEmpty()) {
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
