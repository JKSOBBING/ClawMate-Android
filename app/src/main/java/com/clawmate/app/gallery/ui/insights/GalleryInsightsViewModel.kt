package com.clawmate.app.gallery.ui.insights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.clawmate.app.gallery.data.GalleryRepository
import com.clawmate.app.gallery.insights.GalleryMonthInsight
import com.clawmate.app.gallery.insights.GalleryMonthlyInsightCloudState
import com.clawmate.app.gallery.insights.UnconfiguredMonthlyInsightCloud
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * UI state for the Gallery monthly-insights panel (Phase 15).
 *
 * Loading / Empty / Error are honest states driven by the Room-backed items flow:
 *  - Loading — insights are being derived from stored items (before the first
 *    emission).
 *  - Empty — there are no stored photo items yet, so there is nothing to
 *    summarise (nothing fabricated or invented).
 *  - Loaded — real [GalleryMonthInsight]s plus the always-truthful cloud
 *    NOT_CONFIGURED boundary (AI summary / embedding are not configured).
 *  - Error — derivation failed; surfaced, never masked.
 */
sealed class GalleryInsightsUiState {
    data object Loading : GalleryInsightsUiState()
    data object Empty : GalleryInsightsUiState()
    data class Loaded(
        val insights: List<GalleryMonthInsight>,
        val aiSummary: GalleryMonthlyInsightCloudState,
        val embedding: GalleryMonthlyInsightCloudState,
    ) : GalleryInsightsUiState()

    data class Error(val message: String) : GalleryInsightsUiState()
}

class GalleryInsightsViewModel(
    private val repository: GalleryRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<GalleryInsightsUiState>(GalleryInsightsUiState.Loading)
    val state: StateFlow<GalleryInsightsUiState> = _state

    init {
        viewModelScope.launch {
            repository.observeMonthlyInsights()
                .map<List<GalleryMonthInsight>, GalleryInsightsUiState> { insights ->
                    if (insights.isEmpty()) {
                        GalleryInsightsUiState.Empty
                    } else {
                        GalleryInsightsUiState.Loaded(
                            insights = insights,
                            aiSummary = UnconfiguredMonthlyInsightCloud.aiSummary,
                            embedding = UnconfiguredMonthlyInsightCloud.embedding,
                        )
                    }
                }
                .catch { t: Throwable -> emit(GalleryInsightsUiState.Error(errorMessage(t))) }
                .collect { _state.value = it }
        }
    }

    private fun errorMessage(t: Throwable): String =
        t.message?.takeIf { it.isNotBlank() }
            ?: "月度洞察加载失败（${t::class.java.simpleName}）"

    class Factory(
        private val repository: GalleryRepository,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return GalleryInsightsViewModel(repository) as T
        }
    }
}
