package com.clawmate.app.home.data

import com.clawmate.app.home.domain.HomeProfileViewModel
import com.clawmate.app.home.recommendation.HomeRecommendationRepository

/**
 * Loads the home profile (pillars, recommendations, avatar) for the home tab.
 *
 * On Android the data-manager pipeline that consolidates daily-log data into a
 * real profile is a later phase, so the only current implementation is the
 * honest empty profile ([EmptyHomeProfileSource]). A concrete source may expose
 * an optional [recommendationRepository] (Phase 28) that powers the explicit
 * "生成推荐" button; see [ProfileBackedHomeProfileSource]. Nothing fabricates
 * success.
 */
fun interface HomeProfileSource {
    suspend fun load(): HomeProfileViewModel

    /**
     * Optional repository backing the explicit Home recommendation generation
     * (Phase 28). Defaults to null (no generation). This is a DI seam: a
     * recommendations loader / repository can be supplied, and is only ever
     * invoked from the explicit user button.
     */
    val recommendationRepository: HomeRecommendationRepository?
        get() = null
}

/** Returns the deterministic empty profile (mirrors the source's empty VM). */
object EmptyHomeProfileSource : HomeProfileSource {
    override suspend fun load(): HomeProfileViewModel = HomeProfileViewModel.empty
}
