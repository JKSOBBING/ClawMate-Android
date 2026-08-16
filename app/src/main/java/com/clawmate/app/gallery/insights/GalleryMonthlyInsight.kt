package com.clawmate.app.gallery.insights

import com.clawmate.app.gallery.model.GalleryPhotoItem

/**
 * Portable Gallery monthly-insight domain models.
 *
 * Ported from the HarmonyOS source of truth (read-only reference):
 *   entry/src/main/ets/utils/gallery/GalleryMonthlyInsights.ets (row D2)
 *   entry/src/main/ets/utils/gallery/GalleryTypes.ets
 *   entry/src/main/ets/pages/gallery/GalleryPage.ets (panel usage)
 *
 * The pure bucketing / tag-count / local-summary / LLM-input builder lives in
 * [GalleryMonthlyInsights] and maps directly over the existing Room-backed
 * [GalleryPhotoItem]. No insight is ever fabricated: every value derives only
 * from stored analysis data (date / createdAt / tags / category / title /
 * summary). Cloud LLM monthly summaries & vector embedding stay an explicit
 * NOT_CONFIGURED boundary until a real cloud config exists (see
 * [com.clawmate.app.gallery.insights.GalleryMonthlyInsightCloudState]).
 */

/** A single tag bucket label + how many photos carry it (counts, sorted desc). */
data class GalleryMonthCategoryCount(
    val label: String,
    val count: Int,
)

/**
 * One month bucket of insights over stored gallery photos.
 *
 * [representativeItems] are the first (up to 6) newest photos of the month for
 * quick browsing; [entriesForLlm] is the full monthly item list used when
 * building LLM input (bounded internally by [GalleryMonthlyInsights.buildLlmInput]).
 * [summary] is the deterministic local summary (no cloud call required).
 */
data class GalleryMonthInsight(
    val key: String,
    val label: String,
    val totalCount: Int,
    val categoryCounts: List<GalleryMonthCategoryCount>,
    val representativeItems: List<GalleryPhotoItem>,
    val entriesForLlm: List<GalleryPhotoItem>,
    val summary: String,
)
