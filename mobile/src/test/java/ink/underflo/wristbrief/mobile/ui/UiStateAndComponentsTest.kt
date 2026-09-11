package ink.underflo.wristbrief.mobile.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UiStateAndComponentsTest {

    @Test
    fun touchTargetTokens_meetAccessibilityGuideline() {
        assertTrue(
            "Minimum touch target must be at least 48dp",
            TouchTargetTokens.minTouchTarget >= 48.dp
        )
    }

    @Test
    fun mobileSpacingTokens_areMonotonic() {
        assertTrue(MobileSpacing.none < MobileSpacing.xsmall)
        assertTrue(MobileSpacing.xsmall < MobileSpacing.small)
        assertTrue(MobileSpacing.small < MobileSpacing.medium)
        assertTrue(MobileSpacing.medium <= MobileSpacing.cardPadding)
        assertTrue(MobileSpacing.cardPadding < MobileSpacing.large)
        assertTrue(MobileSpacing.large < MobileSpacing.xlarge)
    }

    @Test
    fun uiState_variantsSupportAllLceStates() {
        val idle: UiState<String> = UiState.Idle
        val loading: UiState<String> = UiState.Loading
        val refreshing: UiState<String> = UiState.Refreshing("cached")
        val content: UiState<String> = UiState.Content("fresh")
        val empty: UiState<String> = UiState.Empty(
            title = "No feeds",
            message = "Add a source to get started",
            actionLabel = "Add feed"
        )
        val offline: UiState<String> = UiState.OfflineCached(
            content = "offline item",
            cachedEpochMs = 123456789L
        )
        val partial: UiState<String> = UiState.PartialFailure(
            content = "partial list",
            failedSourcesCount = 2,
            totalSourcesCount = 5
        )
        val auth: UiState<String> = UiState.AuthRequired(reason = "Session expired")
        val quota: UiState<String> = UiState.QuotaExhausted(resetEpochMs = 999999L)
        val error: UiState<String> = UiState.Error(message = "Network timeout", canRetry = true)

        assertEquals("cached", (refreshing as UiState.Refreshing).content)
        assertEquals("fresh", (content as UiState.Content).content)
        assertEquals("No feeds", (empty as UiState.Empty).title)
        assertEquals(123456789L, (offline as UiState.OfflineCached).cachedEpochMs)
        assertEquals(2, (partial as UiState.PartialFailure).failedSourcesCount)
        assertEquals("Session expired", (auth as UiState.AuthRequired).reason)
        assertEquals(999999L, (quota as UiState.QuotaExhausted).resetEpochMs)
        assertEquals("Network timeout", (error as UiState.Error).message)
    }
}
