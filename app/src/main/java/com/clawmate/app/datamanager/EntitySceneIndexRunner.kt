package com.clawmate.app.datamanager

import com.clawmate.app.datamanager.EntitySceneIndexTypes.SceneEntityIndexResult
import com.clawmate.app.datamanager.LocalConsolidationTypes.IndexedEvent

/**
 * The single explicit, testable rebuild entry for the Phase-33 **workflow-scene +
 * entity index** foundation. Invoking [run] reads the already-persisted Phase-30/32
 * event partitions, then atomically writes exactly two artifacts:
 *
 *  - `workflows/profile-consolidation/indexes/workflow-scene/index.json`;
 *  - `workflows/profile-consolidation/indexes/entity/index.json`.
 *
 * It never touches the persisted profile state / `profile.md` or the manually
 * curated Home data — those surfaces are left byte-for-byte untouched, matching
 * the "preserve existing profile state and manual Home data" boundary.
 *
 * Honest outcomes:
 *  - **EMPTY** — no real indexed events, or events carry no scene/entity evidence:
 *    zero network and no artifact is written.
 *  - **LOCAL_ONLY** — scene/entity indexes built deterministically (zero network).
 *
 * This entry defaults to the deterministic local-only resolver. A model is never
 * auto-invoked; the optional model boundary ([EntityModelBoundary]) is gated to
 * explicit, fully-configured user-triggered actions only (see
 * [EntityModelBoundary.gate]) and Android's production default is
 * `local-only / zero network`.
 */
class EntitySceneIndexRunner(
    private val baseDir: java.io.File,
    private val sceneBuilder: WorkflowSceneIndexBuilder = WorkflowSceneIndexBuilder(baseDir),
    private val entityBuilder: EntitySceneIndexBuilder = EntitySceneIndexBuilder(baseDir),
    private val eventLoader: (() -> List<IndexedEvent>)? = { ProfileIndexBuilder(baseDir).loadEvents() },
) {

    /**
     * Build scope / gating for one rebuild action. The production default is
     * [LocalOnly] (zero network). A caller may pass [ModelRequested] only from an
     * explicit, user-triggered index/enrichment action; the model gate
     * ([EntityModelBoundary.gate]) refuses to invite the network unless the
     * shared config is complete and the action is explicit. Android's
     * deterministic local resolver is authoritative either way.
     */
    sealed interface BuildMode {
        data object LocalOnly : BuildMode

        /** An explicit user-triggered action that *may* invite a model when fully configured. */
        data class ModelRequested(val explicitAction: Boolean, val config: ProfileModelConfig?) : BuildMode
    }

    /**
     * Run the scene+entity rebuild from the persisted event partitions.
     * [mode] defaults to local-only (zero network, the production default).
     */
    fun run(mode: BuildMode = BuildMode.LocalOnly): SceneEntityIndexResult {
        // Evaluate the model gate so an incomplete config or a non-explicit
        // action can never invite a network request; the deterministic local
        // build below runs regardless and is always honest.
        EntityModelBoundary.gate(
            explicitAction = when (mode) {
                is BuildMode.ModelRequested -> mode.explicitAction
                BuildMode.LocalOnly -> false
            },
            config = (mode as? BuildMode.ModelRequested)?.config,
        )
        val events = loadEventsSafe()
        if (events.isEmpty()) {
            return SceneEntityIndexResult(0, 0, 0, 0, nowIsoLocal(), eventCount = 0)
        }
        val sceneDocument = sceneBuilder.build(events)
        val sceneWritten = sceneBuilder.buildAndWrite(events)
        val scenePostings = sceneDocument.postings.values.sumOf { it.size }
        val entityBuild = entityBuilder.buildAndWrite(events)
        return SceneEntityIndexResult(
            sceneCount = sceneWritten,
            scenePostings = scenePostings,
            entityCount = entityBuild.entityCount,
            mentionCount = entityBuild.mentionCount,
            generatedAt = nowIsoLocal(),
            eventCount = events.size,
        )
    }

    /** Bounded, tolerant event load (empty on any failure). */
    private fun loadEventsSafe(): List<IndexedEvent> =
        runCatching { eventLoader?.invoke().orEmpty() }.getOrDefault(emptyList())

    private fun nowIsoLocal(): String =
        java.time.LocalDateTime.now(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))
}
