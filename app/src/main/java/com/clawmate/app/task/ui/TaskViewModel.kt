package com.clawmate.app.task.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.clawmate.app.task.data.WorkflowRepository
import com.clawmate.app.task.json.WorkflowJsonParser
import com.clawmate.app.task.model.WorkflowScene
import com.clawmate.app.task.model.WorkflowSceneConfig
import com.clawmate.app.task.model.WorkflowTask
import com.clawmate.app.task.runner.RunStatus
import com.clawmate.app.task.runner.WorkflowRunResult
import com.clawmate.app.task.runner.WorkflowRunner
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** UI state for the Task screen. */
data class TaskUiState(
    val isLoading: Boolean = true,
    val tasks: List<WorkflowTask> = emptyList(),
    val sceneFilter: Int? = null,          // null = 全部 (all scenes)
    val error: String? = null,
    val emptyMessage: String? = null,
    val runningTaskId: String? = null,
    /** Expanded details: per-task step summary + run result, when loaded. */
    val expandedTaskId: String? = null,
    /** Static step summary (parsed config) for the expanded task. */
    val stepSummary: List<StepSummaryLine> = emptyList(),
    /** Run outcome for the expanded task. */
    val activeResult: WorkflowRunResult? = null,
    val batchRunning: Boolean = false,
    val batchMessage: String? = null,
) {
    val scenes: List<WorkflowScene> get() = WorkflowSceneConfig.scenes
    val filteredTasks: List<WorkflowTask>
        get() = if (sceneFilter == null) tasks
        else tasks.filter { it.sceneId == sceneFilter }
}

/** A compact, human-readable line for the step-summary list. */
data class StepSummaryLine(
    val id: String,
    val type: String,
    val label: String,
    val indent: Int,
)

/** Read-only configuration projection, independent of execution state. */
data class WorkflowConfigUiState(
    val task: WorkflowTask,
    val isLoading: Boolean = true,
    val text: String = "",
    val error: String? = null,
    val truncated: Boolean = false,
)

/**
 * ViewModel for the Task tab: loads tasks from [WorkflowRepository], exposes
 * scene-filtering, enable toggling, task-card expansion with a step summary,
 * and a bounded run action through [WorkflowRunner].
 */
class TaskViewModel(
    private val repository: WorkflowRepository,
    private val runner: WorkflowRunner,
) : ViewModel() {

    private val _state = MutableStateFlow(TaskUiState())
    val state: StateFlow<TaskUiState> = _state.asStateFlow()

    private var runningJob: Job? = null
    private var configJob: Job? = null
    private var configRequest = 0L
    private val _config = MutableStateFlow<WorkflowConfigUiState?>(null)
    val config: StateFlow<WorkflowConfigUiState?> = _config.asStateFlow()

    fun openConfig(task: WorkflowTask) {
        configJob?.cancel()
        val request = ++configRequest
        _config.value = WorkflowConfigUiState(task)
        configJob = viewModelScope.launch {
            val result = try {
                val raw = repository.configJson(task)
                when {
                    raw == null -> WorkflowConfigUiState(task, false, error = "找不到或无法读取任务配置")
                    raw.isBlank() -> WorkflowConfigUiState(task, false, error = "任务配置文件为空")
                    else -> WorkflowConfigUiState(
                        task, false, raw.take(MAX_CONFIG_PREVIEW_CHARS),
                        truncated = raw.length > MAX_CONFIG_PREVIEW_CHARS,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                WorkflowConfigUiState(task, false, error = "读取配置失败，请重试")
            }
            // A slow read must never reopen a dismissed view or replace a
            // newer selection, including reselecting the very same task.
            if (request == configRequest) _config.value = result
        }
    }

    fun closeConfig() {
        ++configRequest
        configJob?.cancel()
        configJob = null
        _config.value = null
    }

    companion object {
        const val MAX_CONFIG_PREVIEW_CHARS = 256_000
    }

    init {
        reload()
    }

    fun reload() {
        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            val result = runCatching { repository.tasks() }
            result.fold(
                onSuccess = { tasks ->
                    _state.update {
                        it.copy(
                            isLoading = false,
                            tasks = tasks,
                            emptyMessage = if (tasks.isEmpty()) "暂无可用 workflow" else null,
                        )
                    }
                },
                onFailure = { e ->
                    _state.update {
                        it.copy(isLoading = false, error = e.message ?: "加载失败", tasks = emptyList())
                    }
                },
            )
        }
    }

    fun selectScene(sceneId: Int?) {
        _state.update { it.copy(sceneFilter = sceneId, error = null) }
    }

    fun toggleEnabled(task: WorkflowTask) {
        viewModelScope.launch {
            runCatching { repository.setTaskEnabled(task.id, !task.enabled) }
                .onSuccess { _state.update { it.copy(tasks = it.tasks.map { t -> if (t.id == task.id) t.copy(enabled = !task.enabled) else t }) } }
                .onFailure { e -> _state.update { it.copy(error = e.message ?: "状态更新失败") } }
        }
    }

    fun toggleExpanded(task: WorkflowTask) {
        val expand = _state.value.expandedTaskId != task.id
        _state.update {
            it.copy(
                expandedTaskId = if (expand) task.id else null,
                activeResult = if (expand) null else it.activeResult,
                stepSummary = emptyList(),
            )
        }
        if (expand) {
            viewModelScope.launch {
                val summary = stepSummaryOf(task)
                if (_state.value.expandedTaskId == task.id) {
                    _state.update { it.copy(stepSummary = summary) }
                }
            }
        }
    }

    /** Parses the task config to a static, tolerant step summary (never throws). */
    private suspend fun stepSummaryOf(task: WorkflowTask): List<StepSummaryLine> {
        val json = runCatching { repository.configJson(task) }.getOrNull() ?: return emptyList()
        val def = WorkflowJsonParser.parseOrDefault(json)
        val lines = mutableListOf<StepSummaryLine>()
        fun walk(steps: List<com.clawmate.app.task.model.WorkflowStep>, indent: Int) {
            for (s in steps) {
                lines += StepSummaryLine(s.id, s.type, s.escapedName, indent)
                if (s.type == "loop") walk(s.steps, indent + 1)
                if (s.type == "if") {
                    walk(s.thenSteps, indent + 1)
                    walk(s.elseSteps, indent + 1)
                }
            }
        }
        walk(def.steps, 0)
        return lines
    }

    fun run(task: WorkflowTask) {
        if (_state.value.runningTaskId != null) return
        runningJob?.cancel()
        runningJob = viewModelScope.launch {
            _state.update {
                it.copy(runningTaskId = task.id, error = null, expandedTaskId = task.id, stepSummary = stepSummaryOf(task))
            }
            val result = runCatching {
                if (!task.enabled) throw IllegalStateException("workflow 未启用")
                val json = repository.configJson(task)
                    ?: throw IllegalStateException("找不到配置文件")
                when (val parsed = WorkflowJsonParser.parse(json)) {
                    is WorkflowJsonParser.Result.Ok -> runner.run(parsed.definition)
                    is WorkflowJsonParser.Result.Err -> throw IllegalStateException("workflow 配置解析失败: ${parsed.message}")
                }
            }
            result.fold(
                onSuccess = { runResult ->
                    runCatching { repository.recordRun(task.id, runResult) }
                    _state.update {
                        it.copy(
                            runningTaskId = null,
                            activeResult = runResult,
                            tasks = it.tasks.map { t ->
                                if (t.id == task.id) t.copy(
                                    lastRunStatus = runResult.status.uiLabel(),
                                    lastMessage = runResult.message,
                                ) else t
                            },
                        )
                    }
                },
                onFailure = { e ->
                    _state.update { it.copy(runningTaskId = null, error = e.message ?: "运行失败") }
                },
            )
        }
    }

    /** Runs enabled workflows in source order, matching HarmonyOS batch execution. */
    fun runEnabledTasks() {
        if (_state.value.batchRunning || _state.value.runningTaskId != null) return
        val enabled = _state.value.tasks.filter { it.enabled }
        if (enabled.isEmpty()) {
            _state.update { it.copy(batchMessage = "没有已开启的 workflow") }
            return
        }
        runningJob?.cancel()
        runningJob = viewModelScope.launch {
            _state.update { it.copy(batchRunning = true, batchMessage = "正在执行 ${enabled.size} 个 workflow", error = null) }
            var success = 0
            var failed = 0
            enabled.forEach { task ->
                _state.update { it.copy(runningTaskId = task.id, expandedTaskId = task.id, stepSummary = emptyList(), activeResult = null) }
                val outcome = runCatching {
                    val json = repository.configJson(task)
                        ?: throw IllegalStateException("找不到配置文件")
                    when (val parsed = WorkflowJsonParser.parse(json)) {
                        is WorkflowJsonParser.Result.Ok -> runner.run(parsed.definition)
                        is WorkflowJsonParser.Result.Err -> throw IllegalStateException("workflow 配置解析失败: ${parsed.message}")
                    }
                }
                outcome.onSuccess { result ->
                    success++
                    runCatching { repository.recordRun(task.id, result) }
                    _state.update { state ->
                        state.copy(tasks = state.tasks.map { current ->
                            if (current.id == task.id) current.copy(lastRunStatus = result.status.uiLabel(), lastMessage = result.message) else current
                        })
                    }
                }.onFailure { error ->
                    failed++
                    _state.update { it.copy(error = error.message ?: "运行失败") }
                }
            }
            _state.update {
                it.copy(batchRunning = false, runningTaskId = null, batchMessage = "批量执行完成：成功 $success，失败 $failed")
            }
        }
    }

    fun cancelRun() {
        runningJob?.cancel()
        runningJob = null
        _state.update { it.copy(runningTaskId = null, batchRunning = false, batchMessage = "已取消执行") }
    }
}

private fun com.clawmate.app.task.runner.RunStatus.uiLabel(): String = when (this) {
    RunStatus.SUCCESS -> "成功"
    RunStatus.BLOCKED -> "被阻断"
    RunStatus.NOT_CONFIGURED -> "未配置"
    RunStatus.CANCELLED -> "已取消"
    RunStatus.FAILED -> "失败"
}
