package ink.underflo.wristbrief.mobile

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ink.underflo.wristbrief.mobile.ui.AppIcon
import ink.underflo.wristbrief.mobile.ui.AppIconKind
import ink.underflo.wristbrief.mobile.ui.glass.GlassSurface
import ink.underflo.wristbrief.mobile.ui.glass.GlassTokens
import ink.underflo.wristbrief.mobile.ui.glass.NeutralFilterChip
import kotlinx.coroutines.launch

@Composable
private fun exploreCategoryLabel(category: String): String = when (category) {
    "All" -> stringResource(R.string.category_all)
    "Tech", "Technology" -> stringResource(R.string.oobe_interest_tech)
    "Science" -> stringResource(R.string.oobe_interest_science)
    "Design" -> stringResource(R.string.oobe_interest_design)
    "Podcasts" -> stringResource(R.string.oobe_interest_podcasts)
    "News" -> stringResource(R.string.oobe_interest_news)
    else -> category
}

/**
 * Explore Destination (Screen 08) following uidocs/stitch_wristbrief_android_design_system/08_explore.
 *
 * Dedicated content source discovery center (not an algorithmic feed).
 * Allows users to discover high-signal curated RSS feeds and podcasts by domain.
 */
@Composable
fun ExploreDestination(
    padding: PaddingValues,
    feedManager: MobileFeedManager,
    onAddFeedDialog: (() -> Unit)? = null,
    darkTheme: Boolean = isSystemInDarkTheme(),
) {
    val scope = rememberCoroutineScope()
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var selectedCategory by rememberSaveable { mutableStateOf<String?>(null) }
    var addedFeedUrls by remember(feedManager) {
        mutableStateOf(feedManager.feeds().map { it.url }.toSet())
    }
    var addingFeedId by remember { mutableStateOf<String?>(null) }

    val categories = listOf("All", "Technology", "Science", "Design", "Podcasts", "News")

    val allCurated = SampleFeeds.curatedFeeds

    val filteredFeeds = remember(searchQuery, selectedCategory, allCurated) {
        allCurated.filter { feed ->
            val matchesCategory = selectedCategory == null ||
                selectedCategory.equals("All", ignoreCase = true) ||
                feed.category.equals(selectedCategory, ignoreCase = true) ||
                (selectedCategory.equals("Technology", ignoreCase = true) && feed.category.equals("Tech", ignoreCase = true)) ||
                (selectedCategory.equals("Podcasts", ignoreCase = true) && feed.isPodcast)
            val matchesSearch = if (searchQuery.isBlank()) true else {
                feed.title.contains(searchQuery, ignoreCase = true) ||
                    feed.description.contains(searchQuery, ignoreCase = true) ||
                    feed.category.contains(searchQuery, ignoreCase = true)
            }
            matchesCategory && matchesSearch
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Top Header
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.nav_explore),
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                        color = GlassTokens.textPrimary(darkTheme),
                    )
                    if (onAddFeedDialog != null) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(GlassTokens.surfaceContainerHigh(darkTheme))
                                .clickable(onClick = onAddFeedDialog),
                            contentAlignment = Alignment.Center,
                        ) {
                            AppIcon(
                                kind = AppIconKind.Add,
                                modifier = Modifier.size(20.dp),
                                tint = GlassTokens.textPrimary(darkTheme),
                            )
                        }
                    }
                }
                Text(
                    text = stringResource(R.string.explore_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = GlassTokens.textSecondary(darkTheme),
                    lineHeight = 22.sp,
                )
            }
        }

        // Search Input Bar
        item {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = {
                    Text(
                        text = stringResource(R.string.library_search_hint),
                        color = GlassTokens.textSecondary(darkTheme),
                    )
                },
                leadingIcon = {
                    AppIcon(
                        kind = AppIconKind.Search,
                        modifier = Modifier.size(20.dp),
                        tint = GlassTokens.textSecondary(darkTheme),
                    )
                },
                trailingIcon = if (searchQuery.isNotBlank()) {
                    {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .clickable { searchQuery = "" },
                            contentAlignment = Alignment.Center,
                        ) {
                            AppIcon(
                                kind = AppIconKind.Close,
                                modifier = Modifier.size(16.dp),
                                tint = GlassTokens.textSecondary(darkTheme),
                            )
                        }
                    }
                } else null,
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = GlassTokens.surfaceContainerHigh(darkTheme),
                    unfocusedContainerColor = GlassTokens.surfaceContainer(darkTheme),
                    focusedBorderColor = GlassTokens.accentTeal(darkTheme),
                    unfocusedBorderColor = GlassTokens.hairline(darkTheme),
                    focusedTextColor = GlassTokens.textPrimary(darkTheme),
                    unfocusedTextColor = GlassTokens.textPrimary(darkTheme),
                ),
                singleLine = true,
            )
        }

        // Horizontal Category Chips
        item {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(categories) { cat ->
                    val isSelected = if (cat == "All") selectedCategory == null else selectedCategory.equals(cat, ignoreCase = true)
                    NeutralFilterChip(
                        label = cat,
                        selected = isSelected,
                        onClick = {
                            selectedCategory = if (cat == "All" || selectedCategory.equals(cat, ignoreCase = true)) null else cat
                        },
                        darkTheme = darkTheme,
                    )
                }
            }
        }

        // Section: Curated Spotlight / Featured Sources
        if (allCurated.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(width = 4.dp, height = 16.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(GlassTokens.accentTeal(darkTheme)),
                        )
                        Text(
                            text = stringResource(R.string.sample_feeds_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = GlassTokens.textPrimary(darkTheme),
                        )
                    }
                    Text(
                        text = stringResource(R.string.explore_editorial_choice),
                        style = MaterialTheme.typography.labelSmall,
                        color = GlassTokens.accentTeal(darkTheme),
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }

        if (filteredFeeds.isEmpty()) {
            item {
                GlassSurface(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = GlassTokens.CardRadius,
                    darkTheme = darkTheme,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Surface(
                            modifier = Modifier.size(56.dp),
                            shape = CircleShape,
                            color = GlassTokens.surfaceContainerHigh(darkTheme),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                AppIcon(AppIconKind.Search, Modifier.size(24.dp), GlassTokens.textSecondary(darkTheme))
                            }
                        }
                        Text(
                            text = if (searchQuery.isNotBlank()) stringResource(R.string.search_empty_title) else stringResource(R.string.library_empty),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = GlassTokens.textPrimary(darkTheme),
                        )
                        Text(
                            text = if (searchQuery.isNotBlank()) stringResource(R.string.search_empty_prompt) else stringResource(R.string.explore_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = GlassTokens.textSecondary(darkTheme),
                            textAlign = TextAlign.Center,
                        )
                        if (searchQuery.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.search_empty_suggestions),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = GlassTokens.accentTeal(darkTheme),
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(top = 2.dp),
                            ) {
                                listOf("Technology", "Design", "Podcasts").forEach { topic ->
                                    Surface(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(999.dp))
                                        .clickable {
                                            searchQuery = ""
                                            selectedCategory = topic
                                        },
                                        shape = RoundedCornerShape(999.dp),
                                        color = GlassTokens.surfaceContainerHigh(darkTheme),
                                    ) {
                                        Text(
                                            text = exploreCategoryLabel(topic),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = GlassTokens.textPrimary(darkTheme),
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else {
            items(filteredFeeds) { feed ->
                val isAdded = feed.url in addedFeedUrls
                val isAdding = addingFeedId == feed.id

                GlassSurface(
                    modifier = Modifier.fillMaxWidth(),
                    strong = false,
                    cornerRadius = GlassTokens.CardRadius,
                    darkTheme = darkTheme,
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Surface(
                                modifier = Modifier.size(44.dp),
                                shape = RoundedCornerShape(12.dp),
                                color = GlassTokens.surfaceContainerHigh(darkTheme),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    if (feed.isPodcast) {
                                        AppIcon(
                                            kind = AppIconKind.Audio,
                                            modifier = Modifier.size(20.dp),
                                            tint = GlassTokens.accentTeal(darkTheme),
                                        )
                                    } else {
                                        Text(
                                            text = feed.title.take(2).uppercase(),
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = GlassTokens.accentTeal(darkTheme),
                                        )
                                    }
                                }
                            }

                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text(
                                        text = feed.title,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = GlassTokens.textPrimary(darkTheme),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(GlassTokens.surfaceContainerHighest(darkTheme))
                                            .padding(horizontal = 6.dp, vertical = 2.dp),
                                    ) {
                                        Text(
                                            text = if (feed.isPodcast) "Podcast" else "RSS",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = GlassTokens.textSecondary(darkTheme),
                                            fontSize = 10.sp,
                                        )
                                    }
                                }
                                    Text(
                                        text = exploreCategoryLabel(feed.category),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = GlassTokens.textSecondary(darkTheme),
                                )
                            }
                        }

                        Text(
                            text = feed.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = GlassTokens.textSecondary(darkTheme),
                            lineHeight = 18.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = if (feed.isPodcast) "Weekly show" else "~8 articles / wk",
                                style = MaterialTheme.typography.labelSmall,
                                color = GlassTokens.textSecondary(darkTheme),
                            )

                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(
                                        if (isAdded) GlassTokens.surfaceContainer(darkTheme)
                                        else GlassTokens.primaryContainer(darkTheme),
                                    )
                                    .border(
                                        width = 1.dp,
                                        color = if (isAdded) GlassTokens.hairline(darkTheme) else androidx.compose.ui.graphics.Color.Transparent,
                                        shape = RoundedCornerShape(999.dp),
                                    )
                                    .clickable(enabled = !isAdded && !isAdding) {
                                        scope.launch {
                                            addingFeedId = feed.id
                                            try {
                                                val res = feedManager.add(feed.url, feed.title, feed.category)
                                                if (res is FeedMutationResult.Success) {
                                                    addedFeedUrls = addedFeedUrls + feed.url
                                                }
                                            } finally {
                                                addingFeedId = null
                                            }
                                        }
                                    }
                                    .padding(horizontal = 14.dp, vertical = 8.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (isAdding) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(14.dp),
                                            strokeWidth = 2.dp,
                                            color = GlassTokens.accentTeal(darkTheme),
                                        )
                                        Text(
                                            text = stringResource(R.string.action_adding),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = GlassTokens.textPrimary(darkTheme),
                                        )
                                    }
                                } else if (isAdded) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        AppIcon(
                                            kind = AppIconKind.Check,
                                            modifier = Modifier.size(14.dp),
                                            tint = GlassTokens.accentTeal(darkTheme),
                                        )
                                        Text(
                                            text = stringResource(R.string.action_subscribed),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Medium,
                                            color = GlassTokens.accentTeal(darkTheme),
                                        )
                                    }
                                } else {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        AppIcon(
                                            kind = AppIconKind.Add,
                                            modifier = Modifier.size(14.dp),
                                            tint = GlassTokens.onPrimaryContainer(darkTheme),
                                        )
                                        Text(
                                            text = stringResource(R.string.action_subscribe),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = GlassTokens.onPrimaryContainer(darkTheme),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(32.dp)) }
    }
}
