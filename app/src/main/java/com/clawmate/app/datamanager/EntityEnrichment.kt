package com.clawmate.app.datamanager

/**
 * Typed, bounded models for the Phase-35 **explicit model entity enrichment**
 * seam (built on top of the Phase-33 deterministic `EntityResolver` /
 * `EntitySceneIndex`).
 *
 * The enrichment is a *model-assisted* pass over the already-built local entity
 * scope: it lets the model *clarify/confirm* candidate entities (type, aliases)
 * and re-attach them to **real** records in the current scope. It can never
 * invent a fact, never overwrite the persisted index by itself, and never adds a
 * secret. Default behavior is `local-only / zero network` and the persisted
 * artifact is only changed by an explicit caller (never by the seam, never from
 * Home or a background job).
 *
 * Safety contract (mirrors Harmony `DataPipelineModelClient.resolveEntities`
 * reduced to the Android entity-index scope):
 *  - types are strictly `person` / `place` / `merchant`; a model `unknown` is
 *    dropped (it is not indexable — never coerced);
 *  - every admitted entity's `record_ids` / `source_refs` must be **real** ids in
 *    the current EntitySceneIndex / record scope — unknown, duplicate,
 *    out-of-bounds or over-long values are dropped;
 *  - names / aliases are bounded real tokens; a URI, external path, image,
 *    token, or unrelated field is never written;
 *  - `workflow_scene_ids` / `entity_mentions` / `source_ref` / `date` are
 *    preserved from the real local scope; the result is auditable but carries no
 *    secret.
 */
object EntityEnrichmentTypes {

    /** Bound cap on the total number of candidate entities packed into one prompt. */
    const val MAX_CANDIDATES_PER_PROMPT: Int = 40

    /** Bound cap on distinct real record ids admitted per enriched entity. */
    const val MAX_RECORD_IDS_PER_ENTITY: Int = 24

    /** Bound cap on distinct real source refs admitted per enriched entity. */
    const val MAX_SOURCE_REFS_PER_ENTITY: Int = 24

    /** Bound cap on distinct aliases admitted per enriched entity. */
    const val MAX_ALIASES_PER_ENTITY: Int = 16

    /** Hard cap on the length of one prompt user payload in characters. */
    const val MAX_PROMPT_CHARACTERS: Int = 32_000

    /** Hard cap on a single entity name (longer values are dropped). */
    const val MAX_ENTITY_NAME_CHARACTERS: Int = 120

    /** The strict entity-types the index admits (aligned with [SceneEntityNormalizer]). */
    val ADMITTED_TYPES: List<String> = listOf("person", "place", "merchant")

    /** The full type vocabulary the model may return (unknown is then dropped). */
    val MODEL_TYPE_VOCABULARY: List<String> = listOf("person", "place", "merchant", "unknown")

    /**
     * One bounded, desensitized entity candidate derived from the real local
     * scope. `candidateId` is a stable synthetic key; `name`/`type` are real,
     * bounded mention tokens. `recordIds` and `sourceRefs` are the real records
     * that mention this entity (must exist in the current scope).
     */
    data class EntityCandidate(
        val candidateId: String = "",
        val type: String = "",
        val name: String = "",
        val aliases: List<String> = emptyList(),
        val recordIds: List<Int> = emptyList(),
        val sourceRefs: List<String> = emptyList(),
        val sceneIds: List<Int> = emptyList(),
        val date: String = "",
    )

    /** A model-reply enriched entity, strictly whitelisted by the validator. */
    data class RichEntity(
        val type: String = "",
        val name: String = "",
        val aliases: List<String> = emptyList(),
        val recordIds: List<Int> = emptyList(),
        val sourceRefs: List<String> = emptyList(),
        /** Auditable, secret-free note about how this entity was produced. */
        val modelNotes: List<String> = emptyList(),
    )

    /** Honest outcome of one enrichment attempt (mirrors [EntitySceneIndexTypes.EntityResolutionStatus]). */
    enum class EnrichmentStatus(val token: String) {
        /** No real entity candidates in scope → zero network, nothing to enrich. */
        EMPTY("empty"),
        /** Deterministic local result kept; model never invited (gate refused). */
        LOCAL_ONLY("local_only"),
        /** At least one admitted, model-clarified enriched entity was produced. */
        MODEL("model"),
        /** The model path failed / every candidate dropped → local result preserved. */
        FALLBACK("fallback"),
    }

    /** The enrichment outcome returned to the explicit caller. The persisted artifact is untouched. */
    data class EnrichmentResult(
        val status: EnrichmentStatus,
        val candidateCount: Int = 0,
        val admittedEntityCount: Int = 0,
        val droppedModelCandidates: Int = 0,
        val generatedAt: String = "",
        /** The *new* enriched entity document for a MODEL success; null otherwise. */
        val enrichedDocument: EntitySceneIndexTypes.EntityDocument? = null,
    )
}
