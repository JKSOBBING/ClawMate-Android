package com.clawmate.app.datamanager

import com.clawmate.app.home.recommendation.HomeRecommendationBounds
import com.clawmate.app.home.recommendation.HomeRecommendationPlannerEvent

/**
 * Bounded mapper from typed [DailyLogEntry]s (produced by [DailyLogParser])
 * into the existing [HomeRecommendationPlannerEvent] shape used by the Phase-28
 * Home recommendation planner. This is the Phase-29 "bounded daily-log event
 * mapper" seam, mirroring Harmony's eventual daily-log → IndexedEvent step but
 * reduced to the pure, device-independent planner-input boundary.
 *
 * Safety contract (all enforced and unit-tested):
 *  - `eventId` is the **stable, deterministic source ref**
 *    (`YYYY-MM-DD/file.md#N`) — the same id a later Harmony-style index would
 *    use, so the planner's `source_event_ids` validation works over daily-log
 *    ids just like gallery ids.
 *  - `date` is the real `sourceDate`; `todo_text`/`summary`/`normalized_fact`
 *    carry only bounded **real** entry text (no fabrication).
 *  - `sources` carries the honest fixed label `DailyLog` (never transmits data).
 *  - `imagePaths` is always empty — neither a raw image path nor bytes nor a
 *    URI is ever emitted for a daily-log entry.
 *  - `workflow_metadata` values are flattened into `keywords` (bounded) so the
 *    model sees real but bounded provenance; nothing untyped is leaked.
 */
object DailyLogPlannerEventMapper {

    /** Honest source descriptor for daily-log-derived planner events. */
    const val SOURCE_DAILY_LOG: String = "DailyLog"

    /**
     * Map parsed daily-log entries to bounded planner events. Returns an empty
     * list when there is nothing usable to send (no entries, or every entry is
     * blank). Bounded by [HomeRecommendationBounds.MAX_EVENTS_PER_REQUEST] so a
     * very large daily-log tree can never flood the request.
     */
    fun toPlannerEvents(entries: List<DailyLogEntry>): List<HomeRecommendationPlannerEvent> =
        entries
            .asSequence()
            .mapNotNull { toPlannerEvent(it) }
            .take(HomeRecommendationBounds.MAX_EVENTS_PER_REQUEST)
            .toList()

    /** Build one bounded planner event from a single daily-log entry, or null
     * when the entry has no usable text (an empty/oversized entry is honestly
     * skipped, never fabricated). */
    fun toPlannerEvent(entry: DailyLogEntry): HomeRecommendationPlannerEvent? {
        val text = entry.text.trim().replace("content://", "content uri:")
        if (text.isEmpty()) return null
        val bounded = bounded(text, HomeRecommendationBounds.MAX_EVENT_NORMALIZED_FACT)
        return HomeRecommendationPlannerEvent(
            eventId = entry.sourceRef,
            date = entry.sourceDate,
            todoType = "",
            todoDeadline = "",
            todoText = bounded(text, HomeRecommendationBounds.MAX_EVENT_TODO_TEXT),
            summary = bounded(text, HomeRecommendationBounds.MAX_EVENT_SUMMARY),
            normalizedFact = bounded,
            sources = listOf(SOURCE_DAILY_LOG),
            domains = emptyList(), // a daily-log text carries no category; honesty over guessing
            keywords = entry.workflowMetadata.entries
                .map { (k, v) -> "$k=$v".take(64) }
                .take(HomeRecommendationBounds.MAX_EVENT_KEYWORDS),
            imagePaths = emptyList(),
        )
    }

    private fun bounded(value: String, maxLength: Int): String {
        val v = value.trim()
        if (v.length <= maxLength) return v
        return v.substring(0, maxOf(0, maxLength - 3)) + "..."
    }

    /**
     * Phase-39 — overlay the persisted batch event-enrichment fields onto already
     * built daily-log planner events. Each daily-log planner event carries
     * `eventId == entry.sourceRef`, so [enrichmentBySourceRef] (keyed by the same
     * real `sourceRef`, produced by [DailyLogEnrichmentResolver]) is looked up
     * strictly by that real id. Only the planner input fields (keywords / domains /
     * todo_type / todo_deadline / todo_text) that a matching enriched event
     * actually provides are copied; an event with no enrichment (no artifact /
     * unknown id / blank fields) is returned unchanged — old behaviour, and a
     * model-produced unknown id can never be injected into Home.
     */
    fun applyEnrichment(
        events: List<HomeRecommendationPlannerEvent>,
        enrichmentBySourceRef: Map<String, DailyLogPlannerEnrichment>,
    ): List<HomeRecommendationPlannerEvent> {
        if (enrichmentBySourceRef.isEmpty()) return events
        return events.map { event ->
            val enrichment = enrichmentBySourceRef[event.eventId] ?: return@map event
            if (enrichment.isEmpty) {
                event
            } else {
                event.copy(
                    keywords = enrichment.keywords,
                    domains = enrichment.domains,
                    todoType = enrichment.todoType,
                    todoDeadline = enrichment.todoDeadline,
                    todoText = enrichment.todoText,
                )
            }
        }
    }
}
