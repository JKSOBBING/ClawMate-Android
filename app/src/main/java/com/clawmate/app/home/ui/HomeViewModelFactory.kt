package com.clawmate.app.home.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
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
 */
class HomeViewModelFactory(
    private val homeRecRepository: HomeRecRepository,
    private val profileSource: HomeProfileSource,
    private val galleryItemsSource: (suspend () -> List<GalleryPhotoItem>)? = null,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(HomeViewModel::class.java)) {
            return HomeViewModel(
                homeRecRepository,
                recommendationRepository = profileSource.recommendationRepository,
                galleryItemsSource = galleryItemsSource,
                profileLoader = { profileSource.load() },
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
