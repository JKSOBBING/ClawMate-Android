package com.clawmate.app.localmodel

import java.io.File

/**
 * Discovers local models under the app-private `filesDir`, porting the
 * HarmonyOS scan in `Index.ets` (`refreshDownloadedModels` /
 * `buildDownloadedModelItem` / `modelLoadableStatus` / `isModelWeightFile`).
 *
 * Two model roots are recognized:
 *  - legacy: `filesDir/model`
 *  - ModelScope: `filesDir/modelscope/{owner}__{repo}`
 *
 * A folder counts as a model directory only when it is validated by
 * [LocalModelPathValidator] and is loadable per the pure status logic
 * (`config.json` + a weight file). Scanning goes through a [ModelFolderProbe]
 * seam so unit tests can exercise it deterministically without real storage;
 * nothing here loads a model or produces output.
 */
object LocalModelInventory {

    /** Weight-file extensions a model dir is required to contain. */
    val WEIGHT_EXTENSIONS: List<String> = listOf(".mnn", ".om", ".safetensors", ".weight")

    /** Max scan depth for weight-file detection (mirrors Harmony depth<=4). */
    const val MAX_SCAN_DEPTH = 4

    /**
     * Minimal data-access seam so scanning is deterministic and device-free in
     * unit tests. The production implementation uses `java.io.File`.
     */
    interface ModelFolderProbe {
        /** Directories/files directly under [dir] (names only). */
        fun listEntries(dir: File): List<String>

        /** True when [f] is a regular file. */
        fun isFile(f: File): Boolean

        /** Size in bytes of [f], or 0 when unknown. */
        fun sizeOf(f: File): Long
    }

    private val defaultProbe = object : ModelFolderProbe {
        override fun listEntries(dir: File): List<String> = dir.list()?.sorted() ?: emptyList()
        override fun isFile(f: File): Boolean = f.isFile
        override fun sizeOf(f: File): Long = if (f.isFile) f.length() else 0L
    }

    /** Scans [filesDir] and returns ordered, validated local models. */
    fun scan(filesDir: String?): List<LocalModelInfo> {
        return scan(filesDir, defaultProbe)
    }

    /** Scans with an explicit probe seam (used by unit tests). */
    fun scan(filesDir: String?, probe: ModelFolderProbe): List<LocalModelInfo> {
        if (filesDir.isNullOrBlank()) return emptyList()
        val root = File(filesDir)
        val out = ArrayList<LocalModelInfo>()

        // legacy direct child "filesDir/model"
        visitLegacy(root, probe, out)

        // ModelScope repos: filesDir/modelscope/
        val modelscope = File(root, "modelscope")
        if (probe.isFile(modelscope)) { /* not a dir */ }
        probe.listEntries(modelscope).forEach { dirName ->
            val dir = File(modelscope, dirName)
            if (probe.isFile(dir)) return@forEach
            if (!isModelDirectory(dir, probe)) return@forEach
            val path = dir.absolutePath
            if (!LocalModelPathValidator.isValidModelDir(filesDir, path)) return@forEach
            val repo = decodeModelScopeRepoDir(dirName)
            out.add(buildInfo(
                id = "modelscope:$repo",
                label = repo,
                path = path,
                probe = probe,
            ))
        }
        return out.sortedBy { it.id }
    }

    private fun visitLegacy(root: File, probe: ModelFolderProbe, out: MutableList<LocalModelInfo>) {
        probe.listEntries(root)
            .filter { it == "model" }
            .forEach { dirName ->
                val dir = File(root, dirName)
                if (probe.isFile(dir)) return@forEach
                val path = dir.absolutePath
                if (!LocalModelPathValidator.isValidModelDir(root.absolutePath, path)) return@forEach
                out.add(buildInfo(id = "legacy:model", label = "默认 model", path = path, probe = probe))
            }
    }

    /** True when [dir] is present and contains config.json + a weight file. */
    fun isModelDirectory(dir: File, probe: ModelFolderProbe = defaultProbe): Boolean {
        if (probe.isFile(dir)) return false
        val entries = probe.listEntries(dir).toSet()
        if (entries.isEmpty()) return false
        return entries.contains("config.json") && hasWeightFile(dir, probe, depth = 0)
    }

    /** Recursively detects a weight file up to [MAX_SCAN_DEPTH]. */
    fun hasWeightFile(dir: File, probe: ModelFolderProbe = defaultProbe, depth: Int = 0): Boolean {
        if (depth > MAX_SCAN_DEPTH) return false
        if (probe.isFile(dir)) {
            return if (dir.length() > 0 || probe.sizeOf(dir) > 0) isWeightFile(dir.name) else false
        }
        return probe.listEntries(dir).any { childName ->
            val child = File(dir, childName)
            if (probe.isFile(child)) {
                (probe.sizeOf(child) > 0) && isWeightFile(child.name)
            } else {
                hasWeightFile(child, probe, depth + 1)
            }
        }
    }

    /** True when [name] ends with a model weight extension. */
    fun isWeightFile(name: String): Boolean {
        val lower = name.lowercase()
        return WEIGHT_EXTENSIONS.any { lower.endsWith(it) }
    }

    /** Port of `Index.ets decodeModelScopeRepoDir`: `owner__repo` -> `owner/repo`. */
    fun decodeModelScopeRepoDir(dir: String): String {
        val sep = dir.indexOf("__")
        if (sep <= 0 || sep + 2 >= dir.length) return dir
        return dir.substring(0, sep) + "/" + dir.substring(sep + 2)
    }

    private fun buildInfo(
        id: String,
        label: String,
        path: String,
        probe: ModelFolderProbe,
    ): LocalModelInfo {
        val dir = File(path)
        val hasConfig = probe.isFile(File(dir, "config.json"))
        val hasWeight = hasWeightFile(dir, probe, 0)
        val ready = hasConfig && hasWeight
        val size = dirSize(dir, probe)
        val statusText = when {
            !hasConfig && !hasWeight -> "缺失 config.json 与权重"
            !hasConfig -> "缺少 config.json"
            !hasWeight -> "缺少模型权重文件"
            else -> "可加载"
        }
        return LocalModelInfo(
            id = id,
            label = label,
            path = path,
            sizeBytes = size,
            statusText = statusText,
            ready = ready,
            hasConfig = hasConfig,
            hasWeightFile = hasWeight,
        )
    }

    private fun dirSize(dir: File, probe: ModelFolderProbe): Long {
        if (probe.isFile(dir)) return probe.sizeOf(dir)
        var total = 0L
        probe.listEntries(dir).forEach { name ->
            val child = File(dir, name)
            total += if (probe.isFile(child)) probe.sizeOf(child) else dirSize(child, probe)
        }
        return total
    }
}
