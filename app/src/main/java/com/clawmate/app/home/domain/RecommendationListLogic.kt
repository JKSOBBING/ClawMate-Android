package com.clawmate.app.home.domain

/**
 * Pure, deterministic recommendation-list reductions mirroring the HarmonyOS
 * `HomePage.ets` pagination/filter logic (4 items per page, "未完成/全部" filter,
 * pending-first when showing all). Kept free of UI so unit tests can cover
 * pagination and completion-toggling behaviour.
 */
object RecommendationListLogic {

    const val PAGE_SIZE: Int = 4

    data class Page(
        val items: List<HomeRecommendationViewModel>,
        val pageIndex: Int,
        val pageCount: Int,
    ) {
        val pageLabel: String get() = "${pageIndex + 1}/${pageCount}"
        val hasPrevious: Boolean get() = pageIndex > 0
        val hasNext: Boolean get() = pageIndex < pageCount - 1
    }

    /** Pending recommendations only, or pending-then-completed when showAll. */
    fun visible(order: List<HomeRecommendationViewModel>, showAll: Boolean): List<HomeRecommendationViewModel> {
        val pending = order.filter { !it.completed }
        if (!showAll) return pending
        return pending + order.filter { it.completed }
    }

    fun pageCount(visibleCount: Int): Int = if (visibleCount <= 0) 1 else ((visibleCount + PAGE_SIZE - 1) / PAGE_SIZE)

    fun clampPageIndex(index: Int, pageCount: Int): Int {
        if (index < 0) return 0
        if (pageCount <= 1) return 0
        return if (index > pageCount - 1) pageCount - 1 else index
    }

    /** Current page items (max [PAGE_SIZE]). Returns empty list when none. */
    fun pageItems(order: List<HomeRecommendationViewModel>, showAll: Boolean, pageIndex: Int): Page {
        val visible = visible(order, showAll)
        if (visible.isEmpty()) {
            return Page(emptyList(), 0, 1)
        }
        val count = pageCount(visible.size)
        val index = clampPageIndex(pageIndex, count)
        val start = index * PAGE_SIZE
        return Page(
            items = visible.subList(start, if (start + PAGE_SIZE > visible.size) visible.size else start + PAGE_SIZE),
            pageIndex = index,
            pageCount = count,
        )
    }

    /** Next index respecting bounds. */
    fun nextPageIndex(index: Int, pageCount: Int): Int = clampPageIndex(index + 1, pageCount)

    /** Previous index respecting bounds. */
    fun previousPageIndex(index: Int, pageCount: Int): Int = clampPageIndex(index - 1, pageCount)
}
