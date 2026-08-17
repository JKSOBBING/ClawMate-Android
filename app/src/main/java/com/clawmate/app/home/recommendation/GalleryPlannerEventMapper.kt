package com.clawmate.app.home.recommendation

import com.clawmate.app.gallery.model.GalleryPhotoItem

/**
 * Converts **real, already-stored [GalleryPhotoItem]s** into bounded
 * [HomeRecommendationPlannerEvent]s for the Home recommendation prompt.
 *
 * Only text content that actually exists on the stored item is copied into the
 * planner event:
 *  - `id`, `date` — real item id / date.
 *  - `todo_text`, `summary`, `normalized_fact` — built from the real
 *    title/category/summary/details, all capped.
 *  - `keywords` — the real item keywords/tags (capped).
 *  - `sources` — the constant honest source label "Gallery" (it describes the
 *    real upstream, never transmits data).
 *
 * What is **never** emitted:
 *  - raw `uri` / image bytes / base64;
 *  - API tokens / credentials;
 *  - fabricated analysis (an item with no analysed text yields honest empty
 *    fields that cannot pass the prompt's non-empty input gate downstream).
 *
 * `imagePaths` is always empty — no raw image reference ever leaves the device.
 */
object GalleryPlannerEventMapper {

    /** Honest source descriptor for gallery-derived events. */
    const val SOURCE_GALLERY: String = "Gallery"

    /**
     * Map real gallery items to bounded planner events. Returns an empty list
     * when there is nothing meaningful to send (no items, or every item is blank).
     */
    fun toPlannerEvents(items: List<GalleryPhotoItem>): List<HomeRecommendationPlannerEvent> =
        items
            .asSequence()
            .mapNotNull { toPlannerEvent(it) }
            .take(HomeRecommendationBounds.MAX_EVENTS_PER_REQUEST)
            .toList()

    /**
     * Build one bounded planner event from a single real gallery item, or null
     * when the item has no usable text content (an un-analysed photo with only a
     * bound URI invented no summary) — such an item is honestly skipped.
     */
    fun toPlannerEvent(item: GalleryPhotoItem): HomeRecommendationPlannerEvent? {
        if (item.title.isBlank() &&
            item.category.isBlank() &&
            item.summary.isBlank() &&
            item.details.isEmpty() &&
            item.keywords.isEmpty() &&
            item.tags.isEmpty()
        ) {
            return null
        }
        val todoText = bounded(
            listOf(item.title, item.category, item.summary)
                .firstOrNull { it.isNotBlank() }
                .orEmpty(),
            HomeRecommendationBounds.MAX_EVENT_TODO_TEXT,
        )
        val summary = bounded(item.summary, HomeRecommendationBounds.MAX_EVENT_SUMMARY)
        // normalized_fact is the richest real text: prefer the item summary,
        // otherwise the joined details, otherwise the title (all real fields).
        val factSource =
            item.summary.takeIf { it.isNotBlank() }
                ?: item.details.joinToString("，").trim().takeIf { it.isNotEmpty() }
                ?: item.title
        val normalizedFact = bounded(factSource, HomeRecommendationBounds.MAX_EVENT_NORMALIZED_FACT)
        return HomeRecommendationPlannerEvent(
            eventId = item.id,
            date = item.date,
            todoType = "",
            todoDeadline = "",
            todoText = todoText,
            summary = summary,
            normalizedFact = normalizedFact,
            sources = listOf(SOURCE_GALLERY),
            domains = domainsFor(item.category),
            keywords = (item.keywords + item.tags)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .take(HomeRecommendationBounds.MAX_EVENT_KEYWORDS),
            imagePaths = emptyList(),
        )
    }

    /**
     * Deterministically map a real gallery [category] to an allowed
     * recommendation domain where that category is genuinely one of the six
     * known domains; otherwise empty (honesty over guessing). The model output
     * domain is validated separately.
     */
    private fun domainsFor(category: String): List<String> {
        val c = category.trim().lowercase()
        if (c.isEmpty()) return emptyList()
        if (c in HOME_RECOMMENDATION_DOMAINS) return listOf(c)
        // A category like "饮食/餐饮/美食" is genuinely food → eat; keep the
        // mapping conservative and textual: only exact known-domain or a small
        // curated synonym set. Unknown categories map to nothing, not to a guess.
        val mapped = when (c) {
            "eat", "餐饮", "美食", "饮食", "外卖", "食品" -> "eat"
            "wear", "服装", "服饰", "鞋包", "穿搭", "美妆" -> "wear"
            "live", "家居", "住房", "住宿", "酒店", "生活" -> "live"
            "travel", "出行", "旅行", "差旅", "交通", "机票", "车票" -> "travel"
            "work", "办公", "工作", "会议", "学习", "文档" -> "work"
            else -> return emptyList()
        }
        return listOf(mapped)
    }

    private fun bounded(value: String, maxLength: Int): String {
        val v = value.trim()
        if (v.length <= maxLength) return v
        return v.substring(0, maxOf(0, maxLength - 3)) + "..."
    }
}
