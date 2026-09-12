package ink.underflo.wristbrief.mobile

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ink.underflo.wristbrief.mobile.media.PodcastProgressStore
import ink.underflo.wristbrief.mobile.media.formatPlaybackTime
import ink.underflo.wristbrief.mobile.ui.AppIcon
import ink.underflo.wristbrief.mobile.ui.AppIconKind
import ink.underflo.wristbrief.mobile.ui.ErrorBanner
import ink.underflo.wristbrief.mobile.ui.glass.GlassSurface
import ink.underflo.wristbrief.mobile.ui.glass.GlassTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
private fun todayCategoryLabel(category: String): String = when (category) {
    "All" -> stringResource(R.string.category_all)
    "Technology" -> stringResource(R.string.oobe_interest_tech)
    "Science" -> stringResource(R.string.oobe_interest_science)
    "Design" -> stringResource(R.string.oobe_interest_design)
    "Business" -> stringResource(R.string.category_business)
    "Podcasts" -> stringResource(R.string.oobe_interest_podcasts)
    else -> category
}

/**
 * Editorial Home / Today Destination (Screen 06 & 07)
 * Implements Stitch WristBrief Android Design System specifications:
 * - 06_home_today (Editorial header, category chips, hero daily brief, continue reading/listening, latest stream)
 * - 07_full_daily_brief (Deep synthesis view with Spoken Audio Brief and structured key points)
 */
@Composable
internal fun TodayDestination(
    padding: PaddingValues,
    inboxRepository: MobileInboxRepository,
    feedManager: MobileFeedManager,
    onAddFeed: () -> Unit,
    onImportOpml: () -> Unit,
    onOpenAskAi: () -> Unit,
    onOpenLibrary: () -> Unit,
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

    val defaultCategories = listOf("All", "Technology", "Science", "Design", "Business", "Podcasts")
    val userCategories = remember(feeds) {
        feeds.mapNotNull { it.category }.filter { it.isNotBlank() }.distinct()
    }
    val allCategories = remember(userCategories) {
        (defaultCategories + userCategories).distinct()
    }

    val displayedItems = remember(items, selectedCategory, feeds) {
        if (selectedCategory == null || selectedCategory == "All") {
            items
        } else if (selectedCategory.equals("Podcasts", ignoreCase = true)) {
            items.filter { it.audioUrl != null }
        } else {
            val feedIds = feeds.filter {
                it.category.equals(selectedCategory, ignoreCase = true) ||
                    (selectedCategory.equals("Technology", ignoreCase = true) && it.category.equals("Tech", ignoreCase = true))
            }.map { it.id }.toSet()
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
        } ?: displayedItems.firstOrNull { it.audioUrl != null }
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
        val input = DailyBriefInputBuilder.build(unread.ifEmpty { items }) ?: return
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
                    tiny = summary.tiny.ifBlank { summary.text.take(160) },
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
        format.format(Date()).uppercase(Locale.getDefault())
    }

    val greetingRes = remember {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        when (hour) {
            in 5..11 -> R.string.today_greeting_morning
            in 12..17 -> R.string.today_greeting_afternoon
            else -> R.string.today_greeting_evening
        }
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

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentPadding = PaddingValues(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // 1. Editorial Date & Greeting Header
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = todayDateFormatted,
                        style = MaterialTheme.typography.labelSmall,
                        color = GlassTokens.textSecondary(darkTheme),
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 1.sp,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = stringResource(greetingRes),
                        style = MaterialTheme.typography.headlineMedium,
                        color = GlassTokens.textPrimary(darkTheme),
                        fontWeight = FontWeight.Bold,
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Search Action Button
                    Surface(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .clickable(onClick = onOpenLibrary),
                        shape = CircleShape,
                        color = GlassTokens.surfaceContainerHigh(darkTheme),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            AppIcon(
                                kind = AppIconKind.Search,
                                modifier = Modifier.size(20.dp),
                                tint = GlassTokens.textPrimary(darkTheme),
                            )
                        }
                    }
                }
            }
        }

        // 2. Horizontal Scrollable Expressive Category Filter Chips
        item {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(allCategories) { category ->
                    val isSelected = if (category == "All") {
                        selectedCategory == null || selectedCategory == "All"
                    } else {
                        selectedCategory.equals(category, ignoreCase = true)
                    }

                    val chipBg = if (isSelected) {
                        GlassTokens.primaryContainer(darkTheme)
                    } else {
                        GlassTokens.surfaceContainer(darkTheme)
                    }
                    val chipText = if (isSelected) {
                        GlassTokens.accentTeal(darkTheme)
                    } else {
                        GlassTokens.textSecondary(darkTheme)
                    }

                    Surface(
                        modifier = Modifier
                            .height(36.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .clickable {
                                selectedCategory = if (category == "All") null else category
                            },
                        shape = RoundedCornerShape(18.dp),
                        color = chipBg,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            if (isSelected) {
                                AppIcon(
                                    kind = AppIconKind.Check,
                                    modifier = Modifier.size(15.dp),
                                    tint = GlassTokens.accentTeal(darkTheme),
                                )
                            }
                            Text(
                                text = todayCategoryLabel(category),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                color = chipText,
                            )
                        }
                    }
                }
            }
        }

        // Error banner if feeds failed
        if (failedFeedTitles.isNotEmpty()) {
            item {
                Box(Modifier.padding(horizontal = 20.dp)) {
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
        }

        // 3. Central Signature Element: Today's Brief Hero Card (Screen 06)
        item {
            Box(Modifier.padding(horizontal = 20.dp)) {
                GlassSurface(
                    modifier = Modifier.fillMaxWidth(),
                    strong = true,
                    cornerRadius = GlassTokens.HeroRadius,
                    darkTheme = darkTheme,
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        // Hero Header
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = GlassTokens.surfaceContainerHighest(darkTheme).copy(alpha = 0.7f),
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        AppIcon(
                                            kind = AppIconKind.Spark,
                                            modifier = Modifier.size(13.dp),
                                            tint = GlassTokens.accentTeal(darkTheme),
                                        )
                                        Text(
                                            text = stringResource(R.string.today_brief_title).uppercase(Locale.getDefault()),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = GlassTokens.accentTeal(darkTheme),
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 0.5.sp,
                                        )
                                    }
                                }

                                val sourceCount = dailyBriefRecord?.sourceCount ?: unread.size
                                Text(
                                    text = stringResource(R.string.today_sources_count, sourceCount),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = GlassTokens.textSecondary(darkTheme),
                                )
                            }

                            val timeText = dailyBriefRecord?.let {
                                val tf = SimpleDateFormat("h:mm a", Locale.getDefault())
                                tf.format(Date(it.generatedAtEpochMs))
                            }
                            Text(
                                text = timeText ?: stringResource(R.string.today_label),
                                style = MaterialTheme.typography.labelSmall,
                                color = GlassTokens.textSecondary(darkTheme),
                            )
                        }

                        // Hero Body Content
                        if (dailyBriefRecord != null && dailyBriefRecord?.dateKey == todayKey) {
                            val record = dailyBriefRecord!!
                            Text(
                                text = record.tiny,
                                style = MaterialTheme.typography.bodyMedium,
                                color = GlassTokens.textPrimary(darkTheme),
                                lineHeight = 22.sp,
                            )

                            // Key Takeaways Inset Box with Distinct Color Dots
                            if (record.bullets.isNotEmpty()) {
                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = GlassTokens.surfaceContainerLow(darkTheme).copy(alpha = 0.6f),
                                ) {
                                    Column(
                                        modifier = Modifier.padding(14.dp),
                                        verticalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        record.bullets.take(3).forEachIndexed { idx, bullet ->
                                            val dotColor = when (idx) {
                                                0 -> GlassTokens.accentTeal(darkTheme)
                                                1 -> MaterialTheme.colorScheme.primary
                                                else -> MaterialTheme.colorScheme.secondary
                                            }
                                            Row(
                                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                                verticalAlignment = Alignment.Top,
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .padding(top = 6.dp)
                                                        .size(6.dp)
                                                        .clip(CircleShape)
                                                        .background(dotColor),
                                                )
                                                Text(
                                                    text = bullet,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = GlassTokens.textSecondary(darkTheme),
                                                    lineHeight = 18.sp,
                                                )
                                            }
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

                            // Hero Action Row: Read full brief (primary pill) + Regenerate (circle button)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Surface(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .clip(RoundedCornerShape(24.dp))
                                        .clickable { showFullBriefDialog = true },
                                    shape = RoundedCornerShape(24.dp),
                                    color = GlassTokens.primaryContainer(darkTheme),
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = stringResource(R.string.daily_brief_read_full),
                                            style = MaterialTheme.typography.labelLarge,
                                            color = GlassTokens.accentTeal(darkTheme),
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        AppIcon(
                                            kind = AppIconKind.ChevronRight,
                                            modifier = Modifier.size(18.dp),
                                            tint = GlassTokens.accentTeal(darkTheme),
                                        )
                                    }
                                }

                                Surface(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(CircleShape)
                                        .clickable(enabled = !isGeneratingBrief, onClick = ::generateDailyBrief),
                                    shape = CircleShape,
                                    color = GlassTokens.surfaceContainer(darkTheme),
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        if (isGeneratingBrief) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(20.dp),
                                                strokeWidth = 2.dp,
                                                color = GlassTokens.accentTeal(darkTheme),
                                            )
                                        } else {
                                            AppIcon(
                                                kind = AppIconKind.Refresh,
                                                modifier = Modifier.size(20.dp),
                                                tint = GlassTokens.textSecondary(darkTheme),
                                            )
                                        }
                                    }
                                }
                            }
                        } else if (unread.isNotEmpty() || items.isNotEmpty()) {
                            // Needs Brief Generation
                            val unreadCount = if (unread.isNotEmpty()) unread.size else items.size
                            Text(
                                text = stringResource(R.string.daily_brief_unread_prompt, unreadCount),
                                style = MaterialTheme.typography.bodyMedium,
                                color = GlassTokens.textSecondary(darkTheme),
                                lineHeight = 22.sp,
                            )

                            briefErrorMessage?.let { err ->
                                Text(
                                    text = err,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }

                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .clip(RoundedCornerShape(24.dp))
                                    .clickable(enabled = !isGeneratingBrief, onClick = ::generateDailyBrief),
                                shape = RoundedCornerShape(24.dp),
                                color = GlassTokens.primaryContainer(darkTheme),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (isGeneratingBrief) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(20.dp),
                                            strokeWidth = 2.dp,
                                            color = GlassTokens.accentTeal(darkTheme),
                                        )
                                        Spacer(Modifier.width(10.dp))
                                        Text(
                                            text = stringResource(R.string.daily_brief_generating),
                                            style = MaterialTheme.typography.labelLarge,
                                            color = GlassTokens.accentTeal(darkTheme),
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                    } else {
                                        AppIcon(
                                            kind = AppIconKind.Spark,
                                            modifier = Modifier.size(18.dp),
                                            tint = GlassTokens.accentTeal(darkTheme),
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            text = stringResource(R.string.daily_brief_generate),
                                            style = MaterialTheme.typography.labelLarge,
                                            color = GlassTokens.accentTeal(darkTheme),
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                    }
                                }
                            }
                        } else {
                            // All caught up
                            Text(
                                text = stringResource(R.string.daily_brief_all_caught_up),
                                style = MaterialTheme.typography.bodyMedium,
                                color = GlassTokens.textSecondary(darkTheme),
                            )
                            OutlinedButton(
                                onClick = onAddFeed,
                                modifier = Modifier.align(Alignment.End),
                            ) {
                                Text(stringResource(R.string.today_add_feed))
                            }
                        }
                    }
                }
            }
        }

        // 4. Continue Listening Section (if podcast available)
        continueListening?.let { podcast ->
            item {
                Column(
                    modifier = Modifier.padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.today_continue_listening),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = GlassTokens.textPrimary(darkTheme),
                        )
                        Text(
                            text = "Audio",
                            style = MaterialTheme.typography.labelSmall,
                            color = GlassTokens.accentTeal(darkTheme),
                            fontWeight = FontWeight.Medium,
                        )
                    }

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { onPlayPodcast(podcast) },
                        shape = RoundedCornerShape(16.dp),
                        color = GlassTokens.surfaceContainerLow(darkTheme),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                // Artwork square
                                Surface(
                                    modifier = Modifier.size(56.dp),
                                    shape = RoundedCornerShape(12.dp),
                                    color = GlassTokens.surfaceContainerHighest(darkTheme),
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        AppIcon(
                                            kind = AppIconKind.Podcast,
                                            modifier = Modifier.size(24.dp),
                                            tint = GlassTokens.accentTeal(darkTheme),
                                        )
                                    }
                                }

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = podcast.feedTitle.uppercase(Locale.getDefault()),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = GlassTokens.accentTeal(darkTheme),
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                    )
                                    Text(
                                        text = podcast.title,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = GlassTokens.textPrimary(darkTheme),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    podcast.description?.let { desc ->
                                        val clean = desc.replace(Regex("<[^>]+>"), "").trim()
                                        if (clean.isNotBlank()) {
                                            Text(
                                                text = clean,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = GlassTokens.textSecondary(darkTheme),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                }

                                // Play button in primaryContainer
                                Surface(
                                    modifier = Modifier.size(44.dp),
                                    shape = CircleShape,
                                    color = GlassTokens.primaryContainer(darkTheme),
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        AppIcon(
                                            kind = AppIconKind.Play,
                                            modifier = Modifier.size(22.dp),
                                            tint = GlassTokens.accentTeal(darkTheme),
                                        )
                                    }
                                }
                            }

                            // Audio Progress bar
                            val hasProgress = activeProgress != null && activeProgress.durationMs > 0L
                            val progressFraction = if (hasProgress) {
                                (activeProgress.positionMs.toFloat() / activeProgress.durationMs.toFloat()).coerceIn(0f, 1f)
                            } else {
                                0f
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (hasProgress) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(4.dp)
                                            .clip(RoundedCornerShape(2.dp))
                                            .background(GlassTokens.surfaceContainerHighest(darkTheme)),
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth(progressFraction)
                                                .height(4.dp)
                                                .clip(RoundedCornerShape(2.dp))
                                                .background(GlassTokens.accentTeal(darkTheme)),
                                        )
                                    }
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    if (hasProgress) {
                                        val playedText = formatPlaybackTime(activeProgress.positionMs, activeProgress.durationMs)
                                        Text(
                                            text = playedText,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = GlassTokens.textSecondary(darkTheme),
                                        )
                                    }
                                    Text(
                                        text = if (hasProgress) "In progress" else "Audio",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = GlassTokens.textSecondary(darkTheme),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 5. Continue Reading Section (if text article available)
        continueReading?.let { reading ->
            val isReadingSaved = inboxRepository.isSaved(reading.id)
            item {
                Column(
                    modifier = Modifier.padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.today_continue_reading),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = GlassTokens.textPrimary(darkTheme),
                        )
                        Text(
                            text = stringResource(R.string.today_latest),
                            style = MaterialTheme.typography.labelSmall,
                            color = GlassTokens.textSecondary(darkTheme),
                        )
                    }

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { onOpenArticle(reading) },
                        shape = RoundedCornerShape(16.dp),
                        color = GlassTokens.surfaceContainer(darkTheme),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
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
                                    Surface(
                                        modifier = Modifier.size(22.dp),
                                        shape = CircleShape,
                                        color = GlassTokens.surfaceContainerHighest(darkTheme),
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text(
                                                text = reading.feedTitle.take(1).uppercase(Locale.getDefault()),
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = GlassTokens.accentTeal(darkTheme),
                                            )
                                        }
                                    }
                                    Text(
                                        text = reading.feedTitle,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = GlassTokens.textSecondary(darkTheme),
                                    )
                                }

                                Surface(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .clickable {
                                            inboxRepository.setSaved(reading.id, !isReadingSaved)
                                            items = inboxRepository.items()
                                        },
                                    shape = CircleShape,
                                    color = Color.Transparent,
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        AppIcon(
                                            kind = if (isReadingSaved) AppIconKind.BookmarkFilled else AppIconKind.Bookmark,
                                            modifier = Modifier.size(18.dp),
                                            tint = if (isReadingSaved) GlassTokens.accentTeal(darkTheme) else GlassTokens.textSecondary(darkTheme),
                                        )
                                    }
                                }
                            }

                            Text(
                                text = reading.title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = GlassTokens.textPrimary(darkTheme),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )

                            reading.description?.let { desc ->
                                val clean = desc.replace(Regex("<[^>]+>"), "").trim()
                                if (clean.isNotBlank()) {
                                    Text(
                                        text = clean,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = GlassTokens.textSecondary(darkTheme),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (!reading.published.isNullOrBlank()) {
                                    Text(
                                        text = reading.published,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = GlassTokens.textSecondary(darkTheme),
                                    )
                                } else {
                                    Spacer(Modifier.width(1.dp))
                                }

                                Surface(
                                    modifier = Modifier
                                        .height(32.dp)
                                        .clip(RoundedCornerShape(16.dp))
                                        .clickable { onOpenArticle(reading) },
                                    shape = RoundedCornerShape(16.dp),
                                    color = GlassTokens.surfaceContainerHigh(darkTheme),
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Text(
                                            text = stringResource(R.string.today_resume),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                        AppIcon(
                                            kind = AppIconKind.ChevronRight,
                                            modifier = Modifier.size(14.dp),
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 6. Latest Stream from Feeds (Articles & Audio)
        if (items.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.today_latest),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = GlassTokens.textPrimary(darkTheme),
                    )
                    TextButton(onClick = onOpenLibrary) {
                        Text(
                            text = stringResource(R.string.today_view_all),
                            style = MaterialTheme.typography.labelSmall,
                            color = GlassTokens.accentTeal(darkTheme),
                        )
                    }
                }
            }

            items(displayedItems.take(6), key = { it.id }) { item ->
                val isSaved = inboxRepository.isSaved(item.id)
                val isUnread = !inboxRepository.isRead(item.id)
                val isPodcast = item.audioUrl != null

                Box(Modifier.padding(horizontal = 20.dp)) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .clickable {
                                if (isPodcast) onPlayPodcast(item) else onOpenArticle(item)
                            },
                        shape = RoundedCornerShape(18.dp),
                        color = GlassTokens.surfaceContainer(darkTheme),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            // Top Row: Source, Time, Save Toggle
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Surface(
                                        modifier = Modifier.size(24.dp),
                                        shape = RoundedCornerShape(6.dp),
                                        color = GlassTokens.surfaceContainerHighest(darkTheme),
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            if (isPodcast) {
                                                AppIcon(
                                                    kind = AppIconKind.Podcast,
                                                    modifier = Modifier.size(14.dp),
                                                    tint = GlassTokens.accentTeal(darkTheme),
                                                )
                                            } else {
                                                Text(
                                                    text = item.feedTitle.take(1).uppercase(Locale.getDefault()),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.primary,
                                                )
                                            }
                                        }
                                    }
                                    Text(
                                        text = item.feedTitle,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Medium,
                                        color = GlassTokens.textPrimary(darkTheme),
                                    )
                                    item.published?.let {
                                        Text(
                                            text = "· ${formattedArticleTimestamp(it)}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = GlassTokens.textSecondary(darkTheme),
                                        )
                                    }
                                }

                                Surface(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .clickable {
                                            inboxRepository.setSaved(item.id, !isSaved)
                                            items = inboxRepository.items()
                                        },
                                    shape = CircleShape,
                                    color = Color.Transparent,
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        AppIcon(
                                            kind = if (isSaved) AppIconKind.BookmarkFilled else AppIconKind.Bookmark,
                                            modifier = Modifier.size(18.dp),
                                            tint = if (isSaved) GlassTokens.accentTeal(darkTheme) else GlassTokens.textSecondary(darkTheme),
                                        )
                                    }
                                }
                            }

                            // Title & Description
                            Text(
                                text = item.title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = if (isUnread) FontWeight.SemiBold else FontWeight.Normal,
                                color = GlassTokens.textPrimary(darkTheme),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )

                            item.description?.let { desc ->
                                val clean = desc.replace(Regex("<[^>]+>"), "").trim()
                                if (clean.isNotBlank()) {
                                    Text(
                                        text = clean,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = GlassTokens.textSecondary(darkTheme),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }

                            // Footer Action Row
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    if (isPodcast) {
                                        Text(
                                            text = "Audio Episode",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = GlassTokens.textSecondary(darkTheme),
                                        )
                                    } else {
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = GlassTokens.surfaceContainerHigh(darkTheme),
                                        ) {
                                            Text(
                                                text = "Article",
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = GlassTokens.textSecondary(darkTheme),
                                            )
                                        }
                                        Text(
                                            text = "5 min read",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = GlassTokens.textSecondary(darkTheme),
                                        )
                                    }
                                }

                                if (isPodcast) {
                                    Surface(
                                        modifier = Modifier
                                            .height(34.dp)
                                            .clip(RoundedCornerShape(17.dp))
                                            .clickable { onPlayPodcast(item) },
                                        shape = RoundedCornerShape(17.dp),
                                        color = GlassTokens.primaryContainer(darkTheme),
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 14.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            AppIcon(
                                                kind = AppIconKind.Play,
                                                modifier = Modifier.size(16.dp),
                                                tint = GlassTokens.accentTeal(darkTheme),
                                            )
                                            Text(
                                                text = stringResource(R.string.action_listen),
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.SemiBold,
                                                color = GlassTokens.accentTeal(darkTheme),
                                            )
                                        }
                                    }
                                } else {
                                    Surface(
                                        modifier = Modifier
                                            .height(34.dp)
                                            .clip(RoundedCornerShape(17.dp))
                                            .clickable { onOpenArticle(item) },
                                        shape = RoundedCornerShape(17.dp),
                                        color = GlassTokens.surfaceContainerHigh(darkTheme),
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 14.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            AppIcon(
                                                kind = AppIconKind.ChevronRight,
                                                modifier = Modifier.size(16.dp),
                                                tint = GlassTokens.textPrimary(darkTheme),
                                            )
                                            Text(
                                                text = stringResource(R.string.action_read),
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.SemiBold,
                                                color = GlassTokens.textPrimary(darkTheme),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 7. Editorial Footnote / Synced Status
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(GlassTokens.semanticSuccess(darkTheme)),
                    )
                    Text(
                        text = stringResource(R.string.today_synced_footnote, feeds.size.coerceAtLeast(1)),
                        style = MaterialTheme.typography.labelSmall,
                        color = GlassTokens.textSecondary(darkTheme),
                    )
                }
                Text(
                    text = stringResource(R.string.today_engine_version),
                    style = MaterialTheme.typography.labelSmall,
                    color = GlassTokens.textSecondary(darkTheme).copy(alpha = 0.6f),
                )
            }
        }
    }

    // 8. Full Daily Brief View (Screen 07 Dialog)
    if (showFullBriefDialog && dailyBriefRecord != null) {
        val record = dailyBriefRecord!!
        FullDailyBriefDialog(
            record = record,
            onDismiss = { showFullBriefDialog = false },
            darkTheme = darkTheme,
        )
    }
}

/**
 * Screen 07: Full Daily Brief
 * Implements Stitch WristBrief Android Design System specifications for 07_full_daily_brief.
 */
@Composable
private fun FullDailyBriefDialog(
    record: DailyBriefRecord,
    onDismiss: () -> Unit,
    darkTheme: Boolean = isSystemInDarkTheme(),
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = GlassTokens.canvas(darkTheme),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // In-Page Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(GlassTokens.surfaceGlassStrong(darkTheme))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Surface(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .clickable(onClick = onDismiss),
                            shape = CircleShape,
                            color = GlassTokens.surfaceContainer(darkTheme),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                AppIcon(
                                    kind = AppIconKind.Back,
                                    modifier = Modifier.size(20.dp),
                                    tint = GlassTokens.textPrimary(darkTheme),
                                )
                            }
                        }

                        Column {
                            Text(
                                text = stringResource(R.string.today_brief_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = GlassTokens.textPrimary(darkTheme),
                            )
                            val dateStr = remember(record.generatedAtEpochMs) {
                                val df = SimpleDateFormat("EEEE, MMM d", Locale.getDefault())
                                df.format(Date(record.generatedAtEpochMs))
                            }
                            Text(
                                text = dateStr,
                                style = MaterialTheme.typography.labelSmall,
                                color = GlassTokens.textSecondary(darkTheme),
                            )
                        }
                    }
                }

                // Scrollable Editorial Content Canvas
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(20.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    // Lead Block: Synthesis Pill, Timestamp, Headline, Lead Body
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = GlassTokens.surfaceContainerHigh(darkTheme),
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        AppIcon(
                                            kind = AppIconKind.Spark,
                                            modifier = Modifier.size(13.dp),
                                            tint = GlassTokens.accentTeal(darkTheme),
                                        )
                                        Text(
                                            text = "SYNTHESIS · ${record.sourceCount} SOURCES",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = GlassTokens.accentTeal(darkTheme),
                                            fontWeight = FontWeight.Bold,
                                        )
                                    }
                                }

                                val timeStr = remember(record.generatedAtEpochMs) {
                                    val tf = SimpleDateFormat("h:mm a", Locale.getDefault())
                                    tf.format(Date(record.generatedAtEpochMs))
                                }
                                Text(
                                    text = "Generated $timeStr",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = GlassTokens.textSecondary(darkTheme),
                                )
                            }

                            Text(
                                text = record.title,
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = GlassTokens.textPrimary(darkTheme),
                                lineHeight = 30.sp,
                            )

                            Text(
                                text = record.tiny,
                                style = MaterialTheme.typography.bodyMedium,
                                color = GlassTokens.textSecondary(darkTheme),
                                lineHeight = 24.sp,
                            )
                        }
                    }


                    // Key Points Section (Screen 07)
                    if (record.bullets.isNotEmpty()) {
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(GlassTokens.accentTeal(darkTheme)),
                                    )
                                    Text(
                                        text = stringResource(R.string.today_key_points).uppercase(Locale.getDefault()),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = GlassTokens.textPrimary(darkTheme),
                                        letterSpacing = 1.sp,
                                    )
                                }

                                record.bullets.forEachIndexed { index, bullet ->
                                    Surface(
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(16.dp),
                                        color = GlassTokens.surfaceContainer(darkTheme),
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(16.dp),
                                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                                            verticalAlignment = Alignment.Top,
                                        ) {
                                            Text(
                                                text = String.format(Locale.getDefault(), "%02d", index + 1),
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = GlassTokens.accentTeal(darkTheme),
                                            )
                                            Text(
                                                text = bullet,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = GlassTokens.textPrimary(darkTheme),
                                                lineHeight = 22.sp,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Full Text Content
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "DETAILED SYNTHESIS",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = GlassTokens.textSecondary(darkTheme),
                                letterSpacing = 1.sp,
                            )
                            Text(
                                text = record.long,
                                style = MaterialTheme.typography.bodyMedium,
                                color = GlassTokens.textPrimary(darkTheme),
                                lineHeight = 24.sp,
                            )
                        }
                    }

                    // Topics In Focus Section
                    if (record.topics.isNotEmpty()) {
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = "TOPICS IN FOCUS",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = GlassTokens.textSecondary(darkTheme),
                                    letterSpacing = 1.sp,
                                )
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(record.topics) { topic ->
                                        Surface(
                                            shape = RoundedCornerShape(14.dp),
                                            color = GlassTokens.surfaceContainerHigh(darkTheme),
                                        ) {
                                            Text(
                                                text = topic,
                                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                                style = MaterialTheme.typography.labelMedium,
                                                color = GlassTokens.textPrimary(darkTheme),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Bottom Dismiss Button
                    item {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .clip(RoundedCornerShape(24.dp))
                                .clickable(onClick = onDismiss),
                            shape = RoundedCornerShape(24.dp),
                            color = GlassTokens.primaryContainer(darkTheme),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = stringResource(R.string.daily_brief_close),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = GlassTokens.accentTeal(darkTheme),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
