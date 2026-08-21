package com.clawmate.app.task.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.lifecycle.viewmodel.compose.viewModel
import com.clawmate.app.task.TaskComponent
import com.clawmate.app.task.model.WorkflowScene
import com.clawmate.app.task.model.WorkflowTask
import com.clawmate.app.task.runner.RunStatus
import com.clawmate.app.R
import com.clawmate.app.ui.ClawMateHeader

/**
 * Compose Task screen — the Android vertical slice of the HarmonyOS 任务 tab
 * (`pages/task/TaskPage.ets`). Presents the 7 collection scenes as a filter
 * list, workflow task cards (title, source, enabled toggle, last status), and
 * an expandable step summary per card exposing the honest run outcome.
 */
@Composable
fun TaskScreen(context: Context, modifier: Modifier = Modifier) {
    val vm: TaskViewModel = viewModel(
        factory = TaskViewModelFactory(TaskComponent.repository(context), TaskComponent.runner(context)),
    )
    val state by vm.state.collectAsState()

    Box(modifier = modifier.fillMaxSize()) {
        val error = state.error
        val empty = state.emptyMessage
        when {
            state.isLoading -> TaskStateWithHeader {
                LoadingState(modifier = Modifier.weight(1f))
            }
            error != null && state.tasks.isEmpty() -> TaskStateWithHeader {
                FullError(vm, error, modifier = Modifier.weight(1f))
            }
            error != null -> TaskContent(vm, state, context)
            empty != null -> TaskStateWithHeader {
                EmptyState(vm, empty, modifier = Modifier.weight(1f))
            }
            else -> TaskContent(vm, state, context)
        }
    }
}

@Composable
private fun TaskStateWithHeader(
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF4F7FB)),
    ) {
        ClawMateHeader(title = "自定义任务", iconRes = R.drawable.header_task)
        content()
    }
}

@Composable
private fun TaskContent(vm: TaskViewModel, state: TaskUiState, context: Context) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF4F7FB)),
    ) {
        ClawMateHeader(title = "自定义任务", iconRes = R.drawable.header_task)
        TaskExecutionHeaderPanel(state.tasks)
        if (state.error != null) {
            Text(
                text = state.error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (state.filteredTasks.isEmpty()) {
                item {
                    Text("此分类下暂无 workflow", style = MaterialTheme.typography.bodyMedium)
                }
            } else if (state.sceneFilter == null) {
                state.scenes.forEach { scene ->
                    val sceneTasks = state.tasks.filter { it.sceneId == scene.sceneId }
                    if (sceneTasks.isNotEmpty()) {
                        item(key = "scene-${scene.sceneId}") {
                            TaskSceneHeading(scene = scene, tasks = sceneTasks)
                        }
                        items(sceneTasks, key = { it.id }) { task ->
                            TaskCard(
                                task = task,
                                scene = scene,
                                isRunning = state.runningTaskId == task.id,
                                isExpanded = state.expandedTaskId == task.id,
                                stepSummary = state.stepSummary,
                                runResult = state.activeResult,
                                onToggle = { vm.toggleEnabled(task) },
                                onExpand = { vm.toggleExpanded(task) },
                                onRun = { vm.run(task) },
                            )
                        }
                    }
                }
            } else {
                items(state.filteredTasks, key = { it.id }) { task ->
                    TaskCard(
                        task = task,
                        scene = state.scenes.firstOrNull { it.sceneId == task.sceneId },
                        isRunning = state.runningTaskId == task.id,
                        isExpanded = state.expandedTaskId == task.id,
                        stepSummary = state.stepSummary,
                        runResult = state.activeResult,
                        onToggle = { vm.toggleEnabled(task) },
                        onExpand = { vm.toggleExpanded(task) },
                        onRun = { vm.run(task) },
                    )
                }
            }
            item {
                BatchRunPanel(
                    enabledCount = state.tasks.count { it.enabled },
                    running = state.batchRunning,
                    message = state.batchMessage,
                    onRun = vm::runEnabledTasks,
                    onCancel = vm::cancelRun,
                )
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { vm.reload() }, modifier = Modifier.align(Alignment.CenterVertically)) {
                        Text("刷新")
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskSceneHeading(scene: WorkflowScene, tasks: List<WorkflowTask>) {
    val accent = parseHexColor(scene.colorHex)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            scene.label,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF11182F),
            modifier = Modifier.weight(1f),
        )
        Text(
            "${tasks.count { it.enabled }}/${tasks.size} 开启",
            fontSize = 11.sp,
            color = accent,
            modifier = Modifier
                .background(Color(0xFFF8F9FE), RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun TaskExecutionHeaderPanel(tasks: List<WorkflowTask>) {
    val enabledCount = tasks.count { it.enabled }
    Card(
        modifier = Modifier
            .fillMaxWidth(0.86f)
            .padding(top = 24.dp, bottom = 18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE3E8F1)),
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                "任务执行",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF11182F),
            )
            Text(
                "已开启 $enabledCount / 全部 ${tasks.size}",
                fontSize = 12.sp,
                color = Color(0xFF6F7584),
                modifier = Modifier.padding(top = 7.dp),
            )
        }
    }
}

@Composable
private fun BatchRunPanel(
    enabledCount: Int,
    running: Boolean,
    message: String?,
    onRun: () -> Unit,
    onCancel: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth(0.86f)
            .padding(bottom = 18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE3E8F1)),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            message?.let {
                Text(it, fontSize = 12.sp, color = Color(0xFF4E5666), modifier = Modifier.padding(bottom = 10.dp))
            }
            Button(
                onClick = if (running) onCancel else onRun,
                enabled = running || enabledCount > 0,
                modifier = Modifier.fillMaxWidth().height(46.dp),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = if (running) Color(0xFF8A93A3) else Color(0xFF276EF1),
                    disabledContainerColor = Color(0xFFA9B7E9),
                ),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(if (running) "停止执行" else "执行已开启任务", fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun TaskCard(
    task: WorkflowTask,
    scene: WorkflowScene?,
    isRunning: Boolean,
    isExpanded: Boolean,
    stepSummary: List<StepSummaryLine>,
    runResult: com.clawmate.app.task.runner.WorkflowRunResult?,
    onToggle: () -> Unit,
    onExpand: () -> Unit,
    onRun: () -> Unit,
) {
    val accent = scene?.let { parseHexColor(it.colorHex) } ?: MaterialTheme.colorScheme.primary
    Card(
        modifier = Modifier.fillMaxWidth(0.86f),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        ),
    ) {
        Column(modifier = Modifier
            .clickable(onClick = onExpand)
            .padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .background(accent.copy(alpha = if (task.enabled) 0.18f else 0.08f), MaterialTheme.shapes.medium),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        task.icon,
                        fontSize = 13.sp,
                        color = accent,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        task.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        task.source,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Switch(checked = task.enabled, onCheckedChange = { onToggle() })
            }
            // Status line
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusBadge(status = task.lastRunStatus, accent = accent)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (isRunning) "执行中…" else (task.lastRun.takeIf { it.isNotBlank() } ?: "尚未执行"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            // Run action row
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = onRun,
                    enabled = task.enabled && !isRunning,
                ) {
                    Text(if (isRunning) "运行中" else "运行")
                }
            }

            if (isExpanded) {
                Spacer(Modifier.height(12.dp))
                StepSummarySection(stepSummary = stepSummary, runResult = runResult, task = task)
            }
        }
    }
}

@Composable
private fun StatusBadge(status: String, accent: androidx.compose.ui.graphics.Color) {
    val tint = when (status) {
        "成功" -> androidx.compose.ui.graphics.Color(0xFF148F73)
        "被阻断", "未配置", "失败" -> androidx.compose.ui.graphics.Color(0xFFD9467D)
        else -> androidx.compose.ui.graphics.Color(0xFF8E95A3)
    }
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .background(tint.copy(alpha = 0.12f), MaterialTheme.shapes.small)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(status, color = tint, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun StepSummarySection(
    stepSummary: List<StepSummaryLine>,
    runResult: com.clawmate.app.task.runner.WorkflowRunResult?,
    task: WorkflowTask,
) {
    Column {
        Text("Workflow 步骤", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        if (stepSummary.isEmpty()) {
            Text("（无步骤详情）", style = MaterialTheme.typography.bodySmall)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                stepSummary.forEach { line ->
                    Row(modifier = Modifier.padding(start = (line.indent * 12).dp)) {
                        Text(
                            "${line.id}. [${line.type}] ${line.label}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("最近运行", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        if (runResult == null) {
            Text(
                task.lastMessage.ifBlank { "尚未运行" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                "${runResult.status}: ${runResult.message}",
                style = MaterialTheme.typography.bodySmall,
                color = runStatusColor(runResult.status),
            )
            if (runResult.steps.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                runResult.steps.take(12).forEach { s ->
                    Text(
                        "  • ${s.type}/${s.stepId} -> ${s.status}: ${s.note}${if (s.detail.isNotBlank()) " — ${s.detail}" else ""}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private fun runStatusColor(status: RunStatus): androidx.compose.ui.graphics.Color = when (status) {
    RunStatus.SUCCESS -> androidx.compose.ui.graphics.Color(0xFF148F73)
    RunStatus.BLOCKED, RunStatus.NOT_CONFIGURED, RunStatus.FAILED -> androidx.compose.ui.graphics.Color(0xFFD9467D)
    RunStatus.CANCELLED -> androidx.compose.ui.graphics.Color(0xFF8E95A3)
}

private fun parseHexColor(hex: String): androidx.compose.ui.graphics.Color {
    val cleaned = hex.removePrefix("#")
    if (cleaned.length == 6) {
        return androidx.compose.ui.graphics.Color(
            android.graphics.Color.parseColor("#FF$cleaned")
        )
    }
    return androidx.compose.ui.graphics.Color(0xFF765BFF)
}

@Composable
private fun LoadingState(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(8.dp))
            Text("正在加载 workflows…")
        }
    }
}

@Composable
private fun FullError(vm: TaskViewModel, message: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("加载失败", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(message, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(12.dp))
            Button(onClick = { vm.reload() }) { Text("重试") }
        }
    }
}

@Composable
private fun EmptyState(vm: TaskViewModel, message: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(message, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            Button(onClick = { vm.reload() }) { Text("重新加载") }
        }
    }
}
