package com.clawmate.platform.localmodel.download

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/** 下载进度快照，直接序列化成前端读取的 JSON。 */
data class ModelDownloadProgress(
    val repo: String,
    val state: String,
    val message: String,
    val percent: Int,
    val downloaded: Long,
    val total: Long,
    val fileIndex: Int,
    val fileCount: Int,
    val currentPath: String,
    val bytesPerSecond: Long,
    val remainingSeconds: Long,
    val failedFiles: List<String>,
) {
    fun toJson(): String = JSONObject().put("repo", repo).put("state", state).put("message", message)
        .put("percent", percent).put("downloaded", downloaded).put("total", total)
        .put("fileIndex", fileIndex).put("fileCount", fileCount).put("currentPath", currentPath)
        .put("bytesPerSecond", bytesPerSecond).put("remainingSeconds", remainingSeconds)
        .put("failed", org.json.JSONArray(failedFiles)).toString()
    companion object {
        val IDLE = ModelDownloadProgress("", "idle", "", 0, 0, 0, 0, 0, "", 0, -1, emptyList())
    }
}

/**
 * 把 ModelScope 仓库下载到应用私有目录 `filesDir/modelscope/{owner}__{repo}`。
 *
 * 与鸿蒙端一致的行为：
 *  - 先读取 `repo/files?Recursive=1` 清单，再逐个文件从 `resolve/master/<path>` 下载；
 *  - 部分文件已存在且小于清单大小时用 `Range` 断点续传，服务端不支持（返回 200）则重下；
 *  - 完成后核对大小与 SHA256（清单提供时），全部通过才写 `.download_manifest.json` 与
 *    `.download_complete`；
 *  - 只有“暂停/继续”，不会在后台偷偷修改模型目录以外的文件。
 *
 * 与鸿蒙端的差异（更严格）：默认只下载实现 `config.json` 引用的主模型文件，视觉/OM 等
 * 子目录文件默认跳过，避免一次写入数 GB；用户可显式选择“完整下载”。跳过的文件只记录在
 * 进度信息里，不写完成标记，因此不会把缺少视觉资源的目录当成完整模型。
 */
class ModelScopeDownloader(
    private val filesDir: File,
    private val client: OkHttpClient = defaultClient(),
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "model-download") },
    /** 单调时钟（默认用 JVM 的 nanoTime，便于纯 JVM 单元测试）。 */
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
    private val now: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    @Volatile private var stop = DownloadStop.NONE
    @Volatile private var progress: ModelDownloadProgress = ModelDownloadProgress.IDLE
    @Volatile private var running = false
    private var job: Future<*>? = null
    /** 当前正在写的文件字节数，用于暂停时保留已完成部分。 */
    @Volatile private var currentFileBytes = 0L
    @Volatile private var lastError = ""

    fun progress(): ModelDownloadProgress = progress
    fun isRunning(): Boolean = running

    /** 请求暂停：当前文件停在下一次读取处，再次开始会从该位置续传。 */
    fun pause(): Boolean {
        if (!running) return false
        stop = DownloadStop.PAUSE
        progress = progress.copy(state = "pausing", message = "正在暂停…")
        return true
    }

    /** 取消：停止并删除该仓库已下载的目录与标记。 */
    fun cancel(): Boolean {
        if (!running) return false
        stop = DownloadStop.CANCEL
        progress = progress.copy(state = "cancelling", message = "正在取消…")
        return true
    }

    /** 开始下载；已有任务在运行时返回 false，由界面提示“已有下载任务”。 */
    fun start(repoRaw: String, includeAuxiliary: Boolean = false): Boolean {
        if (running) return false
        val parts = ModelScopeSpec.parseRepo(repoRaw) ?: run {
            progress = ModelDownloadProgress.IDLE.copy(repo = repoRaw, state = "error",
                message = "仓库格式应为 owner/repo，例如 " + ModelScopeSpec.DEFAULT_REPO)
            return false
        }
        val (owner, repo) = parts
        stop = DownloadStop.NONE
        lastError = ""
        running = true
        progress = ModelDownloadProgress.IDLE.copy(repo = "$owner/$repo", state = "list", message = "正在读取 ModelScope 文件清单…")
        job = executor.submit { runCatching { download(owner, repo, includeAuxiliary) }.onFailure { failure ->
            if (stop == DownloadStop.NONE) { lastError = failure.message ?: "下载失败";  }
            finishWithError(failure)
        } }
        return true
    }

    private fun finishWithError(failure: Throwable) {
        val paused = stop == DownloadStop.PAUSE
        val cancelled = stop == DownloadStop.CANCEL
        progress = progress.copy(
            state = if (paused) "paused" else if (cancelled) "cancelled" else "error",
            message = when {
                paused -> "已暂停，再次开始会续传"
                cancelled -> "已取消下载并清理本仓库文件"
                else -> "下载失败：" + (failure.message ?: "未知错误")
            },
            bytesPerSecond = 0,
            remainingSeconds = -1,
        )
        running = false
        stop = DownloadStop.NONE
    }

    private fun download(owner: String, repo: String, includeAuxiliary: Boolean) {
        val root = File(filesDir, ModelScopeSpec.ROOT_DIR)
        val target = File(root, ModelScopeSpec.encodeRepoDir(owner, repo))
        if (!isInside(filesDir, target)) throw IOException("模型目录越界")
        val before = freeBytes(target)
        val files = fetchFileList(owner, repo)
        val wanted = selectFiles(files, includeAuxiliary)
        val skipped = files.size - wanted.size
        val total = wanted.sumOf { it.size }
        if (before >= 0L && total > before) {
            throw IOException("存储空间不足：需要 ${ModelScopeSpec.formatBytes(total)}，可用 ${ModelScopeSpec.formatBytes(before)}")
        }
        target.mkdirs()
        removeMarkers(target)
        val meter = ModelScopeSpec.SpeedMeter()
        var completed = 0L
        var index = 0
        val failed = ArrayList<String>()
        progress = ModelDownloadProgress("$owner/$repo", "downloading", "开始下载 ${wanted.size} 个文件",
            0, 0, total, 0, wanted.size, "", 0, -1, emptyList())
        for (file in wanted) {
            if (stop != DownloadStop.NONE) break
            val destination = File(target, file.path)
            if (!isInside(target, destination)) { failed.add(file.path); continue }
            destination.parentFile?.mkdirs()
            try {
                val written = downloadFile(owner, repo, file, destination, completed, total, index, wanted.size, meter)
                if (written == null) break
                completed += file.size
                meter.record(clock(), completed)
            } catch (failure: Exception) {
                if (stop != DownloadStop.NONE) break
                failed.add(file.path + "：" + (failure.message ?: "失败"))
                progress = progress.copy(message = "第 ${index + 1} 个文件失败：" + file.path)
            }
            index++
        }
        val paused = stop == DownloadStop.PAUSE
        val cancelled = stop == DownloadStop.CANCEL
        if (cancelled) {
            target.deleteRecursively()
            progress = progress.copy(state = "cancelled", message = "已取消下载并清理本仓库文件", percent = 0, downloaded = 0,
                bytesPerSecond = 0, remainingSeconds = -1)
            running = false; stop = DownloadStop.NONE
            return
        }
        if (paused) {
            val saved = completed + currentFileBytes
            progress = progress.copy(state = "paused", downloaded = saved, percent = ModelScopeSpec.percent(saved, total),
                message = "已暂停，再次开始会从断点续传", bytesPerSecond = 0, remainingSeconds = -1)
            running = false; stop = DownloadStop.NONE
            return
        }
        if (failed.isEmpty()) {
            val at = Instant.ofEpochMilli(now()).toString()
            File(target, ModelScopeSpec.MANIFEST_FILE).writeText(ModelScopeSpec.manifestJson("$owner/$repo", wanted, total, at))
            File(target, ModelScopeSpec.COMPLETE_FILE).writeText(ModelScopeSpec.completeMarker("$owner/$repo", at, wanted.size, total))
            progress = ModelDownloadProgress("$owner/$repo", "done",
                buildString {
                    append("下载完成：共 ${wanted.size} 个文件，${ModelScopeSpec.formatBytes(total)}")
                    if (skipped > 0) append("；按当前选项跳过 $skipped 个视觉/OM 子文件")
                }, 100, total, total, wanted.size, wanted.size, "", 0, -1, emptyList())
        } else {
            progress = progress.copy(state = "error", message = "部分文件失败（${failed.size} 个），未写入完成标记；再次开始会续传",
                failedFiles = failed, downloaded = completed, percent = ModelScopeSpec.percent(completed, total),
                bytesPerSecond = 0, remainingSeconds = -1)
        }
        running = false
        stop = DownloadStop.NONE
    }

    private fun fetchFileList(owner: String, repo: String): List<ModelScopeFile> {
        val request = Request.Builder().url(ModelScopeSpec.fileListUrl(owner, repo))
            .header("User-Agent", USER_AGENT).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("ModelScope 文件清单请求失败 HTTP ${response.code}")
            val body = response.body?.string() ?: throw IOException("ModelScope 文件清单为空")
            val files = ModelScopeSpec.parseFileList(body)
            // config.json 很小，预取内容后决定默认下载哪些文件，避免把数 GB 的视觉资源写进手机。
            val config = files.firstOrNull { it.path == "config.json" && it.size in 1..MAX_CONFIG_BYTES }
                ?: return files
            val text = fetchText(ModelScopeSpec.fileUrl(owner, repo, "config.json")) ?: return files
            return files.map { if (it.path == config.path) it.copy(text = text) else it }
        }
    }

    private fun fetchText(url: String): String? = runCatching {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@runCatching null
            response.body?.string()?.takeIf { it.length <= MAX_CONFIG_BYTES.toInt() }
        }
    }.getOrNull()

    /**
     * 返回本次下载的文件。默认只取 `config.json` 中引用的模型/分词/嵌入文件；
     * 其余子目录（视觉、OM、验证脚本）在“完整下载”时才包含。
     */
    internal fun selectFiles(files: List<ModelScopeFile>, includeAuxiliary: Boolean): List<ModelScopeFile> {
        if (includeAuxiliary) return files
        val config = files.firstOrNull { it.path == "config.json" } ?: return files
        val references = configReferences(config)
        val main = files.filter { file ->
            !file.path.contains('/') || references.any { ref -> file.path == ref || file.path.endsWith("/$ref") }
        }
        return if (main.any { it.path == "config.json" }) main else files
    }

    /** 从 `config.json` 名称中取出引用的文件名：`llm_model`、`llm_weight`、`tokenizer_file`、`embedding_file` 等字符串字段。 */
    internal fun configReferences(config: ModelScopeFile): Set<String> =
        ModelScopeSpec.configReferences(config.text ?: "", config.path)

    /** 下载单个文件；返回实际写入字节数，暂停/取消时返回 null。 */
    private fun downloadFile(
        owner: String, repo: String, file: ModelScopeFile, destination: File,
        completed: Long, total: Long, index: Int, count: Int, meter: ModelScopeSpec.SpeedMeter,
    ): Long? {
        val existing = if (destination.isFile) destination.length() else -1L
        var offset = 0L
        val skip = when (val decision = ModelScopeSpec.resumeDecision(existing, file)) {
            ModelScopeSpec.ResumeDecision.Restart -> { if (destination.exists()) destination.delete(); false }
            ModelScopeSpec.ResumeDecision.SkipSizeOnly ->
                if (file.sha256 == null || sha256Of(destination) == file.sha256) true else { destination.delete(); false }
            is ModelScopeSpec.ResumeDecision.Append -> { offset = decision.offset; false }
        }
        // 暂停可能在两次写盘之间发生：已完整的文件直接跳过，不再发起请求或删除。
        if (skip) return file.size
        if (stop != DownloadStop.NONE) return null
        currentFileBytes = offset
        val builder = Request.Builder().url(ModelScopeSpec.fileUrl(owner, repo, file.path))
            .header("User-Agent", USER_AGENT).get()
        if (offset > 0) builder.header("Range", "bytes=$offset-")
        client.newCall(builder.build()).execute().use { response ->
            when {
                offset > 0 && response.code == 200 -> {
                    // 服务端不支持断点续传：从头重下。
                    destination.delete(); offset = 0
                }
                !response.isSuccessful -> throw IOException("HTTP ${response.code}：${file.path}")
            }
            val body = response.body ?: throw IOException("响应为空：${file.path}")
            val sink = FileOutputStream(destination, offset > 0)
            var written = offset
            sink.use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(BUFFER)
                    var lastReport = 0L
                    while (true) {
                        if (stop != DownloadStop.NONE) return null
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        written += read
                        currentFileBytes = written
                        val at = clock()
                        if (at - lastReport >= REPORT_MILLIS) {
                            lastReport = at
                            val done = completed + written
                            meter.record(at, done)
                            progress = progress.copy(state = "downloading", downloaded = done,
                                percent = ModelScopeSpec.percent(done, total), fileIndex = index, fileCount = count,
                                currentPath = file.path,
                                message = "正在下载 ${file.path}（${ModelScopeSpec.formatBytes(done)} / ${ModelScopeSpec.formatBytes(total)}）",
                                bytesPerSecond = meter.bytesPerSecond(at),
                                remainingSeconds = meter.remainingSeconds(at, total - done))
                        }
                    }
                }
            }
            if (file.size > 0 && written != file.size) {
                destination.delete()
                throw IOException("文件大小不匹配（期望 ${file.size}，实际 $written）")
            }
            val expected = file.sha256
            if (expected != null) {
                progress = progress.copy(state = "verifying", message = "正在校验 ${file.path}…")
                val actual = sha256Of(destination)
                if (actual != expected) {
                    destination.delete()
                    throw IOException("SHA256 不匹配：${file.path}")
                }
            }
            return written
        }
    }

    private fun removeMarkers(dir: File) {
        File(dir, ModelScopeSpec.MANIFEST_FILE).delete()
        File(dir, ModelScopeSpec.COMPLETE_FILE).delete()
    }

    private fun isInside(base: File, candidate: File): Boolean {
        val basePath = base.canonicalFile.path
        val path = candidate.canonicalFile.path
        return path.startsWith(basePath + File.separator)
    }

    /** 估算可用空间：最近存在的上级目录（模型目录通常尚未创建）。无法判断时返回 -1，表示不限制。 */
    private fun freeBytes(dir: File): Long {
        var probe: File? = dir.absoluteFile
        while (probe != null && !probe.exists()) probe = probe.parentFile
        val usable = runCatching { probe?.usableSpace ?: -1L }.getOrDefault(-1L)
        return if (usable > 0) usable else -1L
    }

    override fun close() {
        stop = DownloadStop.CANCEL
        job?.cancel(false)
        executor.shutdownNow()
    }

    companion object {
        private const val BUFFER = 256 * 1024
        private const val REPORT_MILLIS = 250L
        private const val MAX_CONFIG_BYTES = 256 * 1024L
        private const val USER_AGENT = "ClawMate-Android/1.0 (ModelScope download)"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        fun sha256Of(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(4 * 1024 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

/** 当前活跃的下载器（同一时刻只允许一个下载任务）。 */
object ModelDownloadRegistry {
    private val downloaders = ConcurrentHashMap<String, ModelScopeDownloader>()
    fun of(filesDir: File): ModelScopeDownloader = downloaders.computeIfAbsent(filesDir.absolutePath) { ModelScopeDownloader(filesDir) }
}
