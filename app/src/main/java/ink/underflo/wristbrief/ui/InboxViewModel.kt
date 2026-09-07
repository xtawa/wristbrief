package ink.underflo.wristbrief.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ink.underflo.wristbrief.data.FeedInboxRepository
import ink.underflo.wristbrief.data.FeedRepository
import ink.underflo.wristbrief.data.SharedPreferencesFeedStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class InboxUiState(
    val items: List<InboxItemUi> = emptyList(),
    val isLoading: Boolean = false,
    val isOfflineFallback: Boolean = false,
    val errorMessage: String? = null,
    val hasSubscriptions: Boolean = false
)

class InboxViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = FeedInboxRepository(
        loader = FeedRepository(),
        store = SharedPreferencesFeedStore(application)
    )

    private val _uiState = MutableStateFlow(
        InboxUiState(
            items = repository.cachedItems().toUiItems(),
            hasSubscriptions = repository.subscriptions().any { it.enabled }
        )
    )
    val uiState: StateFlow<InboxUiState> = _uiState.asStateFlow()

    init {
        if (_uiState.value.hasSubscriptions) refresh()
    }

    fun refresh() {
        if (_uiState.value.isLoading) return
        if (!repository.subscriptions().any { it.enabled }) {
            _uiState.value = _uiState.value.copy(
                hasSubscriptions = false,
                isLoading = false,
                errorMessage = null
            )
            return
        }

        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.refresh() }
                .onSuccess { result ->
                    _uiState.value = InboxUiState(
                        items = result.items.toUiItems(),
                        isLoading = false,
                        isOfflineFallback = result.isOfflineFallback,
                        errorMessage = if (result.failedFeedIds.isNotEmpty()) {
                            "Some feeds could not refresh"
                        } else {
                            null
                        },
                        hasSubscriptions = true
                    )
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(
                        items = repository.cachedItems().toUiItems(),
                        isLoading = false,
                        isOfflineFallback = true,
                        errorMessage = "Could not refresh feeds"
                    )
                }
        }
    }
}

private fun List<ink.underflo.wristbrief.data.CachedFeedItem>.toUiItems(): List<InboxItemUi> =
    map { cached -> cached.asFeedItem().toInboxItemUi(cached.feedTitle) }
