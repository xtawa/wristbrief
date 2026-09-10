package ink.underflo.wristbrief.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ink.underflo.wristbrief.complication.requestUnreadComplicationUpdate
import ink.underflo.wristbrief.data.CachedFeedItem
import ink.underflo.wristbrief.data.FeedInboxRepository
import ink.underflo.wristbrief.data.FeedRepository
import ink.underflo.wristbrief.data.SharedPreferencesFeedStore
import ink.underflo.wristbrief.data.SubscriptionMutationResult
import ink.underflo.wristbrief.sync.WearItemStateSyncManager
import ink.underflo.wristbrief.tile.requestLatestUnreadTileUpdate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class InboxUiState(
    val items: List<InboxItemUi> = emptyList(),
    val savedItems: List<InboxItemUi> = emptyList(),
    val feeds: List<FeedManagementItemUi> = emptyList(),
    val unreadCount: Int = 0,
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
    private val itemStateSync = WearItemStateSyncManager(application)

    private val _uiState = MutableStateFlow(buildState())
    val uiState: StateFlow<InboxUiState> = _uiState.asStateFlow()

    init {
        if (_uiState.value.hasSubscriptions) refresh()
    }

    fun refresh() {
        if (_uiState.value.isLoading) return
        if (!repository.subscriptions().any { it.enabled }) {
            _uiState.value = buildState()
            requestGlanceUpdates()
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
                    requestGlanceUpdates()
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

    fun setItemRead(id: String, isRead: Boolean) {
        if (repository.setRead(id, isRead)) {
            itemStateSync.recordRead(id, isRead)
            rebuildPreservingTransientState()
            requestGlanceUpdates()
        } else {
            _uiState.value = _uiState.value.copy(errorMessage = "Brief is no longer available")
        }
    }

    fun setItemSaved(id: String, isSaved: Boolean) {
        if (repository.setSaved(id, isSaved)) {
            itemStateSync.recordSaved(id, isSaved)
            rebuildPreservingTransientState()
        } else {
            _uiState.value = _uiState.value.copy(errorMessage = "Brief is no longer available")
        }
    }

    fun setFeedEnabled(id: String, enabled: Boolean) {
        when (repository.setSubscriptionEnabled(id, enabled)) {
            SubscriptionMutationResult.Success -> {
                _uiState.value = buildState()
                requestGlanceUpdates()
                if (enabled) refresh()
            }
            else -> _uiState.value = _uiState.value.copy(errorMessage = "Could not update feed")
        }
    }

    fun removeFeed(id: String) {
        when (repository.removeSubscription(id)) {
            SubscriptionMutationResult.Success -> {
                _uiState.value = buildState()
                requestGlanceUpdates()
            }
            else -> _uiState.value = _uiState.value.copy(errorMessage = "Could not remove feed")
        }
    }

    private fun requestGlanceUpdates() {
        requestLatestUnreadTileUpdate(getApplication())
        requestUnreadComplicationUpdate(getApplication())
    }

    private fun rebuildPreservingTransientState() {
        _uiState.value = buildState(
            isOfflineFallback = _uiState.value.isOfflineFallback,
            errorMessage = _uiState.value.errorMessage
        )
    }

    private fun buildState(
        items: List<InboxItemUi> = repository.cachedItems().toUiItems(),
        isOfflineFallback: Boolean = false,
        errorMessage: String? = null
    ): InboxUiState {
        val subscriptions = repository.subscriptions()
        return InboxUiState(
            items = items,
            savedItems = repository.savedItems().toUiItems(),
            feeds = subscriptions.toFeedManagementItemsUi(),
            unreadCount = repository.unreadCount(),
            isLoading = false,
            isOfflineFallback = isOfflineFallback,
            errorMessage = errorMessage,
            hasSubscriptions = subscriptions.any { it.enabled }
        )
    }

    private fun List<CachedFeedItem>.toUiItems(): List<InboxItemUi> =
        map { cached ->
            cached.toInboxItemUi(
                isRead = repository.isRead(cached.id),
                isSaved = repository.isSaved(cached.id)
            )
        }
}
