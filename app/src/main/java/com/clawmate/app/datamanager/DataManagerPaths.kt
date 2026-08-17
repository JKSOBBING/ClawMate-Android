package com.clawmate.app.datamanager

import java.io.File

/**
 * App-private path layout for the Phase-30 local consolidation + index artifacts,
 * mirroring the Harmony `DataManagerPaths` tree under an Android `filesDir` base
 * (but reduced to the local, model-free artifacts Phase 30 writes):
 *
 * ```
 * <base>/
 *   workflows/
 *     profile-consolidation/
 *       outputs/<month>/<fileName>.md.state.json   (per group consolidated state)
 *       outputs/state-index.json                   (output state index)
 *       indexes/events/<month>.json                (month partition)
 *       indexes/events/partition-index.json        (partition list)
 *       indexes/manifest.json                      (event manifest)
 *       indexes/temporal/<month>.json              (temporal month doc)
 *       indexes/profile/state.json                 (Phase-32 profile state)
 *       indexes/profile/profile.md                 (Phase-32 bounded rendered markdown)
 * ```
 *
 * Reading/querying never creates directories; writes create parents only via the
 * atomic store. All paths are derived from a single injected base dir so unit
 * tests can point at a temp dir.
 */
object DataManagerPaths {

    const val DAILY_LOG_RELATIVE: String = "workflows/daily-log"
    const val CONSOLIDATION_ROOT_RELATIVE: String = "workflows/profile-consolidation"
    const val OUTPUTS_ROOT_RELATIVE: String = "$CONSOLIDATION_ROOT_RELATIVE/outputs"
    const val INDEXES_ROOT_RELATIVE: String = "$CONSOLIDATION_ROOT_RELATIVE/indexes"
    const val EVENTS_DIR_RELATIVE: String = "$INDEXES_ROOT_RELATIVE/events"
    const val TEMPORAL_DIR_RELATIVE: String = "$INDEXES_ROOT_RELATIVE/temporal"

    const val STATE_INDEX_FILE_NAME: String = "state-index.json"
    const val PARTITION_INDEX_FILE_NAME: String = "partition-index.json"
    const val MANIFEST_FILE_NAME: String = "manifest.json"
    const val OUTPUT_STATE_SUFFIX: String = ".state.json"

    const val PROFILE_DIR_RELATIVE: String = "$INDEXES_ROOT_RELATIVE/profile"
    const val PROFILE_STATE_FILE_NAME: String = "state.json"
    const val PROFILE_MARKDOWN_FILE_NAME: String = "profile.md"

    fun dailyLogRoot(baseDir: File): File = File(baseDir, DAILY_LOG_RELATIVE)
    fun outputsRoot(baseDir: File): File = File(baseDir, OUTPUTS_ROOT_RELATIVE)
    fun indexesRoot(baseDir: File): File = File(baseDir, INDEXES_ROOT_RELATIVE)
    fun eventsDir(baseDir: File): File = File(baseDir, EVENTS_DIR_RELATIVE)
    fun temporalDir(baseDir: File): File = File(baseDir, TEMPORAL_DIR_RELATIVE)

    /** Profile subtree: `indexes/profile/`. */
    fun profileDir(baseDir: File): File = File(baseDir, PROFILE_DIR_RELATIVE)

    /** Persisted profile state: `indexes/profile/state.json`. */
    fun profileState(baseDir: File): File = File(profileDir(baseDir), PROFILE_STATE_FILE_NAME)

    /** Bounded rendered markdown: `indexes/profile/profile.md`. */
    fun profileMarkdown(baseDir: File): File = File(profileDir(baseDir), PROFILE_MARKDOWN_FILE_NAME)

    fun stateIndex(baseDir: File): File = File(outputsRoot(baseDir), STATE_INDEX_FILE_NAME)
    fun partitionIndex(baseDir: File): File = File(eventsDir(baseDir), PARTITION_INDEX_FILE_NAME)
    fun manifest(baseDir: File): File = File(indexesRoot(baseDir), MANIFEST_FILE_NAME)

    /** Per-group consolidated-state artifact: `outputs/<month>/<fileName>.md.state.json`. */
    fun outputState(baseDir: File, month: String, fileName: String): File {
        val safeMonth = month.trim().replace('/'.toString(), "").substringBefore('.')
        val safeName = fileName.trim().substringBefore('.') + ".md"
        return File(File(outputsRoot(baseDir), safeMonth), "$safeName$OUTPUT_STATE_SUFFIX")
    }

    /** Month event partition artifact: `indexes/events/<month>.json`. */
    fun eventPartition(baseDir: File, month: String): File =
        File(eventsDir(baseDir), "${month.trim().take(7)}.json")

    /** Temporal month artifact: `indexes/temporal/<month>.json`. */
    fun temporalDoc(baseDir: File, month: String): File =
        File(temporalDir(baseDir), "${month.trim().take(7)}.json")

    /** Relative path of a state artifact under the outputs root (for event ids). */
    fun stateRelativePath(baseDir: File, stateFile: File): String {
        val root = outputsRoot(baseDir)
        val norm = baseDir.toPath().toAbsolutePath().normalize()
        val child = stateFile.toPath().toAbsolutePath().normalize()
        return runCatching {
            val rel = if (child.startsWith(norm)) norm.relativize(child).toString() else stateFile.name
            rel.replace('\\', '/')
        }.getOrElse { stateFile.name }
    }
}
