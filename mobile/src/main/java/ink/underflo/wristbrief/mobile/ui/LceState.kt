package ink.underflo.wristbrief.mobile.ui

/**
 * Standardized UI state sealed interface for WristBrief screens,
 * eliminating ambiguous "empty list means error or loading" patterns.
 */
sealed interface UiState<out T> {
    data object Idle : UiState<Nothing>
    data object Loading : UiState<Nothing>
    data class Refreshing<out T>(val content: T) : UiState<T>
    data class Content<out T>(val content: T) : UiState<T>
    data class Empty(
        val title: String,
        val message: String,
        val actionLabel: String? = null,
    ) : UiState<Nothing>
    data class OfflineCached<out T>(
        val content: T,
        val cachedEpochMs: Long,
    ) : UiState<T>
    data class PartialFailure<out T>(
        val content: T,
        val failedSourcesCount: Int,
        val totalSourcesCount: Int,
    ) : UiState<T>
    data class AuthRequired(
        val reason: String? = null,
    ) : UiState<Nothing>
    data class QuotaExhausted(
        val resetEpochMs: Long? = null,
    ) : UiState<Nothing>
    data class Error(
        val message: String,
        val canRetry: Boolean = true,
    ) : UiState<Nothing>
}
