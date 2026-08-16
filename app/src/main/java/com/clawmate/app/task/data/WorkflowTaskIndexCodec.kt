package com.clawmate.app.task.data

import com.clawmate.app.task.model.WorkflowTask
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Persistence mapping for the task index (mirrors HarmonyOS `task-index.json`).
 * Small dedicated serializable DTOs keep the file format stable and the
 * serialization deterministic regardless of the domain models.
 */
object WorkflowTaskIndexCodec {

    @Serializable
    private data class TaskIndexFile(val version: Int = 1, val tasks: List<TaskFile> = emptyList())

    @Serializable
    private data class TaskFile(
        val id: String = "",
        val sceneId: Int = 0,
        val icon: String = "",
        val iconColor: String = "",
        val title: String = "",
        val source: String = "",
        val enabled: Boolean = true,
        val configFileName: String = "",
        val lastRunStatus: String = "未运行",
        val lastRun: String = "尚未执行",
        val lastMessage: String = "",
        val isUserModified: Boolean = false,
        val useEmbeddedWorkflowJson: Boolean = false,
    )

    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    fun encode(tasks: List<WorkflowTask>): String {
        val file = TaskIndexFile(
            version = 1,
            tasks = tasks.map { t ->
                TaskFile(
                    id = t.id,
                    sceneId = t.sceneId,
                    icon = t.icon,
                    iconColor = t.iconColor,
                    title = t.title,
                    source = t.source,
                    enabled = t.enabled,
                    configFileName = t.configFileName,
                    lastRunStatus = t.lastRunStatus,
                    lastRun = t.lastRun,
                    lastMessage = t.lastMessage,
                    isUserModified = t.isUserModified,
                    useEmbeddedWorkflowJson = t.useEmbeddedWorkflowJson,
                )
            },
        )
        return json.encodeToString(TaskIndexFile.serializer(), file)
    }

    /** Decodes tolerantly; returns null when the JSON is unrecoverable. */
    fun decode(text: String): List<WorkflowTask>? = try {
        json.decodeFromString(TaskIndexFile.serializer(), text).tasks.map { f ->
            WorkflowTask(
                id = f.id,
                sceneId = f.sceneId,
                icon = f.icon,
                iconColor = f.iconColor,
                title = f.title,
                source = f.source,
                enabled = f.enabled,
                configFileName = f.configFileName,
                lastRunStatus = f.lastRunStatus,
                lastRun = f.lastRun,
                lastMessage = f.lastMessage,
                isUserModified = f.isUserModified,
                useEmbeddedWorkflowJson = f.useEmbeddedWorkflowJson,
            )
        }
    } catch (t: Throwable) {
        null
    }
}
