package com.clawmate.app.home.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.clawmate.app.datamanager.DailyLogEntry
import com.clawmate.app.gallery.model.GalleryPhotoItem
import com.clawmate.app.home.data.HomeProfileSource
import com.clawmate.app.home.data.HomeRecRepository
import com.clawmate.app.home.recommendation.HomeRecommendationRepository

/**
 * Simple factory for [HomeViewModel].
 *
 * [profileSource] may expose a [recommendationRepository] (Phase 28). When
 * [galleryItemsSource] is supplied (production wires the real
 * [com.clawmate.app.gallery.data.GalleryRepository] snapshot), the Home's
 * explicit "生成推荐" button is enabled; otherwise generation is unavailable.
 * An optional [dailyLogSource] (Phase 29) contributes real app-private daily-log
 * entries to that same explicit generation (read only on button tap, never on
 * Home load/recomposition/background).
 *
 * An optional [profileGenerationRepository] (Phase 32) backs the explicit
 * "生成画像" button; it reads real app-private indexed events (local-only when
 * unconfigured, model-backed otherwise) and is only invoked on the explicit
 * button tap — never at launch/recomposition/background.
 */
class HomeViewModelFactory(
    private val homeRecRepository: HomeRecRepository,
    private val profileSource: HomeProfileSource,
    private val galleryItemsSource: (suspend () -> List<GalleryPhotoItem>)? = null,
    private val dailyLogSource: (suspend () -> List<DailyLogEntry>)? = null,
    private val profileGenerationRepository: com.clawmate.app.datamanager.ProfileGenerationRepository? = null,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(HomeViewModel::class.java)) {
            return HomeViewModel(
                homeRecRepository,
                recommendationRepository = profileSource.recommendationRepository,
                galleryItemsSource = galleryItemsSource,
                dailyLogSource = dailyLogSource,
                profileGenerationRepository = profileGenerationRepository,
                profileLoader = { profileSource.load() },
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
