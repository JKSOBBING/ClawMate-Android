package com.clawmate.app.datamanager

import com.clawmate.app.datamanager.LocalConsolidationTypes.ConsolidatedState
import com.clawmate.app.datamanager.LocalConsolidationTypes.EventPartition
import com.clawmate.app.datamanager.LocalConsolidationTypes.EventPartitionIndex
import com.clawmate.app.datamanager.LocalConsolidationTypes.IndexedEvent
import com.clawmate.app.datamanager.LocalConsolidationTypes.IndexManifest
import com.clawmate.app.datamanager.LocalConsolidationTypes.SourceManifestEntry
import com.clawmate.app.datamanager.LocalConsolidationTypes.TemporalDocument
import java.io.File

/**
 * Rebuilds and persists the **local** index artifacts from the consolidated
 * states produced by [ConsolidationRepository]:
 *
 *  - month event partitions under `indexes/events/<month>.json`;
 *  - a partition-index listing the partition files;
 *  - a bounded event manifest (source sha + per-event input hashes);
 *  - temporal month docs for bounded query-by-date.
 *
 * Every event id is stable: `${relativeStatePath}#${recordId}` (mirrors Harmony
 * `IndexService.baseEventsFromState`). Building the index is a deterministic,
 * network-free, local-only operation that reads only the state artifacts it (or a
 * prior run) wrote under the app-private base dir.
 */
class IndexService(
    private val baseDir: File,
    private val store: AtomicJsonStore = AtomicJsonStore(),
) {

    /** Rebuild all index artifacts from the given consolidated states. Never throws. */
    fun rebuild(states: List<ConsolidatedState>): IndexBuildResult {
        // Empty / no-consolidation state: do not create any index artifact.
        if (states.isEmpty()) return emptyResult()

        val events = ArrayList<IndexedEvent>()
        val sourceIndex = LinkedHashMap<String, MappedSource>()

        for (state in states) {
            val month = state.metadata.month
            if (month.isBlank()) continue
            val stateFile = DataManagerPaths.outputState(baseDir, month, state.metadata.sourceFiles.firstOrNull() ?: return emptyResult())
            val relative = DataManagerPaths.stateRelativePath(baseDir, stateFile)
            val stateEvents = state.records.mapNotNull { record ->
                val eventDate = record.firstSeenDate.takeIf { ConsolidationText.isIsoDate(it) } ?: return@mapNotNull null
                val lastSeen = record.lastSeenDate.takeIf { ConsolidationText.isIsoDate(it) } ?: eventDate
                IndexedEvent(
                    eventId = "$relative#${record.recordId}",
                    sourceStateFile = relative,
                    recordId = record.recordId,
                    eventDate = eventDate,
                    lastSeenDate = lastSeen,
                    summary = ConsolidationText.normalizeFreeText(record.summary.ifEmpty { record.normalizedFact }),
                    normalizedFact = ConsolidationText.normalizeFreeText(record.normalizedFact),
                    tags = ConsolidationText.stableUnique(record.tags),
                    sourceRefs = ConsolidationText.stableUnique(record.sourceRefs),
                    sources = eventSourceNames(record.sourceRefs),
                    workflowSceneIds = SceneEntityNormalizer.normalizeSceneIds(record.workflowSceneIds),
                )
            }
            events += stateEvents
            sourceIndex.putIfAbsent(relative, MappedSource(stateFile, stateEvents))
        }

        persistPartitions(events)
        persistTemporal(events)
        persistManifest(sourceIndex)
        return IndexBuildResult(
            eventCount = events.size,
            sourceFiles = sourceIndex.size,
            generatedAt = now(),
        )
    }

    private fun persistPartitions(events: List<IndexedEvent>) {
        val byMonth = events.groupBy { it.eventDate.take(7) }
        val partitionFiles = ArrayList<String>()
        for ((month, monthEvents) in byMonth.toSortedMap()) {
            val partitionFile = DataManagerPaths.eventPartition(baseDir, month)
            val written = store.writeJson(
                partitionFile,
                EventPartition(month = month, events = monthEvents.sortedBy { it.eventId }),
            ) { LocalConsolidationCodec.encodePartition(it) }
            if (written != null) partitionFiles.add("$month.json")
        }
        store.writeJson(
            DataManagerPaths.partitionIndex(baseDir),
            EventPartitionIndex(generatedAt = now(), partitionFiles = partitionFiles.sorted()),
        ) { LocalConsolidationCodec.encodePartitionIndex(it) }
    }

    private fun persistTemporal(events: List<IndexedEvent>) {
        val byMonth = events.groupBy { it.eventDate.take(7) }
        for ((month, monthEvents) in byMonth) {
            store.writeJson(
                DataManagerPaths.temporalDoc(baseDir, month),
                TemporalDocument(month = month, eventIds = monthEvents.map { it.eventId }.sorted()),
            ) { LocalConsolidationCodec.encodeTemporal(it) }
        }
    }

    private fun persistManifest(sourceIndex: Map<String, MappedSource>) {
        val sources = LinkedHashMap<String, SourceManifestEntry>()
        for ((relative, mapped) in sourceIndex) {
            val inputHashes = LinkedHashMap<String, String>()
            for (event in mapped.events) inputHashes[event.eventId] = eventInputHash(event)
            sources[relative] = SourceManifestEntry(
                sha256 = rawFileSha256(mapped.stateFile),
                eventIds = mapped.events.map { it.eventId }.sorted(),
                eventInputHashes = inputHashes,
            )
        }
        store.writeJson(
            DataManagerPaths.manifest(baseDir),
            IndexManifest(generatedAt = now(), eventCount = sourceIndex.values.sumOf { it.events.size }, sourceFiles = sourceIndex.size, sources = sources),
        ) { LocalConsolidationCodec.encodeManifest(it) }
    }

    private fun eventSourceNames(sourceRefs: List<String>): List<String> {
        val names = ArrayList<String>()
        val seen = HashSet<String>()
        for (ref in sourceRefs) {
            val file = ref.substringAfter('/', "").substringBefore('#')
            if (file.isNotEmpty() && seen.add(file)) names.add(file)
        }
        return names.ifEmpty { listOf("DailyLog") }
    }

    private fun eventInputHash(event: IndexedEvent): String =
        sha256("${event.eventDate}|${event.normalizedFact}|${event.sourceRefs.joinToString("\u001f")}")

    private fun rawFileSha256(file: File): String =
        runCatching { if (file.isFile) sha256(file.readText()) else "" }.getOrDefault("")

    private fun sha256(value: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun now(): String =
        java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC).toInstant().toString()

    private fun emptyResult() = IndexBuildResult(eventCount = 0, sourceFiles = 0, generatedAt = now())

    private data class MappedSource(val stateFile: File, val events: List<IndexedEvent>)

    /** Honest, countable result of an index rebuild. */
    data class IndexBuildResult(
        val eventCount: Int,
        val sourceFiles: Int,
        val generatedAt: String,
    )
}
