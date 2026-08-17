package com.clawmate.app.datamanager

import com.clawmate.app.datamanager.EntitySceneIndexTypes.WorkflowSceneDocument
import com.clawmate.app.datamanager.EntitySceneIndexTypes.EntityDocument
import kotlinx.serialization.json.Json

/**
 * Deterministic, tolerant JSON serde for the Phase-33 workflow-scene and entity
 * index artifacts. Decoding is defensive: a missing/unknown/coerced field
 * recovers to its serializable default (never throws), so a corrupt or oversized
 * artifact is honestly recovered to an empty document rather than falsified.
 */
object EntitySceneCodec {

    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    fun encodeWorkflowScene(document: WorkflowSceneDocument): String =
        json.encodeToString(WorkflowSceneDocument.serializer(), document)

    fun decodeWorkflowScene(text: String): WorkflowSceneDocument = runCatching {
        json.decodeFromString(WorkflowSceneDocument.serializer(), text)
    }.getOrElse { WorkflowSceneDocument() }

    fun encodeEntity(document: EntityDocument): String =
        json.encodeToString(EntityDocument.serializer(), document)

    fun decodeEntity(text: String): EntityDocument = runCatching {
        json.decodeFromString(EntityDocument.serializer(), text)
    }.getOrElse { EntityDocument() }
}
