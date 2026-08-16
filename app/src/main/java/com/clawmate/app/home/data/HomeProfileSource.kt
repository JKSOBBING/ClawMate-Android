package com.clawmate.app.home.data

import com.clawmate.app.home.domain.HomeProfileViewModel

/**
 * Loads the home profile (pillars, recommendations, avatar) for the home tab.
 *
 * On Android the data-manager pipeline that consolidates daily-log data into a
 * real profile is a later phase, so the only current implementation is the
 * honest empty profile ([EmptyHomeProfileSource]). Nothing fabricates success.
 */
fun interface HomeProfileSource {
    suspend fun load(): HomeProfileViewModel
}

/** Returns the deterministic empty profile (mirrors the source's empty VM). */
object EmptyHomeProfileSource : HomeProfileSource {
    override suspend fun load(): HomeProfileViewModel = HomeProfileViewModel.empty
}
