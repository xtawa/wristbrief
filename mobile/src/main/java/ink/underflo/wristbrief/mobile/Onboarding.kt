package ink.underflo.wristbrief.mobile

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ink.underflo.wristbrief.mobile.ui.glass.GlassTokens
import ink.underflo.wristbrief.mobile.ui.AppIcon
import ink.underflo.wristbrief.mobile.ui.AppIconKind
import kotlinx.coroutines.launch

internal class OnboardingPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("onboarding", Context.MODE_PRIVATE)
    fun isComplete(): Boolean = preferences.getBoolean("complete", false)
    fun complete() = preferences.edit()
        .putBoolean("complete", true)
        .remove("interests")
        .apply()
    fun reset() = preferences.edit()
        .clear()
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
    var selectedInterests by remember { mutableStateOf(setOf("Technology", "News")) }
    Surface(Modifier.fillMaxSize(), color = GlassTokens.canvas(darkTheme)) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = GlassTokens.textPrimary(darkTheme),
                    )
                    Text(
                        "${pageIndex + 1} / ${OnboardingPage.entries.size}",
                        style = MaterialTheme.typography.labelMedium,
                        color = GlassTokens.textSecondary(darkTheme),
                    )
                }
                if (page != OnboardingPage.Ready) {
                    TextButton(onClick = { onComplete(OnboardingAction.Explore) }) {
                        Text(stringResource(R.string.oobe_skip), color = GlassTokens.textSecondary(darkTheme))
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            androidx.compose.material3.LinearProgressIndicator(
                progress = { (pageIndex + 1).toFloat() / OnboardingPage.entries.size.toFloat() },
                modifier = Modifier.fillMaxWidth().height(4.dp),
                color = GlassTokens.controlSelected(darkTheme),
                trackColor = GlassTokens.hairline(darkTheme),
            )
            AnimatedContent(
                targetState = page,
                transitionSpec = { fadeIn(tween(320)) togetherWith fadeOut(tween(180)) },
                modifier = Modifier.weight(1f),
                label = "onboarding-page",
            ) { current ->
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    OnboardingArtwork(current, darkTheme)
                    Spacer(Modifier.height(24.dp))
                    Text(
                        stringResource(current.title),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        color = GlassTokens.textPrimary(darkTheme),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(current.body),
                        style = MaterialTheme.typography.bodyLarge,
                        color = GlassTokens.textSecondary(darkTheme),
                        textAlign = TextAlign.Center,
                    )

                    when (current) {
                        OnboardingPage.Interests -> {
                            Spacer(Modifier.height(20.dp))
                            val topics = listOf("Technology", "Design", "Science", "Podcasts", "News", "Culture")
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                topics.forEach { topic ->
                                    val isSelected = topic in selectedInterests
                                    FilterChip(
                                        selected = isSelected,
                                        onClick = {
                                            selectedInterests = if (isSelected) selectedInterests - topic else selectedInterests + topic
                                        },
                                        label = { Text(topic) },
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
                            if (onAddSampleFeed != null) {
                                Spacer(Modifier.height(20.dp))
                                Text(
                                    stringResource(R.string.sample_feeds_title),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = GlassTokens.textPrimary(darkTheme),
                                )
                                Spacer(Modifier.height(8.dp))
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
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                        enabled = !isAdded && !isAdding,
                                    ) {
                                        if (isAdding) {
                                            androidx.compose.material3.CircularProgressIndicator(
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
                            Spacer(Modifier.height(20.dp))
                            Surface(
                                shape = RoundedCornerShape(GlassTokens.CardRadius),
                                color = GlassTokens.surfaceGlass(darkTheme),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(1.dp, GlassTokens.hairline(darkTheme), RoundedCornerShape(GlassTokens.CardRadius))
                                    .padding(16.dp),
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    listOf(
                                        stringResource(R.string.oobe_feature_briefs),
                                        stringResource(R.string.oobe_feature_transcripts),
                                        stringResource(R.string.oobe_feature_sources),
                                    ).forEach { feature ->
                                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                                            AppIcon(AppIconKind.Bullet, Modifier.size(18.dp), GlassTokens.textPrimary(darkTheme))
                                            Text(feature, style = MaterialTheme.typography.bodyMedium, color = GlassTokens.textPrimary(darkTheme))
                                        }
                                    }
                                }
                            }
                        }
                        OnboardingPage.Ready -> {
                            Spacer(Modifier.height(24.dp))
                            val hasAnyFeeds = feedCount > 0 || addedSampleIds.isNotEmpty()
                            if (hasAnyFeeds) {
                                Button(
                                    onClick = { onComplete(OnboardingAction.Explore) },
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text(stringResource(R.string.oobe_start_reading)) }
                            } else {
                                Button(
                                    onClick = { onComplete(OnboardingAction.AddFeed) },
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text(stringResource(R.string.oobe_add_feed)) }
                                Spacer(Modifier.height(8.dp))
                                OutlinedButton(
                                    onClick = { onComplete(OnboardingAction.ImportOpml) },
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text(stringResource(R.string.oobe_import_opml)) }
                                TextButton(onClick = { onComplete(OnboardingAction.Explore) }) {
                                    Text(stringResource(R.string.oobe_explore))
                                }
                            }
                        }
                        else -> {}
                    }
                }
            }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = GlassTokens.surfaceGlassStrong(darkTheme),
                shape = RoundedCornerShape(20.dp),
                tonalElevation = 2.dp,
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (pageIndex > 0 && page != OnboardingPage.Ready) {
                        TextButton(onClick = { pageIndex-- }) { Text(stringResource(R.string.oobe_back)) }
                    } else {
                        Spacer(Modifier.width(72.dp))
                    }
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        OnboardingPage.entries.forEachIndexed { index, _ ->
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(4.dp)
                                    .background(
                                        if (index <= pageIndex) GlassTokens.controlSelected(darkTheme) else GlassTokens.hairline(darkTheme),
                                        RoundedCornerShape(4.dp),
                                    )
                            )
                        }
                    }
                    if (page != OnboardingPage.Ready) {
                        Button(onClick = { pageIndex++ }) { Text(stringResource(R.string.oobe_next)) }
                    } else {
                        Spacer(Modifier.width(72.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun OnboardingArtwork(page: OnboardingPage, darkTheme: Boolean) {
    Box(
        modifier = Modifier
            .size(76.dp)
            .border(
                width = 1.dp,
                color = GlassTokens.hairline(darkTheme),
                shape = RoundedCornerShape(22.dp),
            )
            .background(
                color = GlassTokens.surfaceGlass(darkTheme),
                shape = RoundedCornerShape(22.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        val kind = when (page) {
            OnboardingPage.Welcome -> AppIconKind.Spark
            OnboardingPage.Interests -> AppIconKind.Bookmark
            OnboardingPage.Content -> AppIconKind.Add
            OnboardingPage.AiFeatures -> AppIconKind.Spark
            OnboardingPage.Ready -> AppIconKind.Check
        }
        AppIcon(kind, Modifier.size(34.dp), GlassTokens.textPrimary(darkTheme))
    }
}
