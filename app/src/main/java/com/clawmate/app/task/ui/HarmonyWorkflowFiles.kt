package com.clawmate.app.task.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clawmate.app.chat.ui.ChatMarkdownText
import com.clawmate.app.task.data.*
import com.clawmate.app.ui.*
import com.clawmate.app.ui.theme.HarmonyTheme

@Composable
internal fun HarmonyWorkflowFiles(store: LocalWorkflowFiles, refreshKey: Any? = null) {
    var revision by remember { mutableIntStateOf(0) }
    var category by remember { mutableStateOf<WorkflowFileCategory?>(null) }
    var selectedPath by remember { mutableStateOf<String?>(null) }
    val snapshot by produceState<WorkflowFilesSnapshot?>(null, store, revision, refreshKey) { value = null; value = store.list() }
    val files = snapshot?.files.orEmpty().filter { it.category == category }
    LaunchedEffect(category, snapshot) {
        if (files.none { it.relativePath == selectedPath }) selectedPath = files.firstOrNull()?.relativePath
    }
    val selected = files.firstOrNull { it.relativePath == selectedPath }
    val preview by produceState<WorkflowFilePreview?>(null, selected, revision, refreshKey) {
        value = null; if (selected != null) value = store.read(selected)
    }
    HarmonyWorkflowFilesContent(snapshot, category, selectedPath, preview, { revision++ },
        { category = it }, { selectedPath = it }, { category = null })
}

/** TaskPage FileManagerPanel + FileViewerPanel; no filesystem mutations in this UI. */
@Composable
internal fun HarmonyWorkflowFilesContent(snapshot: WorkflowFilesSnapshot?, category: WorkflowFileCategory?, selectedPath: String?,
    preview: WorkflowFilePreview?, onRefresh: () -> Unit, onCategory: (WorkflowFileCategory) -> Unit,
    onFile: (String) -> Unit, onClose: () -> Unit,
) {
    var cleanup by remember { mutableStateOf(false) }
    val files = snapshot?.files.orEmpty()
    val selectedName = files.firstOrNull { it.relativePath == selectedPath }?.name.orEmpty().lowercase()
    var showPreview by remember(category, selectedPath) { mutableStateOf(category == WorkflowFileCategory.DAILY || selectedName.endsWith(".md") || selectedName.endsWith(".markdown")) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        WorkflowFilesCard {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Workflow 文件管理", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = HarmonyTheme.primaryText)
                    Text(if (snapshot == null) "正在读取本机文件..." else {
                        val counts = WorkflowFileCategory.values().map { c -> files.count { it.category == c } }
                        "配置 ${counts[0]} 个，daily-log ${counts[1]} 个，运行文件 ${counts[2]} 个，共 ${LocalWorkflowFiles.formatSize(files.sumOf { it.size })}" +
                            if (!snapshot.complete) "（扫描未完成）" else ""
                    }, fontSize = 12.sp, color = HarmonyTheme.secondaryText, maxLines = 2, modifier = Modifier.padding(top = 7.dp))
                }
                HarmonyButton("刷新", onRefresh, Modifier.padding(start = 10.dp).width(64.dp), height = 34.dp, radius = 10.dp,
                    color = HarmonyTheme.text("#FFFFFF"), background = HarmonyTheme.background("#4E5666"), horizontalPadding = 0.dp)
            }
            snapshot?.error?.let { Text(it, fontSize = 12.sp, color = HarmonyTheme.text("#B42318"), modifier = Modifier.padding(top = 8.dp)) }
            WorkflowFileCategory.values().forEach { c ->
                val entries = files.filter { it.category == c }
                Row(Modifier.padding(top = 12.dp).fillMaxWidth().background(HarmonyTheme.background(c.background), RoundedCornerShape(14.dp))
                    .padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(c.title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = HarmonyTheme.primaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (snapshot == null) "读取中..." else "${c.subtitle} · ${entries.size} 个 · ${LocalWorkflowFiles.formatSize(entries.sumOf { it.size })}",
                            fontSize = 12.sp, color = HarmonyTheme.secondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                    }
                    HarmonyButton("查看", { onCategory(c) }, Modifier.padding(start = 10.dp).width(74.dp), height = 34.dp, radius = 10.dp,
                        color = HarmonyTheme.text("#FFFFFF"), background = HarmonyTheme.fill(c.accent), horizontalPadding = 0.dp, enabled = snapshot != null)
                    HarmonyButton("清理", { cleanup = true }, Modifier.padding(start = 8.dp).width(66.dp), height = 34.dp, radius = 10.dp,
                        color = HarmonyTheme.secondaryText, background = HarmonyTheme.background("#F3F5FA"), horizontalPadding = 0.dp)
                }
            }
        }
        if (category != null) WorkflowFilesCard {
            val matching = files.filter { it.category == category }
            val selected = matching.firstOrNull { it.relativePath == selectedPath }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(when (category) { WorkflowFileCategory.CONFIG -> "Workflow 配置文件"; WorkflowFileCategory.DAILY -> "daily-log 文件"; WorkflowFileCategory.RUN -> "运行文件" },
                    fontSize = 16.sp, fontWeight = FontWeight.Bold, color = HarmonyTheme.primaryText, modifier = Modifier.weight(1f))
                HarmonyButton("关闭", onClose, height = 30.dp, radius = 8.dp, fontSize = 11.sp,
                    color = HarmonyTheme.text("#4E5666"), background = HarmonyTheme.background("#F2F5FA"))
            }
            if (matching.isEmpty()) Text(if (snapshot == null) "正在读取文件..." else when (category) {
                WorkflowFileCategory.CONFIG -> "暂无 workflow 配置文件"
                WorkflowFileCategory.DAILY -> "暂无 daily-log 文件"
                WorkflowFileCategory.RUN -> "暂无运行文件"
            }, fontSize = 13.sp,
                color = HarmonyTheme.mutedText, modifier = Modifier.padding(top = 14.dp))
            else {
                Row(Modifier.padding(top = 12.dp).fillMaxWidth().height(40.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    matching.forEach { file -> HarmonyButton("${file.name} (${LocalWorkflowFiles.formatSize(file.size)})", { onFile(file.relativePath) },
                        height = 32.dp, radius = 9.dp, fontSize = 11.sp,
                        color = HarmonyTheme.text(if (file.relativePath == selectedPath) "#FFFFFF" else "#4E5666"),
                        background = HarmonyTheme.background(if (file.relativePath == selectedPath) "#276EF1" else "#F3F5FA")) }
                }
                Text("当前文件：${selected?.name.orEmpty()}", fontSize = 12.sp, color = HarmonyTheme.secondaryText, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(true to "预览", false to "原文").forEach { (value, label) -> HarmonyButton(label, { showPreview = value }, Modifier.width(54.dp),
                        height = 30.dp, radius = 10.dp, fontSize = 12.sp, horizontalPadding = 0.dp,
                        color = HarmonyTheme.text(if (showPreview == value) "#FFFFFF" else "#5968FF"),
                        background = HarmonyTheme.background(if (showPreview == value) "#5968FF" else "#F3F5FA")) }
                }
                if (preview?.truncated == true) Text("仅预览前 ${LocalWorkflowFiles.MAX_PREVIEW_BYTES} 字节", fontSize = 11.sp, color = HarmonyTheme.mutedText, modifier = Modifier.padding(top = 8.dp))
                Column(Modifier.padding(top = 8.dp).fillMaxWidth().height(280.dp)
                    .background(HarmonyTheme.background("#F6F8FC"), RoundedCornerShape(12.dp)).padding(12.dp).verticalScroll(rememberScrollState())) {
                    if (preview == null) CircularProgressIndicator(Modifier.size(18.dp))
                    else if (preview.error != null) Text(preview.error, fontSize = 13.sp, color = HarmonyTheme.secondaryText)
                    else if (showPreview) ChatMarkdownText(preview.text, foreground = HarmonyTheme.text("#2F3645"), textSizeSp = 11f)
                    else SelectionContainer { Text(preview.text, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = HarmonyTheme.text("#2F3645")) }
                }
            }
        }
    }
    if (cleanup) HarmonyModal({ cleanup = false }) {
        Text("文件清理", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = HarmonyTheme.primaryText)
        Text("Android 暂未支持按类别清理，请使用预览查看文件。", fontSize = 14.sp, color = HarmonyTheme.secondaryText, modifier = Modifier.padding(vertical = 16.dp))
        HarmonyButton("知道了", { cleanup = false })
    }
}

@Composable private fun WorkflowFilesCard(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.padding(bottom = 18.dp).fillMaxWidth(.86f).background(HarmonyTheme.surface, RoundedCornerShape(14.dp)).padding(18.dp), content = content)
}
