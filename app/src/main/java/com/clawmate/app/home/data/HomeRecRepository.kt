package com.clawmate.app.home.data

import com.clawmate.app.home.domain.HomeRecommendationViewModel
import kotlinx.coroutines.flow.Flow

/**
 * Persistent store for home-recommendation completion state (the user-mutable
 * `completed` flags). Only flag values are persisted — never any
 * recommendation content, endpoints, keys, or personal data.
 */
interface HomeRecRepository {

    /** Flow of `id -> completed` flags. May be empty. */
    val completionFlags: Flow<Map<String, Boolean>>

    /** Read the stored completion flags once. */
    suspend fun snapshot(): Map<String, Boolean>

    /** Store one recommendation's completion flag. Returns the stored value. */
    suspend fun setCompleted(id: String, completed: Boolean): Boolean

    /** True if [id] is persisted as completed. */
    suspend fun isCompleted(id: String): Boolean =
        snapshot()[id] == true

    /**
     * Fold persisted completion flags onto a candidate recommendation list.
     * Recommendations without a pending flag default to `completed = false`.
     * This never invents recommendations — it only colours the ones supplied.
     */
    fun applyFlags(
        recommendations: List<HomeRecommendationViewModel>,
        flags: Map<String, Boolean>,
    ): List<HomeRecommendationViewModel> =
        recommendations.map { rec ->
            if (rec.id.isBlank() || !flags.containsKey(rec.id)) rec
            else rec.copy(completed = flags[rec.id] == true)
        }
}
