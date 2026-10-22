package com.clawmate.app.settings.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.clawmate.app.chat.data.ChatDatabase
import com.clawmate.app.datamanager.ConsolidationModelConfig
import com.clawmate.app.datamanager.FileDailyLogEventSource
import com.clawmate.app.datamanager.LocalConsolidationIndexRunner
import com.clawmate.app.datamanager.chatmemory.*
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun ChatMemorySettingsCard() {
    val context = LocalContext.current.applicationContext
    val factory = remember(context) { object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ChatMemoryViewModel(
            configProvider = { ConsolidationModelConfig.fromEndpointConfig() },
            synchronize = { config, progress ->
                ChatMemorySync(context.filesDir, RoomChatMemorySessionSource(ChatDatabase.get(context)),
                    ChatMemoryModelClientFactory.client(config)).sync(explicitAction = true, progress = progress)
            },
            refreshLocalIndex = {
                withContext(Dispatchers.IO) {
                    if (FileDailyLogEventSource(context.filesDir).load().errors.isNotEmpty()) {
                        throw IOException("存在无法解析的本地日志")
                    }
                    LocalConsolidationIndexRunner(context.filesDir).run().indexedEvents
                }
            },
        ) as T
    } }
    val vm: ChatMemoryViewModel = viewModel(key = "chat-memory", factory = factory)
    ChatMemoryCard(vm)
}

@Composable
internal fun ChatMemoryCard(vm: ChatMemoryViewModel) {
    val state by vm.state.collectAsState()
    SettingsGroupCard(title = "聊天长期记忆", subtitle = "将聊天中确认的个人信息整理到本机足迹") {
        Text("使用已保存的云端模型设置。每次同步前确认，提取近 7 天尚未处理的消息；不会自动同步。", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = vm::requestSync, enabled = !state.busy) { Text("同步近 7 天聊天") }
        TextButton(onClick = vm::refreshIndex, enabled = !state.busy) { Text("刷新本地记忆索引") }
        if (state.syncing) {
            val progress = state.progress
            Text(if (progress == null) "正在准备聊天同步…" else
                "${if (progress.individualRetry) "正在逐条提取" else "正在同步聊天"} ${progress.processedParts}/${progress.totalParts}",
                style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = vm::cancelSync) { Text("取消同步") }
        }
        if (state.indexing) Text("正在刷新本地索引…", style = MaterialTheme.typography.bodySmall)
        state.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
    state.consent?.let { consent ->
        AlertDialog(
            onDismissRequest = vm::dismissConsent,
            title = { Text("将聊天片段发送给云端模型？") },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("服务：${consent.service}\n模型：${consent.model}\n")
                Text("将近 7 天尚未处理的用户消息和少量上下文发送到此服务，包括本地推理聊天中的片段。工具记录和模型推理过程不发送。\n\n" +
                    "提取结果和处理进度保存到本机。删除聊天不会删除已保存记忆。确认后仅运行本次同步。")
            } },
            confirmButton = { TextButton(onClick = vm::confirmSync) { Text("确认并同步") } },
            dismissButton = { TextButton(onClick = vm::dismissConsent) { Text("取消") } },
        )
    }
}
