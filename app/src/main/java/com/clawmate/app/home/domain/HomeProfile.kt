package com.clawmate.app.home.domain

/**
 * 首页画像聚合（`HomeProfileViewModel`），ported from
 * `DataManagerTypes.ets` / `DataManagerFacade.createEmptyHomeProfileViewModel`.
 *
 * `empty` is true only when the upstream data source is honestly empty. No
 * successful data is invented on Android.
 */
data class HomeProfileViewModel(
    val updatedLabel: String,
    val completeness: Int,
    val totalEvents: Int,
    val domains: List<HomeDomainViewModel>,
    val recommendations: List<HomeRecommendationViewModel>,
    val empty: Boolean,
    val avatar: AvatarSection = AvatarSection.notConfigured,
) {
    companion object {
        /** Honest empty profile (mirrors `createEmptyHomeProfileViewModel`). */
        val empty = HomeProfileViewModel(
            updatedLabel = "暂无画像",
            completeness = 0,
            totalEvents = 0,
            domains = defaultHomeDomains(),
            recommendations = emptyList(),
            empty = true,
        )
    }
}

/** UI lifecycle state for the home tab. */
sealed interface HomeUiState {
    data object Loading : HomeUiState
    data class Ready(val profile: HomeProfileViewModel) : HomeUiState
    data class Error(val message: String) : HomeUiState
}
