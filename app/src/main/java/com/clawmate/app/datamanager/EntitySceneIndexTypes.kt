package com.clawmate.app.datamanager

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Typed, bounded data models for the Phase-33 **workflow-scene + entity index**
 * foundation. These are faithful Kotlin ports of the Harmony `IndexService.ets`
 * `WorkflowSceneDocument` / `EntityDocument` / `EntityDefinition` contracts,
 * written under the app-private `indexes/` tree:
 *
 *  - `workflow-scene/index.json` — `postings: sceneId -> [stable event ids]`;
 *  - `entity/index.json` — canonical `entities` (type/name/aliases/event_ids),
 *    `alias_postings` (lookup-key -> entity keys), and `mention_assignments`
 *    (mention-token -> entity key).
 *
 * Every shape is `@Serializable` with tolerant decode defaults so a corrupt or
 * oversized artifact is honestly recovered (never thrown / never falsified).
 * Scene ids must come from real events; entity names are bounded real mention
 * tokens — never a URI, image, token or whole log segment.
 */
object EntitySceneIndexTypes {

    /** Workflow-scene document (mirrors Harmony `WorkflowSceneDocument`). */
    @Serializable
    data class WorkflowSceneDocument(
        @SerialName("schema") val schema: String = LocalConsolidationTypes.Schemas.WORKFLOW_SCENE_INDEX_V1,
        @SerialName("generated_at") val generatedAt: String = "",
        @SerialName("postings") val postings: Map<String, List<String>> = emptyMap(),
    )

    /** One canonical entity (mirrors Harmony `EntityDefinition`). */
    @Serializable
    data class EntityDefinition(
        @SerialName("type") val type: String = "",
        @SerialName("name") val name: String = "",
        @SerialName("aliases") val aliases: List<String> = emptyList(),
        @SerialName("event_ids") val eventIds: List<String> = emptyList(),
    )

    /** Entity document (mirrors Harmony `EntityDocument`). */
    @Serializable
    data class EntityDocument(
        @SerialName("schema") val schema: String = LocalConsolidationTypes.Schemas.ENTITY_INDEX_V1,
        @SerialName("generated_at") val generatedAt: String = "",
        @SerialName("entities") val entities: Map<String, EntityDefinition> = emptyMap(),
        @SerialName("alias_postings") val aliasPostings: Map<String, List<String>> = emptyMap(),
        @SerialName("mention_assignments") val mentionAssignments: Map<String, String> = emptyMap(),
    )

    /** Honest, countable outcome of one scene+entity index rebuild run. */
    data class SceneEntityIndexResult(
        /** Number of distinct posted scene keys written to the artifact (0 → no artifact). */
        val sceneCount: Int,
        /** Total event↔scene associations across all postings. */
        val scenePostings: Int,
        val entityCount: Int,
        val mentionCount: Int,
        val generatedAt: String,
        /** Total number of real indexed events read from the partitions. */
        val eventCount: Int = 0,
    ) {
        /** Whether the run wrote at least one of the two artifacts. */
        val producedArtifact: Boolean get() = sceneCount > 0 || entityCount > 0
    }

    /** Honest typed outcome of a resolution attempt (local-only by default). */
    enum class EntityResolutionStatus(val token: String) {
        /** No real event mentions and no bounded extraction; no entities. */
        EMPTY("empty"),
        /** Deterministic local resolution succeeded (zero network). */
        LOCAL_ONLY("local_only"),
        /** A configured model was attempted and admitted mapping tokens. */
        MODEL("model"),
        /** The model path failed; the deterministic local result was preserved. */
        ERROR("error"),
    }
}
