package ink.underflo.wristbrief.mobile.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileBackPolicyTest {

    @Test fun dialogOutranksPlayerTranscriptArticleSettingsAndRoot() {
        val snapshot = MobileBackSnapshot(
            hasActiveDialog = true,
            expandedPlayerVisible = true,
            transcriptViewerVisible = true,
            articleDetailVisible = true,
            settingsVisible = true,
        )
        assertEquals(MobileBackAction.DismissDialog, (MobileBackPolicy.onBack(snapshot, 1_000) as MobileBackOutcome.Consumed).action)
    }

    @Test fun expandedPlayerOutranksTranscriptArticleSettingsAndRoot() {
        val snapshot = MobileBackSnapshot(
            expandedPlayerVisible = true,
            transcriptViewerVisible = true,
            articleDetailVisible = true,
            settingsVisible = true,
        )
        assertEquals(MobileBackAction.DismissExpandedPlayer, (MobileBackPolicy.onBack(snapshot, 1_000) as MobileBackOutcome.Consumed).action)
    }

    @Test fun transcriptViewerOutranksArticleSettingsAndRoot() {
        val snapshot = MobileBackSnapshot(
            transcriptViewerVisible = true,
            articleDetailVisible = true,
            settingsVisible = true,
        )
        assertEquals(MobileBackAction.CloseTranscriptViewer, (MobileBackPolicy.onBack(snapshot, 1_000) as MobileBackOutcome.Consumed).action)
    }

    @Test fun articleDetailOutranksSettingsAndRoot() {
        val snapshot = MobileBackSnapshot(articleDetailVisible = true, settingsVisible = true)
        assertEquals(MobileBackAction.CloseArticleDetail, (MobileBackPolicy.onBack(snapshot, 1_000) as MobileBackOutcome.Consumed).action)
    }

    @Test fun settingsBackReturnsToShell() {
        val snapshot = MobileBackSnapshot(settingsVisible = true)
        assertEquals(MobileBackAction.CloseSettings, (MobileBackPolicy.onBack(snapshot, 1_000) as MobileBackOutcome.Consumed).action)
    }

    @Test fun rootFirstBackShowsExitHintInsteadOfExiting() {
        val outcome = MobileBackPolicy.onBack(MobileBackSnapshot(), 10_000)
        assertTrue(outcome is MobileBackOutcome.ExitHint)
        assertEquals(10_000, (outcome as MobileBackOutcome.ExitHint).shownAtEpochMs)
    }

    @Test fun rootSecondBackWithinWindowExits() {
        val outcome = MobileBackPolicy.onBack(
            MobileBackSnapshot(exitHintShownAtEpochMs = 10_000),
            10_000 + MobileBackPolicy.EXIT_HINT_WINDOW_MS - 1,
        )
        assertEquals(MobileBackOutcome.Exit, outcome)
    }

    @Test fun rootBackAfterWindowExpiresShowsHintAgain() {
        val outcome = MobileBackPolicy.onBack(
            MobileBackSnapshot(exitHintShownAtEpochMs = 10_000),
            10_000 + MobileBackPolicy.EXIT_HINT_WINDOW_MS,
        )
        assertTrue(outcome is MobileBackOutcome.ExitHint)
    }

    @Test fun clockRollbackDoesNotExitFromStaleHint() {
        val outcome = MobileBackPolicy.onBack(
            MobileBackSnapshot(exitHintShownAtEpochMs = 10_000),
            nowEpochMs = 9_000,
        )
        assertTrue(outcome is MobileBackOutcome.ExitHint)
    }

    @Test fun miniPlayerIsNotABackLayer() {
        // The policy input has no field for the mini player: while only the mini
        // player is visible (no expanded overlay), back must behave as tab root.
        val outcome = MobileBackPolicy.onBack(MobileBackSnapshot(), 1_000)
        assertTrue(outcome is MobileBackOutcome.ExitHint)
    }

    @Test fun outcomesOnlyCarryNavigationActions() {
        // Back must never stop playback, clear the session, or mutate account data:
        // across every reachable snapshot the outcome is one of the three known
        // navigation-only kinds, and consumed actions are from the closed set.
        val knownActions = setOf(
            MobileBackAction.DismissDialog,
            MobileBackAction.DismissExpandedPlayer,
            MobileBackAction.CloseTranscriptViewer,
            MobileBackAction.CloseArticleDetail,
            MobileBackAction.CloseSettings,
        )
        val booleans = listOf(false, true)
        for (dialog in booleans) {
            for (player in booleans) {
                for (transcript in booleans) {
                    for (article in booleans) {
                        for (settings in booleans) {
                            for (hint in listOf<Long?>(null, 500L)) {
                                val outcome = MobileBackPolicy.onBack(
                                    MobileBackSnapshot(
                                        hasActiveDialog = dialog,
                                        expandedPlayerVisible = player,
                                        transcriptViewerVisible = transcript,
                                        articleDetailVisible = article,
                                        settingsVisible = settings,
                                        exitHintShownAtEpochMs = hint,
                                    ),
                                    nowEpochMs = 1_000,
                                )
                                when (outcome) {
                                    is MobileBackOutcome.Consumed -> assertTrue(outcome.action in knownActions)
                                    is MobileBackOutcome.ExitHint -> Unit
                                    MobileBackOutcome.Exit -> Unit
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
