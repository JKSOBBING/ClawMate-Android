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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.clawmate.app.chat.model.ChatMessage
import com.clawmate.app.chat.model.ChatMessageRole
import com.clawmate.app.chat.model.ChatSession
import com.clawmate.app.chat.model.ChatMode
import com.clawmate.app.chat.model.AttachmentSource
import com.clawmate.app.chat.model.PendingChatAttachment
import com.clawmate.app.chat.model.PendingAttachmentState
import com.clawmate.app.chat.voice.DigitalAvatarSize
import com.clawmate.app.localmodel.LocalModelState
import com.clawmate.app.chat.voice.DigitalAvatarVisualState
import com.clawmate.app.chat.voice.VoiceInteractionStatus
import com.clawmate.app.chat.voice.VoiceSessionSnapshot
import com.clawmate.app.chat.voice.chatAvatarRenderModel
import com.clawmate.app.chat.voice.visualStateForMessage
import com.clawmate.app.gallery.photo.PickResult
import com.clawmate.app.gallery.photo.rememberPhotoPicker
import com.clawmate.app.R
import com.clawmate.app.log.ui.LogScreen

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
fun ChatScreen(
    context: android.content.Context,
    onOpenTasks: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    val vm: ChatViewModel = viewModel(factory = ChatViewModelFactory(context))
    val state by vm.state.collectAsState()
    val voice by vm.voiceState.collectAsState()
    val listState = rememberLazyListState()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    var showLogs by remember { androidx.compose.runtime.mutableStateOf(false) }

    if (showLogs) {
        LogScreen(onBack = { showLogs = false })
        return
    }

    // Re-check the cloud endpoint configuration every time the tab is entered
    // (composition re-entry after a tab switch): an endpoint saved in the
    // Settings center is applied to the runtime config on save, so returning
    // here must surface the configured state without an app restart.
    LaunchedEffect(Unit) {
        vm.refreshConfigState()
    }

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
                    .padding(start = 18.dp, end = 18.dp, top = 22.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = {
                    if (state.drawerOpen) vm.setDrawerOpen(false) else vm.setDrawerOpen(true)
                }) {
                    Icon(Icons.Filled.Menu, contentDescription = "历史会话")
                }
                Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "MobiAgent",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                }
                ChatModeTabs(
                    mode = state.mode,
                    enabled = !state.isSending,
                    onSelect = { mode ->
                        if (mode != state.mode) vm.newSession(mode)
                    },
                )
                TextButton(onClick = { showLogs = true }) {
                    Text("日志")
                }
            }

            MessageList(
                messages = state.messages,
                state = state,
                voice = voice.session,
                vm = vm,
                listState = listState,
                modifier = Modifier.weight(1f),
            )

            if (state.error != null) {
                ErrorBanner(
                    message = state.error.orEmpty(),
                    onDismiss = vm::clearError,
                )
            }

            ChatActionStrip(onOpenTasks = onOpenTasks)
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
                MissingConfigPane(
                    mode = state.mode,
                    localState = state.localModelStatus,
                    onOpenSettings = onOpenSettings,
                )
            }
        }
    }
}

@Composable
private fun ChatActionStrip(onOpenTasks: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Surface(
            color = Color.White,
            shape = RoundedCornerShape(17.dp),
            modifier = Modifier
                .height(34.dp)
                .clickable(onClick = onOpenTasks),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE2E5EA)),
        ) {
            Text(
                "下发任务",
                fontSize = 12.sp,
                color = Color(0xFF222222),
                modifier = Modifier.padding(horizontal = 13.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun ChatModeTabs(
    mode: String,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .background(Color(0xFFF4F6FA), RoundedCornerShape(16.dp))
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        listOf(ChatMode.CLOUD_AGENT, ChatMode.LOCAL_INFERENCE).forEach { option ->
            Box(
                modifier = Modifier
                    .width(46.dp)
                    .height(28.dp)
                    .background(
                        if (mode == option) Color(0xFF111827) else Color.Transparent,
                        RoundedCornerShape(14.dp),
                    )
                    .clickable(enabled = enabled) { onSelect(option) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (option == ChatMode.LOCAL_INFERENCE) "本地" else "云端",
                    fontSize = 12.sp,
                    fontWeight = if (mode == option) FontWeight.Bold else FontWeight.Normal,
                    color = if (mode == option) Color.White else Color(0xFF5D6472),
                )
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
    modifier: Modifier = Modifier,
) {
    if (messages.isEmpty()) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
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
                EmptyChatWelcome(onPrompt = vm::onInputChange, voice = voice)
            }
        }
        return
    }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
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

@Composable
private fun EmptyChatWelcome(onPrompt: (String) -> Unit, voice: VoiceSessionSnapshot) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        // The welcome "hero" digital avatar — the brand illustration as the
        // foreground, animated by the *real* voice session (listens/thinks/speaks
        // pulse the ring + wave bars; idle stays still). No faked motion.
        DigitalAvatar(
            size = DigitalAvatarSize.HERO,
            visualState = DigitalAvatarVisualState.fromVoiceStatus(voice.status),
            useDigitalAvatar = true,
            fallbackText = "助",
            foregroundPainter = painterResource(R.drawable.digital_avatar),
            modifier = Modifier.padding(bottom = 4.dp),
        )
        Text(
            text = "你好，今天想做什么？",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF111827),
            modifier = Modifier.padding(top = 10.dp),
        )
        Text(
            text = "我可以帮你整理信息、执行任务",
            style = MaterialTheme.typography.bodyLarge,
            color = Color(0xFF6F7787),
            modifier = Modifier.padding(top = 6.dp),
        )
        Column(modifier = Modifier.padding(top = 18.dp)) {
            ChatPromptCard(
                title = "整理今天截图里的关键信息",
                description = "从图片和聊天里提取重点",
                prompt = "整理今天截图里的关键信息",
                onPrompt = onPrompt,
            )
            ChatPromptCard(
                title = "规划一个自动化 workflow",
                description = "拆解步骤并生成执行计划",
                prompt = "规划一个收集微信聊天记录的 workflow",
                onPrompt = onPrompt,
            )
            ChatPromptCard(
                title = "淘宝搜索耳机比价",
                description = "帮你比较价格和参数",
                prompt = "淘宝搜索耳机比价",
                onPrompt = onPrompt,
            )
        }
    }
}

@Composable
private fun ChatPromptCard(
    title: String,
    description: String,
    prompt: String,
    onPrompt: (String) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clickable { onPrompt(prompt) },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .background(Color(0xFFF2F5FF), RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text("·", color = Color(0xFF8066FF), fontWeight = FontWeight.Bold)
            }
            Column(modifier = Modifier.padding(start = 12.dp)) {
                Text(title, fontWeight = FontWeight.Bold, color = Color(0xFF111827))
                Text(description, color = Color(0xFF7B8392), style = MaterialTheme.typography.bodySmall)
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

    if (isUser) {
        // User messages keep their current right-aligned, avatar-less layout.
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.End,
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(0.85f),
            ) {
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
        return
    }

    // Assistant / task / notice messages: in-line digital avatar (port of HarmonyOS
    // chatAvatarRenderModel). Only the message the voice session is active on animates.
    val visualState = visualStateForMessage(voice.status, voice.activeMessageId, message.id)
    val render = chatAvatarRenderModel(message, modeMark = "", visualState = visualState)

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        DigitalAvatar(
            size = DigitalAvatarSize.MESSAGE,
            visualState = render.visualState,
            useDigitalAvatar = render.useDigitalAvatar,
            fallbackText = render.fallbackText,
            fallbackColorHex = render.fallbackColorHex,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 6.dp),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = message.content + if (message.isStreaming) " ▌" else "",
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
            if (!message.isStreaming && message.content.isNotBlank()) {
                VoiceBubbleControls(
                    messageId = message.id,
                    text = message.content,
                    voice = voice,
                    vm = vm,
                )
            }
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
                .padding(horizontal = 20.dp)
                .padding(bottom = 22.dp)
                .shadow(14.dp, RoundedCornerShape(26.dp))
                .background(Color.White, RoundedCornerShape(26.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onAttachClicked,
                enabled = !sending,
                modifier = Modifier
                    .size(40.dp)
                    .background(Color.White, RoundedCornerShape(20.dp)),
            ) {
                Icon(Icons.Filled.Add, contentDescription = "添加图片", modifier = Modifier.size(24.dp))
            }
            BasicTextField(
                value = value,
                onValueChange = onChange,
                enabled = !sending,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 40.dp, max = 120.dp)
                    .padding(start = 8.dp),
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color(0xFF4B5563)),
                maxLines = 4,
                decorationBox = { innerTextField ->
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                    ) {
                        if (value.isBlank()) {
                            Text(
                                "输入消息…",
                                color = Color(0xFF7B8392),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                        innerTextField()
                    }
                },
            )
            Button(
                onClick = {
                    if (value.isNotBlank() || pendingAttachments.isNotEmpty()) onSend()
                    else onMicClicked()
                },
                enabled = !sending,
                modifier = Modifier.size(width = 82.dp, height = 40.dp),
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (value.isBlank() && pendingAttachments.isEmpty()) Color(0xFF5968FF) else Color(0xFF111827),
                    disabledContainerColor = Color(0xFFAEB7C8),
                ),
            ) {
                if (value.isBlank() && pendingAttachments.isEmpty()) {
                    Icon(Icons.Filled.Mic, contentDescription = "语音输入", modifier = Modifier.size(20.dp))
                } else {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送", modifier = Modifier.size(18.dp))
                    Text("发送", modifier = Modifier.padding(start = 4.dp))
                }
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

/**
 * Honest, actionable not-ready state shown instead of the input bar when the
 * **current mode's** backend is not usable. It never pretends the model is
 * available and points the user to the real in-app configuration path (Settings
 * → 模型与智能体, applied to the runtime on save).
 *
 * Mode-aware: the cloud mode explains the missing cloud endpoint, while the
 * local mode surfaces the **real** on-device [LocalModelState] (status +
 * message from [LocalModelCapability]) — it does not recycle the cloud copy
 * (fixes the "本地" tab wrongly showing "云端模型尚未配置").
 */
@Composable
private fun MissingConfigPane(
    mode: String,
    localState: LocalModelState?,
    onOpenSettings: () -> Unit,
) {
    val isLocal = mode == ChatMode.LOCAL_INFERENCE
    val icon = if (isLocal) Icons.Filled.Memory else Icons.Filled.CloudOff
    val title = if (isLocal) "本地推理不可用" else "云端模型尚未配置"
    val detail = if (isLocal) {
        localState?.message?.takeIf { it.isNotBlank() } ?: "当前没有可用的本地推理模型。"
    } else {
        "配置完成前不会发送消息。"
    }
    val steps: List<String> = if (isLocal) {
        listOf(
            "1. 本地推理需要设备端 MNN/LLM 推理库与已下载的模型",
            "2. 当前构建为 JNI 桩，未内置 MNN 推理运行时",
            "3. 缺少推理库时会显示“不支持（缺少原生推理库）”",
        )
    } else {
        listOf(
            "1. 打开设置中心的“模型与智能体”",
            "2. 填写地址、密钥和模型名",
            "3. 返回聊天页即可生效，无需重启",
        )
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 24.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Surface(
            color = Color.White,
            shape = RoundedCornerShape(18.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE2E6EF)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Color(0xFF8066FF),
                    modifier = Modifier.size(36.dp),
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 10.dp),
                )
                Text(
                    text = detail,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    color = Color(0xFF687386),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    steps.forEach { step ->
                        Text(step, fontSize = 14.sp, lineHeight = 20.sp)
                    }
                }
                Button(
                    onClick = onOpenSettings,
                    modifier = Modifier.padding(top = 16.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8066FF)),
                ) {
                    Icon(Icons.Filled.Settings, contentDescription = null)
                    Text("  打开设置中心")
                }
            }
        }
    }
}
