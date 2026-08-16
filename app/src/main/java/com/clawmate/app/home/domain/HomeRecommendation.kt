package com.clawmate.app.home.domain

import java.util.Locale

/**
 * 首页待办推荐（`HomeRecommendationViewModel`）及其持久化状态
 * （`HomeRecommendationState`），ported from the HarmonyOS source
 * `datamanager/DataManagerTypes.ets`.
 *
 * A recommendation groups one or more source events behind a todo action. Only
 * its `completed` flag is user-mutable and persisted. Nothing here invents
 * recommendation content — the list is populated by an upstream data source
 * (future data-manager pipeline); until then the home shows an empty state.
 */
data class HomeRecommendationViewModel(
    val id: String,
    val domain: String,
    val tag: String,
    val title: String,
    val desc: String,
    val meta: String,
    val color: String,
    val imageRefs: List<CollectionImageViewModel> = emptyList(),
    val thumbnailUri: String = "",
    val sourceEventIds: List<String> = emptyList(),
    val detail: String = "",
    val agentTask: String = "",
    val completed: Boolean = false,
)

/**
 * Persisted, minimal recommendation fingerprint. Mirrors
 * `HomeRecommendationState` in `DataManagerTypes.ets`.
 */
data class HomeRecommendationState(
    val id: String,
    val tag: String,
    val title: String,
    val desc: String,
    val meta: String,
    val domain: String,
    val sourceEventIds: List<String>,
    val completed: Boolean,
)

/** Reference image slot mirroring `CollectionImageViewModel`. */
data class CollectionImageViewModel(
    val ref: String,
    val uri: String,
    val available: Boolean,
    val label: String,
)

/** Per-domain recommendation accent colors, matching the source facade. */
val HOME_RECOMMENDATION_COLORS: Map<String, String> = mapOf(
    "eat" to "#39C2A2",
    "wear" to "#7669FF",
    "live" to "#FF963D",
    "travel" to "#4B9BFF",
    "work" to "#E35D6A",
    "other" to "#8B93A4",
)

/** Accent color for a recommendation domain with a safe fallback. */
fun recommendationColor(domain: String): String =
    HOME_RECOMMENDATION_COLORS[domain.lowercase(Locale.ROOT)] ?: "#8066FF"
