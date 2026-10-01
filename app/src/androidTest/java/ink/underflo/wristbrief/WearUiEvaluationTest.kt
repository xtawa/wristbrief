package ink.underflo.wristbrief

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performRotaryScrollInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.printToString
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Real round-emulator screen evidence; not a paired phone/Wear or live audio test. */
class WearUiEvaluationTest {
    @get:Rule val composeRule = createEmptyComposeRule()

    private fun capture(name: String) {
        composeRule.waitForIdle()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val config = context.resources.configuration
        assertTrue("Evaluation must use a round Wear display", config.isScreenRound)
        val directory = File(context.filesDir, "ux-evaluation").apply { mkdirs() }
        val prefix = "${config.screenWidthDp}dp-${config.fontScale}-$name"
        val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
        File(directory, "$prefix.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        File(directory, "$prefix.semantics.txt").writeText(composeRule.onRoot(useUnmergedTree = true).printToString())
        File(directory, "$prefix.json").writeText(JSONObject().apply {
            put("screen", name); put("widthDp", config.screenWidthDp); put("fontScale", config.fontScale)
            put("round", config.isScreenRound); put("pairedPhone", false)
            put("widthPx", bitmap.width); put("heightPx", bitmap.height)
        }.toString(2))
        bitmap.recycle()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun captureRoundNavigationAndRotary() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("captureUx") == "true")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            composeRule.onNode(hasText("WristBrief")).assertIsDisplayed()
            capture("01-inbox")
            composeRule.onNode(hasScrollAction()).performRotaryScrollInput { rotateToScrollVertically(500f) }
            capture("02-rotary")
            composeRule.onNode(hasScrollAction()).performScrollToIndex(0)
            composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Library"))
            composeRule.onNode(hasText("Library")).performClick()
            capture("03-saved")
            composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Back to Inbox"))
            composeRule.onNode(hasText("Back to Inbox")).performClick()
            composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Settings"))
            composeRule.onNode(hasText("Settings")).performClick()
            capture("04-feeds")
            scenario.recreate()
            composeRule.waitForIdle()
            capture("05-feeds-recreated")
        }
    }
}
