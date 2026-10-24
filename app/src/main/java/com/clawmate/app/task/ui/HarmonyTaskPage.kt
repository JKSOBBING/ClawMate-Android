package com.clawmate.app.task.ui

import android.app.TimePickerDialog
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clawmate.app.task.model.*
import com.clawmate.app.ui.*
import com.clawmate.app.ui.theme.HarmonyTheme

@Composable
internal fun HarmonyTaskPage(state: TaskUiState, onToggle: (WorkflowTask) -> Unit,
    onViewConfig: (WorkflowTask) -> Unit, onRun: (WorkflowTask) -> Unit,
    onBatchRun: () -> Unit, onCancel: () -> Unit, onRefresh: () -> Unit, onSettings: () -> Unit,
) {
    var configuring by rememberSaveable { mutableStateOf(false) }
    var sceneId by rememberSaveable { mutableIntStateOf(0) }
    var focusedId by rememberSaveable { mutableStateOf("") }
    var boundary by remember { mutableStateOf<String?>(null) }
    val list = rememberLazyListState()
    val busy = state.runningTaskId != null || state.batchRunning
    val filtered = state.tasks.filter { it.sceneId == sceneId }
    val openConfig: (WorkflowTask?) -> Unit = { task ->
        focusedId = task?.id.orEmpty(); task?.let { sceneId = it.sceneId }; configuring = true
    }
    BackHandler(configuring) { configuring = false }
    LaunchedEffect(configuring, focusedId) {
        if (configuring && focusedId.isNotEmpty()) {
            val index = filtered.indexOfFirst { it.id == focusedId }
            if (index >= 0) list.scrollToItem(2 + index)
        } else list.scrollToItem(0)
    }
    HarmonyPage(background = HarmonyTheme.background("#F4F7FB"),
        scrollOffset = { if (list.firstVisibleItemIndex > 0) 8f else list.firstVisibleItemScrollOffset.toFloat() },
        header = { ClawMateHeader(if (configuring) "配置任务" else "自定义任务",
            onBack = if (configuring) ({ configuring = false }) else null,
            actionLabel = if (configuring) "刷新" else null, onAction = onRefresh) }) { insets ->
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = insets,
            horizontalAlignment = Alignment.CenterHorizontally) {
            if (configuring) {
                item("create") { TaskCreatePanel(state.scenes) { boundary = "新建与图形编排界面仍在迁移，请先通过现有任务的 JSON 入口查看和编辑配置。" } }
                item("scenes") {
                    Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 16.dp).horizontalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        state.scenes.forEach { scene -> HarmonyButton(scene.label, { sceneId = scene.sceneId; focusedId = "" },
                            height = 38.dp, radius = 10.dp, fontSize = 13.sp, horizontalPadding = 16.dp,
                            background = HarmonyTheme.background(if (sceneId == scene.sceneId) scene.colorHex else "#FFFFFF"),
                            color = HarmonyTheme.text(if (sceneId == scene.sceneId) "#FFFFFF" else "#6D7481"), bold = sceneId == scene.sceneId) }
                    }
                }
                items(filtered, key = { it.id }) { task -> HarmonyTaskConfigCard(task, focusedId == task.id, busy,
                    { onToggle(task) }, { onRun(task) }, { onViewConfig(task) },
                    { action -> boundary = "$action 尚未完成 Android 适配，请先使用 JSON 入口编辑现有任务。" }) }
                if (filtered.isEmpty()) item { TaskSurface(Modifier.padding(bottom = 18.dp), 22) {
                    Text("当前场景暂无任务", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = HarmonyTheme.primaryText)
                    Text("点击上方新建任务，使用图编辑或 JSON 创建 workflow。", fontSize = 12.sp,
                        color = HarmonyTheme.text("#7B8290"), modifier = Modifier.padding(top = 8.dp))
                } }
                item("files") { TaskFilePanel(state.tasks, onViewConfig) }
            } else {
                item("execution") { TaskExecutionPanel(state.tasks, !busy) { openConfig(null) } }
                item("device") { TaskDevicePanel(!busy) { boundary = "Android 任务控制仍由已配置的适配器决定；手机 HDC 及控制方式切换尚未接入，请前往任务自动化设置检查。" } }
                item("reminder") { TaskReminderPanel { boundary = "Android 采集通知调度尚未接入。时间仅保存到本机，提醒没有启用。" } }
                state.scenes.forEach { scene ->
                    val tasks = state.tasks.filter { it.sceneId == scene.sceneId }
                    if (tasks.isNotEmpty()) {
                        item("scene:${scene.sceneId}") {
                            Row(Modifier.fillMaxWidth(.86f).padding(top = 6.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(scene.label, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                                    color = HarmonyTheme.primaryText, modifier = Modifier.weight(1f))
                                Text("${tasks.count { it.enabled }}/${tasks.size} 开启", fontSize = 11.sp,
                                    color = HarmonyTheme.text(scene.colorHex), modifier = Modifier.background(HarmonyTheme.background("#F8F9FE"), RoundedCornerShape(8.dp))
                                        .padding(horizontal = 8.dp, vertical = 4.dp))
                            }
                        }
                        items(tasks, key = { it.id }) { task -> HarmonySimpleTaskRow(task, scene,
                            state.runningTaskId == task.id, !busy, { onToggle(task) }, { openConfig(task) }) }
                    }
                }
                item("batch") { TaskBatchPanel(state, onBatchRun, onCancel) }
            }
            if (state.isLoading) item("loading") { CircularProgressIndicator(Modifier.padding(20.dp)) }
            state.error?.let { error -> item("error") { Text(error, color = HarmonyTheme.text("#B42318"), fontSize = 12.sp, modifier = Modifier.fillMaxWidth(.86f).padding(14.dp))
                HarmonyButton("重试", onRefresh) } }
            state.emptyMessage?.let { message -> item("empty") { Text(message, fontSize = 13.sp, color = HarmonyTheme.secondaryText,
                modifier = Modifier.fillMaxWidth(.86f).padding(16.dp)) } }
            item("bottom") { Spacer(Modifier.height(22.dp)) }
        }
    }
    boundary?.let { message -> HarmonyModal({ boundary = null }) {
        Text("功能说明", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = HarmonyTheme.primaryText)
        Text(message, fontSize = 14.sp, lineHeight = 22.sp, color = HarmonyTheme.secondaryText, modifier = Modifier.padding(vertical = 16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HarmonyButton("知道了", { boundary = null })
            HarmonyButton("前往设置", { boundary = null; onSettings() })
        }
    } }
}

@Composable
private fun TaskSurface(modifier: Modifier = Modifier, padding: Int = 14,
    background: Color = HarmonyTheme.surface, border: Color? = null, content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth(.86f).background(background, RoundedCornerShape(14.dp))
        .then(if (border == null) Modifier else Modifier.border(1.dp, border, RoundedCornerShape(14.dp)))
        .padding(padding.dp), content = content)
}

@Composable
private fun TaskExecutionPanel(tasks: List<WorkflowTask>, enabled: Boolean, onConfig: () -> Unit) {
    TaskSurface(Modifier.padding(top = 12.dp, bottom = 18.dp), 18, border = HarmonyTheme.border("#E3E8F1")) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("任务执行", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = HarmonyTheme.primaryText)
                Text("已开启 ${tasks.count { it.enabled }} / 全部 ${tasks.size}", fontSize = 12.sp,
                    color = HarmonyTheme.text("#6F7584"), modifier = Modifier.padding(top = 7.dp))
            }
            HarmonyButton("配置任务", onConfig, Modifier.width(92.dp), height = 36.dp, radius = 10.dp,
                color = HarmonyTheme.text("#FFFFFF"), background = HarmonyTheme.fill("#276EF1"), enabled = enabled, horizontalPadding = 0.dp)
        }
    }
}

@Composable
internal fun HarmonySimpleTaskRow(task: WorkflowTask, scene: WorkflowScene, running: Boolean,
    enabled: Boolean, onToggle: () -> Unit, onConfig: () -> Unit,
) {
    Row(Modifier.padding(top = 10.dp).fillMaxWidth(.86f).clip(RoundedCornerShape(12.dp))
        .background(HarmonyTheme.surface).border(1.dp, HarmonyTheme.border(if (running) scene.colorHex else "#E8EDF5"), RoundedCornerShape(12.dp))
        .padding(end = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).heightIn(min = 66.dp).clickable(onClick = onConfig)
            .semantics { contentDescription = "配置任务：${task.title}" }.padding(start = 14.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).background(HarmonyTheme.background(if (task.enabled) "#F1F5FF" else "#F4F5F8"), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                Text(task.icon, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = HarmonyTheme.text(if (task.enabled) scene.colorHex else "#8E95A3"))
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(task.title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = HarmonyTheme.primaryText, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(if (running) "执行中" else if (task.enabled) "已开启" else "已关闭", fontSize = 11.sp,
                    color = HarmonyTheme.text(if (running) "#276EF1" else if (task.enabled) "#148F73" else "#8E95A3"), modifier = Modifier.padding(top = 5.dp))
            }
        }
        HarmonySwitch(task.enabled, { onToggle() }, enabled = enabled, selectedColor = HarmonyTheme.background(scene.colorHex))
    }
}

@Composable
private fun TaskDevicePanel(enabled: Boolean, onConfigure: () -> Unit) {
    TaskSurface(Modifier.padding(bottom = 16.dp)) {
        Text("任务控制方式", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = HarmonyTheme.primaryText)
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf("电脑控制", "手机自己控制").forEach { label -> HarmonyButton(label, onConfigure, Modifier.weight(1f),
                height = 40.dp, fontSize = 14.sp, color = HarmonyTheme.text("#276EF1"),
                background = HarmonyTheme.background("#EEF1FF"), enabled = enabled, horizontalPadding = 0.dp) }
        }
    }
}

@Composable
private fun TaskReminderPanel(onUnavailable: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("collection-reminder-ui", Context.MODE_PRIVATE) }
    var hour by remember { mutableIntStateOf(prefs.getInt("hour", 21)) }
    var minute by remember { mutableIntStateOf(prefs.getInt("minute", 0)) }
    TaskSurface(Modifier.padding(top = 2.dp, bottom = 14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("采集提醒", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = HarmonyTheme.primaryText, modifier = Modifier.weight(1f))
            HarmonyButton("%02d:%02d".format(hour, minute), {
                TimePickerDialog(context, { _, h, m -> hour = h; minute = m; prefs.edit().putInt("hour", h).putInt("minute", m).apply() }, hour, minute, true).show()
            }, Modifier.padding(end = 10.dp), height = 34.dp, radius = 10.dp, fontSize = 15.sp,
                color = HarmonyTheme.text("#276EF1"), background = HarmonyTheme.background("#EAF2FF"), horizontalPadding = 12.dp)
            HarmonySwitch(false, { onUnavailable() })
        }
        Text("每天提醒，点击通知并解锁后采集。", fontSize = 12.sp, color = HarmonyTheme.text("#6F7584"), modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun TaskBatchPanel(state: TaskUiState, onRun: () -> Unit, onCancel: () -> Unit) {
    val count = state.tasks.count { it.enabled }
    val enabled = state.batchRunning || count > 0 && state.runningTaskId == null
    TaskSurface(Modifier.padding(top = 2.dp, bottom = 18.dp), border = HarmonyTheme.border("#E3E8F1")) {
        state.batchMessage?.let { Text(it, fontSize = 12.sp, color = HarmonyTheme.text("#4E5666"), modifier = Modifier.padding(bottom = 10.dp)) }
        HarmonyButton(if (state.batchRunning) "停止执行" else if (count == 0) "暂无开启任务" else "一键执行已开启任务（$count）",
            if (state.batchRunning) onCancel else onRun, Modifier.fillMaxWidth(), height = 46.dp, radius = 12.dp, fontSize = 15.sp,
            bold = true, color = HarmonyTheme.text("#FFFFFF"), background = HarmonyTheme.fill(if (state.batchRunning) "#B42318" else if (enabled) "#276EF1" else "#A9B7E9"), enabled = enabled)
    }
}

@Composable
private fun TaskCreatePanel(scenes: List<WorkflowScene>, onCreate: () -> Unit) {
    TaskSurface(padding = 20, background = HarmonyTheme.background("#F8FAFD"), border = HarmonyTheme.border("#E3E8F1")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("新建任务", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = HarmonyTheme.primaryText)
                    Text("图形编排", fontSize = 11.sp, color = HarmonyTheme.text("#148F73"),
                        modifier = Modifier.padding(start = 10.dp).background(HarmonyTheme.background("#EAF8F3"), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp))
                }
                Text("选择场景后用节点连接定义 workflow", fontSize = 12.sp, color = HarmonyTheme.text("#6F7584"), modifier = Modifier.padding(top = 7.dp))
            }
            Column {
                HarmonyButton("图新建", onCreate, Modifier.width(76.dp), height = 34.dp, radius = 10.dp, color = HarmonyTheme.text("#FFFFFF"), background = HarmonyTheme.fill("#276EF1"), horizontalPadding = 0.dp)
                HarmonyButton("JSON", onCreate, Modifier.width(76.dp).padding(top = 8.dp), height = 30.dp, radius = 9.dp, fontSize = 11.sp, color = HarmonyTheme.text("#276EF1"), background = HarmonyTheme.background("#EEF4FF"), horizontalPadding = 0.dp)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 14.dp).height(28.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            scenes.forEach { Text(it.label, fontSize = 11.sp, color = HarmonyTheme.text(it.colorHex),
                modifier = Modifier.background(HarmonyTheme.background("#F8F9FE"), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp)) }
        }
    }
}

@Composable
private fun HarmonyTaskConfigCard(task: WorkflowTask, focused: Boolean, busy: Boolean, onToggle: () -> Unit,
    onRun: () -> Unit, onJson: () -> Unit, onUnavailable: (String) -> Unit,
) {
    TaskSurface(Modifier.padding(bottom = 16.dp), 18, border = HarmonyTheme.border(if (focused) "#276EF1" else "#E3E8F1")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(54.dp).background(HarmonyTheme.background(if (task.enabled) "#F1F5FF" else "#F4F5F8"), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                Text(task.icon, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = HarmonyTheme.text(task.iconColor))
            }
            Column(Modifier.weight(1f).padding(start = 14.dp, end = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(task.title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = HarmonyTheme.primaryText, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(if (task.enabled) task.lastRunStatus else "已暂停", fontSize = 11.sp, color = HarmonyTheme.text("#66707E"))
                }
                Text("最近运行：${task.lastRun}", fontSize = 12.sp, color = HarmonyTheme.text("#7B8290"), modifier = Modifier.padding(top = 7.dp), maxLines = 1)
                Text("来源模块：${task.source}", fontSize = 12.sp, color = HarmonyTheme.text("#7B8290"), modifier = Modifier.padding(top = 4.dp), maxLines = 1)
                Text(task.lastMessage, fontSize = 11.sp, color = HarmonyTheme.text("#9AA2B1"), modifier = Modifier.padding(top = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            HarmonySwitch(task.enabled, { onToggle() }, enabled = !busy)
        }
        Row(Modifier.fillMaxWidth().padding(top = 14.dp).height(44.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HarmonyButton("运行", onRun, Modifier.width(82.dp), height = 36.dp, radius = 10.dp, color = HarmonyTheme.text("#FFFFFF"), background = HarmonyTheme.background(if (busy) "#A9B7E9" else "#276EF1"), enabled = !busy, horizontalPadding = 0.dp)
            HarmonyButton("图编辑", { onUnavailable("图编辑") }, Modifier.width(86.dp), height = 36.dp, radius = 10.dp, color = HarmonyTheme.text("#148F73"), background = HarmonyTheme.background("#EAF8F3"), horizontalPadding = 0.dp)
            HarmonyButton("JSON", onJson, Modifier.width(82.dp), height = 36.dp, radius = 10.dp, color = HarmonyTheme.text("#276EF1"), background = HarmonyTheme.background("#EEF4FF"), horizontalPadding = 0.dp)
            HarmonyButton("复制", { onUnavailable("复制任务") }, Modifier.width(82.dp), height = 36.dp, radius = 10.dp, color = HarmonyTheme.text("#276EF1"), background = HarmonyTheme.background("#EEF4FF"), horizontalPadding = 0.dp)
            HarmonyButton("删除", { onUnavailable("删除任务") }, Modifier.width(82.dp), height = 36.dp, radius = 10.dp, color = HarmonyTheme.text("#B42318"), background = HarmonyTheme.background("#FFF0ED"), horizontalPadding = 0.dp)
        }
    }
}

@Composable
private fun TaskFilePanel(tasks: List<WorkflowTask>, onOpen: (WorkflowTask) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    TaskSurface(Modifier.padding(bottom = 18.dp), 18) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Workflow 文件管理", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = HarmonyTheme.primaryText)
                Text("已索引 ${tasks.size} 份任务配置", fontSize = 12.sp, color = HarmonyTheme.text("#6F7584"), modifier = Modifier.padding(top = 7.dp))
            }
            HarmonyButton(if (expanded) "收起" else "展开", { expanded = !expanded })
        }
        if (expanded) tasks.forEach { task -> HarmonyButton(task.configFileName, { onOpen(task) }, Modifier.fillMaxWidth().padding(top = 8.dp), height = 36.dp, horizontalPadding = 8.dp) }
    }
}
