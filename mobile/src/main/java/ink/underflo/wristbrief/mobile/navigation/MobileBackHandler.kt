package ink.underflo.wristbrief.mobile.navigation

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import ink.underflo.wristbrief.mobile.R

/**
 * Compose adapter that routes system back / gesture back into [MobileBackPolicy].
 *
 * The caller supplies the current navigation snapshot derived from the real Compose
 * state (no duplicated state is kept here) plus one lambda per navigation action.
 * Exit at tab root finishes the host [Activity] via [Activity.finish] — it never
 * calls `exitProcess`, kills the task, or stops the podcast playback service, and
 * it never touches the account session.
 */
@Composable
fun MobileBackHandler(
    hasActiveDialog: Boolean,
    expandedPlayerVisible: Boolean,
    transcriptViewerVisible: Boolean,
    articleDetailVisible: Boolean,
    settingsVisible: Boolean,
    exitHintShownAtEpochMs: Long?,
    onExitHintChanged: (Long?) -> Unit,
    onDismissDialog: () -> Unit = {},
    onDismissExpandedPlayer: () -> Unit,
    onCloseTranscriptViewer: () -> Unit,
    onCloseArticleDetail: () -> Unit,
    onCloseSettings: () -> Unit,
) {
    val context = LocalContext.current
    val exitHintText = stringResource(R.string.exit_hint_press_back_again)

    BackHandler {
        val snapshot = MobileBackSnapshot(
            hasActiveDialog = hasActiveDialog,
            expandedPlayerVisible = expandedPlayerVisible,
            transcriptViewerVisible = transcriptViewerVisible,
            articleDetailVisible = articleDetailVisible,
            settingsVisible = settingsVisible,
            exitHintShownAtEpochMs = exitHintShownAtEpochMs,
        )
        when (val outcome = MobileBackPolicy.onBack(snapshot, System.currentTimeMillis())) {
            is MobileBackOutcome.Consumed -> {
                onExitHintChanged(null)
                when (outcome.action) {
                    MobileBackAction.DismissDialog -> onDismissDialog()
                    MobileBackAction.DismissExpandedPlayer -> onDismissExpandedPlayer()
                    MobileBackAction.CloseTranscriptViewer -> onCloseTranscriptViewer()
                    MobileBackAction.CloseArticleDetail -> onCloseArticleDetail()
                    MobileBackAction.CloseSettings -> onCloseSettings()
                }
            }
            is MobileBackOutcome.ExitHint -> {
                onExitHintChanged(outcome.shownAtEpochMs)
                Toast.makeText(context, exitHintText, Toast.LENGTH_SHORT).show()
            }
            MobileBackOutcome.Exit -> {
                onExitHintChanged(null)
                (context as? Activity)?.finish()
            }
        }
    }
}
