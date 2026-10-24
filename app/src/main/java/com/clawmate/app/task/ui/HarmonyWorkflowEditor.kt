package com.clawmate.app.task.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.clawmate.app.task.json.WorkflowGraphDraft
import com.clawmate.app.task.json.string
import com.clawmate.app.task.model.WorkflowSceneConfig
import com.clawmate.app.ui.*
import com.clawmate.app.ui.theme.HarmonyTheme
import kotlinx.serialization.json.*

private data class NodeTemplate(val key: String, val label: String, val short: String, val accent: String, val background: String)
private val templates = listOf(
    NodeTemplate("open_app", "打开应用", "打开", "#276EF1", "#EAF2FF"),
    NodeTemplate("gui_task", "GUI 任务", "操作", "#276EF1", "#EAF2FF"),
    NodeTemplate("shot_summary", "截图 + Summary", "采集", "#148F73", "#EAF8F3"),
    NodeTemplate("if", "If 条件", "条件", "#7C4DFF", "#F0EAFF"),
    NodeTemplate("for_loop", "For 循环", "循环", "#7C4DFF", "#F0EAFF"),
    NodeTemplate("until_loop", "Until 循环", "直到", "#7C4DFF", "#F0EAFF"))

/** Source TaskPage.EditorPanel. Graph changes serialize to the same real workflow JSON. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun HarmonyWorkflowEditor(state: WorkflowConfigUiState, canEdit: Boolean,
    onClose: () -> Unit, onEdit: () -> Unit, onChange: (String) -> Unit, onScene: (Int) -> Unit,
    onSave: () -> Unit, onRetry: () -> Unit, onDiscard: () -> Unit, onKeepEditing: () -> Unit,
) {
    var mode by remember(state.task.id, state.editorMode) { mutableStateOf(state.editorMode) }
    var selected by remember(state.task.id) { mutableStateOf<String?>(null) }
    var fieldErrors by remember(state.task.id) { mutableStateOf(setOf<String>()) }
    val raw = state.draft ?: state.text
    val graph = remember(raw) { WorkflowGraphDraft.parse(raw) }
    var beganEditing by remember(state.task.id) { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val editable = canEdit && !state.isSaving && !state.truncated && state.error == null && !state.isLoading
    LaunchedEffect(state.isLoading, state.draft, editable) {
        if (editable && !beganEditing && state.draft == null && state.notice == null) { beganEditing = true; onEdit() }
    }
    LaunchedEffect(state.notice) { if (state.notice == "配置已保存") {
        android.widget.Toast.makeText(context, "Workflow 已保存", android.widget.Toast.LENGTH_SHORT).show(); onClose()
    } }
    val update: (WorkflowGraphDraft) -> Unit = { onChange(it.text()) }
    val error: (String, Boolean) -> Unit = { key, invalid -> fieldErrors = if (invalid) fieldErrors + key else fieldErrors - key }
    Column(Modifier.fillMaxWidth(.92f).padding(bottom = 18.dp)
        .clip(RoundedCornerShape(18.dp)).background(HarmonyTheme.background("#F4F7FB"))
        .border(1.dp, HarmonyTheme.border("#DCE3EF"), RoundedCornerShape(18.dp)).padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (state.isNew) "新建 Workflow" else "编辑 Workflow", fontSize = 18.sp, fontWeight = FontWeight.Bold,
                color = HarmonyTheme.primaryText, modifier = Modifier.weight(1f))
            HarmonyButton("关闭", onClose, height = 32.dp, radius = 8.dp, enabled = !state.isSaving,
                color = HarmonyTheme.text("#4E5666"), background = HarmonyTheme.background("#F2F5FA"))
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf("graph" to "图编辑", "json" to "JSON").forEach { (key, label) ->
                HarmonyButton(label, { if (fieldErrors.isEmpty()) { mode = key; selected = null } }, Modifier.weight(1f),
                    height = 34.dp, radius = 8.dp, color = HarmonyTheme.text(if (mode == key) "#FFFFFF" else "#276EF1"),
                    background = HarmonyTheme.background(if (mode == key) "#276EF1" else "#EEF4FF"), enabled = !state.isSaving)
            }
        }
        when {
            state.isLoading -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally).padding(20.dp))
            state.error != null -> { Text(state.error, color = HarmonyTheme.secondaryText); HarmonyButton("重新读取", onRetry) }
            state.truncated -> {
                Text("配置超过编辑长度限制，仅显示原文预览", color = HarmonyTheme.secondaryText)
                androidx.compose.foundation.text.selection.SelectionContainer { Text(raw, fontSize = 11.sp, fontFamily = FontFamily.Monospace) }
            }
            mode == "json" -> WorkflowEditorSurface {
                WorkflowEditorField("工作流 JSON", raw, onChange, height = 360.dp, mono = true, enabled = editable, fontSize = 12.sp)
            }
            graph == null -> WorkflowEditorSurface {
                Text("当前 JSON 无法转换为流程图，请切换 JSON 修正。原文及未知字段均已保留。", fontSize = 13.sp, color = HarmonyTheme.secondaryText)
            }
            else -> {
                val metadata = graph.root["metadata"] as? JsonObject ?: JsonObject(emptyMap())
                val steps = graph.root["steps"] as JsonArray
                WorkflowEditorSurface {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        EditorTitle("工作流信息", Modifier.weight(1f))
                        Text("${steps.size} 个主节点", fontSize = 11.sp, color = HarmonyTheme.text("#276EF1"),
                            modifier = Modifier.background(HarmonyTheme.background("#EAF2FF"), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp))
                    }
                    WorkflowEditorField("任务标题", metadata.string("description"), { update(graph.replace("metadata", JsonObject(metadata + ("description" to JsonPrimitive(it))))) }, enabled = editable)
                    EditorLabel("分类")
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        WorkflowSceneConfig.scenes.forEach { scene -> HarmonyButton(scene.label, { onScene(scene.sceneId) },
                            height = 30.dp, radius = 8.dp, fontSize = 11.sp, horizontalPadding = 12.dp,
                            color = HarmonyTheme.text(if (state.task.sceneId == scene.sceneId) "#FFFFFF" else scene.colorHex),
                            background = HarmonyTheme.background(if (state.task.sceneId == scene.sceneId) scene.colorHex else "#F8F9FE"), enabled = editable) }
                    }
                    ValidatedJsonField("上下文变量", graph.root["context"] ?: JsonObject(emptyMap()), 46.dp, editable,
                        { update(graph.replace("context", it)) }, { error("context", it) })
                }
                WorkflowEditorSurface(Modifier.padding(top = 12.dp)) {
                    Row(Modifier.fillMaxWidth()) {
                        EditorTitle("节点库", Modifier.weight(1f))
                        Text(if (selected == null) "插入到末尾" else "插入到选中节点后", fontSize = 11.sp, color = HarmonyTheme.secondaryText)
                    }
                    Row(Modifier.padding(top = 12.dp).fillMaxWidth().height(68.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        templates.forEach { item -> Column(Modifier.size(82.dp, 58.dp).clip(RoundedCornerShape(10.dp))
                            .background(HarmonyTheme.background(item.background)).clickable(enabled = editable && fieldErrors.isEmpty()) { update(graph.insert(item.key, selected)) },
                            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(item.short, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = HarmonyTheme.text(item.accent))
                            Text(item.label, fontSize = 10.sp, color = HarmonyTheme.secondaryText, modifier = Modifier.padding(top = 4.dp))
                        } }
                    }
                }
                WorkflowEditorSurface(Modifier.padding(top = 12.dp), background = HarmonyTheme.background("#FAFBFD")) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        EditorTitle("流程图", Modifier.weight(1f))
                        HarmonyButton("取消选中", { if (fieldErrors.isEmpty()) selected = null }, height = 30.dp, radius = 8.dp, fontSize = 11.sp)
                    }
                    if (steps.isEmpty()) Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        EditorTitle("暂无节点")
                        Text("从上方节点库添加“打开应用”“GUI 任务”“截图+Summary”或控制流节点。", fontSize = 12.sp, color = HarmonyTheme.secondaryText, modifier = Modifier.padding(top = 8.dp))
                    } else BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val depth = graph.maxDepth()
                        val width = maxWidth.coerceAtLeast((306 + depth * 120).dp)
                        val body: @Composable () -> Unit = {
                            WorkflowFlowNodes(graph, "steps", 0, selected, editable, fieldErrors.isEmpty(),
                                { selected = it }, { update(it); selected = null; fieldErrors = emptySet() }, update, error)
                        }
                        if (depth >= 1) Row(Modifier.horizontalScroll(rememberScrollState())) { Column(Modifier.width(width)) { body() } }
                        else body()
                    }
                }
            }
        }
        if (fieldErrors.isNotEmpty()) Text("请先修正字段格式；当前输入保留在编辑器中", fontSize = 12.sp, color = HarmonyTheme.text("#B42318"), modifier = Modifier.padding(top = 10.dp))
        state.editError?.let { Text(it, fontSize = 12.sp, color = HarmonyTheme.text("#B42318"), modifier = Modifier.padding(top = 10.dp)) }
        state.notice?.let { Text(it, fontSize = 12.sp, color = HarmonyTheme.text("#148F73"), modifier = Modifier.padding(top = 10.dp)) }
        HarmonyButton(if (state.isSaving) "保存中..." else "保存 Workflow", onSave, Modifier.padding(top = 14.dp).fillMaxWidth(),
            height = 42.dp, radius = 10.dp, fontSize = 13.sp, color = HarmonyTheme.text("#FFFFFF"), background = HarmonyTheme.fill("#276EF1"),
            enabled = editable && state.draft != null && fieldErrors.isEmpty())
    }
    if (state.showDiscardDialog) HarmonyModal(onKeepEditing) {
        EditorTitle("放弃未保存的修改？")
        Text("当前草稿尚未保存。", fontSize = 14.sp, color = HarmonyTheme.secondaryText, modifier = Modifier.padding(vertical = 16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HarmonyButton("继续编辑", onKeepEditing)
            HarmonyButton("放弃修改", onDiscard, color = HarmonyTheme.text("#B42318"))
        }
    }
}

@Composable
private fun WorkflowFlowNodes(graph: WorkflowGraphDraft, container: String, depth: Int, selected: String?, enabled: Boolean,
    valid: Boolean, onSelect: (String?) -> Unit, onStructure: (WorkflowGraphDraft) -> Unit,
    onChange: (WorkflowGraphDraft) -> Unit, onError: (String, Boolean) -> Unit,
) {
    val nodes = graph.at(container) as? JsonArray ?: return
    if (depth > 12) { Text("更深层节点请在 JSON 中编辑", fontSize = 12.sp, color = HarmonyTheme.secondaryText); return }
    var index = 0
    while (index < nodes.size) {
        val i = index
        val node = nodes[i] as JsonObject
        val path = "$container/$i"
        val pair = WorkflowGraphDraft.isPair(nodes, i)
        val active = selected == path
        val accent = if (pair || node.string("type") == "tool") "#148F73" else if (node.string("type") in listOf("if", "loop")) "#7C4DFF" else "#276EF1"
        key(path, node.string("id")) {
        Row(Modifier.padding(top = 12.dp).fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.padding(start = (depth * 14).dp).width(32.dp).padding(end = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(2.dp, 18.dp).background(HarmonyTheme.background("#DCE3EF")))
                Box(Modifier.size(28.dp).background(HarmonyTheme.fill(accent), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                    Text(node.string("id") + if (pair) "+" else "", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = HarmonyTheme.text("#FFFFFF"), maxLines = 1)
                }
                Box(Modifier.size(2.dp, 18.dp).background(HarmonyTheme.background("#DCE3EF")))
            }
            Column(Modifier.weight(1f).padding(start = 10.dp).clip(RoundedCornerShape(14.dp))
                .background(HarmonyTheme.background(if (active) "#F8FBFF" else "#FFFFFF"))
                .border(if (active) 2.dp else 1.dp, HarmonyTheme.border(if (active) accent else "#E3E8F1"), RoundedCornerShape(14.dp)).padding(13.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (pair) "截图 + Summary" else node.string("name").ifBlank { nodeLabel(node) }, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                        color = HarmonyTheme.primaryText, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(if (pair) "2 steps" else node.string("type"), fontSize = 10.sp, color = HarmonyTheme.text(accent),
                        modifier = Modifier.background(HarmonyTheme.background("#EAF2FF"), RoundedCornerShape(7.dp)).padding(horizontal = 7.dp, vertical = 3.dp))
                }
                Text(node.string("task_description").ifBlank { node.string("action").ifBlank { node.string("tool_name") } }, fontSize = 11.sp,
                    color = HarmonyTheme.secondaryText, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                Row(Modifier.padding(top = 10.dp).fillMaxWidth().height(38.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HarmonyButton(if (active) "已选中" else "选中", { if (valid) onSelect(path) }, Modifier.width(74.dp), height = 30.dp, radius = 8.dp, fontSize = 11.sp, enabled = enabled)
                    HarmonyButton("上移", { onStructure(graph.move(path, -1)) }, Modifier.width(68.dp), height = 30.dp, radius = 8.dp, fontSize = 11.sp, enabled = enabled && valid && i > 0)
                    HarmonyButton("下移", { onStructure(graph.move(path, 1)) }, Modifier.width(68.dp), height = 30.dp, radius = 8.dp, fontSize = 11.sp, enabled = enabled && valid && i + (if (pair) 2 else 1) < nodes.size)
                    HarmonyButton("删除", { onStructure(graph.remove(path)) }, Modifier.width(68.dp), height = 30.dp, radius = 8.dp, fontSize = 11.sp, color = HarmonyTheme.text("#B42318"), enabled = enabled && valid)
                }
                if (active) {
                    Box(Modifier.padding(vertical = 12.dp).fillMaxWidth().height(1.dp).background(HarmonyTheme.border("#DCE3EF")))
                    EditorTitle("节点属性")
                    if (pair) WorkflowPairFields(graph, path, "$container/${i + 1}", enabled, onChange, onError)
                    else WorkflowNodeFields(graph, path, enabled, onChange, onError)
                }
                listOf("steps" to "循环体", "then_steps" to "满足条件", "else_steps" to "否则执行").forEach { (branch, label) ->
                    if (node[branch] is JsonArray) {
                        Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = HarmonyTheme.secondaryText, modifier = Modifier.weight(1f))
                            HarmonyButton("添加节点", { onStructure(graph.insert("gui_task", branch = "$path/$branch")) },
                                height = 28.dp, radius = 8.dp, fontSize = 10.sp, enabled = enabled && valid,
                                color = HarmonyTheme.text("#7C4DFF"), background = HarmonyTheme.background("#F0EAFF"))
                        }
                        if ((node[branch] as JsonArray).isEmpty()) Text("空分支", fontSize = 12.sp, color = HarmonyTheme.mutedText, modifier = Modifier.padding(top = 8.dp))
                        WorkflowFlowNodes(graph, "$path/$branch", depth + 1, selected, enabled, valid, onSelect, onStructure, onChange, onError)
                    }
                }
            }
        }
        }
        index += if (pair) 2 else 1
    }
}

private fun nodeLabel(node: JsonObject): String = when (node.string("type")) {
    "gui_task" -> "GUI 任务"; "gui_action" -> "GUI 动作"; "tool" -> "工具调用"; "if" -> "If 条件"; "loop" -> "循环"; else -> node.string("type")
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun WorkflowNodeFields(graph: WorkflowGraphDraft, path: String, enabled: Boolean,
    onChange: (WorkflowGraphDraft) -> Unit, onError: (String, Boolean) -> Unit, pairPath: String? = null,
) {
    val node = graph.at(path) as JsonObject
    EditorLabel("节点 ID")
    Box(Modifier.fillMaxWidth().height(38.dp).background(HarmonyTheme.background("#F6F8FC"), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp), contentAlignment = Alignment.CenterStart) {
        Text(node.string("id"), fontSize = 12.sp, color = HarmonyTheme.text("#1E2633"))
    }
    WorkflowEditorField("显示名称", node.string("name"), { onChange(graph.field(path, "name", JsonPrimitive(it))) }, enabled = enabled)
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("失败策略", fontSize = 11.sp, color = HarmonyTheme.secondaryText, modifier = Modifier.padding(end = 10.dp, bottom = 8.dp))
        listOf(Triple("stop", "停止", "#B42318"), Triple("continue", "继续", "#148F73")).forEach { (value, label, accent) ->
            val active = node.string("on_error").ifBlank { "stop" } == value
            HarmonyButton(label, { onChange(graph.field(path, "on_error", JsonPrimitive(value))) }, Modifier.padding(end = 8.dp, bottom = 8.dp),
                height = 30.dp, radius = 8.dp, fontSize = 11.sp, enabled = enabled, horizontalPadding = 10.dp,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                color = HarmonyTheme.text(if (active) "#FFFFFF" else accent),
                background = HarmonyTheme.background(if (active) accent else "#F2F5FA"))
        }
    }
    EditorLabel("在当前节点后添加")
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        templates.forEach { item -> HarmonyButton(item.label, { onChange(graph.insert(item.key, path)) },
            height = 30.dp, radius = 8.dp, fontSize = 10.sp, enabled = enabled,
            color = HarmonyTheme.text(item.accent), background = HarmonyTheme.background(item.background)) }
    }
    if (pairPath != null) {
        val summary = graph.at(pairPath) as JsonObject
        val inputs = summary["inputs"] as? JsonObject ?: JsonObject(emptyMap())
        WorkflowEditorField("截图文件名", node.string("file_name"), { onChange(graph.field(path, "file_name", JsonPrimitive(it))) }, enabled = enabled)
        WorkflowEditorField("Summary 内容", inputs.string("question"),
            { onChange(graph.field(pairPath, "inputs", JsonObject(inputs + ("question" to JsonPrimitive(it))))) }, 110.dp, enabled = enabled)
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("阻塞执行", fontSize = 12.sp, color = HarmonyTheme.secondaryText, modifier = Modifier.weight(1f))
            HarmonySwitch((inputs["blocking"] as? JsonPrimitive)?.booleanOrNull ?: false,
                { onChange(graph.field(pairPath, "inputs", JsonObject(inputs + ("blocking" to JsonPrimitive(it))))) }, enabled = enabled)
        }
        OptionalSchemaField(inputs["json_schema"], enabled,
            { onChange(graph.field(pairPath, "inputs", JsonObject(if (it == null) inputs - "json_schema" else inputs + ("json_schema" to it)))) }, { onError("$pairPath/schema", it) })
        return
    }
    when (node.string("type")) {
        "gui_task" -> {
            val description = node.string("task_description")
            if (description.startsWith("打开")) WorkflowEditorField("应用名称", description.removePrefix("打开"),
                { onChange(graph.field(path, "task_description", JsonPrimitive("打开$it"))) }, 40.dp, enabled = enabled)
            else WorkflowEditorField("GUI 任务描述", description, { onChange(graph.field(path, "task_description", JsonPrimitive(it))) }, 96.dp, enabled = enabled)
        }
        "gui_action" -> {
            EditorLabel("动作类型")
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("screenshot" to "截图", "click" to "点击", "input" to "输入", "swipe_with_coords" to "坐标滑动", "swipe" to "方向滑动", "keyevent" to "按键", "sleep" to "等待", "app_start" to "打开 App", "app_stop" to "停止 App").forEach { (value, label) ->
                    HarmonyButton(label, { onChange(graph.action(path, value)) }, height = 30.dp, radius = 8.dp, fontSize = 11.sp, enabled = enabled,
                        color = HarmonyTheme.text(if (node.string("action") == value) "#FFFFFF" else "#C76800"),
                        background = HarmonyTheme.background(if (node.string("action") == value) "#C76800" else "#FFF3E2"))
                }
            }
            val fields = when (node.string("action")) {
                "screenshot" -> listOf("file_name" to "文件名")
                "click" -> listOf("x" to "X 坐标", "y" to "Y 坐标")
                "input" -> listOf("text" to "输入文本")
                "swipe_with_coords" -> listOf("start_x" to "起点 X", "start_y" to "起点 Y", "end_x" to "终点 X", "end_y" to "终点 Y")
                "swipe" -> emptyList()
                "keyevent" -> emptyList()
                "sleep" -> listOf("seconds" to "等待秒数")
                "app_start", "app_stop" -> listOf("package_name" to "包名")
                else -> emptyList()
            }
            if (fields.firstOrNull()?.first in listOf("x", "start_x", "seconds")) fields.chunked(2).forEach { group ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(if (node.string("action") == "click") 10.dp else 8.dp)) {
                    group.forEach { (key, label) -> Column(Modifier.weight(1f)) {
                        ValidatedNumberField(label, node[key], enabled, { onChange(graph.field(path, key, it)) }, { onError("$path/$key", it) })
                    } }
                }
            } else fields.forEach { (key, label) ->
                WorkflowEditorField(label, node.string(key), { onChange(graph.field(path, key, JsonPrimitive(it))) }, enabled = enabled)
            }
            if (node.string("action") == "swipe") WorkflowSegments("方向", node.string("direction"),
                listOf("up" to "上", "down" to "下", "left" to "左", "right" to "右"), enabled, "#C76800",
                { onChange(graph.field(path, "direction", JsonPrimitive(it))) })
            if (node.string("action") == "keyevent") WorkflowSegments("按键", node.string("key"),
                listOf("BACK" to "返回", "HOME" to "主页", "ENTER" to "确认"), enabled, "#C76800",
                { onChange(graph.field(path, "key", JsonPrimitive(it))) })
            if (node.string("action") == "keyevent") WorkflowEditorField("自定义 Key", node.string("key"),
                { onChange(graph.field(path, "key", JsonPrimitive(it))) }, enabled = enabled)
        }
        "tool" -> {
            WorkflowEditorField("工具名", node.string("tool_name"), { onChange(graph.field(path, "tool_name", JsonPrimitive(it))) }, enabled = enabled)
            val inputs = node["inputs"] as? JsonObject ?: JsonObject(emptyMap())
            WorkflowSegments("模式", inputs.string("mode").ifBlank { "summary" }, listOf("summary" to "Summary", "answer" to "问答"), enabled, "#148F73",
                { onChange(graph.field(path, "inputs", JsonObject(inputs + ("mode" to JsonPrimitive(it))))) })
            listOf("image" to "图片变量", "question" to "Summary 提示词").forEach { (key, label) ->
                WorkflowEditorField(label, inputs.string(key), { onChange(graph.field(path, "inputs", JsonObject(inputs + (key to JsonPrimitive(it))))) },
                    if (key == "question") 96.dp else 38.dp, enabled = enabled, mono = key == "image", fontSize = 12.sp)
            }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("阻塞执行", fontSize = 12.sp, color = HarmonyTheme.secondaryText, modifier = Modifier.weight(1f))
                HarmonySwitch((inputs["blocking"] as? JsonPrimitive)?.booleanOrNull ?: false, { onChange(graph.field(path, "inputs", JsonObject(inputs + ("blocking" to JsonPrimitive(it))))) }, enabled = enabled)
            }
            OptionalSchemaField(inputs["json_schema"], enabled,
                { onChange(graph.field(path, "inputs", JsonObject(if (it == null) inputs - "json_schema" else inputs + ("json_schema" to it)))) }, { onError("$path/schema", it) })
        }
        "if" -> WorkflowConditionFields(node["condition"] as? JsonObject ?: JsonObject(emptyMap()), "判断条件", enabled,
            { onChange(graph.field(path, "condition", it)) })
        "loop" -> {
            val countKey = if ("max_times" in node) "max_times" else "times"
            ValidatedNumberField(if (countKey == "times") "固定次数" else "最多次数", node[countKey], enabled,
                { onChange(graph.field(path, countKey, it)) }, { onError("$path/$countKey", it) })
            if ("break_if" in node) WorkflowConditionFields(node["break_if"] as? JsonObject ?: JsonObject(emptyMap()), "退出条件", enabled,
                { onChange(graph.field(path, "break_if", it)) })
        }
        else -> Text("此节点保留完整字段，请通过 JSON 编辑其专用参数", fontSize = 12.sp, color = HarmonyTheme.secondaryText)
    }
}

@Composable
private fun WorkflowPairFields(graph: WorkflowGraphDraft, shotPath: String, summaryPath: String, enabled: Boolean,
    onChange: (WorkflowGraphDraft) -> Unit, onError: (String, Boolean) -> Unit,
) { WorkflowNodeFields(graph, shotPath, enabled, onChange, onError, pairPath = summaryPath) }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WorkflowConditionFields(condition: JsonObject, label: String, enabled: Boolean, onChange: (JsonObject) -> Unit) {
    WorkflowEditorField(label, condition.string("left"), { onChange(JsonObject(condition + ("left" to JsonPrimitive(it)))) }, mono = true, enabled = enabled, fontSize = 12.sp)
    EditorLabel("比较方式")
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("==" to "等于", "!=" to "不等于", "contains" to "包含", "not_contains" to "不包含", ">" to "大于", ">=" to "大于等于", "<" to "小于", "<=" to "小于等于").forEach { (value, title) ->
            HarmonyButton(title, { onChange(JsonObject(condition + ("operator" to JsonPrimitive(value)))) }, height = 30.dp, radius = 8.dp,
                fontSize = 11.sp, enabled = enabled, color = HarmonyTheme.text(if (condition.string("operator") == value) "#FFFFFF" else "#7C4DFF"),
                background = HarmonyTheme.background(if (condition.string("operator") == value) "#7C4DFF" else "#F0EAFF"))
        }
    }
    WorkflowEditorField("右侧值", (condition["right"] as? JsonPrimitive)?.content ?: condition["right"]?.toString().orEmpty(), { raw ->
        val value = runCatching { Json.parseToJsonElement(raw) }.getOrNull() ?: JsonPrimitive(raw)
        onChange(JsonObject(condition + ("right" to value)))
    }, mono = true, enabled = enabled, fontSize = 12.sp)
}

@Composable private fun ValidatedNumberField(label: String, value: JsonElement?, enabled: Boolean, onValue: (JsonPrimitive) -> Unit, onError: (Boolean) -> Unit) {
    var draft by remember(label) { mutableStateOf((value as? JsonPrimitive)?.content.orEmpty()) }
    WorkflowEditorField(label, draft, { raw ->
        draft = raw
        val parsed = runCatching { Json.parseToJsonElement(raw) as? JsonPrimitive }.getOrNull()
        val valid = parsed != null && !parsed.isString && parsed.doubleOrNull?.isFinite() == true
        onError(!valid); if (valid) onValue(parsed!!)
    }, enabled = enabled)
}

@Composable private fun OptionalSchemaField(value: JsonElement?, enabled: Boolean, onValue: (JsonElement?) -> Unit, onError: (Boolean) -> Unit) {
    var draft by remember { mutableStateOf(value?.toString().orEmpty()) }
    WorkflowEditorField("结构化输出 Schema", draft, { raw ->
        draft = raw
        if (raw.isBlank()) { onError(false); onValue(null) }
        else {
            val parsed = runCatching { Json.parseToJsonElement(raw) }.getOrNull()
            onError(parsed == null); if (parsed != null) onValue(parsed)
        }
    }, 96.dp, mono = true, enabled = enabled)
}

@Composable private fun ValidatedJsonField(label: String, value: JsonElement, height: Dp, enabled: Boolean, onValue: (JsonObject) -> Unit, onError: (Boolean) -> Unit) {
    var draft by remember(label) { mutableStateOf(value.toString()) }
    WorkflowEditorField(label, draft, { raw ->
        draft = raw
        val parsed = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
        onError(parsed == null); if (parsed != null) onValue(parsed)
    }, height, mono = true, enabled = enabled)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun WorkflowSegments(label: String, selected: String, options: List<Pair<String, String>>, enabled: Boolean, accent: String, onSelect: (String) -> Unit) {
    EditorLabel(label)
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, title) -> HarmonyButton(title, { onSelect(value) }, height = 30.dp, radius = 8.dp, fontSize = 11.sp,
            enabled = enabled, color = HarmonyTheme.text(if (selected == value) "#FFFFFF" else accent),
            background = HarmonyTheme.background(if (selected == value) accent else "#F3F5FA")) }
    }
}

@Composable private fun WorkflowEditorSurface(modifier: Modifier = Modifier, background: androidx.compose.ui.graphics.Color = HarmonyTheme.surface, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().background(background, RoundedCornerShape(14.dp))
        .border(1.dp, HarmonyTheme.border("#E3E8F1"), RoundedCornerShape(14.dp)).padding(14.dp), content = content)
}
@Composable private fun EditorTitle(title: String, modifier: Modifier = Modifier) { Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = HarmonyTheme.primaryText, modifier = modifier) }
@Composable private fun EditorLabel(label: String) { Text(label, fontSize = 11.sp, color = HarmonyTheme.secondaryText, modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp)) }
@Composable internal fun WorkflowEditorField(label: String, value: String, onChange: (String) -> Unit,
    height: Dp = 38.dp, mono: Boolean = false, enabled: Boolean = true,
    fontSize: TextUnit = if (mono) 11.sp else 12.sp,
) {
    EditorLabel(label)
    BasicTextField(value, onChange, Modifier.fillMaxWidth().height(height).semantics { contentDescription = label }
        .clip(RoundedCornerShape(8.dp)).background(HarmonyTheme.background("#EEF2F7")).padding(horizontal = 10.dp, vertical = 7.dp),
        textStyle = TextStyle(fontSize = fontSize, color = HarmonyTheme.primaryText,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default), enabled = enabled,
        singleLine = height <= 40.dp, decorationBox = { field ->
            Box(Modifier.fillMaxSize(), contentAlignment = if (height <= 40.dp) Alignment.CenterStart else Alignment.TopStart) {
                if (value.isEmpty()) Text(when (label) {
                    "任务标题" -> "展示给用户的任务名"
                    "显示名称" -> "可选"
                    "应用名称" -> "例如：支付宝 / 微信 / 哔哩哔哩"
                    "工作流 JSON" -> "请输入 workflow JSON"
                    "结构化输出 Schema" -> "{\"summary\":{\"type\":\"string\",\"description\":\"...\"}}"
                    else -> label
                }, fontSize = fontSize, color = HarmonyTheme.mutedText)
                field()
            }
        })
}
