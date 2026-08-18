package com.clawmate.app.datamanager

import com.clawmate.app.home.recommendation.HomeRecommendationBounds

/**
 * Phase-39 **read-side bridge** that lets the Home recommendation planner consume
 * the Phase-38 persisted batch event-enrichment fields (keywords / domains /
 * entities / todo) for real daily-log entries.
 *
 * Daily-log chapters are read by Home as raw [DailyLogEntry]s (keyed by
 * `sourceRef` = `YYYY-MM-DD/file.md#N`), while the Phase-38 artifact
 * ([BatchEventEnrichmentStore]) is keyed by the consolidated **indexed event id**
 * (`${relativeStatePath}#${recordId}`). The only existing read seam that maps a
 * daily-log `sourceRef` onto an indexed event and already carries the enriched
 * keywords/domains/todo fields is [ProfileIndexBuilder.loadEvents], which applies
 * [BatchEventEnrichmentOverlay] over the persisted artifact. This resolver reuses
 * that boundary — it does **not** read a second database, and it never triggers a
 * model.
 *
 * Contract:
 *  - An entry is enriched **only** when a real indexed event lists its `sourceRef`
 *    in `sourceRefs` **and** that event carries persisted non-blank enriched
 *    fields. With no artifact / no matching event / blank enrich fields the entry
 *    maps to nothing (old behaviour).
 *  - The source event's identity / date / summary / scene ids are never rewritten;
 *    only the bounded planner input fields are produced.
 *  - Lookup is strictly by the real persisted `event_id`-derived fields that the
 *    existing overlay already applied to the indexed event; model output with an
 *    unknown id can never reach Home.
 */
data class DailyLogPlannerEnrichment(
    val keywords: List<String> = emptyList(),
    val domains: List<String> = emptyList(),
    val todoType: String = "",
    val todoDeadline: String = "",
    val todoText: String = "",
) {
    /** Whether any planner-relevant enrich field is populated (non-blank). */
    val isEmpty: Boolean
        get() = keywords.isEmpty() && domains.isEmpty() &&
            todoType.isEmpty() && todoDeadline.isEmpty() && todoText.isEmpty()
}

/**
 * Loads the persisted + overlaid indexed events (through the existing
 * [ProfileIndexBuilder.loadEvents] read boundary) and resolves the bounded
 * enriched planner fields to use for a set of daily-log entries, keyed by
 * `sourceRef`.
 */
class DailyLogEnrichmentResolver(
    private val baseDir: java.io.File,
) {

    /**
     * Resolve the enriched planner fields for [entries], keyed by each real
     * `sourceRef`. A matching event with only blank enrich fields yields nothing
     * for that entry (old behaviour preserved).
     */
    fun resolveBySourceRef(
        entries: List<DailyLogEntry>,
    ): Map<String, DailyLogPlannerEnrichment> =
        resolveBySourceRef(entries, loadOverlaidEvents())

    /**
     * Pure resolution over an already-loaded, already-overlaid list of indexed
     * events (test seam). Returns a bounded map keyed by the real daily-log
     * `sourceRef`.
     */
    fun resolveBySourceRef(
        entries: List<DailyLogEntry>,
        events: List<LocalConsolidationTypes.IndexedEvent>,
    ): Map<String, DailyLogPlannerEnrichment> {
        if (entries.isEmpty()) return emptyMap()
        // Index the real source-ref -> overlaid indexed event (first match wins).
        val bySourceRef = HashMap<String, LocalConsolidationTypes.IndexedEvent>()
        for (event in events) {
            for (ref in event.sourceRefs) {
                if (!bySourceRef.containsKey(ref)) bySourceRef[ref] = event
            }
        }
        val out = LinkedHashMap<String, DailyLogPlannerEnrichment>()
        for (entry in entries) {
            val enrichment = bySourceRef[entry.sourceRef]?.let { toPlannerEnrichment(it) }
                ?: continue
            if (!enrichment.isEmpty) out[entry.sourceRef] = enrichment
        }
        return out
    }

    /** Bounded, honest projection of the enriched fields onto the planner input. */
    private fun toPlannerEnrichment(
        event: LocalConsolidationTypes.IndexedEvent,
    ): DailyLogPlannerEnrichment = DailyLogPlannerEnrichment(
        keywords = ConsolidationText.stableUnique(event.keywords)
            .take(HomeRecommendationBounds.MAX_EVENT_KEYWORDS),
        domains = ConsolidationText.stableUnique(event.domains)
            .take(HomeRecommendationBounds.MAX_EVENT_DOMAINS),
        todoType = bounded(event.todoType, MAX_TODO_FIELD),
        todoDeadline = bounded(event.todoDeadline, MAX_TODO_FIELD),
        todoText = bounded(event.todoText, HomeRecommendationBounds.MAX_EVENT_TODO_TEXT),
    )

    /** Existing read boundary: [ProfileIndexBuilder.loadEvents] already overlays
     * the persisted enrichment artifact (absent artifact -> unchanged events). */
    private fun loadOverlaidEvents(): List<LocalConsolidationTypes.IndexedEvent> =
        runCatching { ProfileIndexBuilder(baseDir).loadEvents() }.getOrDefault(emptyList())

    private fun bounded(value: String, max: Int): String {
        val v = value.trim()
        return if (v.length <= max) v else v.substring(0, maxOf(0, max - 3)) + "..."
    }

    private companion object {
        /** Cap on a single todo-type / deadline token (real, bounded). */
        const val MAX_TODO_FIELD: Int = 64
    }
}
