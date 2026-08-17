package com.clawmate.app.datamanager

import com.clawmate.app.datamanager.CloudConsolidationTypes.ConsolidationPath
import com.clawmate.app.datamanager.CloudConsolidationTypes.ModelConsolidationResult
import com.clawmate.app.datamanager.LocalConsolidationTypes.ConsolidatedState
import com.clawmate.app.datamanager.ModelConsolidationApplier.GroupOutcome
import java.io.File

/**
 * Phase-34 **explicit, user-triggered** cloud/model consolidation entry point.
 *
 * This is the single seam that walks the app-private daily-log tree, groups the
 * source entries by month/file, and produces the persisted consolidated states.
 * Unlike the Phase-30 [ConsolidationRepository] (which is locked to the
 * deterministic local path), this repository **may** invoke the shared
 * OpenAI-compatible [ChatClient] model path — but only when **all** of the
 * following hold (the "explicit gate"):
 *
 *  1. the caller passes `explicitAction = true` (i.e. it is a user-initiated
 *     consolidation action, never a launch / Home-load / recomposition /
 *     background / WorkManager trigger); AND
 *  2. a fully-configured [ConsolidationModelConfig] is provided (base URL + API
 *     key + model are non-blank); AND
 *  3. there is real daily-log input (no auto-invented entries).
 *
 * In every other case the run short-circuits to the **deterministic local-only**
 * path (the same [ConsolidationEngine] used by Phase 30), reports a typed
 * LOCAL result, and never makes a single network call. Even a fully-invoked
 * model run preserves the previous valid state on any failure: a bad JSON /
 * request failure / unverifiable matched id falls back to the local heuristic
 * per fact (typed PARTIAL), and the prior persisted state is never overwritten
 * by a failed run.
 *
 * Model decisions are bounded, serial (one request at a time, never parallel),
 * cancellable, and carry no URI/image/token/path. The run is safe to call on any
 * thread; it does its (file + model) work inside the caller's coroutine.
 */
class CloudConsolidationRepository(
    private val baseDir: File,
    private val store: AtomicJsonStore = AtomicJsonStore(),
) {

    private val eventSource: DailyLogEventSource = DailyLogEventSourceFactory.appPrivate(baseDir)

    /**
     * Run an explicit consolidation.
     *
     * @param explicitAction true only when a user-initiated consolidation action
     *   triggers this (never launch/load/background).
     * @param modelConfig fully-configured model config, or null to stay local-only.
     * @param modelClientProvider builds a [ConsolidationModelClient] from a valid
     *   config (the production path wraps the shared `ChatCompletionsClient`);
     *   tests inject a fake [ChatClient]-backed client.
     * @param isCancelled cooperatively-aborted between groups / facts.
     */
    suspend fun consolidate(
        explicitAction: Boolean,
        modelConfig: ConsolidationModelConfig?,
        modelClientProvider: (ConsolidationModelConfig) -> ConsolidationModelClient,
        isCancelled: () -> Boolean = { false },
    ): ModelConsolidationResult {
        if (isCancelled()) throw kotlinx.coroutines.CancellationException("consolidation cancelled")
        val scan = eventSource.load()
        val entries = scan.entries
        if (entries.isEmpty()) {
            // Zero real input -> zero network, no auto-created artifacts, honest empty local.
            return ModelConsolidationResult.local(ConsolidationPath.LOCAL, 0, null)
        }

        val byGroup = LinkedHashMap<String, MutableList<DailyLogEntry>>()
        for (entry in entries) {
            val key = "${ConsolidationText.monthKeyFromDate(entry.sourceDate)}/${entry.fileName}"
            byGroup.getOrPut(key) { ArrayList() }.add(entry)
        }

        // ---- Explicit gate: unconfigured / not a user action -> local-only ----
        if (!explicitAction || modelConfig == null) {
            val groupStates = runLocalGroups(byGroup)
            val local = ConsolidationEngine.summarize(scan, groupStates)
            return ModelConsolidationResult.local(
                path = ConsolidationPath.LOCAL,
                sourceEntries = entries.size,
                localResult = local,
            )
        }

        // ---- Model path (serial, bounded, cancellable, fall-back to local) ----
        val client = modelClientProvider(modelConfig)
        var totalBefore = 0
        var totalAfter = 0
        var groupsProcessed = 0
        var factsSplit = 0
        var factsCreated = 0
        var factsMerged = 0
        var factsSkipped = 0
        var factsFallback = 0
        var hadFallback = false
        var anyModel = false
        val groupStates = LinkedHashMap<String, ConsolidatedState>()

        for ((key, groupEntries) in byGroup) {
            if (isCancelled()) throw kotlinx.coroutines.CancellationException("consolidation cancelled")
            val month = ConsolidationText.monthKeyFromDate(groupEntries.first().sourceDate)
            val fileName = groupEntries.first().fileName
            val previous = loadState(month, fileName)
            totalBefore += previous.records.size
            val outcome: GroupOutcome = ModelConsolidationApplier.applyPending(
                entries = groupEntries,
                previous = previous,
                splitter = { client.splitEntriesIntoFacts(it) },
                decider = { fact, scoped -> client.decideDedup(fact, scoped) },
                modelPresent = true,
                isCancelled = isCancelled,
            )
            if (persistState(month, fileName, outcome.state)) {
                groupStates[key] = outcome.state
                groupsProcessed++
            }
            totalAfter += outcome.state.records.size
            factsSplit += outcome.factsSplit
            factsCreated += outcome.factsCreated
            factsMerged += outcome.factsMerged
            factsSkipped += outcome.factsSkipped
            factsFallback += outcome.factsFallback
            hadFallback = hadFallback || outcome.hadFallback
            anyModel = anyModel || outcome.usedModel
        }

        if (groupStates.isNotEmpty()) persistStateIndex(groupStates.values.toList())
        val local = ConsolidationEngine.summarize(scan, groupStates)
        val path = when {
            hadFallback -> ConsolidationPath.PARTIAL
            anyModel -> ConsolidationPath.MODEL
            else -> ConsolidationPath.LOCAL
        }
        return ModelConsolidationResult(
            path = path,
            sourceEntries = entries.size,
            groupsScanned = groupStates.size,
            groupsProcessed = groupsProcessed,
            totalRecordsBefore = totalBefore,
            totalRecordsAfter = totalAfter,
            factsSplit = factsSplit,
            factsCreated = factsCreated,
            factsMerged = factsMerged,
            factsSkipped = factsSkipped,
            factsFallback = factsFallback,
            updatedAt = ConsolidationText.epochIsoNowUtc(),
            localResult = local,
        )
    }

    // ---------------------------------------------------------------------
    // Deterministic local path (Phase-30 engine, no model / no network)
    // ---------------------------------------------------------------------

    private fun runLocalGroups(byGroup: LinkedHashMap<String, MutableList<DailyLogEntry>>):
        LinkedHashMap<String, ConsolidatedState> {
        val states = LinkedHashMap<String, ConsolidatedState>()
        for ((key, groupEntries) in byGroup) {
            val month = ConsolidationText.monthKeyFromDate(groupEntries.first().sourceDate)
            val fileName = groupEntries.first().fileName
            val previous = loadState(month, fileName)
            val next = ConsolidationEngine.consolidate(groupEntries, previous)
            if (persistState(month, fileName, next)) states[key] = next
        }
        if (states.isNotEmpty()) persistStateIndex(states.values.toList())
        return states
    }

    // ---------------------------------------------------------------------
    // Persistence (identical layout to the Phase-30 local repository)
    // ---------------------------------------------------------------------

    private fun loadState(month: String, fileName: String): ConsolidatedState {
        val stateFile = DataManagerPaths.outputState(baseDir, month, fileName)
        val raw = store.readJson(stateFile) { LocalConsolidationCodec.decodeState(it) } ?: return ConsolidatedState()
        return LocalConsolidationCodec.rebindRecords(raw)
    }

    private fun persistState(month: String, fileName: String, state: ConsolidatedState): Boolean {
        val stateFile = DataManagerPaths.outputState(baseDir, month, fileName)
        val written = store.writeJson(stateFile, state) { LocalConsolidationCodec.encodeState(it) } != null
        val parent = stateFile.absoluteFile.parentFile ?: return written
        val markdownFile = File(parent, stateFile.name.replace(".state.json", ".md"))
        runCatching { writeMarkdown(markdownFile, state) }
        return written
    }

    private fun persistStateIndex(states: List<ConsolidatedState>) {
        val indexFile = DataManagerPaths.stateIndex(baseDir)
        val months = LinkedHashMap<String, LocalConsolidationTypes.StateIndexEntry>()
        for (state in states) {
            val month = state.metadata.month
            if (month.isBlank()) continue
            val existing = months[month]
            months[month] = LocalConsolidationTypes.StateIndexEntry(
                files = ConsolidationText.stableUnique((existing?.files ?: emptyList()) + state.metadata.sourceFiles).sorted(),
                recordCount = (existing?.recordCount ?: 0) + state.records.size,
            )
        }
        val index = LocalConsolidationTypes.StateIndex(months = months)
        store.writeJson(indexFile, index) { LocalConsolidationCodec.encodeStateIndex(it) }
    }

    private fun writeMarkdown(file: File, state: ConsolidatedState) {
        val lines = ArrayList<String>()
        lines.add("| 序号 | 记录日期 | 最近来源日期 | 内容 | 来源条目 | 去重状态 |")
        lines.add("| --- | --- | --- | --- | --- | --- |")
        for (r in state.records) {
            val refs = r.sourceRefs.takeLast(3).joinToString("<br>") { it.replace("|", "\\|") }.ifEmpty { "-" }
            lines.add(
                "| ${r.recordId} | ${r.firstSeenDate.replace("|", "\\|")} | ${r.lastSeenDate.replace("|", "\\|")} | " +
                    r.normalizedFact.replace("|", "\\|") + " | " + refs + " | ${r.dedupStatus.replace("|", "\\|")} |",
            )
        }
        file.writeText(lines.joinToString("\n") + "\n")
    }
}
