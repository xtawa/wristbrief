package ink.underflo.wristbrief.mobile

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import ink.underflo.wristbrief.mobile.media.PodcastProgressStore
import ink.underflo.wristbrief.mobile.media.formatPlaybackTime
import ink.underflo.wristbrief.mobile.ui.ErrorBanner
import ink.underflo.wristbrief.mobile.ui.AppIcon
import ink.underflo.wristbrief.mobile.ui.AppIconKind
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.isSystemInDarkTheme
import ink.underflo.wristbrief.mobile.ui.glass.GlassSurface
import ink.underflo.wristbrief.mobile.ui.glass.GlassTokens
import ink.underflo.wristbrief.mobile.ui.glass.NeutralFilterChip
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun TodayDestination(
    padding: PaddingValues,
    inboxRepository: MobileInboxRepository,
    feedManager: MobileFeedManager,
    onAddFeed: () -> Unit,
    onImportOpml: () -> Unit,
    onOpenAskAi: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenArticle: (MobileFeedItem) -> Unit = {},
    onPlayPodcast: (MobileFeedItem) -> Unit = {},
    progressStore: PodcastProgressStore? = null,
    darkTheme: Boolean = isSystemInDarkTheme(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf(inboxRepository.items()) }
    var feeds by remember { mutableStateOf(feedManager.feeds()) }
    var isRefreshing by remember { mutableStateOf(false) }
    var refreshStatus by remember { mutableStateOf<String?>(null) }
    var failedFeedTitles by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var selectedCategory by rememberSaveable { mutableStateOf<String?>(null) }

    val categories = remember(feeds) {
        feeds.mapNotNull { it.category }.distinct().sorted()
    }

    val displayedItems = remember(items, selectedCategory, feeds) {
        if (selectedCategory == null) items
        else {
            val feedIds = feeds.filter { it.category.equals(selectedCategory, ignoreCase = true) }.map { it.id }.toSet()
            items.filter { it.feedId in feedIds }
        }
    }

    val unread = displayedItems.filterNot { inboxRepository.isRead(it.id) }
    val saved = displayedItems.filter { inboxRepository.isSaved(it.id) }
    val continueReading = unread.firstOrNull { it.audioUrl == null }
    val activeProgress = remember(items, progressStore) {
        progressStore?.getLatestActive()
    }
    val continueListening = remember(displayedItems, activeProgress) {
        activeProgress?.let { prog ->
            displayedItems.firstOrNull { it.id == prog.episodeId }
        }
    }

    val dailyBriefStore = remember(context) { SharedPreferencesDailyBriefStore(context) }
    var dailyBriefRecord by remember { mutableStateOf(dailyBriefStore.getLatest()) }
    var isGeneratingBrief by remember { mutableStateOf(false) }
    var briefErrorMessage by remember { mutableStateOf<String?>(null) }
    var showFullBriefDialog by remember { mutableStateOf(false) }
    val todayKey = remember { DailyBriefInputBuilder.todayKey() }
    val session = remember(context) { AccountSessionPreferences(context) }.read()
    val summaryClient = remember { PhoneLongSummaryClient() }

    fun generateDailyBrief() {
        if (!BuildConfig.GATEWAY_BASE_URL.startsWith("https://", ignoreCase = true)) {
            briefErrorMessage = context.getString(R.string.ai_service_unavailable_body)
            return
        }
        if (session == null) {
            onOpenAskAi()
            return
        }
        val input = DailyBriefInputBuilder.build(unread) ?: return
        if (dailyBriefRecord != null && dailyBriefRecord?.dateKey == todayKey && dailyBriefRecord?.inputHash == input.inputHash && input.inputHash.isNotBlank()) {
            briefErrorMessage = context.getString(R.string.daily_brief_up_to_date)
            return
        }
        isGeneratingBrief = true
        briefErrorMessage = null
        scope.launch {
            try {
                val summary = withContext(Dispatchers.IO) {
                    summaryClient.summarize(
                        BuildConfig.GATEWAY_BASE_URL,
                        session.sessionToken,
                        input.title,
                        input.content,
                    )
                }
                val record = DailyBriefRecord(
                    dateKey = todayKey,
                    title = input.title,
                    tiny = summary.tiny.ifBlank { summary.text.take(150) },
                    long = summary.text,
                    bullets = summary.bullets,
                    topics = summary.topics,
                    generatedAtEpochMs = System.currentTimeMillis(),
                    sourceCount = input.itemCount,
                    inputHash = input.inputHash,
                )
                dailyBriefStore.save(record)
                dailyBriefRecord = record
            } catch (e: PhoneSummaryRequestException) {
                briefErrorMessage = when (e.failure) {
                    PhoneSummaryFailure.Quota -> context.getString(R.string.ai_quota_exceeded)
                    PhoneSummaryFailure.ProviderUnavailable -> context.getString(R.string.ai_provider_unavailable)
                    else -> context.getString(R.string.ai_error)
                }
            } catch (_: Exception) {
                briefErrorMessage = context.getString(R.string.ai_error)
            } finally {
                isGeneratingBrief = false
            }
        }
    }

    val todayDateFormatted = remember {
        val format = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault())
        format.format(Date())
    }

    LaunchedEffect(Unit) {
        if (feeds.isNotEmpty() && items.isEmpty()) {
            isRefreshing = true
            val res = inboxRepository.refresh()
            items = inboxRepository.items()
            failedFeedTitles = res.failedFeedTitles
            isRefreshing = false
            refreshStatus = if (res.isOfflineFallback) {
                context.getString(R.string.today_offline_fallback)
            } else {
                context.getString(R.string.today_refreshed)
            }
        }
    }

    fun openUrl(url: String?) {
        if (!url.isNullOrBlank()) {
            runCatching {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                context.startActivity(intent)
            }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Date and Greeting Header
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = todayDateFormatted,
                    style = MaterialTheme.typography.labelLarge,
                    color = GlassTokens.textSecondary(darkTheme),
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = stringResource(R.string.today_greeting),
                    style = MaterialTheme.typography.headlineMedium,
                    color = GlassTokens.textPrimary(darkTheme),
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        if (failedFeedTitles.isNotEmpty()) {
            item {
                ErrorBanner(
                    message = stringResource(
                        R.string.today_partial_refresh_failed,
                        failedFeedTitles.size,
                        failedFeedTitles.joinToString(", "),
                    ),
                    onRetry = {
                        scope.launch {
                            isRefreshing = true
                            val res = inboxRepository.refresh()
                            items = inboxRepository.items()
                            failedFeedTitles = res.failedFeedTitles
                            isRefreshing = false
                        }
                    },
                )
            }
        }

        if (feeds.isNotEmpty() && categories.isNotEmpty()) {
            item {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        NeutralFilterChip(
                            label = stringResource(R.string.today_filter_all),
                            selected = selectedCategory == null,
                            onClick = { selectedCategory = null },
                            darkTheme = darkTheme,
                        )
                    }
                    items(categories) { cat ->
                        NeutralFilterChip(
                            label = cat,
                            selected = selectedCategory == cat,
                            onClick = { selectedCategory = if (selectedCategory == cat) null else cat },
                            darkTheme = darkTheme,
                        )
                    }
                }
            }
        }

        // Empty state: No feeds subscribed
        if (feeds.isEmpty()) {
            item {
                GlassSurface(
                    modifier = Modifier.fillMaxWidth(),
                    strong = false,
                    cornerRadius = GlassTokens.CardRadius,
                    darkTheme = darkTheme,
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.today_empty_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = GlassTokens.textPrimary(darkTheme),
                        )
                        Text(
                            text = stringResource(R.string.today_empty_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = GlassTokens.textSecondary(darkTheme),
                        )
                        Spacer(Modifier.height(4.dp))
                        Button(
                            onClick = onAddFeed,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.today_add_feed))
                        }
                        OutlinedButton(
                            onClick = onImportOpml,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.today_import_opml))
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.sample_feeds_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium,
                            color = GlassTokens.textSecondary(darkTheme),
                        )
                        SampleFeeds.curatedFeeds.forEach { sample ->
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        val result = feedManager.add(sample.url, sample.title, sample.category)
                                        if (result is FeedMutationResult.Success) {
                                            feeds = result.feeds
                                            isRefreshing = true
                                            inboxRepository.refresh()
                                            items = inboxRepository.items()
                                            isRefreshing = false
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("+ ${sample.title}")
                            }
                        }
                    }
                }
            }
        } else if (items.isEmpty()) {
            // Feeds exist, but items not yet loaded / empty
            item {
                GlassSurface(
                    modifier = Modifier.fillMaxWidth(),
                    strong = false,
                    cornerRadius = GlassTokens.CardRadius,
                    darkTheme = darkTheme,
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.today_syncing_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = GlassTokens.textPrimary(darkTheme),
                        )
                        Text(
                            text = stringResource(R.string.today_syncing_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = GlassTokens.textSecondary(darkTheme),
                        )
                        Button(
                            enabled = !isRefreshing,
                            onClick = {
                                scope.launch {
                                    isRefreshing = true
                                    val res = inboxRepository.refresh()
                                    items = inboxRepository.items()
                                    isRefreshing = false
                                    refreshStatus = if (res.isOfflineFallback) {
                                        context.getString(R.string.today_offline_fallback)
                                    } else {
                                        context.getString(R.string.today_refreshed)
                                    }
                                }
                            },
                        ) {
                            Text(if (isRefreshing) stringResource(R.string.today_refreshing) else stringResource(R.string.today_refresh))
                        }
                    }
                }
            }
        } else {
            // Daily Brief card
            item {
                GlassSurface(
                    modifier = Modifier.fillMaxWidth(),
                    strong = true,
                    cornerRadius = GlassTokens.HeroRadius,
                    darkTheme = darkTheme,
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.today_brief_title),
                            style = MaterialTheme.typography.titleLarge,
                            color = GlassTokens.textPrimary(darkTheme),
                            fontWeight = FontWeight.SemiBold,
                        )

                        if (dailyBriefRecord != null && dailyBriefRecord?.dateKey == todayKey) {
                            val record = dailyBriefRecord!!
                            val generatedTimeStr = remember(record.generatedAtEpochMs) {
                                val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
                                timeFormat.format(Date(record.generatedAtEpochMs))
                            }
                            Text(
                                text = stringResource(R.string.daily_brief_meta_info, record.sourceCount, generatedTimeStr),
                                style = MaterialTheme.typography.labelMedium,
                                color = GlassTokens.textSecondary(darkTheme),
                            )
                            Text(
                                text = record.tiny,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                color = GlassTokens.textPrimary(darkTheme),
                            )
                            if (record.bullets.isNotEmpty()) {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    record.bullets.take(3).forEach { bullet ->
                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            AppIcon(AppIconKind.Bullet, Modifier.size(14.dp), GlassTokens.textSecondary(darkTheme))
                                            Text(
                                                bullet,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = GlassTokens.textSecondary(darkTheme),
                                            )
                                        }
                                    }
                                }
                            }
                            briefErrorMessage?.let { err ->
                                Text(
                                    text = err,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Button(
                                    onClick = { showFullBriefDialog = true },
                                ) {
                                    Text(stringResource(R.string.daily_brief_read_full))
                                }
                                TextButton(
                                    enabled = !isGeneratingBrief,
                                    onClick = ::generateDailyBrief,
                                ) {
                                    Text(
                                        stringResource(if (isGeneratingBrief) R.string.daily_brief_generating else R.string.daily_brief_regenerate),
                                        color = GlassTokens.textSecondary(darkTheme),
                                    )
                                }
                            }
                        } else if (unread.isNotEmpty()) {
                            Text(
                                text = stringResource(R.string.daily_brief_unread_prompt, unread.size),
                                style = MaterialTheme.typography.bodyMedium,
                                color = GlassTokens.textSecondary(darkTheme),
                            )
                            briefErrorMessage?.let { err ->
                                Text(
                                    text = err,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            Button(
                                enabled = !isGeneratingBrief,
                                onClick = ::generateDailyBrief,
                                modifier = Modifier.align(Alignment.End),
                            ) {
                                Text(stringResource(if (isGeneratingBrief) R.string.daily_brief_generating else R.string.daily_brief_generate))
                            }
                        } else {
                            Text(
                                text = stringResource(R.string.daily_brief_all_caught_up),
                                style = MaterialTheme.typography.bodyMedium,
                                color = GlassTokens.textSecondary(darkTheme),
                            )
                            TextButton(
                                onClick = onOpenAskAi,
                                modifier = Modifier.align(Alignment.End),
                            ) {
                                Text(
                                    stringResource(R.string.today_brief_action),
                                    color = GlassTokens.textSecondary(darkTheme),
                                )
                            }
                        }
                    }
                }
            }

            // Continue reading card
            continueReading?.let { reading ->
                item {
                    GlassSurface(
                        modifier = Modifier.fillMaxWidth(),
                        strong = false,
                        cornerRadius = GlassTokens.CardRadius,
                        darkTheme = darkTheme,
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.today_continue_reading),
                                style = MaterialTheme.typography.labelMedium,
                                color = GlassTokens.textSecondary(darkTheme),
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = reading.title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                color = GlassTokens.textPrimary(darkTheme),
                            )
                            Text(
                                text = reading.feedTitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = GlassTokens.textSecondary(darkTheme),
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Button(onClick = { onOpenArticle(reading) }) {
                                    Text(stringResource(R.string.action_read))
                                }
                                TextButton(
                                    onClick = {
                                        inboxRepository.setRead(reading.id, true)
                                        items = inboxRepository.items()
                                    },
                                ) {
                                    Text(stringResource(R.string.action_mark_read))
                                }
                            }
                        }
                    }
                }
            }

            // Continue listening card
            continueListening?.let { podcast ->
                item {
                    GlassSurface(
                        modifier = Modifier.fillMaxWidth(),
                        strong = false,
                        cornerRadius = GlassTokens.CardRadius,
                        darkTheme = darkTheme,
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.today_continue_listening),
                                style = MaterialTheme.typography.labelMedium,
                                color = GlassTokens.textSecondary(darkTheme),
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = podcast.title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                color = GlassTokens.textPrimary(darkTheme),
                            )
                            Text(
                                text = podcast.feedTitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = GlassTokens.textSecondary(darkTheme),
                            )
                            if (activeProgress != null && activeProgress.positionMs > 0L) {
                                Text(
                                    text = formatPlaybackTime(activeProgress.positionMs, activeProgress.durationMs),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = GlassTokens.textSecondary(darkTheme),
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                            Button(onClick = { onPlayPodcast(podcast) }) {
                                Text(stringResource(R.string.action_listen))
                            }
                        }
                    }
                }
            }

            // Latest unread section
            if (unread.isNotEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.today_latest),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = GlassTokens.textPrimary(darkTheme),
                    )
                }
                items(unread.take(6), key = { it.id }) { item ->
                    val isSaved = inboxRepository.isSaved(item.id)
                    GlassSurface(
                        modifier = Modifier.fillMaxWidth(),
                        strong = false,
                        cornerRadius = GlassTokens.CardRadius,
                        darkTheme = darkTheme,
                    ) {
                        Column(
                            modifier = Modifier.padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = item.title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = GlassTokens.textPrimary(darkTheme),
                            )
                            Text(
                                text = "${item.feedTitle}${item.published?.let { " · $it" }.orEmpty()}",
                                style = MaterialTheme.typography.bodySmall,
                                color = GlassTokens.textSecondary(darkTheme),
                            )
                            item.description?.let { desc ->
                                val clean = desc.replace(Regex("<[^>]+>"), "").trim()
                                if (clean.isNotBlank()) {
                                    Text(
                                        text = clean,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis,
                                        color = GlassTokens.textSecondary(darkTheme),
                                    )
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = {
                                        if (item.audioUrl != null) onPlayPodcast(item) else onOpenArticle(item)
                                    }) {
                                        Text(stringResource(if (item.audioUrl != null) R.string.action_listen else R.string.action_read))
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            inboxRepository.setRead(item.id, true)
                                            items = inboxRepository.items()
                                        },
                                    ) {
                                        Text(stringResource(R.string.action_mark_read))
                                    }
                                }
                                TextButton(
                                    onClick = {
                                        inboxRepository.setSaved(item.id, !isSaved)
                                        items = inboxRepository.items()
                                    },
                                ) {
                                    Text(stringResource(if (isSaved) R.string.action_saved else R.string.action_save))
                                }
                            }
                        }
                    }
                }
            }

            // Saved section
            if (saved.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.today_saved),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = GlassTokens.textPrimary(darkTheme),
                        )
                        TextButton(onClick = onOpenLibrary) {
                            Text(stringResource(R.string.today_view_all))
                        }
                    }
                }
                items(saved.take(3), key = { "saved:${it.id}" }) { item ->
                    GlassSurface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (item.audioUrl != null) onPlayPodcast(item) else onOpenArticle(item)
                            },
                        strong = false,
                        cornerRadius = GlassTokens.CardRadius,
                        darkTheme = darkTheme,
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = item.title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                color = GlassTokens.textPrimary(darkTheme),
                            )
                            Text(
                                text = item.feedTitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = GlassTokens.textSecondary(darkTheme),
                            )
                        }
                    }
                }
            }

            // Bottom Refresh status & action
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    refreshStatus?.let { status ->
                        Text(
                            text = status,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    TextButton(
                        enabled = !isRefreshing,
                        onClick = {
                            scope.launch {
                                isRefreshing = true
                                val res = inboxRepository.refresh()
                                items = inboxRepository.items()
                                isRefreshing = false
                                refreshStatus = if (res.isOfflineFallback) {
                                    context.getString(R.string.today_offline_fallback)
                                } else {
                                    context.getString(R.string.today_refreshed)
                                }
                            }
                        },
                    ) {
                        Text(if (isRefreshing) stringResource(R.string.today_refreshing) else stringResource(R.string.today_refresh))
                    }
                }
            }
        }
    }

    if (showFullBriefDialog && dailyBriefRecord != null) {
        val record = dailyBriefRecord!!
        AlertDialog(
            onDismissRequest = { showFullBriefDialog = false },
            title = { Text(record.title, fontWeight = FontWeight.Bold) },
            text = {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    item {
                        Text(
                            text = record.tiny,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    item {
                        Text(
                            text = record.long,
                            style = MaterialTheme.typography.bodyMedium,
                            lineHeight = MaterialTheme.typography.bodyMedium.lineHeight * 1.3f,
                        )
                    }
                    if (record.bullets.isNotEmpty()) {
                        item {
                            Text(
                                text = stringResource(R.string.ai_prompt_takeaways),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        items(record.bullets) { bullet ->
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                AppIcon(AppIconKind.Bullet, Modifier.size(16.dp), MaterialTheme.colorScheme.primary)
                                Text(bullet, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    if (record.topics.isNotEmpty()) {
                        item {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(record.topics) { topic ->
                                    AssistChip(onClick = {}, label = { Text(topic) })
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { showFullBriefDialog = false }) {
                    Text(stringResource(R.string.daily_brief_close))
                }
            },
        )
    }
}
