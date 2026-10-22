package com.clawmate.app.task.data

import com.clawmate.app.task.json.WorkflowJsonParser
import com.clawmate.app.task.json.WorkflowEditValidator
import com.clawmate.app.task.model.WorkflowTask
import com.clawmate.app.task.runner.WorkflowRunResult
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * File-backed [WorkflowRepository] over an app-private base directory.
 *
 * Layout (mirrors `WorkflowStorage.ets`):
 *   <base>/workflows/configs/<configFileName>.json   (editable workflow JSON)
 *   <base>/workflows/task-index.json                 (persisted task list)
 *
 * On first load the bundled [BundledConfigReader] configs are copied into
 * `configs/` and a default task index is derived from their metadata
 * (title = description, source = name — identical to the source).
 */
class DefaultWorkflowRepository(
    private val baseDir: File,
    private val bundled: BundledConfigReader,
) : WorkflowRepository {

    private val workflowsDir get() = File(baseDir, "workflows")
    private val configsDir get() = File(workflowsDir, "configs")
    private val indexFile get() = File(workflowsDir, "task-index.json")

    override suspend fun tasks(): List<WorkflowTask> = withContext(Dispatchers.IO) { loadTasks() }

    override suspend fun configJson(task: WorkflowTask): String? = withContext(Dispatchers.IO) {
        val file = resolvedConfigFile(task.configFileName)
        if (file.isFile) {
            try {
                file.readText()
            } catch (t: Throwable) {
                null
            }
        } else {
            bundled.read(task.configFileName)
        }
    }

    override suspend fun saveConfigJson(task: WorkflowTask, newJson: String): Unit =
        withContext(Dispatchers.IO) {
            require(WorkflowEditValidator.error(newJson) == null) { "Invalid workflow configuration" }
            ensureWorkspace()
            val file = resolvedConfigFile(task.configFileName)
            val tasks = loadTasks()
            require(tasks.any { it.id == task.id && it.configFileName == task.configFileName }) {
                "Workflow no longer exists"
            }
            val metadata = (WorkflowJsonParser.parse(newJson) as? WorkflowJsonParser.Result.Ok)?.definition?.metadata
            replaceText(file, newJson)
            // Mark the task as user-modified (persisted to the index).
            writeIndex(tasks.map {
                if (it.id == task.id) it.copy(
                    isUserModified = true,
                    title = metadata?.description?.takeIf(String::isNotBlank) ?: it.title,
                    source = metadata?.name?.takeIf(String::isNotBlank) ?: it.source,
                ) else it
            })
        }

    override suspend fun setTaskEnabled(taskId: String, enabled: Boolean): Unit =
        withContext(Dispatchers.IO) {
            writeIndex(loadTasks().map { if (it.id == taskId) it.copy(enabled = enabled) else it })
        }

    override suspend fun recordRun(taskId: String, result: WorkflowRunResult): Unit =
        withContext(Dispatchers.IO) {
            val stamp = nowStamp()
            val statusText = result.status.uiLabel()
            writeIndex(loadTasks().map {
                if (it.id == taskId) {
                    it.copy(lastRunStatus = statusText, lastRun = stamp, lastMessage = result.message)
                } else it
            })
        }

    // ---- internals ----

    private fun loadTasks(): List<WorkflowTask> {
        if (!indexFile.isFile) {
            // First run: install bundled configs and build the default index.
            return bootstrap()
        }
        val decoded = runCatching { WorkflowTaskIndexCodec.decode(indexFile.readText()) }.getOrNull()
        if (decoded.isNullOrEmpty()) {
            // Index present but unreadable/empty: rebuild from what we have.
            return bootstrap()
        }
        // Older builds could persist partially decoded entries when one
        // bundled metadata file was malformed. Remove only unusable records;
        // valid user-edited tasks remain untouched.
        val sanitized = decoded.filter { task ->
            task.id.isNotBlank() &&
                task.configFileName.isNotBlank() &&
                task.title.isNotBlank()
        }
        if (sanitized.isEmpty()) return bootstrap()

        // Harmony's WorkflowStorage.syncCodeDefinedDefaultTasks restores any
        // code-defined defaults missing from a partially persisted index while
        // preserving custom/user-edited records. This is important after an
        // interrupted first install or an older build that wrote a truncated
        // task index.
        val synced = syncMissingDefaults(sanitized)
        if (synced.size != decoded.size || synced != decoded) writeIndex(synced)
        return synced
    }

    private fun bootstrap(): List<WorkflowTask> {
        ensureWorkspace()
        val tasks = DefaultConfigs.entries.mapNotNull { entry ->
            val raw = bundled.read(entry.fileName) ?: return@mapNotNull null
            copiedConfigFile(entry.fileName).writeText(raw)
            definitionFor(raw, entry)
        }
        writeIndex(tasks)
        return tasks
    }

    private fun syncMissingDefaults(existing: List<WorkflowTask>): List<WorkflowTask> {
        val byId = existing.associateBy { it.id }
        val synced = existing.toMutableList()
        DefaultConfigs.entries.forEach { entry ->
            if (byId.containsKey(entry.id)) return@forEach
            val raw = bundled.read(entry.fileName) ?: return@forEach
            val config = copiedConfigFile(entry.fileName)
            if (!config.isFile) config.writeText(raw)
            definitionFor(raw, entry)?.let(synced::add)
        }
        return synced
    }

    private fun definitionFor(raw: String, entry: DefaultConfigs): WorkflowTask? {
        val def = WorkflowJsonParser.parse(raw)
        val metadata = (def as? WorkflowJsonParser.Result.Ok)?.definition?.metadata
        // A single malformed bundled workflow must not make the entire task
        // tab unusable. Keep the task visible with a deterministic fallback;
        // execution still reports the real parser error when the user runs it.
        val title = metadata?.description?.takeIf { it.isNotBlank() }
            ?: entry.fileName.removeSuffix(".json")
        val source = metadata?.name?.takeIf { it.isNotBlank() } ?: entry.fileName.removeSuffix(".json")
        return WorkflowTask(
            id = entry.id,
            sceneId = entry.sceneId,
            icon = iconForScene(entry.sceneId),
            iconColor = colorForScene(entry.sceneId),
            title = title,
            source = source,
            enabled = true,
            configFileName = entry.fileName,
            lastRunStatus = "未运行",
            lastRun = "尚未执行",
            lastMessage = "点击运行后开始执行 workflow",
        )
    }

    private fun writeIndex(tasks: List<WorkflowTask>) {
        ensureWorkspace()
        replaceText(indexFile, WorkflowTaskIndexCodec.encode(tasks))
    }

    private fun ensureWorkspace() {
        if (!workflowsDir.exists()) workflowsDir.mkdirs()
        if (!configsDir.exists()) configsDir.mkdirs()
    }

    private fun resolvedConfigFile(fileName: String): File {
        require(fileName.isNotBlank() && fileName != "." && fileName != ".." &&
            fileName.none { it == '/' || it == '\\' || it == ':' || it.isISOControl() }) {
            "Invalid configuration filename"
        }
        val file = configsDir.resolve(if (fileName.contains('.')) fileName else "$fileName.json")
        require(file.canonicalFile.parentFile == configsDir.canonicalFile) { "Configuration outside workspace" }
        return file
    }

    private fun copiedConfigFile(fileName: String): File = resolvedConfigFile(fileName)

    /** A failed write cannot leave half of a JSON document in the destination. */
    private fun replaceText(destination: File, text: String) {
        val temporary = File.createTempFile("workflow-", ".tmp", destination.parentFile)
        try {
            temporary.outputStream().use { stream ->
                stream.write(text.toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            try {
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
        }
    }

    private fun nowStamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    private fun iconForScene(sceneId: Int): String = when (sceneId) {
        0 -> "Buy"
        1 -> "Food"
        2 -> "Play"
        3 -> "Chat"
        4 -> "Life"
        6 -> "Trip"
        else -> "Social"
    }

    private fun colorForScene(sceneId: Int): String = when (sceneId) {
        0 -> "#4B8BFF"
        1 -> "#39BFA3"
        2 -> "#8066FF"
        3 -> "#FF9347"
        4 -> "#F59E0B"
        6 -> "#0EA5E9"
        else -> "#E85D8E"
    }
}

/** Chinese status label for a run status (used by the task index / UI). */
fun com.clawmate.app.task.runner.RunStatus.uiLabel(): String = when (this) {
    com.clawmate.app.task.runner.RunStatus.SUCCESS -> "成功"
    com.clawmate.app.task.runner.RunStatus.BLOCKED -> "被阻断"
    com.clawmate.app.task.runner.RunStatus.NOT_CONFIGURED -> "未配置"
    com.clawmate.app.task.runner.RunStatus.CANCELLED -> "已取消"
    com.clawmate.app.task.runner.RunStatus.FAILED -> "失败"
}

/** Default tasks: config file -> scene id (mirrors source DEFAULT_WORKFLOW_CONFIGS). */
enum class DefaultConfigs(val id: String, val sceneId: Int, val fileName: String) {
    ALIPAY_BILL("default_alipay_bill", 0, "default_alipay_bill.json"),
    ELE_ORDER("default_ele_order", 1, "default_ele_order.json"),
    BILIBILI_HISTORY("default_bilibili_history", 2, "default_bilibili_history.json"),
    TENCENT_VIDEO_HISTORY("default_tencent_video_history", 2, "default_tencent_video_history.json"),
    WEIXIN_CHAT("default_weixin_chat", 3, "default_weixin_chat.json"),
    WEIXIN_TODAY_CHAT("default_weixin_today_chat", 3, "default_weixin_today_chat.json"),
    DIANPING_ORDER("default_dianping_order", 4, "default_dianping_order.json"),
    MEITUAN_ORDER("default_meituan_order", 4, "default_meituan_order.json"),
    WEIBO_HOT_SEARCH("default_weibo_hot_search", 5, "default_weibo_hot_search.json"),
    XIAOHONGSHU_HISTORY("default_xiaohongshu_history", 5, "default_xiaohongshu_history.json"),
    CTRIP_ORDER("default_ctrip_order", 6, "default_ctrip_order.json"),
}
