package com.clawmate.app.gallery.collection

import com.clawmate.app.datamanager.LocalConsolidationTypes.IndexedEvent

/**
 * Phase-44 **deterministic local collector** that groups the real indexed
 * daily-log events into stable, clickable collections.
 *
 * This is the Android migration of the Harmony
 * `datamanager/CollectionGroupingService.ets` *heuristic* grouping path, but it
 * consumes the Android-native [IndexedEvent] type produced by
 * [com.clawmate.app.datamanager.IndexService] / read via
 * [com.clawmate.app.datamanager.DailyLogLocalIndex]. Unlike the Harmony model
 * planner (which may send events to a cloud model), this service is pure and
 * offline: it derives stable group ids, titles, sources and counts only from the
 * real `event_id` / `event_date` / `summary` / `normalized_fact` / `sources` /
 * `domains` / `keywords` fields. It never claims to be model-graded — callers
 * surface `local`.
 *
 * Guarantees:
 *  - deterministic: same events ⇒ same group ids / titles / counts (sorted
 *    membership); stable across runs and input order;
 *  - low-memory: single-pass bucketing over bounded lists, no whole-index copy;
 *  - honest: no fabricated events, no URI / raw path / token ever leaks into a
 *    title or id; empty / blank / unknown fields are tolerated (an event with no
 *    usable key still lands in a stable fallback bucket).
 */
object IndexedEventCollectionService {

    /** The set of scene ids offered as clickable summary tabs (Harmony subset). */
    private val SUPPORTED_SCENE_IDS = intArrayOf(0, 1, 2, 3)

    /** Internal bucket key delimiter (must not appear in normalized free text). */
    private const val KEY_SEP = '\u001f'

    /**
     * The ordered list of clickable tabs rendered in the 汇总 screen. Only real,
     * known workflows scenes are offered; unknown scene ids fall back to "生活".
     */
    fun tabs(): List<IndexedCollectionTab> = listOf(
        IndexedCollectionTab(sceneId = 0, tabId = "shopping", label = "购物"),
        IndexedCollectionTab(sceneId = 1, tabId = "takeout", label = "外卖"),
        IndexedCollectionTab(sceneId = 2, tabId = "entertainment", label = "娱乐"),
        IndexedCollectionTab(sceneId = 3, tabId = "chat", label = "聊天"),
    )

    /** Find a tab by scene id; falls back to the first tab. */
    fun tabForScene(sceneId: Int): IndexedCollectionTab {
        val requested = normalizeSceneId(sceneId)
        return tabs().firstOrNull { it.sceneId == requested } ?: tabs().first()
    }

    /**
     * Group [events] for the given scene. Only events whose normalized
     * `workflow_scene_ids` intersect [sceneId] are considered (unknown/empty
     * scene ids are treated as scene 0 for the general summary). Returns groups
     * sorted by newest date then size desc. Empty / unmatched input yields an
     * empty list (never a fabricated group).
     */
    fun group(events: List<IndexedEvent>, sceneId: Int): List<IndexedCollectionGroup> {
        val normalizedScene = normalizeSceneId(sceneId)
        val matched = events.filter { matchesScene(it, normalizedScene) }
        if (matched.isEmpty()) return emptyList()

        val buckets = LinkedHashMap<String, MutableList<IndexedEvent>>()
        for (event in matched) {
            val key = bucketKey(event, normalizedScene)
            buckets.getOrPut(key) { mutableListOf() }.add(event)
        }

        val groups = ArrayList<IndexedCollectionGroup>(buckets.size)
        for ((_, bucket) in buckets) {
            val sorted = bucket.sortedWith(
                compareByDescending<IndexedEvent> { it.eventDate }
                    .thenByDescending { it.eventId },
            )
            val domain = dominantDomain(sorted)
            val source = dominantSource(sorted)
            val title = deriveTitle(normalizedScene, sorted, source, domain)
            val ids = sorted.map { it.eventId }
            groups.add(
                IndexedCollectionGroup(
                    groupId = stableGroupId(normalizedScene, ids),
                    title = bound(title, 28),
                    source = source,
                    time = sorted.first().eventDate,
                    meta = "${ids.size} 条",
                    status = "已整理",
                    domain = domain,
                    eventIds = ids,
                    local = true,
                ),
            )
        }
        return groups.sortedWith(
            compareByDescending<IndexedCollectionGroup> { it.time }
                .thenByDescending { it.eventIds.size },
        )
    }

    /**
     * Resolve real events for an explicit, known member id list (from a group's
     * `eventIds`) out of [events]. Unknown ids are silently dropped; the result
     * is the precise set of genuinely matching real events (date+id ordered). The
     * group carries its own [IndexedCollectionGroup.eventIds], so the caller
     * passes exactly those ids — the detail screen renders only real events.
     */
    fun eventsForGroup(memberIds: List<String>, events: List<IndexedEvent>): List<IndexedEvent> {
        if (memberIds.isEmpty()) return emptyList()
        val wanted = memberIds.toHashSet()
        return events.asSequence()
            .filter { it.eventId in wanted }
            .sortedWith(
                compareByDescending<IndexedEvent> { it.eventDate }
                    .thenByDescending { it.eventId },
            )
            .toList()
    }

    /** Map an [IndexedEvent] to the UI-facing detail representation (real data). */
    fun toDetailEvent(event: IndexedEvent): IndexedCollectionEvent = IndexedCollectionEvent(
        eventId = event.eventId,
        date = event.eventDate,
        summary = bound(normalize(event.summary), 160),
        normalizedFact = bound(normalize(event.normalizedFact), 160),
        source = dominantSource(listOf(event)),
    )

    // ---- private ----

    private fun matchesScene(event: IndexedEvent, sceneId: Int): Boolean {
        val sceneIds = normalizeSceneIds(event.workflowSceneIds)
        if (sceneIds.isEmpty()) {
            // Unknown scene metadata is conservatively placed in the general tab.
            return sceneId == 0
        }
        return sceneId in sceneIds
    }

    private fun bucketKey(event: IndexedEvent, sceneId: Int): String {
        val source = firstText(event.sources, sourceDisplayName(event.sourceStateFile)).ifEmpty { "本地日志" }
        val domain = firstText(event.domains, "other")
        val entity = firstEntityName(event)
        val keyword = firstKeyword(event)
        return when (tabForScene(sceneId).tabId) {
            "chat" -> listOf(entity.ifEmpty { source }, keyword.ifEmpty { "聊天" }).joinToString(KEY_SEP.toString())
            "takeout" -> listOf(entity.ifEmpty { source.ifEmpty { displayDomain(domain) } }, domain).joinToString(KEY_SEP.toString())
            "entertainment" -> listOf(keyword.ifEmpty { source }, domain).joinToString(KEY_SEP.toString())
            else -> listOf(source, domain, keyword).joinToString(KEY_SEP.toString())
        }
    }

    private fun deriveTitle(sceneId: Int, events: List<IndexedEvent>, source: String, domain: String): String {
        val entity = events.firstOrNull()?.let { firstEntityName(it) } ?: ""
        val keyword = events.firstOrNull()?.let { firstKeyword(it) } ?: ""
        return when (tabForScene(sceneId).tabId) {
            "chat" -> when {
                keyword.isNotEmpty() -> "${keyword}沟通"
                entity.isNotEmpty() -> "与${entity}的对话"
                else -> "聊天整理"
            }
            "takeout" -> when {
                entity.isNotEmpty() -> "${entity}相关订单"
                keyword.isNotEmpty() -> "${keyword}记录"
                else -> "${displayDomain(domain)}类订单"
            }
            "entertainment" -> when {
                keyword.isNotEmpty() -> "${keyword}记录"
                source.isNotEmpty() -> "${source}娱乐记录"
                else -> "娱乐记录"
            }
            else -> when {
                keyword.isNotEmpty() -> "${keyword}记录"
                entity.isNotEmpty() -> "${entity}相关记录"
                else -> "相关记录"
            }
        }
    }

    /**
     * Stable group id embedding the sorted, joined member ids so detail lookups
     * (`eventsForGroup`) recover membership exactly and ids are reproducible.
     */
    private fun stableGroupId(sceneId: Int, ids: List<String>): String {
        val sorted = ids.distinct().sorted()
        val sig = sorted.joinToString(KEY_SEP.toString())
        return "idx_${tabForScene(sceneId).tabId}_" + Integer.toHexString(sig.hashCode()).take(8)
    }

    private fun normalizeSceneId(sceneId: Int): Int = sceneId.takeIf { it in SUPPORTED_SCENE_IDS } ?: 0

    private fun normalizeSceneIds(sceneIds: List<Int>): List<Int> = sceneIds
        .filter { it in SUPPORTED_SCENE_IDS }
        .distinct()
        .sorted()

    private fun dominantSource(events: List<IndexedEvent>): String {
        val counts = HashMap<String, Int>()
        for (event in events) {
            val s = firstText(event.sources, sourceDisplayName(event.sourceStateFile)).ifEmpty { "本地日志" }
            counts[s] = (counts[s] ?: 0) + 1
        }
        return counts.maxByOrNull { it.value }?.key ?: "本地日志"
    }

    private fun dominantDomain(events: List<IndexedEvent>): String {
        val counts = HashMap<String, Int>()
        for (event in events) {
            val d = firstText(event.domains, "other").ifEmpty { "other" }
            counts[d] = (counts[d] ?: 0) + 1
        }
        return counts.maxByOrNull { it.value }?.key ?: "other"
    }

    private fun firstText(values: List<String>, fallback: String): String =
        values.firstOrNull { it.isNotBlank() }?.trim() ?: fallback

    private fun sourceDisplayName(sourceStateFile: String): String =
        normalize(sourceStateFile.substringAfterLast('/')).ifBlank { "本地日志" }

    private fun firstEntityName(event: IndexedEvent): String {
        val m = event.entityMentions.firstOrNull { normalize(it.name).isNotEmpty() }?.name
        if (m?.isNotBlank() == true) return bound(normalize(m), 16)
        return ""
    }

    private fun firstKeyword(event: IndexedEvent): String {
        val blocked = setOf("微信", "通知", "视频", "记录", "页面", "订单", "聊天", "截图", "图片", "消息")
        val keyword = event.keywords.firstOrNull {
            normalize(it).length in 2..12 && normalize(it) !in blocked
        }
        if (!keyword.isNullOrBlank()) return bound(normalize(keyword), 16)
        return firstTopicFromText("${event.summary} ${event.normalizedFact}")
    }

    private fun firstTopicFromText(value: String): String {
        val text = normalize(value)
            .replace(Regex("\\[[^]]+]"), "")
            .split(Regex("[，。；;,]"))
            .firstOrNull { it.length in 2..16 }
            ?: return ""
        return text
    }

    private fun displayDomain(domain: String): String = when (domain) {
        "eat" -> "吃"
        "wear" -> "穿"
        "live" -> "住"
        "travel" -> "行"
        else -> domain
    }

    private fun normalize(value: String): String = value.trim()

    private fun bound(value: String, max: Int): String = if (value.length <= max) value else value.take(max) + "…"
}
