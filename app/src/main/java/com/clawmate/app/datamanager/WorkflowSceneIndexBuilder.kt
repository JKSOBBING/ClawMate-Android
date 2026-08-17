package com.clawmate.app.datamanager

import com.clawmate.app.datamanager.EntitySceneIndexTypes.WorkflowSceneDocument
import com.clawmate.app.datamanager.LocalConsolidationTypes.IndexedEvent

/**
 * Phase-33 **workflow-scene index** builder. Given the **real** indexed events
 * read from the Phase-30/32 month partitions, it produces the
 * `workflow-scene/index.json` contract: `postings: sceneId -> [stable event ids]`.
 *
 * Every scene id comes **only** from the events' real `workflow_scene_ids`
 * (themselves propagated from the daily-log `task_scene_id` metadata through
 * consolidation). Scene ids are never invented: an event with no scene ids is
 * simply not posted. Postings are deduped, sorted by event id, and the whole
 * artifact is bounded (see [MAX_POSTINGS]).
 *
 * The build is deterministic and network-free. Corrupt / oversized input reads
 * recover to an honest empty document — never thrown.
 */
class WorkflowSceneIndexBuilder(
    private val baseDir: java.io.File,
    private val store: AtomicJsonStore = AtomicJsonStore(),
) {

    /** Cap on posted scene keys so a pathological scene-id cloud stays bounded. */
    private fun maxPostings(events: List<IndexedEvent>): Int = maxOf(1, minOf(events.size * 2, MAX_POSTINGS))

    /**
     * Build the workflow-scene document from real events and persist it.
     * Returns the honest count of posted scenes (0 when no event has a scene id
     * → no artifact is written).
     */
    fun buildAndWrite(events: List<IndexedEvent>): Int {
        val document = build(events)
        if (document.postings.isEmpty()) return 0
        store.writeJson(DataManagerPaths.workflowSceneIndex(baseDir), document) {
            EntitySceneCodec.encodeWorkflowScene(it)
        }
        return document.postings.size
    }

    /** Pure, deterministic build (stateless, testable). Never throws. */
    fun build(events: List<IndexedEvent>): WorkflowSceneDocument {
        val postings = LinkedHashMap<String, MutableList<String>>()
        val sceneLimit = maxPostings(events)
        for (event in events) {
            val ids = SceneEntityNormalizer.normalizeSceneIds(event.workflowSceneIds)
            for (sceneId in ids) {
                val key = sceneId.toString()
                val list = postings.getOrPut(key) { ArrayList() }
                if (!list.contains(event.eventId)) list.add(event.eventId)
                if (postings.size > sceneLimit) break
            }
            if (postings.size > sceneLimit) break
        }
        val sorted = LinkedHashMap<String, List<String>>()
        postings.keys.sortedWith(compareBy({ it.toIntOrNull() ?: Int.MAX_VALUE }, { it }))
            .forEach { sorted[it] = postings.getValue(it).sorted() }
        return WorkflowSceneDocument(generatedAt = nowIsoLocal(), postings = sorted)
    }

    /** Tolerant re-read of the persisted workflow-scene index (empty when missing/corrupt/oversized). */
    fun load(): WorkflowSceneDocument =
        store.readJson(DataManagerPaths.workflowSceneIndex(baseDir)) { EntitySceneCodec.decodeWorkflowScene(it) }
            ?: WorkflowSceneDocument()

    private fun nowIsoLocal(): String =
        java.time.LocalDateTime.now(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))

    companion object {
        /** Bounded cap on the number of posted scene keys. */
        const val MAX_POSTINGS: Int = 64
    }
}
