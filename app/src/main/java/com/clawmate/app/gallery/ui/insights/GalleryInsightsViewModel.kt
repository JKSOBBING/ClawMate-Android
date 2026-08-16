package com.clawmate.app.gallery.ui.insights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.clawmate.app.gallery.cloud.GalleryCloudAdapter
import com.clawmate.app.gallery.data.GalleryRepository
import com.clawmate.app.gallery.insights.GalleryMonthInsight
import com.clawmate.app.gallery.insights.GalleryMonthlyInsightCloudState
import com.clawmate.app.gallery.insights.GalleryMonthlyInsights
import com.clawmate.app.gallery.insights.UnconfiguredMonthlyInsightCloud
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * UI state for the Gallery monthly-insights panel (Phase 15 + Phase 21).
 *
 * Loading / Empty / Error are honest states driven by the Room-backed items flow:
 *  - Loading — insights are being derived from stored items.
 *  - Empty — no stored photo items yet; nothing to summarise (nothing invented).
 *  - Loaded — real [GalleryMonthInsight]s plus honest cloud states.
 *  - Error — local derivation failed; surfaced, never masked.
 *
 * Phase 21: the cloud `aiSummary` / `embedding` states come from a
 * config-driven [GalleryCloudAdapter] behind explicit settings. The gate only
 * reports NOT_CONFIGURED / UNSUPPORTED / READY and never touches the network by
 * itself; the real summary / embedding calls are only ever started from explicit
 * user actions via [generateMonthlySummary] / [embedSelectedMonth]. Before any
 * call the adapter short-circuits to an honest ERROR when not configured, so no
 * silent upload or fabricated text can occur. The deterministic local insight
 * path ([GalleryMonthlyInsights]) always remains.
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
    private val cloudAdapterProvider: suspend () -> GalleryCloudAdapter? = { null },
) : ViewModel() {

    private var cloudAdapter: GalleryCloudAdapter? = null

    private val _aiSummary =
        MutableStateFlow<GalleryMonthlyInsightCloudState>(UnconfiguredMonthlyInsightCloud.aiSummary)
    val aiSummary: StateFlow<GalleryMonthlyInsightCloudState> = _aiSummary.asStateFlow()

    private val _embedding =
        MutableStateFlow<GalleryMonthlyInsightCloudState>(UnconfiguredMonthlyInsightCloud.embedding)
    val embedding: StateFlow<GalleryMonthlyInsightCloudState> = _embedding.asStateFlow()

    private val _state = MutableStateFlow<GalleryInsightsUiState>(GalleryInsightsUiState.Loading)
    val state: StateFlow<GalleryInsightsUiState> = _state

    init {
        viewModelScope.launch {
            cloudAdapter = cloudAdapterProvider()
            refreshCloudStates()
        }
        viewModelScope.launch {
            combine(
                repository.observeMonthlyInsights(),
                _aiSummary,
                _embedding,
            ) { insights, ai, emb ->
                if (insights.isEmpty()) {
                    GalleryInsightsUiState.Empty
                } else {
                    GalleryInsightsUiState.Loaded(
                        insights = insights,
                        aiSummary = ai,
                        embedding = emb,
                    )
                }
            }
                .catch { t: Throwable -> emit(GalleryInsightsUiState.Error(errorMessage(t))) }
                .collect { _state.value = it }
        }
    }

    /**
     * Recompute the cloud gate states from the current adapter configuration
     * (NOT_CONFIGURED / UNSUPPORTED / READY). This performs **no network call**.
     */
    fun refreshCloudStates() {
        val adapter = cloudAdapter
        _aiSummary.value = adapter?.summaryState ?: UnconfiguredMonthlyInsightCloud.aiSummary
        _embedding.value = adapter?.embeddingState ?: UnconfiguredMonthlyInsightCloud.embedding
    }

    /**
     * Generate a real cloud AI summary for [insight] — only ever from an explicit
     * user action. Uses the exact `buildLlmInput` aggregate text as input and a
     * configured adapter. Any failure is surfaced as an honest, redacted ERROR
     * state; if not configured the adapter short-circuits before any I/O.
     */
    fun generateMonthlySummary(insight: GalleryMonthInsight) {
        viewModelScope.launch {
            val adapter = cloudAdapter
            if (adapter == null) {
                _aiSummary.value = GalleryMonthlyInsightCloudState.NotConfigured
                return@launch
            }
            val aggregate = GalleryMonthlyInsights.buildLlmInput(insight)
            try {
                val result = adapter.generateMonthlySummary(aggregate)
                val text = buildString {
                    if (result.title.isNotBlank()) append(result.title).append("：")
                    append(result.summary)
                    if (result.suggestion.isNotBlank()) append("\n").append(result.suggestion)
                }
                _aiSummary.value = GalleryMonthlyInsightCloudState.Ready(
                    text.ifBlank { "云端月度总结已生成" },
                )
            } catch (t: Throwable) {
                val message = (t as? com.clawmate.app.gallery.cloud.GalleryCloudException)
                    ?.safeMessage
                    ?: t.message.orEmpty()
                _aiSummary.value = GalleryMonthlyInsightCloudState.Error(
                    redactMessage(message).ifBlank { "云端月度总结生成失败" },
                )
            }
        }
    }

    /**
     * Embed the representative text of [insight] — only ever from an explicit user
     * action. Uses a configured remote embedding client; surfaces ERROR when the
     * call fails and keeps the honest gate otherwise.
     */
    fun embedSelectedMonth(insight: GalleryMonthInsight) {
        viewModelScope.launch {
            val adapter = cloudAdapter
            if (adapter == null) {
                _embedding.value = GalleryMonthlyInsightCloudState.NotConfigured
                return@launch
            }
            val text = buildEmbeddingText(insight)
            try {
                val result = adapter.embedText(text)
                _embedding.value = GalleryMonthlyInsightCloudState.Ready(
                    "已生成 ${result.vector.size} 维向量（${result.model}）",
                )
            } catch (t: Throwable) {
                val message = (t as? com.clawmate.app.gallery.cloud.GalleryCloudException)
                    ?.safeMessage
                    ?: t.message.orEmpty()
                _embedding.value = GalleryMonthlyInsightCloudState.Error(
                    redactMessage(message).ifBlank { "向量生成失败" },
                )
            }
        }
    }

    /** A bounded text representation of a month used for remote embedding. */
    private fun buildEmbeddingText(insight: GalleryMonthInsight): String =
        com.clawmate.app.gallery.cloud.GalleryCloudBounds.MAX_EMBEDDING_TEXT_LENGTH
            .let { max ->
                val label = insight.label
                val tags = insight.categoryCounts.joinToString("、") { it.label }
                val raw = "$label；$tags；${insight.summary}"
                if (raw.length <= max) raw else raw.substring(0, max)
            }

    private fun redactMessage(message: String): String =
        com.clawmate.app.log.domain.SensitiveLogRedactor.redact(message)

    private fun errorMessage(t: Throwable): String =
        t.message?.takeIf { it.isNotBlank() }
            ?: "月度洞察加载失败（${t::class.java.simpleName}）"

    class Factory(
        private val repository: GalleryRepository,
        private val cloudAdapterProvider: suspend () -> GalleryCloudAdapter? = { null },
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return GalleryInsightsViewModel(repository, cloudAdapterProvider) as T
        }
    }
}
