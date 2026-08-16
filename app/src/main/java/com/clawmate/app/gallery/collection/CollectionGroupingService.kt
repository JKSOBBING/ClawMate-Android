package com.clawmate.app.gallery.collection

import com.clawmate.app.gallery.model.CollectionGroup
import com.clawmate.app.gallery.model.GalleryPhotoItem

/**
 * Deterministic heuristics that group photo items into collections.
 *
 * Ported from the HarmonyOS source of truth (read-only reference):
 *   entry/src/main/ets/datamanager/CollectionGroupingService.ets
 *
 * The HarmonyOS version groups *indexed events* by source/domain/entity/
 * keyword. On Android the closest available input is the set of imported
 * gallery photos (by category/tags/source), so we map each photo to a small
 * event-like tuple and run the same stable key + derive-title logic.
 *
 * All functions are pure (no Android dependencies) and fully deterministic so
 * they can be unit-tested without a device.
 */
object CollectionGroupingService {

    private const val CHAT = "chat"
    private const val ORDERS = "orders"
    private const val NOTIFICATIONS = "notifications"
    private const val ENTERTAINMENT = "entertainment"

    /** Domain id -> Chinese display name (subset of DataManagerTypes). */
    private val DISPLAY_DOMAINS = mapOf(
        "eat" to "吃",
        "wear" to "穿",
        "live" to "住",
        "travel" to "行",
        "work" to "办公",
        "other" to "其他",
    )

    /**
     * Groups [items] using the default general-purpose tab behaviour.
     * [tabId] selects the grouping rules; the gallery screen uses the generic
     * branch (source|domain|keyword).
     *
     * Returns groups sorted by time desc then group size desc, each group
     * containing stable event ids. Empty input yields an empty list.
     */
    fun groupPhotos(tabId: String, items: List<GalleryPhotoItem>): List<CollectionGroup> {
        if (items.isEmpty()) return emptyList()
        val grouped = LinkedHashMap<String, MutableList<GalleryPhotoItem>>()
        items.forEach { item ->
            val key = heuristicGroupKey(tabId, item)
            grouped.getOrPut(key) { mutableListOf() }.add(item)
        }
        val candidates = grouped.values.map { events -> groupFromEvents(tabId, events) }

        // Normalise: drop groups with no surviving members, then assign a stable id.
        val normalized = normalizeCollectionGroups(tabId, items, candidates)
        return normalized.mapIndexed { index, group ->
            group.copy(groupId = stableGroupId(tabId, index, group.eventIds))
        }
    }

    private fun heuristicGroupKey(tabId: String, item: GalleryPhotoItem): String {
        val source = itemSource(item)
        val domain = firstText(listOf(item.category), "other")
        val entity = firstEntityName(item)
        val keyword = firstMeaningfulKeyword(item)
        return when (tabId) {
            CHAT -> if (isGalleryChatItem(item)) "gallery-chat" else keyword.ifEmpty { source.ifEmpty { "chat" } }
            ORDERS -> listOf(entity.ifEmpty { source.ifEmpty { displayDomain(domain) } }, domain).joinToString("|")
            NOTIFICATIONS -> listOf(source, keyword.ifEmpty { "提醒" }).joinToString("|")
            ENTERTAINMENT -> listOf(keyword.ifEmpty { source }, domain).joinToString("|")
            else -> listOf(source, domain, keyword).joinToString("|")
        }
    }

    private fun groupFromEvents(tabId: String, events: List<GalleryPhotoItem>): CollectionGroup {
        val sorted = events.sortedWith(
            compareByDescending<GalleryPhotoItem> { it.date }
                .thenByDescending { it.id },
        )
        val domain = dominantDomain(sorted)
        val source = dominantSource(sorted)
        val title = deriveGroupTitle(tabId, sorted, source, domain)
        val ids = sorted.map { it.id }
        return CollectionGroup(
            groupId = "",
            title = summarizeText(title, 28),
            source = source,
            time = sorted.firstOrNull()?.date ?: "",
            meta = "${ids.size} 条",
            status = "已整理",
            domain = domain,
            eventIds = ids,
        )
    }

    private fun deriveGroupTitle(
        tabId: String,
        events: List<GalleryPhotoItem>,
        source: String,
        domain: String,
    ): String {
        val entity = events.firstOrNull()?.let { firstEntityName(it) } ?: ""
        val keyword = events.firstOrNull()?.let { firstMeaningfulKeyword(it) } ?: ""
        return when (tabId) {
            CHAT -> if (isGalleryChatItem(events.first())) "聊天截图"
            else if (keyword.isNotEmpty()) "${keyword}沟通"
            else "聊天整理"

            ORDERS -> if (entity.isNotEmpty()) "${entity}相关订单"
            else if (keyword.isNotEmpty()) "${keyword}记录"
            else "${displayDomain(domain)}类订单"

            NOTIFICATIONS -> if (keyword.isNotEmpty()) "${keyword}提醒"
            else if (source.isNotEmpty()) "${source}通知"
            else "通知记录"

            ENTERTAINMENT -> if (keyword.isNotEmpty()) "${keyword}记录"
            else if (source.isNotEmpty()) "${source}娱乐记录"
            else "娱乐记录"

            else -> when {
                keyword.isNotEmpty() -> "$keyword 图片"
                entity.isNotEmpty() -> "$entity 图片"
                else -> "相关图片"
            }
        }
    }

    /** Re-assigns groups so each item appears at most once; drops empty groups. */
    private fun normalizeCollectionGroups(
        tabId: String,
        items: List<GalleryPhotoItem>,
        candidates: List<CollectionGroup>,
    ): List<CollectionGroup> {
        val eventMap = items.associateBy { it.id }
        val assigned = mutableSetOf<String>()
        val normalized = mutableListOf<CollectionGroup>()
        candidates.forEach { candidate ->
            val ids = candidate.eventIds.distinct().filter { id -> eventMap[id] != null && id !in assigned }
            if (ids.isEmpty()) return@forEach
            ids.forEach { assigned.add(it) }
            val groupEvents = ids.mapNotNull { eventMap[it] }
            val base = groupFromEvents(tabId, groupEvents)
            normalized.add(
                base.copy(
                    eventIds = ids,
                    time = base.time,
                ),
            )
        }

        val missing = items.filter { it.id !in assigned }
        if (missing.isNotEmpty()) {
            missing.groupBy { heuristicGroupKey(tabId, it) }.values.forEach { groupEvents ->
                val base = groupFromEvents(tabId, groupEvents)
                val ids = base.eventIds.filter { id -> eventMap[id] != null && id !in assigned }
                if (ids.isEmpty()) return@forEach
                ids.forEach { assigned.add(it) }
                normalized.add(groupFromEvents(tabId, ids.mapNotNull { eventMap[it] }))
            }
        }

        return normalized.sortedWith(
            compareByDescending<CollectionGroup> { it.time }
                .thenByDescending { it.eventIds.size },
        )
    }

    // ---- Small deterministic helpers (adapted from DataManagerUtils) ----

    private fun firstText(values: List<String>, fallback: String): String {
        values.firstOrNull { it.isNotBlank() }?.let { return it }
        return fallback
    }

    private fun itemSource(item: GalleryPhotoItem): String = item.category.ifBlank { "图片" }

    private fun firstEntityName(item: GalleryPhotoItem): String =
        item.tags.firstOrNull { it.length >= 2 && it.length <= 12 } ?: ""

    private fun firstMeaningfulKeyword(item: GalleryPhotoItem): String =
        item.keywords.firstOrNull { it.length >= 2 && it.length <= 12 } ?: ""

    private fun dominantDomain(events: List<GalleryPhotoItem>): String =
        events.groupingBy { it.category }.eachCount().toList()
            .sortedWith(compareBy<Pair<String, Int>> { it.first == "other" }.thenByDescending { it.second })
            .firstOrNull()?.first?.ifBlank { "other" } ?: "other"

    private fun dominantSource(events: List<GalleryPhotoItem>): String =
        events.groupingBy { itemSource(it) }.eachCount().toList()
            .maxByOrNull { it.second }?.first ?: "图片"

    private fun isGalleryChatItem(item: GalleryPhotoItem): Boolean =
        item.category == CHAT || item.tags.any { it == "聊天" || it == "微信" }

    private fun displayDomain(domain: String): String =
        DISPLAY_DOMAINS[domain] ?: domain

    private fun summarizeText(text: String, maxLength: Int): String {
        if (text.length <= maxLength) return text
        return text.take(maxLength)
    }

    private fun stableGroupId(tabId: String, index: Int, eventIds: List<String>): String {
        val sig = eventIds.sorted().joinToString("|")
        return "grp_${tabId}_$index" + "_" + Integer.toHexString(sig.hashCode()).take(6)
    }
}
