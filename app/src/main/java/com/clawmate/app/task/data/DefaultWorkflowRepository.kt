package com.clawmate.app.task.data

import com.clawmate.app.task.json.WorkflowJsonParser
import com.clawmate.app.task.model.WorkflowTask
import com.clawmate.app.task.runner.WorkflowRunResult
import java.io.File
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
            ensureWorkspace()
            val file = resolvedConfigFile(task.configFileName)
            file.writeText(newJson)
            // Mark the task as user-modified (persisted to the index).
            val tasks = loadTasks()
            writeIndex(tasks.map {
                if (it.id == task.id) it.copy(isUserModified = true) else it
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
        return decoded
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

    private fun definitionFor(raw: String, entry: DefaultConfigs): WorkflowTask? {
        val def = WorkflowJsonParser.parse(raw)
        val metadata = (def as? WorkflowJsonParser.Result.Ok)?.definition?.metadata
        val title = metadata?.description?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("default config ${entry.fileName} missing description")
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
        indexFile.writeText(WorkflowTaskIndexCodec.encode(tasks))
    }

    private fun ensureWorkspace() {
        if (!workflowsDir.exists()) workflowsDir.mkdirs()
        if (!configsDir.exists()) configsDir.mkdirs()
    }

    private fun resolvedConfigFile(fileName: String): File =
        if (fileName.contains('/') || fileName.contains('.')) configsDir.resolve(fileName)
        else configsDir.resolve("$fileName.json")

    private fun copiedConfigFile(fileName: String): File = configsDir.resolve(fileName)

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
