package com.clawmate.app.task.ui

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.clawmate.app.R
import com.clawmate.app.task.TaskComponent
import com.clawmate.app.task.model.WorkflowScene
import com.clawmate.app.task.model.WorkflowTask
import com.clawmate.app.ui.ClawMateHeader
import com.clawmate.app.ui.theme.ClawMateDesign

/**
 * Compose Task screen — Android vertical slice of HarmonyOS TaskPage.ets.
 * Shows an execution header panel, scene-grouped compact task rows, and a
 * batch-run button, mirroring the HarmonyOS execution-list layout.
 */
@Composable
fun TaskScreen(
    context: Context,
    modifier: Modifier = Modifier,
    onOpenSettings: () -> Unit = {},
) {
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
            error != null -> TaskContent(vm, state, context, onOpenSettings)
            empty != null -> TaskStateWithHeader {
                EmptyState(vm, empty, modifier = Modifier.weight(1f))
            }
            else -> TaskContent(vm, state, context, onOpenSettings)
        }
    }
}

@Composable
private fun TaskStateWithHeader(
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ClawMateDesign.Colors.PageBackground),
    ) {
        ClawMateHeader(title = "自定义任务", iconRes = R.drawable.header_task)
        content()
    }
}

@Composable
private fun TaskContent(
    vm: TaskViewModel,
    state: TaskUiState,
    context: Context,
    onOpenSettings: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ClawMateDesign.Colors.PageBackground),
    ) {
        ClawMateHeader(title = "自定义任务", iconRes = R.drawable.header_task)
        TaskExecutionHeaderPanel(tasks = state.tasks, onOpenConfig = onOpenSettings)
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
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (state.tasks.isEmpty()) {
                item {
                    Text(
                        "暂无可用 workflow",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            } else {
                state.scenes.forEach { scene ->
                    val sceneTasks = state.tasks.filter { it.sceneId == scene.sceneId }
                    if (sceneTasks.isNotEmpty()) {
                        item(key = "scene-${scene.sceneId}") {
                            TaskSceneHeading(scene = scene, tasks = sceneTasks)
                        }
                        items(sceneTasks, key = { it.id }) { task ->
                            SimpleTaskRow(
                                task = task,
                                scene = scene,
                                isRunning = state.runningTaskId == task.id,
                                onToggle = { vm.toggleEnabled(task) },
                            )
                        }
                    }
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
        }
    }
}

@Composable
private fun TaskExecutionHeaderPanel(tasks: List<WorkflowTask>, onOpenConfig: () -> Unit) {
    val enabledCount = tasks.count { it.enabled }
    Card(
        modifier = Modifier
            .fillMaxWidth(0.86f)
            .padding(top = 24.dp, bottom = 18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, Color(0xFFE3E8F1)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
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
            Button(
                onClick = onOpenConfig,
                modifier = Modifier.width(92.dp).height(36.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF276EF1)),
                shape = RoundedCornerShape(10.dp),
            ) {
                Text("配置任务", fontSize = 12.sp, color = Color.White)
            }
        }
    }
}

@Composable
private fun TaskSceneHeading(scene: WorkflowScene, tasks: List<WorkflowTask>) {
    val accent = parseHexColor(scene.colorHex)
    Row(
        modifier = Modifier
            .fillMaxWidth(0.86f)
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

/**
 * Compact task row matching HarmonyOS SimpleTaskRow.
 * 40x40 icon, title + status label, and a switch — no expand, no run button,
 * no step summary (those belong in the config/editor view).
 */
@Composable
private fun SimpleTaskRow(
    task: WorkflowTask,
    scene: WorkflowScene,
    isRunning: Boolean,
    onToggle: () -> Unit,
) {
    val accent = parseHexColor(scene.colorHex)
    val borderColor = if (isRunning) accent else Color(0xFFE8EDF5)
    val statusText = when {
        isRunning -> "执行中"
        task.enabled -> "已开启"
        else -> "已关闭"
    }
    val statusColor = when {
        isRunning -> Color(0xFF276EF1)
        task.enabled -> Color(0xFF148F73)
        else -> Color(0xFF8E95A3)
    }
    val iconColor = if (task.enabled) accent else Color(0xFF8E95A3)
    val iconBg = if (task.enabled) Color(0xFFF1F5FF) else Color(0xFFF4F5F8)

    Card(
        modifier = Modifier
            .fillMaxWidth(0.86f)
            .padding(top = 10.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, borderColor),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(iconBg, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    task.icon,
                    fontSize = 12.sp,
                    color = iconColor,
                    fontWeight = FontWeight.Bold,
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp, end = 12.dp),
            ) {
                Text(
                    task.title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF11182F),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    statusText,
                    fontSize = 11.sp,
                    color = statusColor,
                    modifier = Modifier.padding(top = 5.dp),
                )
            }
            Switch(
                checked = task.enabled,
                onCheckedChange = { onToggle() },
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
            .padding(top = 10.dp, bottom = 18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, Color(0xFFE3E8F1)),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            message?.let {
                Text(
                    it,
                    fontSize = 12.sp,
                    color = Color(0xFF4E5666),
                    modifier = Modifier.padding(bottom = 10.dp),
                )
            }
            val buttonText = when {
                running -> "执行中"
                enabledCount == 0 -> "暂无开启任务"
                else -> "一键执行已开启任务（${enabledCount}）"
            }
            Button(
                onClick = if (running) onCancel else onRun,
                enabled = running || enabledCount > 0,
                modifier = Modifier.fillMaxWidth().height(46.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (running) Color(0xFF8A93A3) else Color(0xFF276EF1),
                    disabledContainerColor = Color(0xFFA9B7E9),
                ),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(
                    buttonText,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
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

private fun parseHexColor(hex: String): Color {
    val cleaned = hex.removePrefix("#")
    if (cleaned.length == 6) {
        return Color(android.graphics.Color.parseColor("#FF${cleaned}"))
    }
    return Color(0xFF765BFF)
}
