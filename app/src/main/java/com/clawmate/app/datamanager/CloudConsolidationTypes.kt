package com.clawmate.app.datamanager

import com.clawmate.app.datamanager.LocalConsolidationTypes.ConsolidatedRecord
import com.clawmate.app.datamanager.LocalConsolidationTypes.ConsolidatedState
import com.clawmate.app.datamanager.LocalConsolidationTypes.ConsolidationResult
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Typed, bounded model for the Phase-34 **model-backed** consolidation/dedup
 * path. This is a faithful port of the Harmony `DataManagerTypes.ets`
 * `DedupDecision` + `CandidateFact` surface and the `DataPipelineModelClient.ets`
 * split/dedup prompt contracts, adapted to the Android local pipeline:
 *
 *  - The **model output is always untrusted**: every field is independently
 *    decoded and whitelisted, decision tokens are normalised to the strict
 *    `new|duplicate|merge_with_existing|skip_ambiguous` set, and any
 *    `matched_record_id` must resolve to a **real** candidate record id in the
 *    current recent scope — dangling / duplicated / out-of-bounds ids force the
 *    safe heuristic/local fallback (never fabricate a record, never overwrite an
 *    unrelated record).
 *  - Generated candidate facts must carry their `source_ref` / `source_date` /
 *    `workflow_scene_id` back so they re-join the correct entry and the Phase-33
 *    scene/entity indexes stay consistent.
 *  - Nothing here carries a URI, image, token, or unrelated local field.
 *
 * Every shape is `@Serializable` so the bounded prompt JSON is built directly
 * from these models and decoded tolerantly.
 */
object CloudConsolidationTypes {

    /** Strict decision tokens the model may emit (Harmony `DedupDecision.decision`). */
    object DedupDecisions {
        const val NEW: String = "new"
        const val DUPLICATE: String = "duplicate"
        const val MERGE_WITH_EXISTING: String = "merge_with_existing"
        const val SKIP_AMBIGUOUS: String = "skip_ambiguous"

        /** The full whitelist; any other token is normalised to [NEW]. */
        val ALL: Set<String> =
            setOf(NEW, DUPLICATE, MERGE_WITH_EXISTING, SKIP_AMBIGUOUS)
    }

    /**
     * A validated dedup decision for one candidate fact against a bounded set of
     * recent-scope candidate records (mirrors Harmony `DedupDecision`).
     */
    @Serializable
    data class DedupDecision(
        @SerialName("decision") val decision: String = DedupDecisions.NEW,
        @SerialName("matched_record_ids") val matchedRecordIds: List<Int> = emptyList(),
        @SerialName("reason") val reason: String = "",
        @SerialName("merged_summary") val mergedSummary: String = "",
        @SerialName("merged_fact") val mergedFact: String = "",
        @SerialName("merged_tags") val mergedTags: List<String> = emptyList(),
    )

    /**
     * A candidate fact for dedup (mirrors Harmony `CandidateFact`). It is either
     * produced deterministically by the local split or (strictly bounded) emitted
     * by the model; either way it always carries its real `source_ref` /
     * `source_date` / `workflow_scene_id`.
     */
    @Serializable
    data class CandidateFact(
        @SerialName("summary") val summary: String = "",
        @SerialName("normalized_fact") val normalizedFact: String = "",
        @SerialName("event_date") val eventDate: String = "",
        @SerialName("tags") val tags: List<String> = emptyList(),
        @SerialName("model_notes") val modelNotes: String = "",
        @SerialName("raw_excerpt") val rawExcerpt: String = "",
        @SerialName("source_ref") val sourceRef: String = "",
        @SerialName("source_date") val sourceDate: String = "",
        @SerialName("workflow_scene_id") val workflowSceneId: Int = -1,
        @SerialName("dedup_text") val dedupText: String = "",
    )

    // ---------------------------------------------------------------------
    // Wire prompt payloads (the bounded JSON sent to / read from the model)
    // ---------------------------------------------------------------------

    /** One entry in the batch fact-split prompt (bounded real text only). */
    @Serializable
    internal data class BatchSplitPromptEntry(
        @SerialName("source_ref") val sourceRef: String,
        @SerialName("source_date") val sourceDate: String,
        @SerialName("workflow_scene_label") val workflowSceneLabel: String = "",
        @SerialName("entry_text") val entryText: String,
    )

    /** The batch fact-split prompt payload (mirrors `buildBatchSplitPromptPayload`). */
    @Serializable
    internal data class BatchSplitPromptPayload(
        @SerialName("entries") val entries: List<BatchSplitPromptEntry>,
    )

    /** One raw model item in a split reply (facts are validated before use). */
    @Serializable
    internal data class RawSplitItem(
        @SerialName("source_ref") val sourceRef: String = "",
        @SerialName("facts") val facts: List<RawSplitFact> = emptyList(),
    )

    /** One raw model fact in a split reply (untrusted, validated upstream). */
    @Serializable
    internal data class RawSplitFact(
        @SerialName("summary") val summary: String = "",
        @SerialName("normalized_fact") val normalizedFact: String = "",
        @SerialName("event_date") val eventDate: String = "",
        @SerialName("tags") val tags: List<String> = emptyList(),
        @SerialName("model_notes") val modelNotes: String = "",
    )

    /** The expected root of a split reply (only `items` is consumed). */
    @Serializable
    internal data class SplitReply(
        @SerialName("items") val items: List<RawSplitItem> = emptyList(),
    )

    /** One bounded recent-scope existing record sent to the dedup model. */
    @Serializable
    internal data class DedupExistingRecord(
        @SerialName("record_id") val recordId: Int,
        @SerialName("first_seen_date") val firstSeenDate: String,
        @SerialName("last_seen_date") val lastSeenDate: String,
        @SerialName("summary") val summary: String,
        @SerialName("normalized_fact") val normalizedFact: String,
        @SerialName("tags") val tags: List<String>,
    )

    /** The bounded dedup prompt payload (candidate + recent-scope candidates). */
    @Serializable
    internal data class DedupPromptPayload(
        @SerialName("candidate") val candidate: DedupCandidatePayload,
        @SerialName("existing_records") val existingRecords: List<DedupExistingRecord>,
    )

    /** The bounded candidate side of a dedup prompt. */
    @Serializable
    internal data class DedupCandidatePayload(
        @SerialName("summary") val summary: String,
        @SerialName("normalized_fact") val normalizedFact: String,
        @SerialName("event_date") val eventDate: String,
        @SerialName("tags") val tags: List<String>,
    )

    // ---------------------------------------------------------------------
    // Orchestration result / status types
    // ---------------------------------------------------------------------

    /** Which consolidation path produced the result. */
    enum class ConsolidationPath {
        /** Model split + model dedup ran for every bounded candidate fact. */
        MODEL,

        /** A model failure forced a heuristic/local fallback decision for at least one fact. */
        PARTIAL,

        /** Fully deterministic local path (no model; unconfigured / not explicit / no entries). */
        LOCAL,
    }

    /** Honest, countable, typed result of one model-consolidation run. */
    data class ModelConsolidationResult(
        val path: ConsolidationPath,
        val sourceEntries: Int,
        val groupsScanned: Int = 0,
        val groupsProcessed: Int = 0,
        val totalRecordsBefore: Int = 0,
        val totalRecordsAfter: Int = 0,
        val factsSplit: Int = 0,
        val factsDeduped: Int = 0,
        val factsCreated: Int = 0,
        val factsMerged: Int = 0,
        val factsSkipped: Int = 0,
        val factsFallback: Int = 0,
        val updatedAt: String = "",
        /** When present, the deterministic local result that is always preserved. */
        val localResult: ConsolidationResult? = null,
        val error: String? = null,
    ) {
        /** Typed convenience alias checks used by callers. */
        val isLocalOnly: Boolean get() = path == ConsolidationPath.LOCAL
        val isModelPath: Boolean get() = path == ConsolidationPath.MODEL
        val isPartial: Boolean get() = path == ConsolidationPath.PARTIAL

        companion object {
            fun notConfigured(entries: Int): ModelConsolidationResult = local(
                path = ConsolidationPath.LOCAL,
                sourceEntries = entries,
                localResult = null,
            )

            fun local(
                path: ConsolidationPath = ConsolidationPath.LOCAL,
                sourceEntries: Int,
                localResult: ConsolidationResult?,
            ): ModelConsolidationResult = ModelConsolidationResult(
                path = path,
                sourceEntries = sourceEntries,
                groupsScanned = localResult?.groupsScanned ?: 0,
                groupsProcessed = localResult?.groupsWithRecords ?: 0,
                totalRecordsBefore = 0,
                totalRecordsAfter = localResult?.totalRecords ?: 0,
                updatedAt = localResult?.updatedAt.orEmpty(),
                localResult = localResult,
            )

            fun error(sourceEntries: Int, message: String): ModelConsolidationResult = ModelConsolidationResult(
                path = ConsolidationPath.LOCAL,
                sourceEntries = sourceEntries,
                updatedAt = ConsolidationText.epochIsoNowUtc(),
                error = message,
            )
        }
    }

    /** Per-group internal outcome combining the model + deterministic result. */
    data class GroupConsolidation(
        val month: String,
        val fileName: String,
        val state: ConsolidatedState,
        /** True when a model decision influenced at least one fact in the group. */
        val usedModel: Boolean,
        /** True when at least one fact fell back to a heuristic decision after a model failure. */
        val hadFallback: Boolean,
    )

    /**
     * Internal, auditable decision record written into a record's `model_notes`
     * (alongside any real model reason string). Never carries a secret, URI or
     * token — only the bounded decision token + a short safe reason.
     */
    data class AuditedDecision(
        val decision: String,
        val reason: String = "",
    ) {
        /** Canonical short note stored on a record (bounded, prunable to 20 entries). */
        val note: String get() = if (reason.isBlank()) decision
        else "$decision(${ConsolidationText.normalizeFreeText(reason)})"
    }
}
