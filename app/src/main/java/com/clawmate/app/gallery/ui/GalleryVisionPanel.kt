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
import com.clawmate.app.gallery.data.DefaultGalleryRepository
import com.clawmate.app.gallery.model.GalleryPhotoItem
import com.clawmate.app.gallery.vision.*
import com.clawmate.app.image.AndroidChatImagePreparer
import com.clawmate.app.settings.SettingsComponent
import kotlinx.coroutines.CancellationException

internal fun galleryVisionFactory(context: Context, repository: GalleryRepository): ViewModelProvider.Factory {
    val app = context.applicationContext
    return object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = GalleryVisionViewModel(
            { GalleryVisionConfig.fromSettings(SettingsComponent.repository(app).snapshot()) },
            { config, item -> GalleryVisionClient.fromConfig(config).analyze(AndroidChatImagePreparer(app).prepare(item.uri), item.ocrText) },
            { item, analysis ->
                val saved = repository.saveVisionAnalysis(item.id, item.uri, analysis)
                var grouped = false
                if (saved) {
                    try { repository.refreshGroups(DefaultGalleryRepository.GALLERY_TAB_ID); grouped = true }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { /* The metadata save is still a real success. */ }
                }
                GalleryVisionSaveResult(saved, grouped)
            },
        ) as T
    }
}

@Composable
internal fun GalleryVisionPanel(item: GalleryPhotoItem, vm: GalleryVisionViewModel, onOpenSettings: () -> Unit) {
    val state by vm.state.collectAsState()
    val matches = state.itemId == item.id
    Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("云端视觉分析", style = MaterialTheme.typography.titleMedium)
            Text("识别当前图片的标题、分类、摘要、细节与标签。确认后上传压缩图片和已有 OCR 文字，先预览再保存。",
                style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { vm.request(item) }, enabled = !state.busy) { Text("分析这张图片") }
            if (state.busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(if (matches) "正在处理…" else "正在处理另一张图片…", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = vm::stop) { Text("停止图片处理") }
            }
            if (matches) {
                state.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onOpenSettings) { Text("打开设置") }
                }
                state.preview?.let { analysis ->
                    Text("分析预览 · AI 生成", style = MaterialTheme.typography.titleSmall)
                    SelectionContainer { Text("${analysis.title}\n${analysis.category}\n${analysis.summary}") }
                    analysis.details.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                    if (analysis.tags.isNotEmpty()) Text(analysis.tags.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                    Text("保存会替换这张图片的分析字段，请先核对。", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = vm::savePreview, enabled = !state.busy) { Text("保存分析结果") }
                }
                state.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
    state.consent?.takeIf { it.itemId == item.id }?.let { consent ->
        AlertDialog(onDismissRequest = vm::dismiss, title = { Text("上传这张图片进行分析？") },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("图片：${consent.title.ifBlank { "当前图片" }}\n服务：${consent.service}\n模型：${consent.model}\n\n" +
                    "将发送压缩图片及其已有 OCR 文字，不附带原始 EXIF/GPS 元数据。分析结果会先显示预览，点击保存后才更新本机记录。")
            } },
            confirmButton = { TextButton(onClick = vm::confirm) { Text("确认上传并分析") } },
            dismissButton = { TextButton(onClick = vm::dismiss) { Text("取消") } },
        )
    }
}
