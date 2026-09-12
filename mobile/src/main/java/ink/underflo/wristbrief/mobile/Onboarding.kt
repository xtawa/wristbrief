package ink.underflo.wristbrief.mobile

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ink.underflo.wristbrief.mobile.ui.glass.GlassTokens
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
) {
    var pageIndex by rememberSaveable { mutableStateOf(0) }
    val page = OnboardingPage.entries[pageIndex]
    val scope = rememberCoroutineScope()
    var addedSampleIds by remember { mutableStateOf(setOf<String>()) }
    var selectedInterests by remember { mutableStateOf(setOf("Technology", "News")) }
    val darkTheme = isSystemInDarkTheme()

    Surface(Modifier.fillMaxSize(), color = GlassTokens.canvas(darkTheme)) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = GlassTokens.textPrimary(darkTheme),
                )
                if (page != OnboardingPage.Ready) {
                    TextButton(onClick = { onComplete(OnboardingAction.Explore) }) {
                        Text(stringResource(R.string.oobe_skip), color = GlassTokens.textSecondary(darkTheme))
                    }
                }
            }
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
                    Spacer(Modifier.height(32.dp))
                    Text(
                        stringResource(current.title),
                        style = MaterialTheme.typography.headlineLarge,
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
                                    OutlinedButton(
                                        onClick = {
                                            scope.launch {
                                                onAddSampleFeed(sample)
                                                addedSampleIds = addedSampleIds + sample.id
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                        enabled = !isAdded,
                                    ) {
                                        Text(if (isAdded) "✓ ${sample.title}" else "+ ${sample.title}")
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
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("• Focused daily briefs synthesized calmly", style = MaterialTheme.typography.bodyMedium, color = GlassTokens.textPrimary(darkTheme))
                                    Text("• Word-level podcast transcripts with audio seek", style = MaterialTheme.typography.bodyMedium, color = GlassTokens.textPrimary(darkTheme))
                                    Text("• Strictly follows your feeds — zero algorithmic spam", style = MaterialTheme.typography.bodyMedium, color = GlassTokens.textPrimary(darkTheme))
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
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (pageIndex > 0 && page != OnboardingPage.Ready) {
                    TextButton(onClick = { pageIndex-- }) { Text(stringResource(R.string.oobe_back)) }
                }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center) {
                    OnboardingPage.entries.forEachIndexed { index, _ ->
                        Box(
                            Modifier
                                .padding(4.dp)
                                .size(if (index == pageIndex) 22.dp else 8.dp, 8.dp)
                                .background(
                                    if (index == pageIndex) GlassTokens.controlSelected(darkTheme) else GlassTokens.hairline(darkTheme),
                                    CircleShape,
                                )
                        )
                    }
                }
                if (page != OnboardingPage.Ready) {
                    Button(onClick = { pageIndex++ }) { Text(stringResource(R.string.oobe_next)) }
                }
            }
        }
    }
}

@Composable
private fun OnboardingArtwork(page: OnboardingPage, darkTheme: Boolean) {
    val shadowColor = GlassTokens.shadow(darkTheme)
    Box(
        modifier = Modifier
            .size(180.dp)
            .shadow(
                elevation = 6.dp,
                shape = RoundedCornerShape(GlassTokens.HeroRadius),
                ambientColor = shadowColor,
                spotColor = shadowColor,
            )
            .border(
                width = 1.dp,
                color = GlassTokens.hairline(darkTheme),
                shape = RoundedCornerShape(GlassTokens.HeroRadius),
            )
            .background(
                color = GlassTokens.surfaceGlass(darkTheme),
                shape = RoundedCornerShape(GlassTokens.HeroRadius),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(120.dp)) {
            val stroke = 2.dp.toPx()
            val primaryColor = GlassTokens.textPrimary(darkTheme)
            val secondaryColor = GlassTokens.textSecondary(darkTheme)
            val hairlineColor = GlassTokens.hairline(darkTheme)

            when (page) {
                OnboardingPage.Welcome -> {
                    drawCircle(hairlineColor, radius = size.minDimension * 0.42f, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
                    drawCircle(secondaryColor.copy(alpha = 0.35f), radius = size.minDimension * 0.28f, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
                    drawCircle(primaryColor, radius = 6.dp.toPx())
                }
                OnboardingPage.Interests -> {
                    drawRoundRect(hairlineColor, topLeft = Offset(size.width * 0.15f, size.height * 0.22f), size = Size(size.width * 0.70f, 20.dp.toPx()), cornerRadius = androidx.compose.ui.geometry.CornerRadius(10.dp.toPx()))
                    drawRoundRect(primaryColor.copy(alpha = 0.2f), topLeft = Offset(size.width * 0.25f, size.height * 0.44f), size = Size(size.width * 0.50f, 20.dp.toPx()), cornerRadius = androidx.compose.ui.geometry.CornerRadius(10.dp.toPx()))
                    drawRoundRect(hairlineColor, topLeft = Offset(size.width * 0.18f, size.height * 0.66f), size = Size(size.width * 0.64f, 20.dp.toPx()), cornerRadius = androidx.compose.ui.geometry.CornerRadius(10.dp.toPx()))
                }
                OnboardingPage.Content -> {
                    drawRoundRect(hairlineColor, topLeft = Offset(size.width * 0.22f, size.height * 0.18f), size = Size(size.width * 0.56f, size.height * 0.64f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(12.dp.toPx()), style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
                    drawLine(secondaryColor, Offset(size.width * 0.32f, size.height * 0.34f), Offset(size.width * 0.68f, size.height * 0.34f), strokeWidth = stroke)
                    drawLine(secondaryColor, Offset(size.width * 0.32f, size.height * 0.46f), Offset(size.width * 0.62f, size.height * 0.46f), strokeWidth = stroke)
                    drawLine(secondaryColor, Offset(size.width * 0.32f, size.height * 0.58f), Offset(size.width * 0.52f, size.height * 0.58f), strokeWidth = stroke)
                }
                OnboardingPage.AiFeatures -> {
                    val cx = center.x
                    val cy = center.y
                    drawLine(secondaryColor, Offset(cx - 24.dp.toPx(), cy), Offset(cx + 24.dp.toPx(), cy), strokeWidth = stroke)
                    drawLine(secondaryColor, Offset(cx, cy - 24.dp.toPx()), Offset(cx, cy + 24.dp.toPx()), strokeWidth = stroke)
                    drawCircle(primaryColor, radius = 5.dp.toPx(), center = center)
                    drawCircle(hairlineColor, radius = 24.dp.toPx(), style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
                }
                OnboardingPage.Ready -> {
                    drawCircle(primaryColor.copy(alpha = 0.10f), radius = size.minDimension * 0.38f)
                    drawCircle(primaryColor, radius = size.minDimension * 0.38f, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
                    drawLine(primaryColor, Offset(size.width * 0.36f, size.height * 0.50f), Offset(size.width * 0.47f, size.height * 0.62f), strokeWidth = 3.dp.toPx())
                    drawLine(primaryColor, Offset(size.width * 0.47f, size.height * 0.62f), Offset(size.width * 0.66f, size.height * 0.40f), strokeWidth = 3.dp.toPx())
                }
            }
        }
    }
}
