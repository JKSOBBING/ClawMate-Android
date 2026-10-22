package com.clawmate.app.task.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import com.clawmate.app.R
import com.clawmate.app.ui.ClawMateHeader
import com.clawmate.app.ui.theme.ClawMateDesign

/** Original configuration text and a validated editor; saving never executes it. */
@Composable
fun WorkflowConfigScreen(
    state: WorkflowConfigUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    canEdit: Boolean,
    onEdit: () -> Unit,
    onDraftChange: (String) -> Unit,
    onSave: () -> Unit,
    onCancelEdit: () -> Unit,
    onDiscard: () -> Unit,
    onKeepEditing: () -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    if (state.showDiscardDialog) {
        AlertDialog(
            onDismissRequest = onKeepEditing,
            title = { Text("放弃未保存的修改？") },
            text = { Text("原配置仍保留，当前草稿将被放弃。") },
            confirmButton = { TextButton(onClick = { keyboard?.hide(); onDiscard() }) { Text("放弃修改") } },
            dismissButton = { TextButton(onClick = onKeepEditing) { Text("继续编辑") } },
        )
    }
    Column(Modifier.fillMaxSize().background(ClawMateDesign.Colors.PageBackground).imePadding()) {
        ClawMateHeader("任务配置", R.drawable.header_task, subtitle = state.task.title)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(onClick = onBack, enabled = !state.isSaving) { Text("返回任务") }
            if (state.draft != null) {
                TextButton(onClick = onCancelEdit, enabled = !state.isSaving) { Text("取消编辑") }
                Button(onClick = { keyboard?.hide(); onSave() }, enabled = state.hasChanges && !state.isSaving) {
                    Text(if (state.isSaving) "保存中…" else "保存")
                }
            } else if (!state.isLoading && state.error == null && !state.truncated) {
                TextButton(onClick = onEdit, enabled = canEdit) { Text("编辑 JSON") }
            }
        }
        Text(
            state.task.configFileName,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall,
        )
        when {
            state.isLoading -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            state.error != null -> Column(
                Modifier.weight(1f).fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(state.error, color = MaterialTheme.colorScheme.error)
                Button(onClick = onRetry, modifier = Modifier.padding(top = 12.dp)) { Text("重新读取") }
            }
            else -> {
                state.notice?.let {
                    Text(it, modifier = Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.primary)
                }
                if (!canEdit && state.draft == null) {
                    Text("任务执行结束后可编辑配置", modifier = Modifier.padding(horizontal = 20.dp))
                }
                state.editError?.let {
                    Text(it, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.error)
                }
                if (state.draft != null) {
                    OutlinedTextField(
                        value = state.draft,
                        onValueChange = onDraftChange,
                        enabled = !state.isSaving,
                        label = { Text("工作流 JSON") },
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        isError = state.editError != null,
                        modifier = Modifier.weight(1f).fillMaxWidth().padding(16.dp),
                    )
                    return@Column
                }
                if (state.truncated) {
                    Text(
                        "配置较长，仅预览前 ${TaskViewModel.MAX_CONFIG_PREVIEW_CHARS} 个字符，原文件未修改。",
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                SelectionContainer(
                    Modifier.weight(1f).fillMaxWidth().padding(16.dp)
                        .background(ClawMateDesign.Colors.Surface)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = state.text,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        softWrap = false,
                        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(12.dp),
                    )
                }
            }
        }
    }
}
