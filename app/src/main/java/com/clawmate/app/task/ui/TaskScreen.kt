package com.clawmate.app.task.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.clawmate.app.task.TaskComponent
import com.clawmate.app.task.model.WorkflowScene
import com.clawmate.app.task.model.WorkflowTask
import com.clawmate.app.task.runner.RunStatus

/**
 * Compose Task screen — the Android vertical slice of the HarmonyOS 任务 tab
 * (`pages/task/TaskPage.ets`). Presents the 7 collection scenes as a filter
 * list, workflow task cards (title, source, enabled toggle, last status), and
 * an expandable step summary per card exposing the honest run outcome.
 */
@Composable
fun TaskScreen(context: Context, modifier: Modifier = Modifier) {
    val vm: TaskViewModel = viewModel(
        factory = TaskViewModelFactory(TaskComponent.repository(context), TaskComponent.runner()),
    )
    val state by vm.state.collectAsState()

    Box(modifier = modifier.fillMaxSize()) {
        val error = state.error
        val empty = state.emptyMessage
        when {
            state.isLoading -> LoadingState()
            error != null && state.tasks.isEmpty() -> FullError(vm, error)
            empty != null -> EmptyState(vm, empty)
            else -> TaskContent(vm, state, context)
        }
    }
}

@Composable
private fun TaskContent(vm: TaskViewModel, state: TaskUiState, context: Context) {
    Column(modifier = Modifier.fillMaxSize()) {
        SceneFilterBar(
            scenes = state.scenes,
            selected = state.sceneFilter,
            onSelect = vm::selectScene,
        )
        if (state.error != null) {
            Text(
                text = state.error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.filteredTasks.isEmpty()) {
                item {
                    Text("此分类下暂无 workflow", style = MaterialTheme.typography.bodyMedium)
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
private fun SceneFilterBar(scenes: List<WorkflowScene>, selected: Int?, onSelect: (Int?) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = selected == null,
            onClick = { onSelect(null) },
            label = { Text("全部") },
        )
        scenes.forEach { scene ->
            FilterChip(
                selected = selected == scene.sceneId,
                onClick = { onSelect(scene.sceneId) },
                label = { Text(scene.label) },
            )
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
        modifier = Modifier.fillMaxWidth(),
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
                        .size(40.dp)
                        .background(accent.copy(alpha = if (task.enabled) 0.18f else 0.08f), MaterialTheme.shapes.medium),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(task.icon.take(1), color = accent, fontWeight = FontWeight.Bold)
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
private fun LoadingState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(8.dp))
            Text("正在加载 workflows…")
        }
    }
}

@Composable
private fun FullError(vm: TaskViewModel, message: String) {
    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
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
private fun EmptyState(vm: TaskViewModel, message: String) {
    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(message, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            Button(onClick = { vm.reload() }) { Text("重新加载") }
        }
    }
}
