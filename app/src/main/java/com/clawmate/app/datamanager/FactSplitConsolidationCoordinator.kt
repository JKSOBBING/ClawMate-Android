package com.clawmate.app.datamanager

import com.clawmate.app.datamanager.CloudConsolidationTypes.CandidateFact
import com.clawmate.app.datamanager.CloudConsolidationTypes.ConsolidationPath
import com.clawmate.app.datamanager.CloudConsolidationTypes.ModelConsolidationResult
import com.clawmate.app.datamanager.FactSplitTypes.FactSplitResult
import com.clawmate.app.datamanager.FactSplitTypes.FactSplitStatus
import com.clawmate.app.datamanager.FactSplitTypes.SplitFact
import java.io.File
import kotlinx.coroutines.CancellationException

/**
 * Phase-38 **downstream coordinator** that genuinely consumes the Phase-37
 * fact-splitting capability into an **existing** model-aware consolidation
 * engine and persists the consolidated outputs / index / profile.
 *
 * It is the thing that replaces the Phase-37 "candidate Map returned, never
 * persisted" behaviour with a real, user-triggered, persisted run:
 *
 *  1. Run [FactSplitRunner.splitFacts] over the **real** daily-log scope
 *     (model-aware when the action is explicit + fully configured; otherwise
 *     deterministic local-only / EMPTY — zero network).
 *  2. Convert the returned candidate facts (keyed by real `source_ref`) into
 *     bounded [CandidateFact]s.
 *  3. Feed them into [CloudConsolidationRepository.consolidateWithSplitFacts],
 *     which applies them through the existing [ModelConsolidationApplier] with
 *     the same strict model dedup and **persists** per-group state.json +
 *     markdown + the state-index (outputs) and re-derives the local index /
 *     profile summary.
 *
 * No second consolidation engine is introduced: the grouping, dedup, and
 * persistence all live in the existing [CloudConsolidationRepository] /
 * [ModelConsolidationApplier]; this coordinator only bridges the fact-split
 * result into that engine and reports honest combined state.
 *
 * Safety invariants (unchanged from its parts):
 *  - default disabled / zero network unless an explicit + fully-configured
 *    action and real daily-log entries;
 *  - the model path is serial / bounded / cancellable; cancellation rethrows
 *    [CancellationException];
 *  - nothing runs from Application / Home load / recomposition / a plain
 *    constructor — only an explicit caller (the Settings action) invokes [run];
 *  - the split facts never carry a URI / token / secret;
 *  - a failed / malformed / unknown-id split always falls back to the
 *    deterministic local split and the prior valid persisted state is preserved.
 */
class FactSplitConsolidationCoordinator(
    private val baseDir: File,
    private val factSplitRunner: FactSplitRunner = FactSplitRunner(baseDir),
    private val repository: CloudConsolidationRepository = CloudConsolidationRepository(baseDir),
    private val indexService: IndexService = IndexService(baseDir),
    private val profileIndexBuilder: ProfileIndexBuilder = ProfileIndexBuilder(baseDir),
) {

    /**
     * Run an explicit fact-split → persisted consolidation. [mode] is forwarded
     * to [FactSplitRunner.splitFacts] (its production default is LocalOnly →
     * zero network). When the fact split short-circuits (EMPTY because no real
     * entries, or LOCAL_ONLY because the model gate refused), the coordinator
     * reports the corresponding typed status and — because there is no usable
     * model fact split — leaves the persisted state untouched (no writes).
     */
    suspend fun run(
        mode: FactSplitRunner.SplitMode = FactSplitRunner.SplitMode.LocalOnly,
        clientProvider: ((ConsolidationModelConfig) -> ConsolidationModelClient)? = null,
        isCancelled: () -> Boolean = { false },
    ): CoordinatorResult {
        if (isCancelled()) throw CancellationException("fact-split consolidation cancelled before start")

        val split = factSplitRunner.splitFacts(
            mode = mode,
            clientProvider = clientProvider,
            isCancelled = isCancelled,
        )
        val splitStatus = split.status
        when (splitStatus) {
            FactSplitStatus.EMPTY -> return CoordinatorResult(
                status = CoordinatorStatus.EMPTY,
                factSplit = split,
                consolidation = null,
            )
            FactSplitStatus.LOCAL_ONLY -> return CoordinatorResult(
                status = CoordinatorStatus.LOCAL_ONLY,
                factSplit = split,
                consolidation = null,
            )
            FactSplitStatus.MODEL, FactSplitStatus.FALLBACK -> {
                // Proceed: a MODEL split carries admitted model facts; FALLBACK
                // carries the deterministic local split (never fabricated). Both
                // are safe real candidate facts to consolidate + persist.
            }
        }

        val modelConfig = when (mode) {
            is FactSplitRunner.SplitMode.ModelRequested -> mode.config
            FactSplitRunner.SplitMode.LocalOnly -> null
        }
        val candidateFacts = toCandidateFacts(split.facts)

        // A FALLBACK split means the model fact path failed; consolidating still
        // persists real deterministic facts, so the result is honest about it.
        val consolidation = runCatching {
            repository.consolidateWithSplitFacts(
                explicitAction = true,
                modelConfig = modelConfig,
                modelClientProvider = clientProvider ?: ConsolidationModelClientFactory::client,
                splitFacts = candidateFacts,
                isCancelled = isCancelled,
            )
        }.getOrElse { e ->
            if (e is CancellationException) throw e
            ModelConsolidationResult.error(sourceEntries = split.sourceCount, message = e.message ?: "consolidation failed")
        }

        // Rebuild the event index + profile from the freshly persisted states so
        // the persisted consolidated outputs are mirrored into the index/profile
        // artifacts (the same index/profile rebuild the local periodic job runs).
        if (consolidation.error == null && consolidation.totalRecordsAfter > 0) {
            val states = loadPersistedStates()
            if (states.isNotEmpty()) {
                indexService.rebuild(states)
                profileIndexBuilder.buildLocal()
            }
        }

        val status = when (splitStatus) {
            FactSplitStatus.MODEL -> CoordinatorStatus.MODEL
            FactSplitStatus.FALLBACK -> CoordinatorStatus.FALLBACK
            else -> CoordinatorStatus.LOCAL_ONLY
        }
        return CoordinatorResult(
            status = status,
            factSplit = split,
            consolidation = consolidation,
        )
    }

    /** Re-read the persisted consolidated states from the outputs tree (never creates). */
    private fun loadPersistedStates(): List<LocalConsolidationTypes.ConsolidatedState> {
        val outputs = DataManagerPaths.outputsRoot(baseDir)
        if (!outputs.isDirectory) return emptyList()
        val states = ArrayList<LocalConsolidationTypes.ConsolidatedState>()
        val store = AtomicJsonStore()
        outputs.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }?.forEach { monthDir ->
            monthDir.listFiles()?.filter { it.name.endsWith(".state.json") }?.sortedBy { it.name }?.forEach { file ->
                store.readJson(file) { LocalConsolidationCodec.decodeState(it) }
                    ?.let { states.add(LocalConsolidationCodec.rebindRecords(it)) }
            }
        }
        return states
    }

    /** Convert the bounded fact-split surface into the engine's [CandidateFact]s. */
    internal fun toCandidateFacts(splitFacts: Map<String, List<SplitFact>>): Map<String, List<CandidateFact>> {
        val out = LinkedHashMap<String, List<CandidateFact>>()
        for ((ref, facts) in splitFacts) {
            out[ref] = facts.map { f ->
                CandidateFact(
                    summary = f.summary,
                    normalizedFact = f.normalizedFact,
                    eventDate = f.eventDate,
                    tags = f.tags,
                    modelNotes = f.modelNotes,
                    rawExcerpt = f.rawExcerpt,
                    sourceRef = f.sourceRef,
                    sourceDate = f.sourceDate,
                    workflowSceneId = f.workflowSceneId,
                    dedupText = f.dedupText,
                )
            }
        }
        return out
    }

    /** Honest, countable combined status of one explicit run. */
    enum class CoordinatorStatus(val token: String) {
        /** No real daily-log entries → zero network; nothing persisted. */
        EMPTY("empty"),
        /** Model gate refused → deterministic local split returned, nothing persisted. */
        LOCAL_ONLY("local_only"),
        /** Model fact path admitted ≥1 ref and consolidation persisted outputs/index/profile. */
        MODEL("model"),
        /** Model fact path failed → deterministic local facts consolidated + persisted (PARTIAL). */
        FALLBACK("fallback"),
    }

    data class CoordinatorResult(
        val status: CoordinatorStatus,
        val factSplit: FactSplitResult,
        val consolidation: ModelConsolidationResult?,
    ) {
        val persisted: Boolean get() = consolidation != null &&
            (consolidation.path == ConsolidationPath.MODEL ||
                consolidation.path == ConsolidationPath.PARTIAL ||
                consolidation.path == ConsolidationPath.LOCAL) &&
            consolidation.error == null
    }
}
