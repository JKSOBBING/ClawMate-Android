package com.clawmate.app.datamanager

import com.clawmate.app.datamanager.EntityEnrichmentTypes.EntityCandidate
import com.clawmate.app.datamanager.EntityEnrichmentTypes.EnrichmentResult
import com.clawmate.app.datamanager.EntityEnrichmentTypes.EnrichmentStatus
import com.clawmate.app.datamanager.EntityEnrichmentTypes.RichEntity
import com.clawmate.app.datamanager.EntitySceneIndexTypes.EntityDefinition
import com.clawmate.app.datamanager.EntitySceneIndexTypes.EntityDocument
import com.clawmate.app.datamanager.LocalConsolidationTypes.IndexedEvent
import kotlinx.coroutines.CancellationException
import java.io.File

/**
 * The single explicit, testable **model entity enrichment** seam for Phase 35
 * (model-assisted enrichment of the Phase-33 deterministic `EntitySceneIndex`).
 *
 * Invoking [enrich] loads the real event scope, computes the deterministic local
 * entity document (read-only; the persisted artifact is **never written** from
 * this seam), and — only for an explicit, fully-configured, user-triggered action
 * that also has real entity candidates — invites the model to clarify the bounded
 * candidates. The strictly-validated model output is merged into a **new** entity
 * document that is returned to the caller; the caller (UI/host) decides whether
 * to persist it. By default the old artifact stays byte-for-byte unchanged.
 *
 * Honest outcomes:
 *  - **EMPTY** — no real entity candidates → zero network, nothing enriched;
 *  - **LOCAL_ONLY** — model not configured / not an explicit action → zero network,
 *    the deterministic local document is returned as-is;
 *  - **MODEL** — the model was invited, its reply strictly validated, and at least
 *    one enriched entity was admitted;
 *  - **FALLBACK** — the model path failed / malformed JSON / every candidate
 *    dropped → the deterministic local document is preserved.
 *
 * Safety boundaries:
 *  - **Zero network** unless configured + an explicit action + real candidates.
 *  - **Serial / bounded / cancellable** model call; a cancelled run rethrows
 *    [CancellationException].
 *  - The model path **never** calls the model-backed [CloudConsolidationRepository]
 *    and never runs from Home/background — only an explicit action.
 *  - All records/source refs are whitelisted against the current scope; the model
 *    can never invent facts or ids; `workflow_scene_ids` / `entity_mentions` /
 *    `source_ref` / `date` are preserved from the real local scope.
 */
class EntityEnrichmentRunner(
    private val baseDir: File,
    private val eventLoader: (() -> List<IndexedEvent>)? = { ProfileIndexBuilder(baseDir).loadEvents() },
    private val entityBuilder: EntitySceneIndexBuilder = EntitySceneIndexBuilder(baseDir),
) {

    /**
     * Build scope/gating for one enrichment action. The production default is
     * [LocalOnly] (zero network, old artifact unchanged). A caller may pass
     * [ModelRequested] only from an explicit, user-triggered action.
     */
    sealed interface EnrichMode {
        data object LocalOnly : EnrichMode

        /** An explicit user-triggered action that *may* invite a model when fully configured. */
        data class ModelRequested(val explicitAction: Boolean, val config: ProfileModelConfig?) : EnrichMode
    }

    /**
     * Run the enrichment. [mode] defaults to local-only (zero network). When the
     * model path is allowed, [clientProvider] builds the model client from a fully
     * configured [ConsolidationModelConfig] (tests inject a fake). A cancelled run
     * throws [CancellationException]. Returns a new [EnrichmentResult] with the
     * candidate enriched document; it never writes the persisted artifact.
     */
    suspend fun enrich(
        mode: EnrichMode = EnrichMode.LocalOnly,
        clientProvider: ((ConsolidationModelConfig) -> EntityEnrichmentModelClient)? = null,
        isCancelled: () -> Boolean = { false },
    ): EnrichmentResult {
        if (isCancelled()) throw CancellationException("entity enrichment cancelled before start")

        val events = loadEventsSafe()
        val localScope = buildLocalScope(events)
        val candidates = collectCandidates(events, localScope)
        if (candidates.isEmpty() || localScope.recordIds.isEmpty()) {
            return EnrichmentResult(status = EnrichmentStatus.EMPTY, candidateCount = 0, generatedAt = nowIsoLocal())
        }

        // ---- Gate: only an explicit + fully-configured action may invite the model. ----
        val gate = resolveGate(mode)
        val config = gate.config
        val localDocument = readLocalDocument()
        if (config == null || clientProvider == null) {
            // Local-only / not configured / not an explicit action -> zero network.
            return EnrichmentResult(
                status = EnrichmentStatus.LOCAL_ONLY,
                candidateCount = candidates.size,
                admittedEntityCount = 0,
                generatedAt = nowIsoLocal(),
                enrichedDocument = localDocument,
            )
        }
        if (isCancelled()) throw CancellationException("entity enrichment cancelled before model call")

        val client = clientProvider(config)
        val enriched = try {
            client.enrich(candidates, localScope.recordIds, localScope.sourceRefs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        if (isCancelled()) throw CancellationException("entity enrichment cancelled after model call")

        if (enriched.isEmpty()) {
            return EnrichmentResult(
                status = EnrichmentStatus.FALLBACK,
                candidateCount = candidates.size,
                admittedEntityCount = 0,
                generatedAt = nowIsoLocal(),
                enrichedDocument = localDocument,
            )
        }
        val merged = mergeEnriched(localDocument, enriched, events)
        return EnrichmentResult(
            status = EnrichmentStatus.MODEL,
            candidateCount = candidates.size,
            admittedEntityCount = merged.entities.size - localDocument.entities.size,
            generatedAt = nowIsoLocal(),
            enrichedDocument = merged,
        )
    }

    // ---------------------------------------------------------------------
    // Scope + candidate collection (real events only, bounded, no secrets)
    // ---------------------------------------------------------------------

    private data class LocalScope(
        val recordIds: Set<Int>,
        val sourceRefs: Set<String>,
    )

    private fun buildLocalScope(events: List<IndexedEvent>): LocalScope {
        val recordIds = HashSet<Int>()
        val sourceRefs = HashSet<String>()
        for (event in events) {
            event.recordId.takeIf { it > 0 }?.let(recordIds::add)
            event.sourceRefs.forEach { sourceRefs.add(ConsolidationText.normalizeFreeText(it)) }
        }
        return LocalScope(recordIds, sourceRefs)
    }

    /**
     * Build bounded, desensitized entity candidates from the real events. Each
     * candidate carries real record ids / source refs / scene ids / date already in
     * scope. No URI/image/token/external path is ever included.
     */
    private fun collectCandidates(events: List<IndexedEvent>, scope: LocalScope): List<EntityCandidate> {
        val out = ArrayList<EntityCandidate>()
        val seen = HashSet<String>()
        for (event in events) {
            val mentions = entityBuilder.eventMentions(event)
            for (mention in mentions) {
                val type = ConsolidationText.normalizeFreeText(mention.type).lowercase()
                if (type !in EntityEnrichmentTypes.ADMITTED_TYPES) continue
                val name = SceneEntityNormalizer.normalizeEntityName(mention.name)
                if (name.isEmpty() || name.length > EntityEnrichmentTypes.MAX_ENTITY_NAME_CHARACTERS) continue
                val recordId = event.recordId
                if (recordId !in scope.recordIds) continue
                val sourceRefs = event.sourceRefs.map(ConsolidationText::normalizeFreeText).filter { it in scope.sourceRefs }
                if (sourceRefs.isEmpty()) continue
                val key = "${type}:${name}:$recordId:${sourceRefs.sorted().joinToString("|")}"
                if (!seen.add(key)) continue
                out.add(
                    EntityCandidate(
                        candidateId = stableCandidateId(type, name, recordId, sourceRefs),
                        type = type,
                        name = name,
                        aliases = SceneEntityNormalizer.uniqueEntityNames(mention.aliases),
                        recordIds = listOf(recordId),
                        sourceRefs = sourceRefs,
                        sceneIds = SceneEntityNormalizer.normalizeSceneIds(event.workflowSceneIds),
                        date = event.eventDate,
                    ),
                )
                if (out.size >= EntityEnrichmentTypes.MAX_CANDIDATES_PER_PROMPT) return out
            }
        }
        return out
    }

    private fun stableCandidateId(type: String, name: String, recordId: Int, sourceRefs: List<String>): String =
        "c_" + Integer.toHexString(
            ("$type\u001f$name\u001f$recordId\u001f${sourceRefs.sorted().joinToString("\u001f")}").hashCode(),
        )

    // ---------------------------------------------------------------------
    // Local document (read-only, deterministic, old artifact preserved)
    // ---------------------------------------------------------------------

    private fun readLocalDocument(): EntityDocument =
        runCatching { entityBuilder.load() }.getOrDefault(EntityDocument())

    /**
     * Merge the strictly-validated model [enriched] entities into [local]. Real
     * event ids come only from [events]; source refs / scene ids / dates are
     * preserved because the underlying real events are never mutated. The model can
     * only refine a canonical entity's name/type/aliases within the whitelist.
     */
    private fun mergeEnriched(
        local: EntityDocument,
        enriched: List<RichEntity>,
        events: List<IndexedEvent>,
    ): EntityDocument {
        val eventIdsByRecord = HashMap<Int, MutableList<String>>()
        for (event in events) {
            event.recordId.takeIf { it > 0 }?.let {
                eventIdsByRecord.getOrPut(it) { ArrayList() }.add(event.eventId)
            }
        }
        val entities = LinkedHashMap<String, EntityDefinition>()
        // Start from the deterministic local entities (authoritative).
        for ((key, def) in local.entities) entities[key] = def

        for (rich in enriched) {
            val richEventIds = rich.recordIds.flatMap { eventIdsByRecord[it].orEmpty() }
            val key = SceneEntityNormalizer.entityDisplayKey(rich.type, rich.name)
            val existing = entities[key]
            val eventIds = if (existing == null) richEventIds
            else ConsolidationText.stableUnique(existing.eventIds + richEventIds)
            entities[key] = EntityDefinition(
                type = rich.type,
                name = rich.name,
                aliases = if (existing == null) rich.aliases else
                    SceneEntityNormalizer.uniqueEntityNames(existing.aliases + rich.aliases),
                eventIds = eventIds.take(EntityEnrichmentTypes.MAX_RECORD_IDS_PER_ENTITY * 4),
            )
        }
        // Preserve alias postings / mention assignments from the deterministic local doc.
        return EntityDocument(
            generatedAt = nowIsoLocal(),
            entities = entities,
            aliasPostings = local.aliasPostings,
            mentionAssignments = local.mentionAssignments,
        )
    }

    // ---------------------------------------------------------------------
    // Gate resolution
    // ---------------------------------------------------------------------

    private data class ResolvedGate(
        val config: ConsolidationModelConfig?,
    )

    private fun resolveGate(mode: EnrichMode): ResolvedGate {
        val explicit = when (mode) {
            is EnrichMode.ModelRequested -> mode.explicitAction
            EnrichMode.LocalOnly -> false
        }
        val gate = EntityModelBoundary.gate(
            explicitAction = explicit,
            config = (mode as? EnrichMode.ModelRequested)?.config,
        )
        return when (gate) {
            EntityModelBoundary.Gate.CONFIGURED -> {
                // Build a ConsolidationModelConfig from the validated profile config fields.
                val p = (mode as? EnrichMode.ModelRequested)?.config
                val cfg = p?.let { ConsolidationModelConfig.fromValues(it.baseUrl, it.apiKey, it.modelName) }
                ResolvedGate(config = cfg)
            }
            else -> ResolvedGate(config = null)
        }
    }

    private fun loadEventsSafe(): List<IndexedEvent> =
        runCatching { eventLoader?.invoke().orEmpty() }.getOrDefault(emptyList())

    private fun nowIsoLocal(): String =
        java.time.LocalDateTime.now(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))
}
