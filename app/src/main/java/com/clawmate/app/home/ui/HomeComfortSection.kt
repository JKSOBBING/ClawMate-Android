package com.clawmate.app.home.ui

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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.clawmate.app.datamanager.ConsolidationModelConfig
import com.clawmate.app.home.comfort.*
import java.time.LocalDate
import androidx.compose.ui.platform.LocalContext
import com.clawmate.app.chat.data.ChatDatabase
import com.clawmate.app.datamanager.chatmemory.RoomChatMemorySessionSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun HomeComfortSection(onOpenSettings: () -> Unit) {
    val factory = remember { object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = HomeComfortViewModel(
            configProvider = { ConsolidationModelConfig.fromEndpointConfig() },
            request = { config, history, topic -> HomeComfortClient.fromConfig(config).reply(history, LocalDate.now().toString(), topic) },
        ) as T
    } }
    val vm: HomeComfortViewModel = viewModel(key = "home-comfort", factory = factory)
    HomeComfortCard(vm, onOpenSettings)
    val context = LocalContext.current.applicationContext
    val topicFactory = remember(context) { object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = HomeComfortTopicsViewModel(
            { ConsolidationModelConfig.fromEndpointConfig() },
            { withContext(Dispatchers.IO) {
                val now = System.currentTimeMillis()
                HomeComfortTopics.context(RoomChatMemorySessionSource(ChatDatabase.get(context)).loadRecent(now), now)
            } },
            { config, input -> HomeComfortClient.fromConfig(config).topics(input) },
        ) as T
    } }
    val topics: HomeComfortTopicsViewModel = viewModel(key = "home-comfort-topics", factory = topicFactory)
    HomeComfortTopicsCard(topics, { vm.open(it) }, onOpenSettings)
}

@Composable
internal fun HomeComfortCard(vm: HomeComfortViewModel, onOpenSettings: () -> Unit) {
    val state by vm.state.collectAsState()
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("陪你聊聊", style = MaterialTheme.typography.titleMedium)
            Text("聊聊压力、心情或生活小事，一起理清思路。", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { vm.open() }) { Text("打开陪伴聊天") }
        }
    }
    if (state.open) {
        val scroll = rememberScrollState()
        LaunchedEffect(state.messages.size, state.sending, state.error) {
            withFrameNanos { }
            scroll.animateScrollTo(scroll.maxValue)
        }
        AlertDialog(onDismissRequest = vm::close, title = { Text(state.topic?.title ?: "陪你聊聊") },
            text = { Column(Modifier.verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("发送时会使用已保存的云端模型。本次聊天仅在当前应用会话中保留，重新开始会清空这里的内容。AI 陪伴不提供诊断。",
                    style = MaterialTheme.typography.bodySmall)
                state.topic?.let { topic ->
                    Text("话题依据 · AI 建议，以你现在的感受为准", style = MaterialTheme.typography.labelSmall)
                    topic.evidence.forEach { Text("${it.date} · ${it.content}", style = MaterialTheme.typography.bodySmall) }
                }
                if (state.messages.isEmpty()) Text("今天想聊点什么？", style = MaterialTheme.typography.bodyMedium)
                state.messages.forEach { message ->
                    Column {
                        Text(if (message.role == HomeComfortRole.USER) "你" else "AI 陪伴", style = MaterialTheme.typography.labelSmall)
                        SelectionContainer { Text(message.content) }
                        if (message.truncated) Text("回复较长，已显示前 ${HomeComfortPolicy.REPLY_LIMIT} 字。", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (state.sending) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("正在回复…")
                    TextButton(onClick = vm::stop) { Text("停止回复") }
                }
                state.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    if (!state.sending && state.messages.lastOrNull()?.role == HomeComfortRole.USER) {
                        TextButton(onClick = vm::retry) { Text("重试回复") }
                    }
                    TextButton(onClick = { vm.close(); onOpenSettings() }) { Text("打开设置") }
                }
                OutlinedTextField(state.input, vm::input, label = { Text("想说的话（最多700字）") },
                    modifier = Modifier.fillMaxWidth(), enabled = !state.sending)
                TextButton(onClick = vm::send, enabled = !state.sending && state.input.isNotBlank()) { Text("发送给 AI 陪伴") }
                TextButton(onClick = vm::restart) { Text("重新开始陪伴聊天") }
            } },
            confirmButton = { TextButton(onClick = vm::close) { Text(if (state.sending) "关闭并停止" else "返回首页") } },
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun HomeComfortTopicsCard(vm: HomeComfortTopicsViewModel, onOpenTopic: (HomeComfortTopic) -> Unit, onOpenSettings: () -> Unit) {
    val state by vm.state.collectAsState()
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("从近期聊天找话题 · AI 建议", style = MaterialTheme.typography.titleMedium)
            Text("点击后会把近7天最多48条用户消息片段（含本地推理聊天）发至已保存的云端模型，不读取画像。话题仅供参考。",
                style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = vm::refresh, enabled = !state.loading) { Text(if (state.generated) "重新生成陪伴话题" else "生成陪伴话题") }
            if (state.loading) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                TextButton(onClick = vm::cancel) { Text("取消生成话题") }
            }
            if (state.generated && state.topics.isEmpty()) Text("没有找到可直接支持的话题，随便聊聊也可以。", style = MaterialTheme.typography.bodySmall)
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onOpenSettings) { Text("打开设置") }
            }
            state.topics.forEach { topic ->
                OutlinedCard(onClick = { onOpenTopic(topic) }, enabled = !state.loading, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(topic.title, style = MaterialTheme.typography.titleSmall)
                        Text(topic.summary, style = MaterialTheme.typography.bodySmall)
                        Text("${topic.evidence.size} 项依据 · 聊这个话题", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}
