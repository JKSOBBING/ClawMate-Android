package com.clawmate.app.datamanager

import com.clawmate.app.datamanager.LocalConsolidationTypes.QueryOptions
import com.clawmate.app.datamanager.LocalConsolidationTypes.QueryResult
import com.clawmate.app.datamanager.LocalConsolidationTypes.IndexedEvent
import com.clawmate.app.datamanager.LocalConsolidationTypes.EventPartition
import java.io.File

/**
 * Bounded, **read-only** local index over the persisted event partitions built by
 * [IndexService]. Querying by date / month / source / keyword reads only the
 * app-private `indexes/events/{month}.json` partitions (never creating them, never
 * touching the network or external storage). Missing/corrupt partitions recover to
 * an empty result rather than throwing.
 *
 * This mirrors the relevant query surface of Harmony `IndexService.query`, reduced
 * to the dimensions the local (no-cloud) index persists.
 */
class DailyLogLocalIndex(
    private val baseDir: File,
    private val store: AtomicJsonStore = AtomicJsonStore(),
) {

    /**
     * Phase-38 — persisted batch event-enrichment overlay: once an explicit
     * enrichment action has written the versioned artifact, event-query readers
     * see the persisted keywords/domains/entities/todo/enrichment-method fields
     * (looked up by real event id). Absent / corrupt artifact ⇒ no overlay (old
     * behaviour).
     */
    private val enrichmentStore: BatchEventEnrichmentStore = BatchEventEnrichmentStore(baseDir)

    /** Enumerate known partitions from the partition-index (falls back to month dir). */
    private fun partitionNames(): List<String> {
        val indexFile = DataManagerPaths.partitionIndex(baseDir)
        val index = store.readJson(indexFile) { LocalConsolidationCodec.decodePartitionIndex(it) }
        return if (index != null && index.partitionFiles.isNotEmpty()) index.partitionFiles.sorted()
        else listMonthDirPartitionNames()
    }

    private fun listMonthDirPartitionNames(): List<String> {
        val dir = DataManagerPaths.eventsDir(baseDir)
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles()
            ?.filter { it.name.endsWith(".json") && it.name != DataManagerPaths.PARTITION_INDEX_FILE_NAME }
            ?.map { it.name }
            ?.sorted() ?: emptyList()
    }

    private fun loadEvents(): List<IndexedEvent> {
        val result = ArrayList<IndexedEvent>()
        for (name in partitionNames()) {
            val file = File(DataManagerPaths.eventsDir(baseDir), name)
            val partition = store.readJson(file) { LocalConsolidationCodec.decodePartition(it) }
            if (partition != null && partition.events.isNotEmpty()) result += partition.events
        }
        return result
    }

    /**
     * Query events by optional date / month / source / keyword. Returns a bounded,
     * newest-first [QueryResult]. Empty options with no persisted index produce an
     * empty result (no auto-creation).
     */
    fun query(options: QueryOptions): QueryResult {
        val events = loadEvents()
        val matched = events.filter { event ->
            (options.date.isBlank() || event.eventDate == options.date) &&
                (options.month.isBlank() || event.eventDate.startsWith(options.month)) &&
                (options.source.isBlank() || event.sourceRefs.any { it.contains(options.source) }) &&
                (options.keyword.isBlank() || matchesKeyword(event, options.keyword))
        }.sortedWith(
            compareByDescending<IndexedEvent> { it.eventDate }
                .thenByDescending { it.eventId },
        )
        // Phase-38 — overlay persisted batch event-enrichment fields (absent →
        // events unchanged = old behaviour).
        val overlaid = BatchEventEnrichmentOverlay.overlay(matched, enrichmentStore.load())
        val limit = if (options.limit > 0) options.limit else DEFAULT_QUERY_LIMIT
        val selected = overlaid.take(limit)
        return QueryResult(
            totalEvents = overlaid.size,
            events = selected,
            eventsTruncated = overlaid.size > selected.size,
        )
    }

    private fun matchesKeyword(event: IndexedEvent, keyword: String): Boolean {
        val field = ConsolidationText.normalizeFreeText(
            "${event.summary} ${event.normalizedFact} ${event.tags.joinToString(" ")} " +
                "${event.keywords.joinToString(" ")} ${event.domains.joinToString(" ")} " +
                "${event.sourceRefs.joinToString(" ")}",
        ).lowercase()
        return field.contains(keyword.trim().lowercase())
    }

    companion object {
        const val DEFAULT_QUERY_LIMIT: Int = 200
    }
}
