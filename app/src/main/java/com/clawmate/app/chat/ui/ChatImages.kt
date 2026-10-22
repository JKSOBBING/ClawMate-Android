package com.clawmate.app.chat.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.clawmate.app.chat.data.ChatImageFileStore

@Composable
internal fun ChatImageConsentDialog(vm: ChatViewModel) {
    val state by vm.state.collectAsState()
    state.imageConsent?.let { consent ->
        AlertDialog(onDismissRequest = vm::dismissImageConsent,
            title = { Text("发送图片到云端聊天？") },
            text = { Text("服务：${consent.destination.service}\n模型：${consent.destination.model}\n图片：${consent.attachments.size} 张\n\n" +
                "请确认该模型支持图片输入。确认后发送压缩图片及当前文字，不附带 EXIF/GPS 元数据。" +
                "本次使用普通聊天，不执行工具或任务。处理后的图片将保存在本机，删除会话时一并删除；后续追问请重新附图。") },
            confirmButton = { TextButton(onClick = vm::confirmImageSend) { Text("确认发送图片") } },
            dismissButton = { TextButton(onClick = vm::dismissImageConsent) { Text("取消") } })
    }
}

@Composable
internal fun ChatStoredImages(names: List<String>) {
    if (names.isEmpty()) return
    val context = LocalContext.current
    val store = remember(context) { ChatImageFileStore(context.applicationContext.filesDir) }
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        names.take(4).forEachIndexed { index, name ->
            val file = remember(name, store) { runCatching { store.file(name).takeIf { it.isFile } }.getOrNull() }
            var failed by remember(name) { mutableStateOf(false) }
            if (file == null || failed) Text("图片 ${index + 1} 无法读取", style = MaterialTheme.typography.labelSmall)
            else AsyncImage(file, "历史图片 ${index + 1}（本机）", Modifier.size(88.dp), contentScale = ContentScale.Fit,
                onError = { failed = true })
        }
    }
    Text("历史图片保存在本机；追问图片内容请重新附图。", style = MaterialTheme.typography.labelSmall)
}
