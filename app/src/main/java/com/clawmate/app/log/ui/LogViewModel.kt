package com.clawmate.app.log.ui

import androidx.lifecycle.ViewModel
import com.clawmate.app.log.data.InAppLogRepository
import com.clawmate.app.log.domain.LogFilter
import com.clawmate.app.log.domain.LogSource
import com.clawmate.app.log.domain.filterLogs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Phase 14 — ViewModel for the in-app Log viewer.
 *
 * Owns the viewer chrome states (selected source tab, auto-refresh,
 * auto-scroll, free-text query, minimum level, whether a manual refresh has
 * been performed) and projects the [InAppLogRepository]'s whole-buffer flow
 * through the pure [filterLogs] to produce the rendered, newest-first list.
 * The repository itself is shared process-wide via LogComponent, so the
 * entries the viewer sees are exactly the records other code paths appended.
 */
class LogViewModel(
    private val repository: InAppLogRepository,
) : ViewModel() {

    private val _filter = MutableStateFlow(initialFilter())
    val filter: StateFlow<LogFilter> = _filter.asStateFlow()

    /** The rendered list, newest-first, filtered by [filter]. */
    data class ViewerState(
        val visibleCount: Int,
        val totalCount: Int,
        val isEmpty: Boolean,
        val entries: List<com.clawmate.app.log.domain.InAppLogEntry>,
    )

    private val _viewer: MutableStateFlow<ViewerState> =
        MutableStateFlow(ViewerState(0, 0, true, emptyList()))
    val viewer: StateFlow<ViewerState> = _viewer.asStateFlow()

    /** Whether the user has pressed Refresh (drives the honest empty-state copy). */
    private val _refreshedOnce = MutableStateFlow(false)
    val refreshedOnce: StateFlow<Boolean> = _refreshedOnce.asStateFlow()

    /**
     * Project the repository buffer through the current filter. Called on every
     * repository emission and on every filter change. [visibleCount] describes
     * the filtered subset of the current source tab.
     */
    fun refresh() {
        val f = _filter.value
        val all = repository.snapshot()
        val visible = filterLogs(all, f)
        _viewer.value = ViewerState(
            visibleCount = visible.size,
            totalCount = maxOf(all.count { it.source == f.source }, visible.size),
            isEmpty = visible.isEmpty(),
            entries = visible,
        )
        _refreshedOnce.value = true
    }

    fun setSource(source: LogSource) {
        _filter.update { it.copy(source = source) }
        refresh()
    }

    fun setMinLevelOrNull(level: com.clawmate.app.log.domain.LogLevel?) {
        _filter.update { it.copy(minLevel = level ?: com.clawmate.app.log.domain.LogLevel.DEBUG) }
        refresh()
    }

    fun setQuery(query: String) {
        _filter.update { it.copy(query = query) }
        refresh()
    }

    fun setAutoRefresh(enabled: Boolean) {
        _filter.update { it.copy(autoRefresh = enabled) }
    }

    fun setAutoScroll(enabled: Boolean) {
        _filter.update { it.copy(autoScroll = enabled) }
    }

    fun clearSource() {
        repository.clear(_filter.value.source)
        refresh()
    }

    fun clearAll() {
        repository.clear()
        refresh()
    }

    private fun initialFilter() = LogFilter(source = LogSource.APPLICATION)
}
