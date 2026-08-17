package com.clawmate.app.datamanager

import com.clawmate.app.datamanager.LocalConsolidationTypes.ConsolidationResult
import com.clawmate.app.datamanager.LocalConsolidationTypes.ConsolidatedState
import com.clawmate.app.datamanager.LocalConsolidationTypes.StateIndex
import java.io.File

/**
 * Explicit orchestrator for the Phase-30 **local** daily-log consolidation: scan
 * existing daily-log via [DailyLogEventSource], consolidate each month/file group
 * deterministically, persist each group's state artifact (plus a rendered markdown
 * summary), and rebuild the output `state-index.json`.
 *
 * Safety boundaries (matching the rest of Phase 29/30):
 *  - reads only existing app-private daily-log under the injected base dir;
 *  - never auto-creates daily-log input, never touches URI/image/external storage
 *    or the network;
 *  - bounded per-group records and atomic writes;
 *  - explicitly invoked (from an explicit user action) — never on launch or in
 *    the background.
 */
class ConsolidationRepository(
    private val baseDir: File,
    private val store: AtomicJsonStore = AtomicJsonStore(),
) {

    private val eventSource: DailyLogEventSource = DailyLogEventSourceFactory.appPrivate(baseDir)

    /**
     * Run consolidation over the existing daily-log tree and persist the resulting
     * artifacts. Never throws: parse/serialize failures are counted in the scan's
     * error list and a group that fails to persist keeps its prior state.
     */
    fun consolidateNow(onGroup: ((month: String, fileName: String, state: ConsolidatedState) -> Unit)? = null): ConsolidationResult {
        val scan = eventSource.load()
        val byGroup = LinkedHashMap<String, MutableList<DailyLogEntry>>()
        for (entry in scan.entries) {
            val key = groupKey(entry)
            byGroup.getOrPut(key) { ArrayList() }.add(entry)
        }

        val states = LinkedHashMap<String, ConsolidatedState>()
        for ((key, groupEntries) in byGroup) {
            val month = ConsolidationText.monthKeyFromDate(groupEntries.first().sourceDate)
            val fileName = groupEntries.first().fileName
            val previous = loadState(month, fileName)
            val next = ConsolidationEngine.consolidate(groupEntries, previous)
            if (persistState(month, fileName, next)) {
                states[key] = next
                onGroup?.invoke(month, fileName, next)
            }
        }

        // Only persist the state-index when there is at least one group artifact;
        // an empty daily-log tree must not auto-create any file.
        if (states.isNotEmpty()) persistStateIndex(states.values.toList())
        return ConsolidationEngine.summarize(scan, states)
    }

    /** Persist the consolidated state (JSON artifact + markdown summary). */
    private fun persistState(month: String, fileName: String, state: ConsolidatedState): Boolean {
        val stateFile = DataManagerPaths.outputState(baseDir, month, fileName)
        val written = store.writeJson(stateFile, state) { LocalConsolidationCodec.encodeState(it) } != null
        val markdownFile = File(stateFile.absoluteFile.parentFile, stateFile.name.replace(".state.json", ".md"))
        runCatching { writeMarkdown(markdownFile, state) }
        return written
    }

    /** Load the previously-persisted state for a group, tolerating missing/corrupt. */
    private fun loadState(month: String, fileName: String): ConsolidatedState {
        val stateFile = DataManagerPaths.outputState(baseDir, month, fileName)
        val raw = store.readJson(stateFile) { LocalConsolidationCodec.decodeState(it) } ?: return ConsolidatedState()
        return LocalConsolidationCodec.rebindRecords(raw)
    }

    private fun writeMarkdown(file: File, state: ConsolidatedState) {
        val lines = ArrayList<String>()
        lines.add("| 序号 | 记录日期 | 最近来源日期 | 内容 | 来源条目 | 去重状态 |")
        lines.add("| --- | --- | --- | --- | --- | --- |")
        state.records.forEach { r ->
            val refs = r.sourceRefs.takeLast(3).joinToString("<br>") { escapeTable(it) }.ifEmpty { "-" }
            lines.add(
                "| ${r.recordId} | ${escapeTable(r.firstSeenDate)} | ${escapeTable(r.lastSeenDate)} | " +
                    escapeTable(r.normalizedFact) + " | " + refs + " | ${escapeTable(r.dedupStatus)} |",
            )
        }
        file.writeText(lines.joinToString("\n") + "\n")
    }

    private fun escapeTable(value: String): String = value.replace("|", "\\|")

    private fun persistStateIndex(states: List<ConsolidatedState>) {
        val indexFile = DataManagerPaths.stateIndex(baseDir)
        val index = buildStateIndex(states)
        store.writeJson(indexFile, index) { LocalConsolidationCodec.encodeStateIndex(it) }
    }

    /** Build a bounded month -> entries state-index from the consolidated states. */
    fun buildStateIndex(states: List<ConsolidatedState>): StateIndex {
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
        return StateIndex(months = months)
    }

    private fun groupKey(entry: DailyLogEntry): String =
        "${ConsolidationText.monthKeyFromDate(entry.sourceDate)}/${entry.fileName}"
}
