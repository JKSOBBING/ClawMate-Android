package com.clawmate.app.datamanager

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Typed, bounded data models for the Phase-30 **local-only** consolidation and
 * index foundation. These are a faithful subset port of the Harmony
 * `DataManagerTypes.ets` surfaces most relevant to the local pipeline
 * (`AggregatedRecord` / `ConsolidatedState` / `IndexedEvent` / `EventPartition`
 * / `QueryResult`), reduced to *no cloud model*, so a record's
 * `summary` / `normalized_fact` / `tags` are derived deterministically from the
 * real daily-log text and its exact-normalized dedup — never fabricated by an
 * external model.
 *
 * Every shape is `@Serializable` with tolerant decode defaults so a corrupt or
 * partial on-disk JSON artifact is recovered to an empty/defensive value rather
 * than thrown (see [LocalConsolidationCodec]).
 */
object LocalConsolidationTypes {

    /**
     * Bounded schema/version markers written into each JSON artifact so readers
     * can detect format drift. Mirrors the Harmony schemas, prefixed with the
     * `-local-` scope to signal these are device-local, model-free artifacts.
     */
    object Schemas {
        const val CONSOLIDATED_STATE_V1: String = "datamanager-consolidation-state-local-v1"
        const val STATE_INDEX_V1: String = "datamanager-consolidation-output-state-index-local-v1"
        const val EVENT_PARTITION_V1: String = "datamanager-event-partition-index-local-v1"
        const val EVENT_MANIFEST_V1: String = "datamanager-event-manifest-local-v1"
        const val TEMPORAL_V1: String = "datamanager-temporal-local-v1"
        const val WORKFLOW_SCENE_INDEX_V1: String = "datamanager-workflow-scene-index-local-v1"
        const val ENTITY_INDEX_V1: String = "datamanager-entity-index-local-v1"
    }

    /**
     * One typed entity mention found on a real event (mirrors Harmony
     * `EntityMention`, reduced to the three indexed types `person`/`place`/
     * `merchant`). Only these tokens are ever admitted into the entity index;
     * `name`/`aliases` are bounded real text extracted from an event, never a
     * fabricated value. Unknown types, empty names and over-long fields are
     * dropped by the conservative entity resolver / builder.
     */
    @Serializable
    data class EntityMention(
        @SerialName("type") val type: String = "",
        @SerialName("name") val name: String = "",
        @SerialName("aliases") val aliases: List<String> = emptyList(),
    )

    /**
     * One consolidated record (mirrors Harmony `AggregatedRecord`). `record_id`
     * is a stable, monotonic index scoped to its consolidated source file;
     * `summarized`/`dedup_text` are deterministic local derivations.
     */
    @Serializable
    data class ConsolidatedRecord(
        @SerialName("record_id") val recordId: Int = 0,
        @SerialName("first_seen_date") val firstSeenDate: String = "",
        @SerialName("last_seen_date") val lastSeenDate: String = "",
        @SerialName("source_refs") val sourceRefs: List<String> = emptyList(),
        @SerialName("source_dates") val sourceDates: List<String> = emptyList(),
        @SerialName("summary") val summary: String = "",
        @SerialName("normalized_fact") val normalizedFact: String = "",
        @SerialName("raw_excerpts") val rawExcerpts: List<String> = emptyList(),
        @SerialName("tags") val tags: List<String> = emptyList(),
        @SerialName("workflow_scene_ids") val workflowSceneIds: List<Int> = emptyList(),
        @SerialName("dedup_status") val dedupStatus: String = "new",
        @SerialName("dedup_text") val dedupText: String = "",
        /**
         * Auditable, bounded model/local decision notes (e.g. `new`,
         * `duplicate(exact_existing_match)`, `merge_with_existing(signature_match)`).
         * Never carries a secret, URI or token. Empty for the deterministic-local
         * path (backward compatible with old Phase-30 artifacts that lack the key).
         */
        @SerialName("model_notes") val modelNotes: List<String> = emptyList(),
    )

    /** Bounded consolidated-state metadata for one month/file group. */
    @Serializable
    data class ConsolidatedMetadata(
        @SerialName("source_files") val sourceFiles: List<String> = emptyList(),
        @SerialName("source_dates") val sourceDates: List<String> = emptyList(),
        @SerialName("latest_record_index") val latestRecordIndex: Int = 0,
        @SerialName("updated_at") val updatedAt: String = "",
        @SerialName("total_records") val totalRecords: Int = 0,
        @SerialName("month") val month: String = "",
        /**
         * Stable per-source-ref processing hash (port of Harmony
         * `ConsolidatedMetadata.processed_entry_hashes`) so the model path can
         * skip entries it has already processed. Backward compatible: old
         * artifacts decode to an empty map and are processed once.
         */
        @SerialName("processed_entry_hashes") val processedEntryHashes: Map<String, String> = emptyMap(),
    )

    /** The persisted consolidation artifact for one month/file group. */
    @Serializable
    data class ConsolidatedState(
        @SerialName("schema") val schema: String = Schemas.CONSOLIDATED_STATE_V1,
        @SerialName("metadata") val metadata: ConsolidatedMetadata = ConsolidatedMetadata(),
        @SerialName("records") val records: List<ConsolidatedRecord> = emptyList(),
    )

    /** Honest, countable result of a consolidation run. */
    data class ConsolidationResult(
        val sourceEntries: Int,
        val groupsScanned: Int,
        val groupsWithRecords: Int,
        val totalRecords: Int,
        val updatedAt: String,
    )

    /**
     * A bounded, local index event (mirrors the relevant subset of Harmony `IndexedEvent`).
     * Phase-38 adds optional **batch event-enrichment fields** (keywords / domains /
     * todo / enrichment-method) with empty defaults so old Phase-30/33 artifacts
     * (which lack these keys) still decode to an un-enriched event — i.e. the
     * readers keep their pre-enrichment behaviour when no enrichment artifact exists.
     */
    @Serializable
    data class IndexedEvent(
        @SerialName("event_id") val eventId: String = "",
        @SerialName("source_state_file") val sourceStateFile: String = "",
        @SerialName("record_id") val recordId: Int = 0,
        @SerialName("event_date") val eventDate: String = "",
        @SerialName("last_seen_date") val lastSeenDate: String = "",
        @SerialName("summary") val summary: String = "",
        @SerialName("normalized_fact") val normalizedFact: String = "",
        @SerialName("tags") val tags: List<String> = emptyList(),
        @SerialName("source_refs") val sourceRefs: List<String> = emptyList(),
        @SerialName("sources") val sources: List<String> = listOf("DailyLog"),
        @SerialName("workflow_scene_ids") val workflowSceneIds: List<Int> = emptyList(),
        @SerialName("entity_mentions") val entityMentions: List<EntityMention> = emptyList(),
        // ---- Phase-38 batch event-enrichment fields (optional; empty default) ----
        @SerialName("keywords") val keywords: List<String> = emptyList(),
        @SerialName("domains") val domains: List<String> = emptyList(),
        @SerialName("todo_type") val todoType: String = "",
        @SerialName("todo_deadline") val todoDeadline: String = "",
        @SerialName("todo_text") val todoText: String = "",
        @SerialName("todo_agent_task") val todoAgentTask: String = "",
        @SerialName("enrichment_method") val enrichmentMethod: String = "",
    )

    /** Month-partitioned index artifact (mirrors Harmony `EventPartition`). */
    @Serializable
    data class EventPartition(
        @SerialName("schema") val schema: String = Schemas.EVENT_PARTITION_V1,
        @SerialName("month") val month: String = "",
        @SerialName("events") val events: List<IndexedEvent> = emptyList(),
    )

    /** Partition-index artifact listing the partition files (brace for non-enumerable dirs). */
    @Serializable
    data class EventPartitionIndex(
        @SerialName("schema") val schema: String = Schemas.EVENT_PARTITION_V1,
        @SerialName("generated_at") val generatedAt: String = "",
        @SerialName("partition_files") val partitionFiles: List<String> = emptyList(),
    )

    /** Event-sources manifest (sha of source + event ids + per-event input hashes). */
    @Serializable
    data class SourceManifestEntry(
        @SerialName("sha256") val sha256: String = "",
        @SerialName("event_ids") val eventIds: List<String> = emptyList(),
        @SerialName("event_input_hashes") val eventInputHashes: Map<String, String> = emptyMap(),
    )

    /** Index manifest (bounded). */
    @Serializable
    data class IndexManifest(
        @SerialName("schema") val schema: String = Schemas.EVENT_MANIFEST_V1,
        @SerialName("generated_at") val generatedAt: String = "",
        @SerialName("event_count") val eventCount: Int = 0,
        @SerialName("source_files") val sourceFiles: Int = 0,
        @SerialName("sources") val sources: Map<String, SourceManifestEntry> = emptyMap(),
    )

    /** Temporal month document so query-by-date scans stay bounded. */
    @Serializable
    data class TemporalDocument(
        @SerialName("schema") val schema: String = Schemas.TEMPORAL_V1,
        @SerialName("month") val month: String = "",
        @SerialName("event_ids") val eventIds: List<String> = emptyList(),
    )

    /** Output state-index artifact (bounded month -> files/record-count). */
    @Serializable
    data class StateIndex(
        @SerialName("schema") val schema: String = Schemas.STATE_INDEX_V1,
        @SerialName("months") val months: Map<String, StateIndexEntry> = emptyMap(),
    )

    /** One month's entry in the output state-index. */
    @Serializable
    data class StateIndexEntry(
        @SerialName("files") val files: List<String> = emptyList(),
        @SerialName("record_count") val recordCount: Int = 0,
    )

    /** Query options for [DailyLogLocalIndex]. */
    data class QueryOptions(
        val date: String = "",
        val month: String = "",
        val source: String = "",
        val keyword: String = "",
        val limit: Int = 0,
    )

    /** Query result (mirrors Harmony `QueryResult`). */
    data class QueryResult(
        val totalEvents: Int,
        val events: List<IndexedEvent>,
        val eventsTruncated: Boolean,
    )
}
