package com.clawmate.app.datamanager

import com.clawmate.app.datamanager.CloudConsolidationTypes.CandidateFact
import com.clawmate.app.datamanager.FactSplitTypes.FactSplitResult
import com.clawmate.app.datamanager.FactSplitTypes.FactSplitSourceOutcome
import com.clawmate.app.datamanager.FactSplitTypes.FactSplitStatus
import com.clawmate.app.datamanager.FactSplitTypes.SplitFact
import com.clawmate.app.datamanager.FactSplitTypes.SplitSourceScope
import java.io.File
import kotlinx.coroutines.CancellationException

/**
 * The single explicit, testable **fact-splitting** seam for Phase 37 (a faithful
 * Android port of the Harmony `DataPipelineModelClient.splitEntriesIntoFacts` /
 * `splitEntryIntoFacts` fact-batching path).
 *
 * Invoking [splitFacts] loads the **real** daily-log entries from the app-private
 * tree ([DailyLogEventSource]), computes the deterministic local heuristic split
 * (always-safe, zero-network baseline), and — **only** for an explicit,
 * fully-configured, user-triggered action that also has real entries — invites
 * the shared `ChatClient` via the existing Phase-34 [ConsolidationModelClient]
 * to split the bounded entries into candidate facts. The strictly-whitelisted
 * model facts are returned to the caller keyed by the entries' **real**
 * `source_ref`; the caller (UI / host) decides whether to persist. By default no
 * artifact is changed.
 *
 * Honest outcomes:
 *  - **EMPTY** — no real daily-log entries → zero network, nothing split;
 *  - **LOCAL_ONLY** — model not configured / not an explicit action → zero network,
 *    the deterministic local split is returned as-is;
 *  - **MODEL** — the model was invited and at least one `source_ref`'s facts were
 *    admitted;
 *  - **FALLBACK** — the model path failed / malformed JSON / every reply dropped →
 *    the deterministic local split is preserved for the affected refs.
 *
 * Safety boundaries:
 *  - **Zero network** unless configured + an explicit action + real entries.
 *  - **Serial / bounded / cancellable** model calls; a cancelled run rethrows
 *    [CancellationException].
 *  - The model path **never** calls the persisted [CloudConsolidationRepository]
 *    and never runs from Home / launch / recomposition — only an explicit action.
 *  - Only **real** `source_ref` / `source_date` / `workflow_scene_id` are accepted;
 *    unknown / duplicate / out-of-scope refs and out-of-bounds dates are dropped.
 */
class FactSplitRunner(
    private val baseDir: File,
    private val eventSource: DailyLogEventSource = DailyLogEventSourceFactory.appPrivate(baseDir),
) {

    /** Gating/scope for one fact-split action. The production default is [LocalOnly]. */
    sealed interface SplitMode {
        data object LocalOnly : SplitMode

        /** An explicit user-triggered action that *may* invite a model when fully configured. */
        data class ModelRequested(val explicitAction: Boolean, val config: ConsolidationModelConfig?) : SplitMode
    }

    /**
     * Run the fact split. [mode] defaults to local-only (zero network). When the
     * model path is allowed, [clientProvider] builds a [ConsolidationModelClient]
     * from a fully configured [ConsolidationModelConfig] (tests inject a fake).
     * Returns a [FactSplitResult] keyed by real `source_ref`; it never persists.
     */
    suspend fun splitFacts(
        mode: SplitMode = SplitMode.LocalOnly,
        clientProvider: ((ConsolidationModelConfig) -> ConsolidationModelClient)? = null,
        isCancelled: () -> Boolean = { false },
    ): FactSplitResult {
        if (isCancelled()) throw CancellationException("fact split cancelled before start")

        val scopes = loadRealScopes()
        if (scopes.isEmpty()) {
            return FactSplitResult(status = FactSplitStatus.EMPTY, sourceCount = 0, generatedAt = nowIsoLocal())
        }

        // Deterministic local split (always-safe, zero-network baseline).
        val local = splitDeterministic(scopes)

        // ---- Gate: only an explicit + fully-configured action may invite the model. ----
        val cfg = resolveConfig(mode)
        if (cfg == null || clientProvider == null) {
            return FactSplitResult(
                status = FactSplitStatus.LOCAL_ONLY,
                sourceCount = scopes.size,
                admittedSourceCount = scopes.size,
                generatedAt = nowIsoLocal(),
                facts = local,
            )
        }
        if (isCancelled()) throw CancellationException("fact split cancelled before model call")

        val client = clientProvider(cfg)
        // Track which source refs fell back to the deterministic local split so we
        // can honestly report MODEL (>=1 ref model-admitted) vs FALLBACK (every
        // reply malformed/dropped) rather than assuming the model succeeded.
        val fallbackRefs = LinkedHashSet<String>()
        val modelFacts: Map<String, List<CandidateFact>> = try {
            client.splitEntriesIntoFacts(
                entries = scopes.take(FactSplitTypes.Bounds.MAX_MODEL_SOURCES)
                    .map { it.toDailyLogEntry() },
                onSourceFallback = { ref -> fallbackRefs.add(ref) },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyMap()
        }
        if (isCancelled()) throw CancellationException("fact split cancelled after model call")

        val validRefs = scopes.map { it.sourceRef }.toSet()
        // Only real source_refs in scope; unknown/duplicate refs are never admitted.
        val modelAdmitted = LinkedHashMap<String, List<SplitFact>>()
        val dropped = ArrayList<String>()
        for ((ref, facts) in modelFacts) {
            if (ref !in validRefs) { dropped.add(ref); continue }
            if (facts.isEmpty()) { dropped.add(ref); continue }
            // A ref that fell back to the deterministic split is not "model-admitted".
            if (ref in fallbackRefs) continue
            modelAdmitted[ref] = facts.map(::toSplitFact)
        }

        val modelAdmittedRefs = modelAdmitted.keys.toSet()
        if (modelAdmittedRefs.isEmpty()) {
            return FactSplitResult(
                status = FactSplitStatus.FALLBACK,
                sourceCount = scopes.size,
                admittedSourceCount = scopes.size,
                droppedModelItems = dropped.size,
                generatedAt = nowIsoLocal(),
                facts = local,
            )
        }

        // Merge: model-admitted refs win, the rest keep the deterministic local split.
        val merged = LinkedHashMap<String, List<SplitFact>>()
        for (scope in scopes) {
            val ref = scope.sourceRef
            merged[ref] = modelAdmitted[ref] ?: (local[ref] ?: deterministicSplit(scope))
        }
        return FactSplitResult(
            status = FactSplitStatus.MODEL,
            sourceCount = scopes.size,
            admittedSourceCount = modelAdmittedRefs.size,
            droppedModelItems = dropped.size,
            generatedAt = nowIsoLocal(),
            facts = merged,
        )
    }

    /** Per-source audit of which refs used the model vs the local split (caller-facing). */
    fun sourceOutcomes(scopes: List<SplitSourceScope>, modelRefs: Set<String>): List<FactSplitSourceOutcome> =
        scopes.map { scope ->
            FactSplitSourceOutcome(
                sourceRef = scope.sourceRef,
                usedModel = scope.sourceRef in modelRefs,
                factCount = if (scope.sourceRef in modelRefs) 1 else 0,
            )
        }

    /** Deterministic, zero-network split for every real scope (Harmony fallback parity). */
    private fun splitDeterministic(scopes: List<SplitSourceScope>): Map<String, List<SplitFact>> {
        val out = LinkedHashMap<String, List<SplitFact>>()
        for (scope in scopes) out[scope.sourceRef] = deterministicSplit(scope)
        return out
    }

    /** Bounded deterministic split for one real scope (mirrors `ConsolidationModelClient.heuristicSplit`). */
    internal fun deterministicSplit(scope: SplitSourceScope): List<SplitFact> {
        val entry = scope.toDailyLogEntry()
        return deterministicHeuristicSplit(entry).map(::toSplitFact)
    }

    private fun resolveConfig(mode: SplitMode): ConsolidationModelConfig? {
        val explicit = when (mode) {
            is SplitMode.ModelRequested -> mode.explicitAction
            SplitMode.LocalOnly -> false
        }
        if (!explicit) return null
        val config = when (mode) {
            is SplitMode.ModelRequested -> mode.config
            SplitMode.LocalOnly -> null
        }
        // Reuse the same explicit gate as the other seams: only a fully configured
        // config may proceed.
        return if (config != null) ConsolidationModelConfig.fromValues(config.baseUrl, config.apiKey, config.modelName) else null
    }

    /** Load + validate the **real** daily-log scope (refuses fabricated/invalid entries). */
    private fun loadRealScopes(): List<SplitSourceScope> {
        val entries = runCatching { eventSource.load() }
            .getOrDefault(DailyLogScanResult(emptyList(), emptyList()))
            .entries
        val seen = HashSet<String>()
        val scopes = ArrayList<SplitSourceScope>()
        for (entry in entries) {
            val scope = SplitSourceScope(
                sourceDate = entry.sourceDate,
                sourceRef = entry.sourceRef,
                workflowSceneId = entry.workflowMetadata["task_scene_id"]?.trim()?.toIntOrNull() ?: -1,
                entryText = entry.text,
                fileName = entry.fileName,
                entryIndex = entry.entryIndex,
                workflowMetadata = entry.workflowMetadata,
            )
            // Refuse fabricated/empty scope; replace duplicate source_refs (only the
            // first real occurrence is admitted so a ref can never be split twice).
            if (!scope.isReal) continue
            if (seen.add(scope.sourceRef)) scopes.add(scope)
        }
        return scopes
    }

    private fun toSplitFact(fact: CandidateFact): SplitFact = SplitFact(
        summary = fact.summary,
        normalizedFact = fact.normalizedFact,
        eventDate = fact.eventDate,
        tags = fact.tags,
        modelNotes = fact.modelNotes,
        rawExcerpt = fact.rawExcerpt,
        sourceRef = fact.sourceRef,
        sourceDate = fact.sourceDate,
        workflowSceneId = fact.workflowSceneId,
        dedupText = fact.dedupText,
    )

    private fun nowIsoLocal(): String =
        java.time.LocalDateTime.now(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))
}

/** Small mapping helper so [SplitSourceScope] can be fed to [ConsolidationModelClient]. */
internal fun SplitSourceScope.toDailyLogEntry(): DailyLogEntry = DailyLogEntry(
    sourceDate = sourceDate,
    fileName = fileName,
    entryIndex = entryIndex,
    text = entryText,
    workflowMetadata = workflowMetadata,
    latestEntryIndex = entryIndex,
    sourceRef = sourceRef,
)
