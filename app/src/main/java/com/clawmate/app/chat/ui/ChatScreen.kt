package com.clawmate.app.chat.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
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
import com.clawmate.app.chat.model.ChatSession
import com.clawmate.app.chat.model.AttachmentSource
import com.clawmate.app.chat.model.PendingChatAttachment
import com.clawmate.app.chat.model.PendingAttachmentState
import com.clawmate.app.chat.voice.VoiceInteractionStatus
import com.clawmate.app.chat.voice.VoiceSessionSnapshot
import com.clawmate.app.gallery.photo.PickResult
import com.clawmate.app.gallery.photo.rememberPhotoPicker

/**
 * Compose chat screen for the cloud-model vertical slice (Phase 2) extended
 * with the Phase-7 on-device voice interaction controls.
 *
 * Renders a live chat session with:
 *   - full history (LazyColumn),
 *   - a text input + send action,
 *   - incremental assistant streaming (each delta appends to the open bubble),
 *   - loading (isSending), empty, error, and missing-config controls.
 *   - voice controls: mic / listening / transcript on the input bar, and
 *     speak / stop / replay on assistant bubbles (Android TextToSpeech).
 *
 * No replies are faked: if the cloud model is not configured the input bar is
 * disabled and an actionable message is shown; if a request fails, the error
 * banner explains what happened. Voice input never fabricates a transcript —
 * the editable field is only appended to after a real final ASR result.
 * Recording requires RECORD_AUDIO: the mic button requests that permission and
 * surfaces the requirement in the UI; the ASR adapter never reads the mic
 * without it.
 */
@Composable
fun ChatScreen(context: android.content.Context) {
    val vm: ChatViewModel = viewModel(factory = ChatViewModelFactory(context))
    val state by vm.state.collectAsState()
    val voice by vm.voiceState.collectAsState()
    val listState = rememberLazyListState()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    // Keep the ViewModel's drawer flag in sync with the actual drawer state
    // (including swipe gestures), and open/close the drawer when the flag is
    // changed programmatically (e.g. after selecting a session or toggling).
    LaunchedEffect(state.drawerOpen) {
        if (state.drawerOpen) drawerState.open() else drawerState.close()
    }
    LaunchedEffect(drawerState.currentValue) {
        vm.setDrawerOpen(drawerState.currentValue == DrawerValue.Open)
    }

    // Runtime RECORD_AUDIO permission; requested only when the user taps the mic.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            vm.beginVoiceInput()
        }
    }

    // Phase 45 — image attachment selection. Reuses the existing least-privilege
    // Android Photo Picker (each picked URI is read with an explicit grant; no
    // broad storage permission is requested). Picking is strictly user-triggered;
    // nothing is uploaded here. The picked content URIs are held in memory only
    // as opaque references (see ChatAttachmentPolicy) and are never written into
    // logs or the model context.
    val remainingSlots = (com.clawmate.app.chat.model.CHAT_IMAGE_ATTACHMENT_MAX_COUNT -
        state.pendingAttachments.size).coerceAtLeast(0)
    val pickImages = rememberPhotoPicker(
        maxSelectNumber = remainingSlots.coerceAtLeast(1),
        onResult = { result ->
            when (result) {
                is PickResult.Picked -> vm.addPendingAttachments(
                    uris = result.uris.map { it.toString() },
                    source = AttachmentSource.ALBUM,
                )
                is PickResult.Cancelled -> Unit // no-op; composer unchanged
                is PickResult.Unavailable -> vm.showAttachmentNote(result.reason.ifEmpty { "系统相册选择器不可用。" })
            }
        },
    )

    // Auto-scroll to the newest message while streaming.
    LaunchedEffect(state.messages.size, state.isSending) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.lastIndex)
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            SessionDrawer(
                sessions = state.sessions,
                activeSessionId = state.sessionId,
                onClose = { vm.setDrawerOpen(false) },
                onNewSession = { vm.newSession() },
                onSelect = { id ->
                    vm.selectSession(id)
                    vm.setDrawerOpen(false)
                },
                onDelete = { id -> vm.deleteSession(id) },
            )
        },
    ) {
        Column(modifier = Modifier.fillMaxSize().imePadding()) {
            // Header bar: open the session drawer + current session title.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = {
                    if (state.drawerOpen) vm.setDrawerOpen(false) else vm.setDrawerOpen(true)
                }) {
                    Icon(Icons.Filled.Menu, contentDescription = "历史会话")
                }
                Text(
                    text = state.title.ifEmpty { "新会话" },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
            }

            MessageList(
                messages = state.messages,
                state = state,
                voice = voice.session,
                vm = vm,
                listState = listState,
            )

            if (state.error != null) {
                ErrorBanner(
                    message = state.error.orEmpty(),
                    onDismiss = vm::clearError,
                )
            }

            VoiceStatusBanner(voice.session)

            if (state.isConfigured) {
                AttachmentNoteBanner(state.attachmentNote)
                InputBar(
                    value = state.inputText,
                    onChange = vm::onInputChange,
                    onSend = vm::send,
                    sending = state.isSending,
                    onMicClicked = {
                        if (voice.asrRecordPermissionGranted) vm.beginVoiceInput()
                        else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    pendingAttachments = state.pendingAttachments,
                    onAttachClicked = {
                        if (!state.isSending && remainingSlots > 0) pickImages()
                    },
                    onRemoveAttachment = vm::removePendingAttachment,
                )
            } else {
                MissingConfigPane()
            }
        }
    }
}

/**
 * The session list shown in the navigation drawer: a "new session" action plus
 * every persisted session with a delete action. Selecting a session (or the new
 * one) is delegated up to [ChatViewModel].
 */
@Composable
private fun SessionDrawer(
    sessions: List<ChatSession>,
    activeSessionId: String,
    onClose: () -> Unit,
    onNewSession: () -> Unit,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    ModalDrawerSheet(modifier = Modifier.fillMaxSize(0.85f)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "历史会话",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "收起历史会话")
            }
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = onNewSession) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text(" 新会话", style = MaterialTheme.typography.labelLarge)
            }
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
        androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.weight(1f)) {
            items(sessions, key = { it.id }) { session ->
                SessionDrawerItem(
                    session = session,
                    active = session.id == activeSessionId,
                    onClick = { onSelect(session.id) },
                    onDelete = { onDelete(session.id) },
                )
            }
            if (sessions.isEmpty()) {
                item(key = "__empty__") {
                    Text(
                        "暂无会话，点上方新建",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionDrawerItem(
    session: ChatSession,
    active: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    NavigationDrawerItem(
        icon = {
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "删除会话")
            }
        },
        label = {
            Column(modifier = Modifier.padding(end = 4.dp)) {
                Text(
                    text = session.displayTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                )
                Text(
                    text = "${session.messageCount} 条消息 · ${session.preview}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        },
        selected = active,
        onClick = onClick,
    )
}

@Composable
private fun MessageList(
    messages: List<ChatMessage>,
    state: ChatUiState,
    voice: VoiceSessionSnapshot,
    vm: ChatViewModel,
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
            MessageBubble(
                message = message,
                voice = voice,
                vm = vm,
            )
        }
        if (state.activeToolTrace != null) {
            item(key = "__tool__") {
                ToolStatusPill(state.activeToolTrace.orEmpty())
            }
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

/** A small inline status pill shown while the agent executes a tool. */
@Composable
private fun ToolStatusPill(traceLine: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(0.9f),
    ) {
        Text(
            text = traceLine.replace("\n", " · ").take(120) + (if (traceLine.length > 120) "…" else ""),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun MessageBubble(
    message: ChatMessage,
    voice: VoiceSessionSnapshot,
    vm: ChatViewModel,
) {
    val isUser = message.role == ChatMessageRole.USER
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
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
        // Tool execution traces (agent loop) shown under assistant messages.
        if (message.trace.isNotEmpty()) {
            Text(
                text = message.trace.joinToString("\n"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp, top = 4.dp),
            )
        }
        // Phase-7 voice output controls for assistant messages (Android TTS).
        if (!isUser && !message.isStreaming && message.content.isNotBlank()) {
            VoiceBubbleControls(
                messageId = message.id,
                text = message.content,
                voice = voice,
                vm = vm,
            )
        }
    }
}

/** Speak / pause / replay controls on an assistant bubble. */
@Composable
private fun VoiceBubbleControls(
    messageId: String,
    text: String,
    voice: VoiceSessionSnapshot,
    vm: ChatViewModel,
) {
    val isActiveMessage = voice.activeMessageId == messageId
    val speaking = voice.status == VoiceInteractionStatus.SPEAKING && isActiveMessage
    val paused = voice.status == VoiceInteractionStatus.PAUSED && isActiveMessage
    val synthesizing = voice.status == VoiceInteractionStatus.SYNTHESIZING && isActiveMessage

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(start = 8.dp, top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        when {
            synthesizing -> Text("正在合成语音…", style = MaterialTheme.typography.labelSmall)
            speaking -> {
                // Tap while speaking stops (equivalent to a pause; no fake audio).
                IconButton(onClick = { vm.speakMessage(messageId, text) }) {
                    Icon(Icons.Filled.Stop, contentDescription = "停止朗读")
                }
                IconButton(onClick = { vm.replayMessage(messageId, text) }) {
                    Icon(Icons.Filled.Replay, contentDescription = "重播")
                }
            }
            paused -> {
                IconButton(onClick = { vm.speakMessage(messageId, text) }) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "继续朗读")
                }
            }
            else -> {
                IconButton(onClick = { vm.speakMessage(messageId, text) }) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "朗读")
                }
                IconButton(onClick = { vm.replayMessage(messageId, text) }) {
                    Icon(Icons.Filled.Replay, contentDescription = "重播")
                }
            }
        }
    }
}

/** Live listening/transcribing status + the accumulated partial transcript. */
@Composable
private fun VoiceStatusBanner(voice: VoiceSessionSnapshot) {
    val listening = voice.status == VoiceInteractionStatus.LISTENING
    val transcribing = voice.status == VoiceInteractionStatus.TRANSCRIBING
    if (!listening && !transcribing) return
    val showing = if (voice.partialTranscript.isNotBlank()) voice.partialTranscript else voice.finalTranscript
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.small,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Filled.GraphicEq, contentDescription = null, modifier = Modifier.padding(2.dp))
            Text(
                text = if (listening) "正在聆听…" else "正在转写…",
                style = MaterialTheme.typography.labelMedium,
            )
            if (showing.isNotBlank()) {
                Text(
                    text = showing,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                )
            }
        }
    }
}

@Composable
private fun InputBar(
    value: String,
    onChange: (String) -> Unit,
    onSend: () -> Unit,
    sending: Boolean,
    onMicClicked: () -> Unit,
    pendingAttachments: List<PendingChatAttachment>,
    onAttachClicked: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // Selected image attachments (in-memory projection). Each shows only its
        // source label — never the underlying content URI or any path.
        if (pendingAttachments.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                pendingAttachments.forEach { attachment ->
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.padding(top = 4.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = attachment.displayName,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                            if (attachment.state == PendingAttachmentState.UPLOAD_UNSUPPORTED) {
                                Text(
                                    text = "未上传",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(start = 6.dp),
                                )
                            }
                            IconButton(
                                onClick = { onRemoveAttachment(attachment.id) },
                                enabled = !sending,
                                modifier = Modifier.size(24.dp),
                            ) {
                                Icon(Icons.Filled.Close, contentDescription = "移除图片", modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
        }
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
            // Attach image(s): opens the system photo picker (user-triggered,
            // least-privilege; no automatic upload). Kept selectable even for a
            // pure-image message so `发送` can surface the honest unsupported state.
            IconButton(
                onClick = onAttachClicked,
                enabled = !sending,
            ) {
                Icon(Icons.Filled.Add, contentDescription = "添加图片")
            }
            // Voice input (on-device ASR). First tap requests RECORD_AUDIO; once
            // granted it starts the recognizer. No mic is opened until granted.
            IconButton(
                onClick = onMicClicked,
                enabled = !sending,
            ) {
                Icon(Icons.Filled.Mic, contentDescription = "语音输入")
            }
            Button(
                onClick = onSend,
                enabled = (value.isNotBlank() || pendingAttachments.isNotEmpty()) && !sending,
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送")
            }
        }
    }
}

/** Honest, dismissible note about attachment status (e.g. unsupported upload / picker unavailable). */
@Composable
private fun AttachmentNoteBanner(message: String?) {
    if (message.isNullOrBlank()) return
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
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
