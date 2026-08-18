package com.clawmate.app.datamanager

import com.clawmate.app.datamanager.LocalConsolidationTypes.IndexedEvent

/**
 * Phase-38 **read-side overlay** that applies the persisted batch event-enrichment
 * artifact onto a set of indexed events, so an existing event-query / profile /
 * Home-input read boundary can actually consume the persisted keywords / domains /
 * entities / todo / enrichment-method fields after an explicit enrichment action.
 *
 * Every field is looked up strictly by the **real** `event_id` in the persisted
 * artifact ([BatchEventEnrichmentStore]); an unknown / absent / blank-field id
 * leaves the event untouched (old behaviour). When the artifact is missing or
 * empty, every event is returned unchanged — so the default, un-enriched
 * behaviour is preserved. The source `IndexedEvent` values are copied with the
 * enrich fields overlaid; the event's identity / date / summary / scene ids are
 * never changed.
 */
object BatchEventEnrichmentOverlay {

    /**
     * Overlay [fields] (loaded from the persisted artifact) onto [events]. Events
     * whose id has no persisted enrich fields are returned unchanged.
     */
    fun overlay(
        events: List<IndexedEvent>,
        fields: Map<String, PersistedEnrichedFields>,
    ): List<IndexedEvent> {
        if (fields.isEmpty()) return events
        return events.map { event ->
            val f = fields[event.eventId] ?: return@map event
            if (f.entities.isEmpty() && f.keywords.isEmpty() && f.domains.isEmpty() &&
                f.todoType.isEmpty() && f.enrichmentMethod.isEmpty()
            ) {
                event
            } else {
                event.copy(
                    keywords = ConsolidationText.stableUnique(f.keywords),
                    domains = ConsolidationText.stableUnique(f.domains),
                    entityMentions = f.entities,
                    todoType = f.todoType,
                    todoDeadline = f.todoDeadline,
                    todoText = f.todoText,
                    todoAgentTask = f.todoAgentTask,
                    enrichmentMethod = f.enrichmentMethod,
                )
            }
        }
    }
}
