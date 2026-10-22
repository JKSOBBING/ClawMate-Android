package com.clawmate.app.datamanager

import com.clawmate.app.datamanager.LocalConsolidationTypes.QueryOptions
import com.clawmate.app.datamanager.LocalConsolidationTypes.QueryResult
import com.clawmate.app.datamanager.LocalConsolidationTypes.IndexedEvent
import com.clawmate.app.datamanager.LocalConsolidationTypes.EventPartition
import com.clawmate.app.datamanager.LocalConsolidationTypes.EventPartitionIndex
import java.io.File
import java.util.Locale
import kotlinx.serialization.json.Json

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
    // The general codec recovers corrupt JSON to an empty object. Queries need
    // to distinguish that failure from an intentionally built empty index.
    private val indexJson = Json { ignoreUnknownKeys = true }

    private fun readPartitionIndex(): EventPartitionIndex? =
        store.readJson(DataManagerPaths.partitionIndex(baseDir)) {
            indexJson.decodeFromString(EventPartitionIndex.serializer(), it)
        }

    private fun readPartition(file: File): EventPartition? = store.readJson(file) {
        indexJson.decodeFromString(EventPartition.serializer(), it)
    }

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
        val index = readPartitionIndex()
        return if (index != null) index.partitionFiles.filter(::safePartitionName).sorted()
        else listMonthDirPartitionNames()
    }

    private fun listMonthDirPartitionNames(): List<String> {
        val dir = DataManagerPaths.eventsDir(baseDir)
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles()
            ?.filter { safePartitionName(it.name) }
            ?.map { it.name }
            ?.sorted() ?: emptyList()
    }

    /** Distinguishes an explicitly built empty index from absent/corrupt data. */
    fun hasBuiltIndex(): Boolean {
        val index = readPartitionIndex()
        val names = index?.partitionFiles ?: listMonthDirPartitionNames()
        if (index == null && names.isEmpty()) return false
        return names.all { name ->
            safePartitionName(name) && readPartition(File(DataManagerPaths.eventsDir(baseDir), name)) != null
        }
    }

    private fun safePartitionName(name: String): Boolean =
        Regex("\\d{4}-(0[1-9]|1[0-2])\\.json").matches(name)

    private fun loadEvents(): List<IndexedEvent> {
        val result = ArrayList<IndexedEvent>()
        for (name in partitionNames()) {
            val file = File(DataManagerPaths.eventsDir(baseDir), name)
            val partition = readPartition(file)
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
        // Enriched keywords/entities/deadlines must participate in filtering,
        // not merely be added to the already-selected results.
        val events = BatchEventEnrichmentOverlay.overlay(loadEvents(), enrichmentStore.load())
        val timeUsed = listOf(options.date, options.month, options.startDate, options.endDate).any(String::isNotBlank)
        val includeDeadline = options.includeTodoDeadlineInTimeRange && timeUsed
        val matched = events.filter { event ->
            (matchesTime(event.eventDate, options) || (includeDeadline &&
                ConsolidationText.isIsoDate(event.todoDeadline) && matchesTime(event.todoDeadline, options))) &&
                (options.source.isBlank() || event.sourceRefs.any { it.contains(options.source) }) &&
                (options.keyword.isBlank() || matchesKeyword(event, options.keyword)) &&
                (options.keywords.isEmpty() || if (options.keywordOperator == "or")
                    options.keywords.any { matchesKeyword(event, it) }
                else options.keywords.all { matchesKeyword(event, it) }) &&
                (options.domains.isEmpty() || event.domains.any { it in options.domains }) &&
                (options.entities.isEmpty() || options.entities.any { selector -> matchesEntity(event, selector) })
        }.sortedWith(
            compareByDescending<IndexedEvent> {
                if (includeDeadline && ConsolidationText.isIsoDate(it.todoDeadline)) it.todoDeadline else it.eventDate
            }
                .thenByDescending { it.eventId },
        )
        // Phase-38 — overlay persisted batch event-enrichment fields (absent →
        // events unchanged = old behaviour).
        val limit = if (options.limit > 0) options.limit else DEFAULT_QUERY_LIMIT
        val selected = matched.take(limit)
        return QueryResult(
            totalEvents = matched.size,
            events = selected,
            eventsTruncated = matched.size > selected.size,
        )
    }

    private fun matchesTime(day: String, options: QueryOptions): Boolean =
        (options.date.isBlank() || day == options.date) &&
            (options.month.isBlank() || day.startsWith(options.month)) &&
            (options.startDate.isBlank() || day >= options.startDate) &&
            (options.endDate.isBlank() || day <= options.endDate)

    private fun matchesEntity(event: IndexedEvent, selector: String): Boolean {
        val type = selector.substringBefore(':').lowercase(Locale.ROOT)
        val name = selector.substringAfter(':', "").trim().lowercase(Locale.ROOT)
        return name.isNotEmpty() && event.entityMentions.any { mention ->
            mention.type.lowercase(Locale.ROOT) == type &&
                (listOf(mention.name) + mention.aliases).any { it.trim().lowercase(Locale.ROOT) == name }
        }
    }

    private fun matchesKeyword(event: IndexedEvent, keyword: String): Boolean {
        val field = ConsolidationText.normalizeFreeText(
            "${event.summary} ${event.normalizedFact} ${event.tags.joinToString(" ")} " +
                "${event.keywords.joinToString(" ")} ${event.domains.joinToString(" ")} " +
                "${event.sourceRefs.joinToString(" ")} ${event.sources.joinToString(" ")} " +
                "${event.entityMentions.flatMap { listOf(it.name) + it.aliases }.joinToString(" ")} " +
                "${event.todoText}",
        ).lowercase(Locale.ROOT)
        return field.contains(ConsolidationText.normalizeFreeText(keyword).lowercase(Locale.ROOT))
    }

    companion object {
        const val DEFAULT_QUERY_LIMIT: Int = 200
    }
}
