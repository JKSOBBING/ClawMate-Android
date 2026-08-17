package com.clawmate.app.datamanager

import java.io.File

/**
 * Bounded source of real daily-log text for the Home recommendation pipeline.
 *
 * The contract, matching the Phase-29 requirements, is deliberately narrow and
 * safe:
 *  - It reads **only** existing daily-log text already present under the
 *    app-private `filesDir/workflows/daily-log/YYYY-MM-DD/<any-markdown-file>` tree.
 *  - It never scans external storage, never opens a URI or image, and never
 *    makes a network call.
 *  - It never *creates* daily-log files (writing is the caller/workflow
 *    importer's job via [DailyLogWorkspace]).
 *  - Every file is bounded by [DailyLogParser]; a bad file is skipped and
 *    reported through [DailyLogScanResult.errors], never thrown.
 */
interface DailyLogEventSource {

    /**
     * Synchronously read the currently-stored daily-log entries. The returned
     * [DailyLogScanResult] carries the typed entries plus an honest, countable
     * error summary. Calling this on the main thread is discouraged (callers
     * invoke it from [Dispatchers.IO] via [readFromAppsPrivateDir]).
     */
    fun load(): DailyLogScanResult

    /** Whether any daily-log document exists in the app-private tree. */
    fun isEmpty(): Boolean

    companion object {
        /** An empty source with no daily-log data (zero IO). */
        fun empty(): DailyLogEventSource =
            object : DailyLogEventSource {
                override fun load(): DailyLogScanResult = DailyLogScanResult(entries = emptyList(), errors = emptyList())
                override fun isEmpty(): Boolean = true
            }
    }
}

/** Result of scanning the app-private daily-log tree. */
data class DailyLogScanResult(
    val entries: List<DailyLogEntry>,
    val errors: List<DailyLogParseError>,
) {
    val errorCount: Int get() = errors.size
}

/**
 * Real file-backed [DailyLogEventSource] over the app-private
 * `baseDir/workflows/daily-log/YYYY-MM-DD/<any-markdown-file>` tree (mirrors Harmony
 * `DataManagerPaths.dailyLogRoot` under an Android `filesDir` base).
 *
 * This source is **read-only**: it never creates directories or files. If a
 * workflow importer wants to feed daily-log data later, it writes via
 * [DailyLogWorkspace] (see [DailyLogWorkspace.writeDocument]).
 */
class FileDailyLogEventSource internal constructor(
    private val baseDir: File,
) : DailyLogEventSource {

    private val dailyLogRoot get() = File(baseDir, DailyLogWorkspace.DAILY_LOG_ROOT_RELATIVE)

    override fun isEmpty(): Boolean = dailyLogRootSafe()?.let { root ->
        root.listFiles()
            ?.filter { DailyLogPaths.isValidDayDirectory(it.name) && it.isDirectory }
            ?.none { dayDir ->
                dayDir.listFiles()?.any { it.name.endsWith(".md") } == true
            }
    } != false

    override fun load(): DailyLogScanResult {
        val root = dailyLogRootSafe() ?: return DailyLogScanResult(emptyList(), emptyList())
        val entries = ArrayList<DailyLogEntry>()
        val errors = ArrayList<DailyLogParseError>()
        root.listFiles()
            ?.filter { DailyLogPaths.isValidDayDirectory(it.name) && it.isDirectory }
            ?.sortedBy { it.name }
            ?.forEach { dayDir ->
                dayDir.listFiles()
                    ?.filter { it.name.endsWith(".md") }
                    ?.sortedBy { it.name }
                    ?.forEach { file ->
                        val content = readBounded(file)
                        if (content != null) {
                            val result = DailyLogParser.parse(dayDir.name, file.name, content)
                            entries.addAll(result.entries)
                            errors.addAll(result.errors)
                        }
                    }
            }
        return DailyLogScanResult(entries = entries, errors = errors)
    }

    private fun dailyLogRootSafe(): File? {
        // Never create — only read an already-present tree.
        if (!dailyLogRoot.exists() || !dailyLogRoot.isDirectory) return null
        return dailyLogRoot
    }

    /** Read a file, bounded; returns null (and reports nothing) on IO error so
     * a transient failure silently yields no entry for that file (honest skip). */
    private fun readBounded(file: File): String? {
        if (!file.isFile || file.length() > DailyLogBounds.MAX_DOCUMENT_CHARACTERS.toLong() * 4L) {
            return null
        }
        return try {
            file.readText()
        } catch (t: Throwable) {
            null
        }
    }
}

/**
 * Test / integration seam that reads the daily-log tree from an arbitrary root
 * (used by unit tests over a temp dir). Its parsing behaviour is identical to
 * [FileDailyLogEventSource]; it is provided so tests never need an Android
 * `filesDir`.
 */
class TestDailyLogEventSource internal constructor(
    private val baseDir: File,
) : DailyLogEventSource {
    private val delegate = FileDailyLogEventSource(baseDir)
    override fun load(): DailyLogScanResult = delegate.load()
    override fun isEmpty(): Boolean = delegate.isEmpty()
}

/** Factory for the real file source bound to a `filesDir`-style base. */
object DailyLogEventSourceFactory {
    /** Build a source over an app-private base dir (a `File(context.filesDir)`). */
    fun appPrivate(baseDir: File): DailyLogEventSource = FileDailyLogEventSource(baseDir)

    /** Build a source over an arbitrary temp dir for tests/fixtures. */
    fun overDirectory(baseDir: File): DailyLogEventSource = TestDailyLogEventSource(baseDir)
}
