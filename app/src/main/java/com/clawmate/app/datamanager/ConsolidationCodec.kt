package com.clawmate.app.datamanager

import com.clawmate.app.datamanager.LocalConsolidationTypes.ConsolidatedState
import com.clawmate.app.datamanager.LocalConsolidationTypes.IndexedEvent
import com.clawmate.app.datamanager.LocalConsolidationTypes.EventPartition
import com.clawmate.app.datamanager.LocalConsolidationTypes.EventPartitionIndex
import com.clawmate.app.datamanager.LocalConsolidationTypes.IndexManifest
import com.clawmate.app.datamanager.LocalConsolidationTypes.TemporalDocument
import com.clawmate.app.datamanager.LocalConsolidationTypes.StateIndex
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Deterministic JSON serde for the Phase-30 local artifacts. Decoding is tolerant:
 * a missing/unknown/coerced field recovers to its default (defensive) value rather
 * than throwing, so a partial or corrupt on-disk artifact fails open to an empty
 * state — the reader then re-consolidates from the real daily-log text.
 */
object LocalConsolidationCodec {

    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /** Encode a consolidated state (schema + version embedded in the artifact). */
    fun encodeState(state: ConsolidatedState): String = json.encodeToString(ConsolidatedState.serializer(), state)

    /** Decode a consolidated state; returns an empty state when corrupt/incompatible. */
    fun decodeState(text: String): ConsolidatedState = runCatching {
        json.decodeFromString(ConsolidatedState.serializer(), text)
    }.getOrElse { ConsolidatedState() }

    fun encodePartition(partition: EventPartition): String =
        json.encodeToString(EventPartition.serializer(), partition)

    fun decodePartition(text: String): EventPartition = runCatching {
        json.decodeFromString(EventPartition.serializer(), text)
    }.getOrElse { EventPartition() }

    fun encodePartitionIndex(index: EventPartitionIndex): String =
        json.encodeToString(EventPartitionIndex.serializer(), index)

    fun decodePartitionIndex(text: String): EventPartitionIndex = runCatching {
        json.decodeFromString(EventPartitionIndex.serializer(), text)
    }.getOrElse { EventPartitionIndex() }

    fun encodeManifest(manifest: IndexManifest): String =
        json.encodeToString(IndexManifest.serializer(), manifest)

    fun decodeManifest(text: String): IndexManifest = runCatching {
        json.decodeFromString(IndexManifest.serializer(), text)
    }.getOrElse { IndexManifest() }

    fun encodeTemporal(document: TemporalDocument): String =
        json.encodeToString(TemporalDocument.serializer(), document)

    fun decodeTemporal(text: String): TemporalDocument = runCatching {
        json.decodeFromString(TemporalDocument.serializer(), text)
    }.getOrElse { TemporalDocument() }

    fun encodeEvents(events: List<IndexedEvent>): String =
        json.encodeToString(ListSerializer(IndexedEvent.serializer()), events)

    fun encodeStateIndex(index: StateIndex): String =
        json.encodeToString(StateIndex.serializer(), index)

    fun decodeStateIndex(text: String): StateIndex = runCatching {
        json.decodeFromString(StateIndex.serializer(), text)
    }.getOrElse { StateIndex() }

    /** Structurally re-bound a decoded record list (normalize dedup text, drop bad items). */
    fun rebindRecords(state: ConsolidatedState): ConsolidatedState {
        val rebound = state.records.mapNotNull { record ->
            var r = record
            r = r.copy(dedupText = ConsolidationText.normalizeDedupText(r.dedupText.ifEmpty { r.normalizedFact }))
            r = r.copy(sourceRefs = ConsolidationText.stableUnique(r.sourceRefs))
            r = r.copy(sourceDates = ConsolidationText.stableUnique(r.sourceDates).sorted())
            r = r.copy(tags = ConsolidationText.normalizeTags(r.tags))
            if (r.recordId < 0 || r.normalizedFact.isBlank()) null else r
        }
        return state.copy(records = rebound)
    }
}
