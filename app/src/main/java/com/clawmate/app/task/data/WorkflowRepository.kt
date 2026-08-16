package com.clawmate.app.task.data

import com.clawmate.app.task.model.WorkflowTask
import com.clawmate.app.task.runner.WorkflowRunResult
import java.io.File

/**
 * Reads a single bundled workflow JSON resource by its config file name
 * (e.g. `default_ele_order.json`). The Android implementation reads from
 * `app/src/main/res/raw`; tests use a [FileDirBundledConfigReader] over a temp
 * directory.
 */
fun interface BundledConfigReader {
    fun read(configFileName: String): String?
}

/** Reads bundled configs from a source directory (used by tests / fallback). */
class FileDirBundledConfigReader(private val dir: File) : BundledConfigReader {
    override fun read(configFileName: String): String? {
        val file = dir.resolve(configFileName)
        return if (file.isFile) file.readText() else null
    }
}

/**
 * Workflow repository: loads bundled task/config resources into app-private
 * storage, persists user edits (the editable workflow JSON plus a task index)
 * and run status. Mirrors the HarmonyOS `WorkflowStorage.ets` file contract in
 * an Android app-private `workflows/` directory.
 */
interface WorkflowRepository {

    /** All persisted tasks (enabled state, run status, titles). */
    suspend fun tasks(): List<WorkflowTask>

    /** The raw editable config JSON for a task. */
    suspend fun configJson(task: WorkflowTask): String?

    /** Replace the editable config JSON for a task and mark it user-modified. */
    suspend fun saveConfigJson(task: WorkflowTask, newJson: String)

    /** Updates a task's enabled flag. */
    suspend fun setTaskEnabled(taskId: String, enabled: Boolean)

    /** Persists a finished run's status/message against a task. */
    suspend fun recordRun(taskId: String, result: WorkflowRunResult)
}

/** Load outcome for the task list. */
sealed class TasksResult {
    data class Ready(val tasks: List<WorkflowTask>) : TasksResult()
    data class Empty(val message: String) : TasksResult()
    data class Error(val message: String) : TasksResult()
}
