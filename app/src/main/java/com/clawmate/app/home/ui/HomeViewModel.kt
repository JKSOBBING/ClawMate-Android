package com.clawmate.app.home.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.clawmate.app.datamanager.DailyLogEntry
import com.clawmate.app.gallery.model.GalleryPhotoItem
import com.clawmate.app.home.data.HomeRecRepository
import com.clawmate.app.home.domain.HomeProfileViewModel
import com.clawmate.app.home.domain.HomeRecommendationViewModel
import com.clawmate.app.home.domain.HomeUiState
import com.clawmate.app.home.domain.RecommendationListLogic
import com.clawmate.app.home.domain.toViewModel
import com.clawmate.app.home.recommendation.HomeRecommendationGenerationState
import com.clawmate.app.home.recommendation.HomeRecommendationRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * UI state for the home tab's recommendation list.
 */
data class RecommendationListUi(
    val recommendations: List<HomeRecommendationViewModel> = emptyList(),
    val showAll: Boolean = false,
    val pageIndex: Int = 0,
) {
    val page: RecommendationListLogic.Page
        get() = RecommendationListLogic.pageItems(recommendations, showAll, pageIndex)
}

/**
 * ViewModel for the home tab: loads the (honest, possibly empty) home profile
 * and drives recommendation completion, filtering, pagination and expansion.
 *
 * Recommendation completion toggles are persisted through [HomeRecRepository];
 * the list itself never invents recommendations — it colours the ones supplied
 * by the profile source (or generated explicitly, Phase 28) with the stored
 * flags.
 *
 * Phase 28: when a [HomeRecommendationRepository] and a gallery-items source are
 * injected, the explicit [generateRecommendations] action turns the honest empty
 * state into a real generation flow (未配置 / 无数据 / 运行中 / 成功 / 失败). It is
 * **never** called at launch, on recomposition or from a background job — only
 * from the explicit Home button.
 *
 * Phase 29: an optional [dailyLogSource] contributes real app-private daily-log
 * entries to the same explicit generation (bounded [DailyLogEntry]s read from
 * the `workflows/daily-log` tree). It is read only when the user taps the
 * button — never from Home load/recomposition or a background job — and only
 * from app-private files; no external storage / URI / image / network is touched.
 */
class HomeViewModel(
    private val homeRecRepository: HomeRecRepository,
    private val recommendationRepository: HomeRecommendationRepository? = null,
    private val galleryItemsSource: (suspend () -> List<GalleryPhotoItem>)? = null,
    private val dailyLogSource: (suspend () -> List<DailyLogEntry>)? = null,
    profileLoader: suspend () -> HomeProfileViewModel,
) : ViewModel() {

    private val _state = MutableStateFlow<HomeUiState>(HomeUiState.Loading)
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    private val _list = MutableStateFlow(RecommendationListUi())
    val list: StateFlow<RecommendationListUi> = _list.asStateFlow()

    /**
     * Phase-28 explicit generation state — drives the Home "生成推荐" button.
     * Idle until the user explicitly taps the button.
     */
    private val _generation = MutableStateFlow<HomeRecommendationGenerationState>(
        HomeRecommendationGenerationState.Idle,
    )
    val generation: StateFlow<HomeRecommendationGenerationState> = _generation.asStateFlow()

    init {
        load(profileLoader)
    }

    fun load(profileLoader: suspend () -> HomeProfileViewModel) {
        viewModelScope.launch {
            _state.value = HomeUiState.Loading
            val result = runCatching { profileLoader() }
            result.onSuccess { profile ->
                val flags = try {
                    homeRecRepository.snapshot()
                } catch (e: Exception) {
                    emptyMap()
                }
                val coloured = homeRecRepository.applyFlags(profile.recommendations, flags)
                _list.update { it.copy(recommendations = coloured, showAll = false, pageIndex = 0) }
                _state.value = HomeUiState.Ready(profile.copy(recommendations = coloured))
            }
            result.onFailure { e ->
                _state.value = HomeUiState.Error(e.message ?: "加载首页画像失败")
            }
        }
    }

    // --------------------------------------------------- explicit Phase-28 generate

    /**
     * Explicit user action: generate recommendations from the real stored gallery
     * items plus real app-private daily-log entries (Phase 29) and inject the
     * successful result into the current Home state, applying/persisting
     * completion flags. With no repository/gallery source injected, reports an
     * honest disabling state. Never auto-invoked — the gallery and daily-log
     * sources are read only when the user taps the button, never at Home
     * load/recomposition or from a background job.
     */
    fun generateRecommendations() {
        val repository = recommendationRepository
        val gallerySource = galleryItemsSource
        if (repository == null || gallerySource == null || !repository.isConfigured()) {
            _generation.value = HomeRecommendationGenerationState.Error(
                message = "未配置推荐生成",
                userMessage = "未配置云端模型或图库来源，无法生成推荐。",
            )
            return
        }
        viewModelScope.launch {
            _generation.value = HomeRecommendationGenerationState.Running
            val items = try {
                gallerySource()
            } catch (e: kotlinx.coroutines.CancellationException) {
                _generation.value = HomeRecommendationGenerationState.Idle
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            val dailyLog = try {
                dailyLogSource?.invoke().orEmpty()
            } catch (e: kotlinx.coroutines.CancellationException) {
                _generation.value = HomeRecommendationGenerationState.Idle
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            val result = repository.generate(items, dailyLog)
            when (result.state) {
                is HomeRecommendationGenerationState.Success -> onGenerationSuccess(result)
                else -> _generation.value = result.state
            }
        }
    }

    private suspend fun onGenerationSuccess(result: com.clawmate.app.home.recommendation.HomeRecommendationGenerationResult) {
        val state = result.state as HomeRecommendationGenerationState.Success
        val viewModels = state.recommendations.map { it.toViewModel() }
        val flags = try {
            homeRecRepository.snapshot()
        } catch (e: Exception) {
            emptyMap()
        }
        val coloured = homeRecRepository.applyFlags(viewModels, flags)
        // Fold the freshly generated recommendations into the current profile.
        val current = _state.value
        if (current is HomeUiState.Ready) {
            _state.value = HomeUiState.Ready(
                current.profile.copy(recommendations = coloured),
            )
        }
        _list.update { it.copy(recommendations = coloured, showAll = false, pageIndex = 0) }
        // The completion flags are now persisted (true for completed items). Keep
        // the generation state as a successful summary for the button label.
        _generation.value = HomeRecommendationGenerationState.Success(state.recommendations)
    }

    // ----------------------------------------------------------- list actions

    fun setShowAll(showAll: Boolean) {
        _list.update { it.copy(showAll = showAll, pageIndex = 0) }
    }

    fun nextPage() {
        _list.update { current ->
            val count = current.page.pageCount
            current.copy(pageIndex = RecommendationListLogic.nextPageIndex(current.pageIndex, count))
        }
    }

    fun previousPage() {
        _list.update { current ->
            val count = current.page.pageCount
            current.copy(pageIndex = RecommendationListLogic.previousPageIndex(current.pageIndex, count))
        }
    }

    /**
     * Persist a recommendation's completion flag and update the local list.
     * Returns the effective value (false when the id is rejected).
     */
    fun setRecommendationCompleted(id: String, completed: Boolean) {
        viewModelScope.launch {
            val stored = homeRecRepository.setCompleted(id, completed)
            _list.update { current ->
                val updated = current.recommendations.map {
                    if (it.id == id) it.copy(completed = stored) else it
                }
                current.copy(recommendations = updated)
            }
        }
    }
}
