package com.clawmate.app.datamanager

import com.clawmate.app.datamanager.BatchEventEnrichTypes.EnrichedEventFields
import com.clawmate.app.datamanager.BatchEventEnrichTypes.EnrichmentResult
import com.clawmate.app.datamanager.BatchEventEnrichTypes.EnrichmentStatus
import com.clawmate.app.datamanager.LocalConsolidationTypes.IndexedEvent
import kotlinx.coroutines.CancellationException
import java.io.File

/**
 * The single explicit, testable **generic batch event-enrichment** seam for
 * Phase 36 (a faithful Android port of the Harmony `DataPipelineModelClient.
 * enrichEvents` path over the Phase-30 deterministic `IndexedEvent` set).
 *
 * Invoking [enrich] loads the real indexed events, computes the deterministic
 * local enrich (always-safe, zero-network baseline), and — only for an explicit,
 * fully-configured, user-triggered action that also has real events — invites the
 * model to extract keywords / domains / entities / todo for the bounded events.
 * The strictly-validated model enrich fields are merged with the deterministic
 * baseline and returned to the caller keyed by **real** event id; the caller
 * (UI/host) decides whether to persist. By default no artifact is changed.
 *
 * Honest outcomes:
 *  - **EMPTY** — no real indexed events → zero network, nothing enriched;
 *  - **LOCAL_ONLY** — model not configured / not an explicit action → zero network,
 *    the deterministic local enrich is returned as-is;
 *  - **MODEL** — the model was invited, its reply strictly validated, and at least
 *    one event's enrich fields were admitted;
 *  - **FALLBACK** — the model path failed / malformed JSON / every reply dropped →
 *    the deterministic local enrich is preserved.
 *
 * Safety boundaries:
 *  - **Zero network** unless configured + an explicit action + real events.
 *  - **Serial / bounded / cancellable** model calls; a cancelled run rethrows
 *    [CancellationException].
 *  - The model path **never** calls the model-backed [CloudConsolidationRepository]
 *    and never runs from Home/background — only an explicit action.
 *  - Only **real** event ids are enriched; the model can never fabricate an event,
 *    id, keyword, domain or entity; `workflow_scene_ids` / record identity / date
 *    are preserved from the real local scope and never rewritten by enrichment.
 */
class BatchEventEnricher(
    private val baseDir: File,
    private val eventLoader: (() -> List<IndexedEvent>)? = { ProfileIndexBuilder(baseDir).loadEvents() },
) {

    /**
     * Gating/scope for one enrichment action. The production default is
     * [LocalOnly] (zero network); a caller may pass [ModelRequested] only from an
     * explicit, user-triggered action.
     */
    sealed interface EnrichMode {
        data object LocalOnly : EnrichMode

        /** An explicit user-triggered action that *may* invite a model when fully configured. */
        data class ModelRequested(val explicitAction: Boolean, val config: ProfileModelConfig?) : EnrichMode
    }

    /**
     * Run the batch enrichment. [mode] defaults to local-only (zero network).
     * When the model path is allowed, [clientProvider] builds the model client
     * from a fully configured [ConsolidationModelConfig] (tests inject a fake).
     * Returns an [EnrichmentResult] keyed by real event id; it never persists.
     */
    suspend fun enrich(
        mode: EnrichMode = EnrichMode.LocalOnly,
        clientProvider: ((ConsolidationModelConfig) -> BatchEventEnrichModelClient)? = null,
        isCancelled: () -> Boolean = { false },
    ): EnrichmentResult {
        if (isCancelled()) throw CancellationException("batch enrich cancelled before start")

        val events = loadEventsSafe()
        if (events.isEmpty()) {
            return EnrichmentResult(status = EnrichmentStatus.EMPTY, eventCount = 0, generatedAt = nowIsoLocal())
        }

        // Deterministic local enrich (always-safe, zero-network baseline).
        val local = enrichDeterministic(events)

        // ---- Gate: only an explicit + fully-configured action may invite the model. ----
        val cfg = resolveConfig(mode)
        if (cfg == null || clientProvider == null) {
            // Local-only / not configured / not an explicit action -> zero network.
            return EnrichmentResult(
                status = EnrichmentStatus.LOCAL_ONLY,
                eventCount = events.size,
                admittedEventCount = events.size,
                generatedAt = nowIsoLocal(),
                enrichedFields = local,
            )
        }
        if (isCancelled()) throw CancellationException("batch enrich cancelled before model call")

        val realIds = events.map { it.eventId }.toSet()
        val client = clientProvider(cfg)
        val modelFields: Map<String, EnrichedEventFields> = try {
            client.enrich(events.take(MAX_MODEL_EVENTS), realIds)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyMap()
        }
        if (isCancelled()) throw CancellationException("batch enrich cancelled after model call")

        if (modelFields.isEmpty()) {
            return EnrichmentResult(
                status = EnrichmentStatus.FALLBACK,
                eventCount = events.size,
                admittedEventCount = events.size,
                droppedModelEvents = 0,
                generatedAt = nowIsoLocal(),
                enrichedFields = local,
            )
        }
        val merged = LinkedHashMap<String, EnrichedEventFields>()
        for (event in events) {
            merged[event.eventId] = modelFields[event.eventId] ?: (local[event.eventId] ?: deterministicEnrich(event))
        }
        return EnrichmentResult(
            status = EnrichmentStatus.MODEL,
            eventCount = events.size,
            admittedEventCount = merged.size,
            droppedModelEvents = events.size - (events.count { it.eventId in modelFields }),
            generatedAt = nowIsoLocal(),
            enrichedFields = merged,
        )
    }

    /** Deterministic, zero-network enrich for every real event (Harmony fallback parity). */
    private fun enrichDeterministic(events: List<IndexedEvent>): Map<String, EnrichedEventFields> {
        val out = LinkedHashMap<String, EnrichedEventFields>()
        for (event in events) {
            out[event.eventId] = deterministicEnrich(event)
        }
        return out
    }

    /** Bounded deterministic enrich for one event (keywords/domains/entities; no todo). */
    internal fun deterministicEnrich(event: IndexedEvent): EnrichedEventFields {
        val keywords = EnrichRules.deriveRuleKeywords(event)
            .take(BatchEventEnrichTypes.MAX_KEYWORDS_PER_EVENT)
        var domains = EnrichRules.normalizeDomains(
            EnrichRules.deriveRuleDomains(event),
        )
        if (domains.isEmpty()) domains = listOf("other")
        val entities = SceneEntityNormalizer.deriveRuleEntities(EnrichRules.ruleEntityText(event))
            .take(BatchEventEnrichTypes.MAX_ENTITIES_PER_EVENT)
        return EnrichedEventFields(
            eventId = event.eventId,
            keywords = keywords,
            domains = domains,
            entities = entities,
            enrichmentMethod = FALLBACK_METHOD,
        )
    }

    private fun resolveConfig(mode: EnrichMode): ConsolidationModelConfig? {
        val explicit = when (mode) {
            is EnrichMode.ModelRequested -> mode.explicitAction
            EnrichMode.LocalOnly -> false
        }
        if (!explicit) return null
        val config = when (mode) {
            is EnrichMode.ModelRequested -> mode.config
            EnrichMode.LocalOnly -> null
        }
        // Reuse the same explicit gate as the entity-enrichment / model seams:
        // only a fully configured config may proceed.
        return if (config != null) ConsolidationModelConfig.fromValues(config.baseUrl, config.apiKey, config.modelName) else null
    }

    private fun loadEventsSafe(): List<IndexedEvent> =
        runCatching { eventLoader?.invoke().orEmpty() }.getOrDefault(emptyList())

    private fun nowIsoLocal(): String =
        java.time.LocalDateTime.now(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))

    companion object {
        /** Honest provenance marker for the deterministic fallback enrich. */
        const val FALLBACK_METHOD: String = "heuristic_batch_fallback"

        /** Cap on real events invited to the model in one explicit action. */
        const val MAX_MODEL_EVENTS: Int = 2000
    }
}
