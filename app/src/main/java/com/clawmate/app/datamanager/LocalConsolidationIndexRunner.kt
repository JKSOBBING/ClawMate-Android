package com.clawmate.app.datamanager

import com.clawmate.app.datamanager.LocalConsolidationTypes.ConsolidationResult
import com.clawmate.app.datamanager.LocalConsolidationTypes.ConsolidatedState
import java.io.File

/**
 * The single explicit entry point for Phase-30 **local** consolidation + indexing.
 * Invoking [run] scans only existing app-private daily-log, consolidates each
 * month/file group deterministically, persists the state + index artifacts, and
 * returns a typed, honest result. It is **zero-network** and never auto-creates
 * daily-log input — only the explicit, user-driven caller should invoke it (e.g.
 * from the existing Home "生成推荐" explicit action), never on launch/recomposition
 * or from background/WorkManager scheduling.
 */
class LocalConsolidationIndexRunner(
    private val baseDir: File,
) {

    private val repository: ConsolidationRepository = ConsolidationRepository(baseDir)
    private val indexService: IndexService = IndexService(baseDir)

    /** Run local consolidation + index rebuild over `baseDir`, returning a typed outcome. */
    fun run(): RunOutcome {
        val consolidation = repository.consolidateNow()
        val states = loadStatesAfterConsolidation()
        val indexResult = indexService.rebuild(states)
        return RunOutcome(
            consolidation = consolidation,
            indexedEvents = indexResult.eventCount,
            indexedSourceFiles = indexResult.sourceFiles,
            generatedAt = indexResult.generatedAt,
        )
    }

    /** Re-read the persisted consolidated states from the outputs tree (never creates). */
    private fun loadStatesAfterConsolidation(): List<ConsolidatedState> {
        val outputs = DataManagerPaths.outputsRoot(baseDir)
        if (!outputs.isDirectory) return emptyList()
        val states = ArrayList<ConsolidatedState>()
        val store = AtomicJsonStore()
        outputs.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }?.forEach { monthDir ->
            monthDir.listFiles()?.filter { it.name.endsWith(".state.json") }?.sortedBy { it.name }?.forEach { file ->
                store.readJson(file) { LocalConsolidationCodec.decodeState(it) }
                    ?.let { states.add(LocalConsolidationCodec.rebindRecords(it)) }
            }
        }
        return states
    }

    /** Typed, countable outcome of a consolidation+index run. */
    data class RunOutcome(
        val consolidation: ConsolidationResult,
        val indexedEvents: Int,
        val indexedSourceFiles: Int,
        val generatedAt: String,
    )
}
