package com.clawmate.app.localmodel.download


/**
 * ModelScope 上单个文件的下载任务（来自 `repo/files` 清单）。
 *
 * 字段与鸿蒙端 `ModelScopeFileTask` 一致：相对路径、字节数、可选的 SHA256。
 */
data class ModelScopeFile(
    val path: String,
    val size: Long,
    val sha256: String? = null,
    /** 清单里自带的文本内容（目前只有很小的 `config.json` 会预取），用于按需选择文件。 */
    val text: String? = null,
)

/** 一次下载的结果统计。[failed] 非空表示部分文件失败，不写完成标记。 */
data class ModelScopeDownloadSummary(
    val repo: String,
    val fileCount: Int,
    val totalBytes: Long,
    val downloadedBytes: Long,
    val failed: List<String>,
    val paused: Boolean,
) {
    val complete: Boolean get() = !paused && failed.isEmpty()
}

enum class DownloadStop { NONE, PAUSE, CANCEL }

/**
 * 纯逻辑部分：仓库名校验、目标目录、文件清单解析、路径安全、URL 构造、限速与进度估算、
 * 续传判定和完成标记。所有函数都是确定性的，便于单元测试；不做网络或磁盘操作。
 */
object ModelScopeSpec {
    const val API_ROOT = "https://modelscope.cn"
    const val ROOT_DIR = "modelscope"
    const val MANIFEST_FILE = ".download_manifest.json"
    const val COMPLETE_FILE = ".download_complete"

    /** 鸿蒙端默认仓库；作为界面的默认输入，不代表已经下载或可用。 */
    const val DEFAULT_REPO = "fengerhu1/mobi_2B_offline_npu2_kirin9030_online_v2"

    /** 单个文件上限（10 GiB），防止异常清单把磁盘写满。 */
    const val MAX_FILE_BYTES = 10L * 1024 * 1024 * 1024
    const val MAX_FILES = 512

    private val SEGMENT = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,99}")
    private val SHA256 = Regex("[0-9a-f]{64}")

    /** `ModelScope/owner/repo` 或 `owner/repo` → (owner, repo)；格式不符返回 null。 */
    fun parseRepo(raw: String): Pair<String, String>? {
        val value = raw.trim().removePrefix("ModelScope/").removePrefix("modelscope/").trim().trimEnd('/')
        val parts = value.split('/')
        if (parts.size != 2) return null
        if (!SEGMENT.matches(parts[0]) || !SEGMENT.matches(parts[1])) return null
        return parts[0] to parts[1]
    }

    /** `owner/repo` → `owner__repo`（与鸿蒙端目录命名一致）。 */
    fun encodeRepoDir(owner: String, repo: String): String = "${owner}__${repo}"

    /** `owner__repo` → `owner/repo`。 */
    fun decodeRepoDir(dir: String): String {
        val index = dir.indexOf("__")
        if (index <= 0 || index + 2 >= dir.length) return dir
        return dir.substring(0, index) + "/" + dir.substring(index + 2)
    }

    fun fileListUrl(owner: String, repo: String): String =
        "$API_ROOT/api/v1/models/${enc(owner)}/${enc(repo)}/repo/files?Recursive=1"

    fun fileUrl(owner: String, repo: String, path: String): String =
        "$API_ROOT/models/${enc(owner)}/${enc(repo)}/resolve/master/" + encodePath(path)

    fun encodePath(path: String): String = path.replace('\\', '/').split('/').joinToString("/") { enc(it) }

    private fun enc(value: String): String = java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    /** 相对路径安全校验：不允许绝对路径、`..`、空段、控制字符或超长路径。 */
    fun safeRelativePath(path: String): String? {
        val normalized = path.replace('\\', '/')
        if (normalized.isEmpty() || normalized.length > 512) return null
        if (normalized.startsWith("/")) return null
        val segments = normalized.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) return null
        if (segments.any { part -> part.any { it.code <= 32 || it.code == 127 } }) return null
        return normalized
    }

    /**
     * 解析 `repo/files?Recursive=1` 响应。兼容 `Data.Files` / `data.files` / `Files` / 顶层数组。
     * 跳过目录条目和非法条目；没有有效文件时抛出异常，避免把空仓库当成下载完成。
     * 使用与平台无关的 [MiniJson] 解析器，纯 JVM 单元测试可直接覆盖。
     */
    fun parseFileList(raw: String): List<ModelScopeFile> {
        require(raw.length <= 16 * 1024 * 1024) { "ModelScope 文件清单过大" }
        val items = extractArray(MiniJson.parse(raw)) ?: throw IllegalStateException("ModelScope 文件清单格式异常")
        val out = LinkedHashMap<String, ModelScopeFile>()
        for (item in items) {
            if (item !is Map<*, *>) continue
            val type = stringField(item, "Type", "type")
            if (type == "tree" || type == "dir" || type == "directory") continue
            val path = stringField(item, "Path", "path")
            val size = numberField(item, "Size", "size")
            if (size == null || size < 0 || size > MAX_FILE_BYTES) continue
            val safe = safeRelativePath(path) ?: continue
            out[safe] = ModelScopeFile(safe, size, sha256Field(item))
            if (out.size > MAX_FILES) throw IllegalStateException("ModelScope 文件清单超过 $MAX_FILES 个文件，请选择更小的仓库")
        }
        if (out.isEmpty()) throw IllegalStateException("ModelScope 文件清单为空或格式异常")
        return out.values.toList()
    }

    private fun extractArray(root: Any?): List<*>? {
        if (root is List<*>) return root
        if (root !is Map<*, *>) return null
        (root["Data"] ?: root["data"])?.let { data ->
            if (data is List<*>) return data
            if (data is Map<*, *>) (data["Files"] ?: data["files"])?.let { if (it is List<*>) return it }
        }
        (root["Files"] ?: root["files"])?.let { if (it is List<*>) return it }
        return null
    }

    private fun stringField(item: Map<*, *>, upper: String, lower: String): String {
        val value = if (item.containsKey(upper)) item[upper] else item[lower]
        return if (value == null) "" else value.toString()
    }

    private fun numberField(item: Map<*, *>, upper: String, lower: String): Long? {
        val value = if (item.containsKey(upper)) item[upper] else item[lower]
        return when (value) {
            null -> null
            is Number -> value.toLong()
            else -> value.toString().trim().toLongOrNull()
        }
    }

    private fun sha256Field(item: Map<*, *>): String? {
        for (key in listOf("Sha256", "SHA256", "sha256", "Sha256Hash", "sha256Hash", "sha256_hash")) {
            val value = item[key]
            if (value == null) continue
            val normalized = normalizeSha256(value.toString())
            if (normalized != null) return normalized
        }
        return null
    }

    /** 从任意文本中取第一段 64 位十六进制摘要（大约等于 `sha256:` 前缀也被接受）。 */
    fun normalizeSha256(text: String): String? {
        val lower = text.trim().lowercase()
        return SHA256.find(lower)?.value
    }

    /** 断点续传判定：已有文件与清单的比较结果。 */
    sealed interface ResumeDecision {
        data object Restart : ResumeDecision
        data class Append(val offset: Long) : ResumeDecision
        /** 大小与清单一致；是否通过 SHA256 由下载器实际校验后决定。 */
        data object SkipSizeOnly : ResumeDecision
    }

    fun resumeDecision(existingSize: Long, file: ModelScopeFile): ResumeDecision = when {
        existingSize < 0 || existingSize > file.size -> ResumeDecision.Restart
        existingSize == file.size -> ResumeDecision.SkipSizeOnly
        existingSize > 0 -> ResumeDecision.Append(existingSize)
        else -> ResumeDecision.Restart
    }

    /** 下载进度百分比（0..100），总字节为 0 时返回 0。 */
    fun percent(downloaded: Long, total: Long): Int {
        if (total <= 0) return 0
        return (downloaded.coerceIn(0, total) * 100 / total).toInt().coerceIn(0, 100)
    }

    /**
     * 滑动窗口中计算的下载速度（字节/秒）与剩余秒数。样本不足时返回 0，
     * 页面据此显示“计算中”而不是编造速度。
     */
    class SpeedMeter(private val windowMillis: Long = 5000) {
        private val samples = ArrayDeque<Pair<Long, Long>>()
        fun reset() { samples.clear() }
        fun record(nowMillis: Long, bytes: Long) {
            samples.addLast(nowMillis to bytes)
            while (samples.size > 2 && nowMillis - samples.first().first > windowMillis) samples.removeFirst()
        }
        fun bytesPerSecond(nowMillis: Long): Long {
            if (samples.size < 2) return 0
            val first = samples.first(); val last = samples.last()
            val elapsed = last.first - first.first
            if (elapsed <= 0) return 0
            return ((last.second - first.second) * 1000 / elapsed).coerceAtLeast(0)
        }
        fun remainingSeconds(nowMillis: Long, remaining: Long): Long {
            val speed = bytesPerSecond(nowMillis)
            if (speed <= 0) return -1
            return (remaining + speed - 1) / speed
        }
    }

    fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = listOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble() / 1024
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) { value /= 1024; unit++ }
        return String.format("%.1f %s", value, units[unit])
    }

    /** `.download_manifest.json` 内容（与鸿蒙端字段一致，便于离线核对）。 */
    fun manifestJson(repo: String, files: List<ModelScopeFile>, totalBytes: Long, completedAt: String): String {
        val entries = files.map { file ->
            val entry = LinkedHashMap<String, Any?>()
            entry["path"] = file.path
            entry["size"] = file.size
            entry["verified"] = true
            file.sha256?.let { entry["sha256"] = it }
            entry
        }
        val root = LinkedHashMap<String, Any?>()
        root["repo"] = repo
        root["completedAt"] = completedAt
        root["fileCount"] = files.size
        root["totalBytes"] = totalBytes
        root["files"] = entries
        return MiniJson.write(root, pretty = true)
    }

    /** `.download_complete` 内容。 */
    fun completeMarker(repo: String, completedAt: String, fileCount: Int, totalBytes: Long): String =
        "repo=$repo\ncompletedAt=$completedAt\nfileCount=$fileCount\ntotalBytes=$totalBytes\n"

    /** 模型目录是否带有完整的下载标记。 */
    fun isCompleteDir(entries: Set<String>): Boolean = entries.contains(COMPLETE_FILE)

    /**
     * 从 config.json 文本里取出被引用的文件名（`llm_model`、`llm_weight`、`tokenizer_file`、
     * `embedding_file` 以及嵌套对象中的同名字符串字段）。只返回看起来像文件名的短字符串。
     */
    fun configReferences(configText: String, configPath: String = "config.json"): Set<String> {
        if (configText.isBlank()) return emptySet()
        val out = LinkedHashSet<String>()
        walk(MiniJson.parse(configText), out)
        return out
    }

    private fun walk(node: Any?, out: MutableSet<String>) {
        val map = node as? Map<*, *> ?: return
        for ((rawKey, value) in map) {
            val key = rawKey?.toString() ?: continue
            when (value) {
                is Map<*, *> -> walk(value, out)
                is List<*> -> value.forEach { walk(it, out) }
                is String -> if (isFileNameReference(key, value)) out.add(value.trim())
            }
        }
    }

    private fun isFileNameReference(key: String, value: String): Boolean {
        if (!key.endsWith("_file") && !key.endsWith("_model") && !key.endsWith("_weight") && key !in setOf("model", "weight", "llm", "tokenizer", "embedding")) return false
        val v = value.trim()
        if (v.isEmpty() || v.length > 200) return false
        if (v.startsWith("/") || v.contains("..")) return false
        return v.any { it == '.' }
    }
}
