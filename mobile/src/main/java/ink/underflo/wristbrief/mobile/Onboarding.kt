package ink.underflo.wristbrief.mobile

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

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
    Sources(R.string.oobe_sources_title, R.string.oobe_sources_body),
    Devices(R.string.oobe_devices_title, R.string.oobe_devices_body),
    Done(R.string.oobe_done_title, R.string.oobe_done_body),
}

@Composable
internal fun WristBriefOnboarding(onComplete: (OnboardingAction) -> Unit) {
    var pageIndex by rememberSaveable { mutableStateOf(0) }
    val page = OnboardingPage.entries[pageIndex]

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("WristBrief", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                if (page != OnboardingPage.Done) TextButton(onClick = { onComplete(OnboardingAction.Explore) }) { Text(stringResource(R.string.oobe_skip)) }
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
                    OnboardingArtwork(current)
                    Spacer(Modifier.height(36.dp))
                    Text(stringResource(current.title), style = MaterialTheme.typography.headlineLarge, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(current.body), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    if (current == OnboardingPage.Done) {
                        Spacer(Modifier.height(24.dp))
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
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (pageIndex > 0 && page != OnboardingPage.Done) TextButton(onClick = { pageIndex-- }) { Text(stringResource(R.string.oobe_back)) }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center) {
                    OnboardingPage.entries.forEachIndexed { index, _ ->
                        Box(Modifier.padding(4.dp).size(if (index == pageIndex) 22.dp else 8.dp, 8.dp).background(if (index == pageIndex) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant, CircleShape))
                    }
                }
                if (page != OnboardingPage.Done) Button(onClick = { pageIndex++ }) { Text(stringResource(R.string.oobe_next)) }
            }
        }
    }
}

@Composable
private fun OnboardingArtwork(page: OnboardingPage) {
    val animation = remember(page) { Animatable(0f) }
    LaunchedEffect(page) {
        animation.animateTo(
            targetValue = 360f,
            animationSpec = tween(if (page == OnboardingPage.Done) 1_200 else 1_800),
        )
    }
    val phase = animation.value
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.tertiary
    val container = MaterialTheme.colorScheme.primaryContainer
    Canvas(Modifier.size(240.dp)) {
        drawCircle(container, radius = size.minDimension * .43f)
        when (page) {
            OnboardingPage.Welcome -> rotate(phase) {
                repeat(3) { i -> drawArc(if (i % 2 == 0) primary else secondary, i * 110f, 64f, false, topLeft = Offset(size.width * .18f, size.height * .18f), size = Size(size.width * .64f, size.height * .64f), style = androidx.compose.ui.graphics.drawscope.Stroke(12.dp.toPx())) }
            }
            OnboardingPage.Sources -> repeat(4) { i -> drawRoundRect(if (i == 0) primary else secondary.copy(alpha = .55f), Offset(size.width * .22f, size.height * (.22f + i * .14f)), Size(size.width * .56f, 18.dp.toPx()), cornerRadius = androidx.compose.ui.geometry.CornerRadius(9.dp.toPx())) }
            OnboardingPage.Devices -> {
                drawRoundRect(primary, Offset(size.width * .18f, size.height * .28f), Size(size.width * .42f, size.height * .48f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(22.dp.toPx()))
                drawCircle(secondary, size.width * .16f, Offset(size.width * .72f, size.height * .52f))
            }
            OnboardingPage.Done -> {
                repeat(22) { i ->
                    val angle = Math.toRadians((i * 37f + phase).toDouble())
                    val radius = size.minDimension * (.24f + (i % 4) * .055f)
                    drawRect(if (i % 2 == 0) primary else secondary, Offset(center.x + kotlin.math.cos(angle).toFloat() * radius, center.y + kotlin.math.sin(angle).toFloat() * radius), Size(5.dp.toPx(), 14.dp.toPx()))
                }
                drawCircle(primary, size.width * .18f)
                drawLine(Color.White, Offset(size.width * .42f, size.height * .51f), Offset(size.width * .48f, size.height * .58f), 9.dp.toPx())
                drawLine(Color.White, Offset(size.width * .48f, size.height * .58f), Offset(size.width * .61f, size.height * .42f), 9.dp.toPx())
            }
        }
    }
}
