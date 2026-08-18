package com.clawmate.app.gallery.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.clawmate.app.gallery.collection.IndexedCollectionEvent
import com.clawmate.app.gallery.collection.IndexedCollectionGroup
import com.clawmate.app.gallery.collection.IndexedCollectionTab
import com.clawmate.app.gallery.collection.IndexedEventCollectionService
import com.clawmate.app.gallery.collection.IndexedEventReader
import com.clawmate.app.datamanager.LocalConsolidationTypes.IndexedEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Honest UI state for the index-backed summary grouping. */
sealed class IndexedCollectionUiState {
    /** No read performed yet; nothing shown. */
    data object Idle : IndexedCollectionUiState()

    /** An explicit refresh is in flight (bounded, cancelable). */
    data object Refreshing : IndexedCollectionUiState()

    /** Local deterministic groupings for the active scene tab. */
    data class Loaded(
        val tab: IndexedCollectionTab,
        val groups: List<IndexedCollectionGroup>,
        /** Total indexed events read (real count; may exceed displayed groups). */
        val indexedEventCount: Int,
        /** Always "local" — the deterministic, non-model projection. */
        val generation: String,
    ) : IndexedCollectionUiState()

    /** A real read failure surfaced honestly (no model/network invented). */
    data class Error(val message: String) : IndexedCollectionUiState()
}

/** Detail state for an opened indexed group. */
data class IndexedGroupDetailState(
    val group: IndexedCollectionGroup,
    /** The real events belonging to the group, resolved from the loaded index. */
    val events: List<IndexedCollectionEvent>,
)

/**
 * Phase-44 ViewModel for the index-backed 汇总 grouping.
 *
 * Reads are **explicit and user-triggered** via [refreshIndex] (the "同步/整理"
 * action) — never on construction/recomposition. Reads run boundedly (single
 * [com.clawmate.app.datamanager.DailyLogLocalIndex.query] with the default 200
 * cap), on a background dispatcher, and are cancelable through [viewModelScope].
 * No model / network call is ever performed: the injected
 * [IndexedEventReader] (real: [LocalIndexEnvironmentIndexedEventReader]) is
 * read-only and local.
 *
 * Photo grouping (Room-based [CollectionGroup]) is intentionally *not* touched
 * here; this combines real indexed daily-log events into clickable collections.
 */
class CollectionIndexViewModel(
    private val reader: IndexedEventReader,
    private val service: IndexedEventCollectionService = IndexedEventCollectionService,
) : ViewModel() {

    private val _uiState = MutableStateFlow<IndexedCollectionUiState>(IndexedCollectionUiState.Idle)
    val uiState: StateFlow<IndexedCollectionUiState> = _uiState

    private val _selectedTab = MutableStateFlow(IndexedEventCollectionService.tabs().first())
    val selectedTab: StateFlow<IndexedCollectionTab> = _selectedTab

    private val _detail = MutableStateFlow<IndexedGroupDetailState?>(null)
    val detail: StateFlow<IndexedGroupDetailState?> = _detail

    /** The latest bounded event list read (used to resolve group members for detail). */
    @Volatile
    private var loadedEvents: List<IndexedEvent> = emptyList()

    /** Select a scene tab. Does not trigger I/O; the currently loaded view is re-projected. */
    fun selectTab(sceneId: Int) {
        val tab = IndexedEventCollectionService.tabForScene(sceneId)
        _selectedTab.value = tab
        val current = _uiState.value
        if (current is IndexedCollectionUiState.Loaded) {
            _uiState.value = reproject(tab)
        }
        closeDetail()
    }

    /**
     * Explicit user-triggered refresh of the index-backed summ groupings. Reads
     * the real indexed events boundedly, then groups them locally. Cancelable
     * via the ViewModel scope.
     */
    fun refreshIndex() {
        if (_uiState.value is IndexedCollectionUiState.Refreshing) return
        viewModelScope.launch {
            val tab = _selectedTab.value
            _uiState.value = IndexedCollectionUiState.Refreshing
            try {
                val events = reader.read(tab.sceneId)
                loadedEvents = events
                _uiState.value = reproject(tab)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                _uiState.value = IndexedCollectionUiState.Error(
                    t.message ?: "读取本地索引失败（${t.javaClass.simpleName}）",
                )
            }
        }
    }

    /** Open the detail of a group; resolves only the real member events. */
    fun openGroup(groupId: String) {
        val current = _uiState.value
        if (current !is IndexedCollectionUiState.Loaded) return
        val group = current.groups.firstOrNull { it.groupId == groupId } ?: return
        val members = service.eventsForGroup(group.eventIds, loadedEvents).map(service::toDetailEvent)
        _detail.value = IndexedGroupDetailState(group = group, events = members)
    }

    fun closeDetail() {
        _detail.value = null
    }

    private fun reproject(tab: IndexedCollectionTab): IndexedCollectionUiState.Loaded {
        val groups = service.group(loadedEvents, tab.sceneId)
        return IndexedCollectionUiState.Loaded(
            tab = tab,
            groups = groups,
            indexedEventCount = loadedEvents.size,
            generation = "local",
        )
    }

    class Factory(
        private val reader: IndexedEventReader,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            CollectionIndexViewModel(reader) as T
    }
}
