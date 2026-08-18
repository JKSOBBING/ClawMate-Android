package com.clawmate.app.datamanager

import com.clawmate.app.datamanager.BatchEventEnrichTypes.EnrichedEventFields
import java.io.File
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Phase-38 **persisted batch event-enrichment** store — a versioned, atomically
 * written, app-private artifact that makes the Phase-36 [BatchEventEnricher]
 * keyed typed result usable as real downstream data (event query / profile /
 * Home recommendation input), instead of being returned-and-dropped.
 *
 * - **Versioned + atomic.** Writes go through [AtomicJsonStore] (temp + rename),
 *   the artifact embeds a schema/version token and the events whose ids were
 *   actually enriched, so a corrupt / partial write is recovered to an empty
 *   artifact rather than thrown.
 * - **Strictly real `event_id` keyed.** Only the ids present in the caller's
 *   [EnrichedEventFields] map are written; the model can never invent an event,
 *   id, keyword, domain or entity (those invariants are already enforced by the
 *   enricher's whitelist before this store is reached).
 * - **Only on an explicit MODEL success.** [canPersist] admits only a MODEL
 *   result with a non-empty field map. LOCAL_ONLY / FALLBACK / EMPTY are never
 *   written and the prior artifact is preserved byte-for-byte (a failed model run
 *   never erases the deterministic local fields / old index).
 * - **Consumed by a read seam.** [load] returns the persisted map keyed by real
 *   event id; readers (e.g. [DailyLogLocalIndex], [ProfileIndexBuilder]) overlay
 *   it onto indexed events. With no artifact the readers keep their old behavior.
 * - **No secret.** Only bounded keywords / domains / entities / todo /
 *   enrichment-method are persisted; a URI / path / token / secret is never
 *   written (the enricher already refuses them).
 */
class BatchEventEnrichmentStore(
    private val baseDir: File,
    private val store: AtomicJsonStore = AtomicJsonStore(),
) {

    /** Whether [result] is a persisted-eligible MODEL outcome with real fields. */
    fun canPersist(result: BatchEventEnrichTypes.EnrichmentResult): Boolean =
        result.status == BatchEventEnrichTypes.EnrichmentStatus.MODEL &&
            result.enrichedFields.isNotEmpty()

    /**
     * Atomically persist the enriched fields of a MODEL success, **only** for the
     * real event ids the enricher returned. Returns true on a successful write;
     * false (no throw) on a non-MODEL result or a write failure (the caller
     * surfaces an honest error and keeps the old artifact).
     */
    fun write(result: BatchEventEnrichTypes.EnrichmentResult): Boolean {
        if (!canPersist(result)) return false
        return runCatching {
            val fields = LinkedHashMap<String, PersistedEnrichedFields>()
            for ((eventId, f) in result.enrichedFields) {
                fields[eventId] = PersistedEnrichedFields.from(f)
            }
            val artifact = BatchEventEnrichmentArtifact(
                schema = BatchEventEnrichmentArtifact.SCHEMA,
                generatedAt = result.generatedAt.ifBlank { nowIsoLocal() },
                eventCount = fields.size,
                fields = fields,
            )
            store.writeJson(DataManagerPaths.eventEnrichment(baseDir), artifact) {
                BatchEventEnrichmentCodec.encodeArtifact(it)
            } != null
        }.getOrDefault(false)
    }

    /**
     * Tolerant read of the persisted enrichment map keyed by real event id.
     * Returns an empty map when the artifact is missing / corrupt, so readers
     * fall back to their default behaviour.
     */
    fun load(): Map<String, PersistedEnrichedFields> =
        store.readJson(DataManagerPaths.eventEnrichment(baseDir)) {
            BatchEventEnrichmentCodec.decodeArtifact(it)
        }?.fields.orEmpty()

    private fun nowIsoLocal(): String =
        java.time.LocalDateTime.now(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))
}

/**
 * Bounded, serializable per-event enrichment fields persisted by
 * [BatchEventEnrichmentStore]. Only real, bounded tokens are stored; never a URI /
 * path / token / secret.
 */
@Serializable
data class PersistedEnrichedFields(
    @SerialName("event_id") val eventId: String = "",
    @SerialName("keywords") val keywords: List<String> = emptyList(),
    @SerialName("domains") val domains: List<String> = emptyList(),
    @SerialName("entities") val entities: List<LocalConsolidationTypes.EntityMention> = emptyList(),
    @SerialName("todo_type") val todoType: String = "",
    @SerialName("todo_deadline") val todoDeadline: String = "",
    @SerialName("todo_text") val todoText: String = "",
    @SerialName("todo_agent_task") val todoAgentTask: String = "",
    @SerialName("enrichment_method") val enrichmentMethod: String = "",
) {
    companion object {
        /** Copy from the bounded enrich surface (ids already whitelisted upstream). */
        fun from(f: EnrichedEventFields): PersistedEnrichedFields = PersistedEnrichedFields(
            eventId = f.eventId,
            keywords = f.keywords,
            domains = f.domains,
            entities = f.entities,
            todoType = f.todoType,
            todoDeadline = f.todoDeadline,
            todoText = f.todoText,
            todoAgentTask = f.todoAgentTask,
            enrichmentMethod = f.enrichmentMethod,
        )
    }
}

/**
 * Versioned envelope for the persisted batch enrichment artifact.
 */
@Serializable
data class BatchEventEnrichmentArtifact(
    @SerialName("schema") val schema: String = "",
    @SerialName("generated_at") val generatedAt: String = "",
    @SerialName("event_count") val eventCount: Int = 0,
    @SerialName("fields") val fields: Map<String, PersistedEnrichedFields> = emptyMap(),
) {
    companion object {
        const val SCHEMA: String = "datamanager-event-enrichment-local-v1"
    }
}

/** Deterministic JSON serde for the batch-enrichment artifact (tolerant decode). */
object BatchEventEnrichmentCodec {
    fun encodeArtifact(artifact: BatchEventEnrichmentArtifact): String =
        SERDE.encodeToString(BatchEventEnrichmentArtifact.serializer(), artifact)

    fun decodeArtifact(text: String): BatchEventEnrichmentArtifact? = runCatching {
        SERDE.decodeFromString(BatchEventEnrichmentArtifact.serializer(), text)
    }.getOrNull()

    private val SERDE = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }
}
