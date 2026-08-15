package com.clawmate.app.chat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.clawmate.app.chat.model.ChatMessage
import com.clawmate.app.chat.model.ChatMessageRole

/**
 * Compose chat screen for the cloud-model vertical slice.
 *
 * Renders a live chat session with:
 *   - full history (LazyColumn),
 *   - a text input + send action,
 *   - incremental assistant streaming (each delta appends to the open bubble),
 *   - loading (isSending), empty, error, and missing-config controls.
 *
 * No replies are faked: if the cloud model is not configured, the input bar is
 * disabled and an actionable message is shown; if a request fails, the error
 * banner explains what happened.
 */
@Composable
fun ChatScreen(context: android.content.Context) {
    val vm: ChatViewModel = viewModel(factory = ChatViewModelFactory(context))
    val state by vm.state.collectAsState()
    val listState = rememberLazyListState()

    // Auto-scroll to the newest message while streaming.
    LaunchedEffect(state.messages.size, state.isSending) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.lastIndex)
        }
    }

    Column(modifier = Modifier.fillMaxSize().imePadding()) {
        MessageList(
            messages = state.messages,
            state = state,
            listState = listState,
        )

        if (state.error != null) {
            ErrorBanner(
                message = state.error.orEmpty(),
                onDismiss = vm::clearError,
            )
        }

        if (state.isConfigured) {
            InputBar(
                value = state.inputText,
                onChange = vm::onInputChange,
                onSend = vm::send,
                sending = state.isSending,
            )
        } else {
            MissingConfigPane()
        }
    }
}

@Composable
private fun MessageList(
    messages: List<ChatMessage>,
    state: ChatUiState,
    listState: androidx.compose.foundation.lazy.LazyListState,
) {
    if (messages.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (state.isSending) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Text(
                        "模型思考中…",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            } else {
                Text(
                    "开始一段新的对话",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
        return
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(messages, key = { it.id }) { message ->
            MessageBubble(message)
        }
        if (state.isSending) {
            item(key = "__loading__") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.padding(4.dp))
                    Text("模型思考中…", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val isUser = message.role == ChatMessageRole.USER
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = if (isUser) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(0.85f),
        ) {
            Text(
                text = if (isUser) message.content
                else message.content + if (message.isStreaming) " ▌" else "",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(12.dp),
            )
        }
    }
}

@Composable
private fun InputBar(
    value: String,
    onChange: (String) -> Unit,
    onSend: () -> Unit,
    sending: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            enabled = !sending,
            modifier = Modifier.weight(1f),
            placeholder = { Text("输入消息…") },
            maxLines = 4,
        )
        Button(
            onClick = onSend,
            enabled = value.isNotBlank() && !sending,
        ) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送")
        }
    }
}

@Composable
private fun ErrorBanner(message: String, onDismiss: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth().padding(8.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    }
}

@Composable
private fun MissingConfigPane() {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "尚未配置云端模型",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            "请在本地配置文件中填写云端模型端点（baseUrl / API Key），\n填写后重启应用即可开始对话。",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}
