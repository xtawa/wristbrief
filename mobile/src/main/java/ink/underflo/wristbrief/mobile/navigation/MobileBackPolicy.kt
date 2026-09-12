package ink.underflo.wristbrief.mobile.navigation

/**
 * Pure back-navigation arbitration for the state-driven (non-NavHost) app shell.
 *
 * The app renders pages from flat Compose state (`selectedArticle`, `showSettings`,
 * `activeTranscriptState`, `showExpandedPlayer`, current [ink.underflo.wristbrief.mobile.MobileDestination])
 * instead of a navigation back stack, so system back has no built-in mapping.
 * This policy encodes the single source of truth for back priority:
 *
 * 1. in-screen confirmation dialog
 * 2. expanded player overlay
 * 3. transcript viewer
 * 4. article detail
 * 5. settings
 * 6. tab root -> double-back-to-exit
 *
 * The mini player never intercepts back. Navigation surfaces owned by destinations
 * (their own dialogs) install their own inner `BackHandler` and therefore win over
 * this policy by innermost-wins composition rules.
 *
 * The policy is free of Android dependencies and of any reference to playback,
 * session, or account state: back must never stop playback, clear the account
 * session, or otherwise mutate anything outside the navigation states above.
 */
data class MobileBackSnapshot(
    val hasActiveDialog: Boolean = false,
    val expandedPlayerVisible: Boolean = false,
    val transcriptViewerVisible: Boolean = false,
    val articleDetailVisible: Boolean = false,
    val settingsVisible: Boolean = false,
    val exitHintShownAtEpochMs: Long? = null,
)

/** Navigation-only side effect requested by the policy. */
sealed interface MobileBackAction {
    data object DismissDialog : MobileBackAction
    data object DismissExpandedPlayer : MobileBackAction
    data object CloseTranscriptViewer : MobileBackAction
    data object CloseArticleDetail : MobileBackAction
    data object CloseSettings : MobileBackAction
}

sealed interface MobileBackOutcome {
    /** A layer consumed back; `exitHintShownAtEpochMs` should be cleared. */
    data class Consumed(val action: MobileBackAction) : MobileBackOutcome

    /** First back at tab root: show the exit hint, remember [shownAtEpochMs]. */
    data class ExitHint(val shownAtEpochMs: Long) : MobileBackOutcome

    /** Second back at tab root within the hint window: the activity may finish. */
    data object Exit : MobileBackOutcome
}

object MobileBackPolicy {
    const val EXIT_HINT_WINDOW_MS: Long = 2_000L

    fun onBack(snapshot: MobileBackSnapshot, nowEpochMs: Long): MobileBackOutcome {
        if (snapshot.hasActiveDialog) return MobileBackOutcome.Consumed(MobileBackAction.DismissDialog)
        if (snapshot.expandedPlayerVisible) return MobileBackOutcome.Consumed(MobileBackAction.DismissExpandedPlayer)
        if (snapshot.transcriptViewerVisible) return MobileBackOutcome.Consumed(MobileBackAction.CloseTranscriptViewer)
        if (snapshot.articleDetailVisible) return MobileBackOutcome.Consumed(MobileBackAction.CloseArticleDetail)
        if (snapshot.settingsVisible) return MobileBackOutcome.Consumed(MobileBackAction.CloseSettings)
        return exitAtRoot(snapshot.exitHintShownAtEpochMs, nowEpochMs)
    }

    private fun exitAtRoot(exitHintShownAtEpochMs: Long?, nowEpochMs: Long): MobileBackOutcome {
        if (exitHintShownAtEpochMs == null) return MobileBackOutcome.ExitHint(nowEpochMs)
        val elapsed = nowEpochMs - exitHintShownAtEpochMs
        if (elapsed >= 0 && elapsed < EXIT_HINT_WINDOW_MS) return MobileBackOutcome.Exit
        return MobileBackOutcome.ExitHint(nowEpochMs)
    }
}
