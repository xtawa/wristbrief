package ink.underflo.wristbrief.mobile

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.RssFeed
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ink.underflo.wristbrief.mobile.ui.AppIcon
import ink.underflo.wristbrief.mobile.ui.AppIconKind
import ink.underflo.wristbrief.mobile.ui.glass.GlassTokens
import kotlinx.coroutines.launch

internal class OnboardingPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("onboarding", Context.MODE_PRIVATE)
    fun isComplete(): Boolean = preferences.getBoolean("complete", false)
    fun complete() = preferences.edit()
        .putBoolean("complete", true)
        .remove("interests")
        .apply()
}

internal enum class OnboardingAction { AddFeed, ImportOpml, Explore }

private enum class OnboardingPage(@StringRes val title: Int, @StringRes val body: Int) {
    Welcome(R.string.oobe_welcome_title, R.string.oobe_welcome_body),
    Interests(R.string.oobe_interests_title, R.string.oobe_interests_body),
    Content(R.string.oobe_content_title, R.string.oobe_content_body),
    AiFeatures(R.string.oobe_ai_title, R.string.oobe_ai_body),
    Ready(R.string.oobe_ready_title, R.string.oobe_ready_body),
}

private data class InterestItem(val key: String, @StringRes val labelRes: Int)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WristBriefOnboarding(
    feedCount: Int = 0,
    itemCount: Int = 0,
    onAddSampleFeed: (suspend (SampleFeed) -> Unit)? = null,
    onComplete: (OnboardingAction) -> Unit,
    darkTheme: Boolean = isSystemInDarkTheme(),
) {
    var pageIndex by rememberSaveable { mutableStateOf(0) }
    val page = OnboardingPage.entries[pageIndex]
    val scope = rememberCoroutineScope()
    var addedSampleIds by remember { mutableStateOf(setOf<String>()) }
    var addingSampleIds by remember { mutableStateOf(setOf<String>()) }
    var selectedInterests by remember { mutableStateOf(setOf("tech", "news")) }

    val interestTopics = remember {
        listOf(
            InterestItem("tech", R.string.oobe_interest_tech),
            InterestItem("design", R.string.oobe_interest_design),
            InterestItem("science", R.string.oobe_interest_science),
            InterestItem("podcasts", R.string.oobe_interest_podcasts),
            InterestItem("news", R.string.oobe_interest_news),
            InterestItem("culture", R.string.oobe_interest_culture),
        )
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = GlassTokens.canvas(darkTheme),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Header: Branding and Skip action
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
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(GlassTokens.accentTeal(darkTheme)),
                    )
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = GlassTokens.textPrimary(darkTheme),
                    )
                }

                if (page != OnboardingPage.Ready) {
                    TextButton(onClick = { onComplete(OnboardingAction.Explore) }) {
                        Text(
                            text = stringResource(R.string.oobe_skip),
                            color = GlassTokens.textSecondary(darkTheme),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                } else {
                    Spacer(Modifier.width(48.dp))
                }
            }

            Spacer(Modifier.height(16.dp))

            // Main Page Animated Content
            AnimatedContent(
                targetState = page,
                transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(160)) },
                modifier = Modifier.weight(1f),
                label = "onboarding-page",
            ) { current ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    OnboardingArtwork(current, darkTheme)

                    Spacer(Modifier.height(28.dp))

                    Text(
                        text = stringResource(current.title),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        color = GlassTokens.textPrimary(darkTheme),
                    )

                    Spacer(Modifier.height(12.dp))

                    Text(
                        text = stringResource(current.body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = GlassTokens.textSecondary(darkTheme),
                        textAlign = TextAlign.Center,
                        lineHeight = 22.sp,
                    )

                    when (current) {
                        OnboardingPage.Interests -> {
                            Spacer(Modifier.height(24.dp))
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                interestTopics.forEach { topic ->
                                    val isSelected = topic.key in selectedInterests
                                    FilterChip(
                                        selected = isSelected,
                                        onClick = {
                                            selectedInterests = if (isSelected) {
                                                selectedInterests - topic.key
                                            } else {
                                                selectedInterests + topic.key
                                            }
                                        },
                                        label = {
                                            Text(
                                                text = stringResource(topic.labelRes),
                                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                            )
                                        },
                                        shape = RoundedCornerShape(16.dp),
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = GlassTokens.controlSelected(darkTheme),
                                            selectedLabelColor = GlassTokens.onControlSelected(darkTheme),
                                            containerColor = GlassTokens.surfaceGlass(darkTheme),
                                            labelColor = GlassTokens.textPrimary(darkTheme),
                                        ),
                                    )
                                }
                            }
                        }

                        OnboardingPage.Content -> {
                            if (onAddSampleFeed != null && SampleFeeds.curatedFeeds.isNotEmpty()) {
                                Spacer(Modifier.height(24.dp))
                                Text(
                                    text = stringResource(R.string.sample_feeds_title),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = GlassTokens.textPrimary(darkTheme),
                                )
                                Spacer(Modifier.height(10.dp))
                                SampleFeeds.curatedFeeds.forEach { sample ->
                                    val isAdded = sample.id in addedSampleIds
                                    val isAdding = sample.id in addingSampleIds
                                    OutlinedButton(
                                        onClick = {
                                            scope.launch {
                                                addingSampleIds = addingSampleIds + sample.id
                                                try {
                                                    onAddSampleFeed(sample)
                                                    addedSampleIds = addedSampleIds + sample.id
                                                } finally {
                                                    addingSampleIds = addingSampleIds - sample.id
                                                }
                                            }
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp),
                                        enabled = !isAdded && !isAdding,
                                        shape = RoundedCornerShape(16.dp),
                                    ) {
                                        if (isAdding) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(18.dp),
                                                strokeWidth = 2.dp,
                                            )
                                        } else {
                                            AppIcon(
                                                kind = if (isAdded) AppIconKind.Check else AppIconKind.Add,
                                                modifier = Modifier.size(18.dp),
                                            )
                                        }
                                        Spacer(Modifier.size(8.dp))
                                        Text(sample.title)
                                    }
                                }
                            }
                        }

                        OnboardingPage.AiFeatures -> {
                            Spacer(Modifier.height(24.dp))
                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = GlassTokens.surfaceGlassStrong(darkTheme),
                                border = BorderStroke(1.dp, GlassTokens.hairline(darkTheme)),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(
                                    modifier = Modifier.padding(20.dp),
                                    verticalArrangement = Arrangement.spacedBy(14.dp),
                                ) {
                                    listOf(
                                        stringResource(R.string.oobe_feature_briefs),
                                        stringResource(R.string.oobe_feature_transcripts),
                                        stringResource(R.string.oobe_feature_sources),
                                    ).forEach { feature ->
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(24.dp)
                                                    .clip(CircleShape)
                                                    .background(GlassTokens.accentTeal(darkTheme).copy(alpha = 0.15f)),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Rounded.Check,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(14.dp),
                                                    tint = GlassTokens.accentTeal(darkTheme),
                                                )
                                            }
                                            Text(
                                                text = feature,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = GlassTokens.textPrimary(darkTheme),
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        OnboardingPage.Ready -> {
                            Spacer(Modifier.height(28.dp))
                            val hasAnyFeeds = feedCount > 0 || addedSampleIds.isNotEmpty()
                            if (hasAnyFeeds) {
                                Button(
                                    onClick = { onComplete(OnboardingAction.Explore) },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(16.dp),
                                ) {
                                    Text(stringResource(R.string.oobe_start_reading))
                                }
                            } else {
                                Button(
                                    onClick = { onComplete(OnboardingAction.AddFeed) },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(16.dp),
                                ) {
                                    Text(stringResource(R.string.oobe_add_feed))
                                }
                                Spacer(Modifier.height(10.dp))
                                OutlinedButton(
                                    onClick = { onComplete(OnboardingAction.ImportOpml) },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(16.dp),
                                ) {
                                    Text(stringResource(R.string.oobe_import_opml))
                                }
                                Spacer(Modifier.height(6.dp))
                                TextButton(onClick = { onComplete(OnboardingAction.Explore) }) {
                                    Text(
                                        stringResource(R.string.oobe_explore),
                                        color = GlassTokens.textSecondary(darkTheme),
                                    )
                                }
                            }
                        }

                        else -> Unit
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Floating Navigation & Animated Indicator Pill Bar
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = GlassTokens.surfaceGlassStrong(darkTheme),
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.dp, GlassTokens.hairline(darkTheme)),
                tonalElevation = 3.dp,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (pageIndex > 0 && page != OnboardingPage.Ready) {
                        TextButton(onClick = { pageIndex-- }) {
                            Text(
                                text = stringResource(R.string.oobe_back),
                                color = GlassTokens.textSecondary(darkTheme),
                            )
                        }
                    } else {
                        Spacer(Modifier.width(64.dp))
                    }

                    // Sleek Animated Indicator Pills
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OnboardingPage.entries.forEachIndexed { index, _ ->
                            val isCurrent = index == pageIndex
                            val isPast = index < pageIndex
                            val animatedWidth by animateDpAsState(
                                targetValue = if (isCurrent) 22.dp else 7.dp,
                                label = "indicator-width",
                            )
                            Box(
                                modifier = Modifier
                                    .height(7.dp)
                                    .width(animatedWidth)
                                    .clip(CircleShape)
                                    .background(
                                        when {
                                            isCurrent -> GlassTokens.controlSelected(darkTheme)
                                            isPast -> GlassTokens.controlSelected(darkTheme).copy(alpha = 0.5f)
                                            else -> GlassTokens.hairline(darkTheme)
                                        }
                                    )
                            )
                        }
                    }

                    if (page != OnboardingPage.Ready) {
                        Button(
                            onClick = { pageIndex++ },
                            shape = RoundedCornerShape(16.dp),
                        ) {
                            Text(stringResource(R.string.oobe_next))
                        }
                    } else {
                        Spacer(Modifier.width(64.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun OnboardingArtwork(page: OnboardingPage, darkTheme: Boolean) {
    val (icon, tint) = when (page) {
        OnboardingPage.Welcome -> Icons.Rounded.AutoAwesome to GlassTokens.accentTeal(darkTheme)
        OnboardingPage.Interests -> Icons.Rounded.Explore to MaterialTheme.colorScheme.primary
        OnboardingPage.Content -> Icons.Rounded.RssFeed to MaterialTheme.colorScheme.tertiary
        OnboardingPage.AiFeatures -> Icons.Rounded.Psychology to GlassTokens.accentTeal(darkTheme)
        OnboardingPage.Ready -> Icons.Rounded.CheckCircle to MaterialTheme.colorScheme.primary
    }

    Box(
        modifier = Modifier
            .size(112.dp)
            .clip(RoundedCornerShape(32.dp))
            .background(GlassTokens.surfaceGlassStrong(darkTheme))
            .border(1.dp, GlassTokens.hairline(darkTheme), RoundedCornerShape(32.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(tint.copy(alpha = if (darkTheme) 0.16f else 0.1f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(38.dp),
                tint = tint,
            )
        }
    }
}
