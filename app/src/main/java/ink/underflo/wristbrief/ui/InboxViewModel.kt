package ink.underflo.wristbrief.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ink.underflo.wristbrief.data.FeedInboxRepository
import ink.underflo.wristbrief.data.FeedRepository
import ink.underflo.wristbrief.data.SharedPreferencesFeedStore
import ink.underflo.wristbrief.data.SubscriptionMutationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class InboxUiState(
    val items: List<InboxItemUi> = emptyList(),
    val feeds: List<FeedManagementItemUi> = emptyList(),
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

    private val _uiState = MutableStateFlow(buildState())
    val uiState: StateFlow<InboxUiState> = _uiState.asStateFlow()

    init {
        if (_uiState.value.hasSubscriptions) refresh()
    }

    fun refresh() {
        if (_uiState.value.isLoading) return
        if (!repository.subscriptions().any { it.enabled }) {
            _uiState.value = buildState()
            return
        }

        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.refresh() }
                .onSuccess { result ->
                    _uiState.value = buildState(
                        items = result.items.toUiItems(),
                        isOfflineFallback = result.isOfflineFallback,
                        errorMessage = if (result.failedFeedIds.isNotEmpty()) {
                            "Some feeds could not refresh"
                        } else {
                            null
                        }
                    )
                }
                .onFailure {
                    _uiState.value = buildState(
                        items = repository.cachedItems().toUiItems(),
                        isOfflineFallback = true,
                        errorMessage = "Could not refresh feeds"
                    )
                }
        }
    }

    fun setFeedEnabled(id: String, enabled: Boolean) {
        when (repository.setSubscriptionEnabled(id, enabled)) {
            SubscriptionMutationResult.Success -> {
                _uiState.value = buildState()
                if (enabled) refresh()
            }
            else -> _uiState.value = _uiState.value.copy(errorMessage = "Could not update feed")
        }
    }

    fun removeFeed(id: String) {
        when (repository.removeSubscription(id)) {
            SubscriptionMutationResult.Success -> _uiState.value = buildState()
            else -> _uiState.value = _uiState.value.copy(errorMessage = "Could not remove feed")
        }
    }

    private fun buildState(
        items: List<InboxItemUi> = repository.cachedItems().toUiItems(),
        isOfflineFallback: Boolean = false,
        errorMessage: String? = null
    ): InboxUiState {
        val subscriptions = repository.subscriptions()
        return InboxUiState(
            items = items,
            feeds = subscriptions.toFeedManagementItemsUi(),
            isLoading = false,
            isOfflineFallback = isOfflineFallback,
            errorMessage = errorMessage,
            hasSubscriptions = subscriptions.any { it.enabled }
        )
    }
}

private fun List<ink.underflo.wristbrief.data.CachedFeedItem>.toUiItems(): List<InboxItemUi> =
    map { cached -> cached.asFeedItem().toInboxItemUi(cached.feedTitle) }
