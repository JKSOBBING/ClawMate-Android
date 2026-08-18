package com.clawmate.app.datamanager

/**
 * Typed, bounded data models for the Phase-37 **explicit fact-splitting** seam —
 * a faithful Android port of the Harmony `DataPipelineModelClient.
 * splitEntriesIntoFacts` / `splitEntryIntoFacts` fact-batching path, on top of the
 * existing Phase-29 `DailyLogEntry` (real `SourceEntry` scope) and the existing
 * Phase-34 `ConsolidationModelClient` (which reuses the shared `ChatClient` +
 * [ConsolidationModelConfig]).
 *
 * The seam has the same safety philosophy as every other model seam in the app:
 *  - it is **explicit-action-only** and **default-disabled** (zero network);
 *  - it accepts **only real `DailyLogEntry`/`source_ref` scope** and returns
 *    candidate facts keyed by the entry's real `source_ref` — the model can never
 *    invent a source, a date, or a fact;
 *  - requests and replies are strict JSON, bounded by batch / characters / count;
 *  - a model failure or malformed reply always falls back to the deterministic
 *    local split — never a fabricated fact;
 *  - the returned result is **returned to the caller**; persisting it is the
 *    caller's explicit decision (caller-only persistence seam). Nothing here
 *    auto-writes an artifact and nothing carries a URI / path / token / secret.
 */
object FactSplitTypes {

    /**
     * Strict, bounded source scope consumed by the seam. Mirrors the Harmony
     * `SourceEntry` fields the split prompt actually uses (`source_date` /
     * `source_ref` / `workflow_scene_id` / `entry_text`), adapted to the Android
     * `DailyLogEntry` + `task_scene_id` metadata. Only these are real; a caller
     * fabricating a ref/date/text is refused by [FactSplitRunner] validation.
     */
    data class SplitSourceScope(
        val sourceDate: String,   // YYYY-MM-DD
        val sourceRef: String,
        val workflowSceneId: Int = -1,
        val entryText: String,
        val fileName: String = "",
        val entryIndex: Int = 0,
        val workflowMetadata: Map<String, String> = emptyMap(),
    ) {
        val isReal: Boolean
            get() = ConsolidationText.isIsoDate(sourceDate) &&
                sourceRef.isNotBlank() && entryText.isNotBlank()
    }

    /**
     * The bounded candidate-fact result surface keyed by a real `source_ref`.
     * Mirrors Harmony `CandidateFact` (summary / normalized_fact / event_date /
     * tags / model_notes / raw_excerpt / source_ref / source_date /
     * workflow_scene_id / dedup_text). Never carries a secret, URI or path.
     */
    data class SplitFact(
        val summary: String = "",
        val normalizedFact: String = "",
        val eventDate: String = "",
        val tags: List<String> = emptyList(),
        val modelNotes: String = "",
        val rawExcerpt: String = "",
        val sourceRef: String = "",
        val sourceDate: String = "",
        val workflowSceneId: Int = -1,
        val dedupText: String = "",
    )

    /** Honest, countable outcome of one fact-split attempt. */
    enum class FactSplitStatus(val token: String) {
        /** No real daily-log entries → zero network, nothing to split. */
        EMPTY("empty"),
        /** Deterministic local split kept; model never invited (gate refused). */
        LOCAL_ONLY("local_only"),
        /** Model invited and at least one source_ref's facts were admitted. */
        MODEL("model"),
        /** The model path failed / every reply malformed → deterministic local split kept. */
        FALLBACK("fallback"),
    }

    /**
     * The typed result returned to the explicit caller, keyed by **real**
     * `source_ref`. It is never auto-persisted; the caller (UI / host) chooses
     * whether and how to store the facts (e.g. feed them to consolidation).
     */
    data class FactSplitResult(
        val status: FactSplitStatus,
        val sourceCount: Int = 0,
        val admittedSourceCount: Int = 0,
        val facts: Map<String, List<SplitFact>> = emptyMap(),
        val droppedModelItems: Int = 0,
        val generatedAt: String = "",
    ) {
        val totalFacts: Int get() = facts.values.sumOf { it.size }
    }

    /** A replaced/kept model fact admission outcome per source_ref (auditable). */
    data class FactSplitSourceOutcome(
        val sourceRef: String,
        val usedModel: Boolean,
        val factCount: Int,
    )

    /** Boundaries / caps for the fact-split seam (Harmony `BATCH_FACT_SPLIT_REQUEST_SIZE`). */
    object Bounds {
        /** Cap on source entries per model batch request. */
        const val MAX_SOURCES_PER_REQUEST: Int = 8
        /** Hard cap on source entries invited to the model in one explicit action. */
        const val MAX_MODEL_SOURCES: Int = 400
        /** Hard cap on a single entry text carried in a prompt. */
        const val MAX_ENTRY_CHARS: Int = 400
        /** Hard cap on a single admitted normalized fact. */
        const val MAX_FACT_CHARS: Int = 800
        /** Hard cap on facts admitted per entry from a model reply. */
        const val MAX_FACTS_PER_ENTRY: Int = 12
    }
}
