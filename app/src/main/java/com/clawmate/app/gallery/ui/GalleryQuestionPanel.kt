package com.clawmate.app.gallery.ui

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.clawmate.app.gallery.data.GalleryRepository
import com.clawmate.app.gallery.question.*
import com.clawmate.app.gallery.vision.GalleryVisionConfig
import com.clawmate.app.settings.SettingsComponent

internal fun galleryQuestionFactory(context: Context, repository: GalleryRepository): ViewModelProvider.Factory {
    val app = context.applicationContext
    return object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = GalleryQuestionViewModel(
            { GalleryVisionConfig.fromSettings(SettingsComponent.repository(app).snapshot()) },
            { query -> repository.search(query) },
            { config, input -> GalleryQuestionClient.fromConfig(config).answer(input) },
        ) as T
    }
}

@Composable
internal fun GalleryQuestionPanel(vm: GalleryQuestionViewModel, onOpenItem: (String) -> Unit, onOpenSettings: () -> Unit) {
    val state by vm.state.collectAsState()
    Card(Modifier.fillMaxWidth(0.90f).padding(vertical = 8.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("图库问答", style = MaterialTheme.typography.titleMedium)
            Text("先检索本机记录，再确认发送候选图片的文字信息。不会上传原图。", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(state.question, vm::updateQuestion, Modifier.fillMaxWidth(), enabled = !state.busy,
                label = { Text("询问图库记录") }, placeholder = { Text("例如：会议安排在什么时间？") }, maxLines = 3)
            Row {
                TextButton(vm::request, enabled = !state.busy && state.question.isNotBlank()) { Text("提问") }
                if (state.busy) TextButton(vm::stop) { Text("停止问答") }
                else if (state.question.isNotEmpty() || state.answer != null) TextButton(vm::clear) { Text("清空问答") }
            }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                TextButton(onOpenSettings) { Text("打开设置") }
            }
            state.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            state.answer?.let { answer ->
                Text("回答 · AI 生成", style = MaterialTheme.typography.titleSmall)
                Text("对应问题：${state.answeredQuestion}", style = MaterialTheme.typography.bodySmall)
                SelectionContainer { Text(answer.answer) }
                Text("依据为已保存的分析/OCR记录，请核对原图。", style = MaterialTheme.typography.bodySmall)
                answer.matches.forEach { evidence ->
                    Text("${evidence.date} · ${evidence.title}\n${evidence.excerpt}", style = MaterialTheme.typography.bodySmall)
                    TextButton({ onOpenItem(evidence.itemId) }) { Text("查看图片：${evidence.title}") }
                }
            }
        }
    }
    state.consent?.let { consent ->
        AlertDialog(onDismissRequest = vm::dismiss, title = { Text("发送候选记录进行问答？") },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("服务：${consent.service}\n模型：${consent.model}\n问题：${consent.input.question}")
                Text("将发送以下 ${consent.input.evidence.size} 张图片记录的日期、标题、分类、摘要、细节、标签和截短 OCR 文字。" +
                    "不发送原图、文件路径或原始 EXIF/GPS 元数据。")
                consent.input.evidence.forEach { Text("${it.date} · ${it.title}\n${it.excerpt}", style = MaterialTheme.typography.bodySmall) }
            } },
            confirmButton = { TextButton(vm::confirm) { Text("确认发送记录") } },
            dismissButton = { TextButton(vm::dismiss) { Text("取消") } })
    }
}
