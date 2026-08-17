package com.clawmate.app.datamanager

import com.clawmate.app.datamanager.LocalConsolidationTypes.EntityMention

/**
 * Typed, bounded data models for the Phase-36 **explicit generic batch event
 * enrichment** seam — a faithful, Android-scoped port of the Harmony
 * `DataPipelineModelClient.enrichEvents` path (batch keyword / domain / entity /
 * todo extraction over indexed events).
 *
 * The seam *enriches* the real, already-indexed events produced by the Phase-30
 * deterministic pipeline. It never creates an event, never overwrites the
 * persisted index by itself, and never adds a secret. Default behavior is
 * `local-only / zero network`; the model is only invited by an explicit,
 * fully-configured, user-triggered action against real event ids, and the
 * enriched result is **returned to the caller** — the caller (UI / host)
 * decides whether and how to persist it. The old artifact is left byte-for-byte
 * unchanged by this seam.
 *
 * Safety contract (mirrors Harmony `enrichEventFromPayload` reduced to the
 * Android `IndexedEvent` scope):
 *  - `event_id` on every model reply must resolve to a **real** indexed event id
 *    in the current scope — unknown, duplicate or out-of-bounds ids are dropped;
 *  - `keywords` are deduped with the real deterministic rule keywords and capped;
 *  - `domains` are normalized to the strict `eat|wear|live|travel|work|other`
 *    set; `other` is dropped when any specific domain is present, and an empty
 *    set falls back to `['other']` (Harmony parity);
 *  - `entities` are filtered to the strict `person|place|merchant` types with
 *    real bounded name/alias tokens (a model `unknown` type is dropped);
 *  - `todo` is normalized to a whitelisted `type`, a strict ISO `deadline`,
 *    bounded `text`, and an `agent_task` only in the pure-chat `type=buy` case;
 *  - no URI / image / token / secret / external path is ever carried in or out;
 *  - the result is serial, cancellable, auditable and carries no secret.
 */
object BatchEventEnrichTypes {

    /** The strict six-value domain vocabulary the model may emit (Harmony parity). */
    val ADMITTED_DOMAINS: List<String> = listOf("eat", "wear", "live", "travel", "work", "other")

    /** The whitelisted `todo.type` tokens (Harmony `normalizeTodoType`). */
    val TODO_TYPE_ALLOWED: List<String> = listOf(
        "buy", "pay", "pickup", "order",
        "renew", "book", "repair", "appointment",
        "attend", "plan", "depart", "check_in", "reimburse",
        "other",
    )

    /** Cap on keywords admitted for one event after dedup. */
    const val MAX_KEYWORDS_PER_EVENT: Int = 40

    /** Cap on domains admitted for one event (after the `other` coercion). */
    const val MAX_DOMAINS_PER_EVENT: Int = 6

    /** Cap on entities admitted for one event. */
    const val MAX_ENTITIES_PER_EVENT: Int = 24

    /** Cap on field-name/keyword/todo-text character widths (over-bounds dropped). */
    const val MAX_TOKEN_CHARACTERS: Int = 120

    /**
     * The bounded enrich fields produced for one real event. `eventId` always
     * references a real indexed event (never fabricated). The event's identity
     * fields (date / summary / normalized_fact / workflow_scene_ids / record_id)
     * are never changed by enrichment.
     */
    data class EnrichedEventFields(
        val eventId: String = "",
        val keywords: List<String> = emptyList(),
        val domains: List<String> = emptyList(),
        val entities: List<EntityMention> = emptyList(),
        val todoType: String = "",
        val todoDeadline: String = "",
        val todoText: String = "",
        val todoAgentTask: String = "",
        /** Auditable, secret-free provenance (e.g. `model_batch`, `heuristic_batch_fallback`). */
        val enrichmentMethod: String = "",
    )

    /** Honest, countable outcome of one batch-enrichment attempt. */
    enum class EnrichmentStatus(val token: String) {
        /** No real indexed events → zero network, nothing to enrich. */
        EMPTY("empty"),
        /** Deterministic local enrich kept; model never invited (gate refused). */
        LOCAL_ONLY("local_only"),
        /** Model invited and at least one event's enrich fields were admitted. */
        MODEL("model"),
        /** The model path failed / every reply dropped → deterministic local enrich kept. */
        FALLBACK("fallback"),
    }

    /**
     * The enrichment outcome returned to the explicit caller, keyed by real event
     * id. The persisted index is untouched — this is a new result surface only.
     */
    data class EnrichmentResult(
        val status: EnrichmentStatus,
        val eventCount: Int = 0,
        val admittedEventCount: Int = 0,
        val droppedModelEvents: Int = 0,
        val generatedAt: String = "",
        /** The enriched fields per real event for a MODEL success; empty otherwise. */
        val enrichedFields: Map<String, EnrichedEventFields> = emptyMap(),
    )
}
