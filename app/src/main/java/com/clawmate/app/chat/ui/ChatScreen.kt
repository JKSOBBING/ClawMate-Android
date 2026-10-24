package com.clawmate.app.chat.ui

import androidx.compose.foundation.layout.*
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import com.clawmate.app.ui.*
import com.clawmate.app.ui.theme.HarmonyTheme
import android.Manifest
import android.widget.Toast
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
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.ContentCopy
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
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
import com.clawmate.app.chat.model.ChatMessageKind
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
import com.clawmate.app.ui.theme.ClawMateDesign
import com.clawmate.app.log.ui.LogScreen
import com.clawmate.app.R

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
 * The composer remains visible without configuration; an attempted send opens
 * the configuration explanation without issuing a request; if a request fails, the error
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
    ChatImageConsentDialog(vm)

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
    var holdingVoice by remember { androidx.compose.runtime.mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted && holdingVoice) {
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

    val debugEnabled = rememberUiDebugEnabled()
    var showConfiguration by remember { androidx.compose.runtime.mutableStateOf(false) }
    var showThinkingBoundary by remember { androidx.compose.runtime.mutableStateOf(false) }
    var showAttachmentSources by remember { androidx.compose.runtime.mutableStateOf(false) }
    var cameraUri by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf<String?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { accepted ->
        val uri = cameraUri
        if (accepted && uri != null) vm.addPendingAttachments(listOf(uri), AttachmentSource.CAMERA)
        else uri?.let { runCatching { context.contentResolver.delete(android.net.Uri.parse(it), null, null) } }
        cameraUri = null
    }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current
    val keyboardVisible = WindowInsets.ime.getBottom(density) > 0
    var footerHeight by remember { androidx.compose.runtime.mutableIntStateOf(220) }
    val send = {
        if (state.isSending) vm.cancelSend()
        else if (state.inputText.isNotBlank() || state.pendingAttachments.isNotEmpty()) {
            if (!state.isConfigured) showConfiguration = true
            else { focus.clearFocus(); keyboard?.hide(); vm.send() }
        }
    }
    ModalNavigationDrawer(drawerState = drawerState, drawerContent = {
        HarmonySessionDrawer(state.sessions, state.sessionId,
            onClose = { vm.setDrawerOpen(false) }, onNewSession = { mode -> vm.newSession(mode) },
            onSelect = { vm.selectSession(it); vm.setDrawerOpen(false) }, onDelete = vm::deleteSession)
    }, scrimColor = HarmonyTheme.background("#66000000")) {
        Box(Modifier.fillMaxSize()) {
            HarmonyPage(background = HarmonyTheme.background(if (state.messages.isEmpty()) "#F4F8FF" else "#FFFFFF"),
                header = {
                    Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 18.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(44.dp, 40.dp).clickable { vm.setDrawerOpen(true) }
                            , contentAlignment = Alignment.Center) {
                            Text("☰", fontSize = 28.sp, color = HarmonyTheme.text("#111827"))
                        }
                        Text("MobiAgent", fontSize = 18.sp, fontWeight = FontWeight.Bold,
                            color = HarmonyTheme.text("#111827"), modifier = Modifier.weight(1f), maxLines = 1)
                        ChatModeTabs(state.mode, !state.isSending) { if (it != state.mode) vm.newSession(it) }
                        if (debugEnabled) HarmonyButton("日志", { showLogs = true }, Modifier.padding(start = 8.dp).width(52.dp),
                            height = 36.dp, fontSize = 14.sp, color = HarmonyTheme.text("#5968FF"),
                            background = Color.Transparent, horizontalPadding = 0.dp)
                    }
                }) { insets ->
                val bottom = with(density) { footerHeight.toDp() }
                if (state.messages.isEmpty()) HarmonyChatWelcome(vm::onInputChange, voice.session, insets, bottom)
                else MessageList(state.messages, state, voice.session, vm, listState,
                    Modifier.fillMaxSize(), PaddingValues(start = 28.dp, end = 28.dp,
                        top = insets.calculateTopPadding(), bottom = bottom))
            }
            Column(Modifier.align(Alignment.BottomCenter).onSizeChanged { footerHeight = it.height }
                .imePadding().navigationBarsPadding().padding(bottom = if (keyboardVisible) 0.dp else 88.dp)) {
                state.error?.let { ErrorBanner(it, vm::clearError) }
                AttachmentNoteBanner(state.attachmentNote)
                HarmonyPendingAttachments(state.pendingAttachments, !state.isSending) {
                    state.pendingAttachments.forEach { vm.removePendingAttachment(it.id) }
                }
                Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (state.mode != ChatMode.LOCAL_INFERENCE) HarmonyChatAction("深度思考 关") { showThinkingBoundary = true }
                    HarmonyChatAction("下发任务", onOpenTasks)
                }
                VoiceStatusBanner(voice.session)
                HarmonyChatComposer(state.inputText, vm::onInputChange, state.mode, state.isSending,
                    voice.session.status == VoiceInteractionStatus.LISTENING, send,
                    onVoiceStart = {
                        holdingVoice = true
                        if (voice.asrRecordPermissionGranted) vm.beginVoiceInput()
                        else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }, onVoiceRelease = { holdingVoice = false; vm.stopVoiceInput() },
                    onAttach = { if (!state.isSending && remainingSlots > 0) showAttachmentSources = true })
            }
        }
    }
    if (showConfiguration) HarmonyModal({ showConfiguration = false }) {
        Text(if (state.mode == ChatMode.LOCAL_INFERENCE) "本地推理不可用" else "云端模型尚未配置",
            fontSize = 18.sp, fontWeight = FontWeight.Bold, color = HarmonyTheme.primaryText)
        Text(if (state.mode == ChatMode.LOCAL_INFERENCE) state.localModelStatus?.message ?: "请先配置并加载本地模型。"
            else "请在模型与智能体设置中填写地址、密钥和模型名。配置完成前不会发送消息。",
            fontSize = 14.sp, lineHeight = 22.sp, color = HarmonyTheme.secondaryText, modifier = Modifier.padding(vertical = 16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HarmonyButton("取消", { showConfiguration = false })
            HarmonyButton("前往设置", { showConfiguration = false; onOpenSettings() })
        }
    }
    if (showThinkingBoundary) HarmonyModal({ showThinkingBoundary = false }) {
        Text("深度思考", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = HarmonyTheme.primaryText)
        Text("当前 Android 模型适配器尚未接入单次深度思考参数，此开关暂不可用。",
            fontSize = 14.sp, color = HarmonyTheme.secondaryText, modifier = Modifier.padding(vertical = 16.dp))
        HarmonyButton("知道了", { showThinkingBoundary = false })
    }
    if (showAttachmentSources) HarmonyAttachmentSources({ showAttachmentSources = false },
        onAlbum = { showAttachmentSources = false; pickImages() }, onCamera = {
            showAttachmentSources = false
            try {
                val directory = java.io.File(context.cacheDir, "chat-camera").also { it.mkdirs() }
                val file = java.io.File.createTempFile("capture-", ".jpg", directory)
                val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                cameraUri = uri.toString()
                camera.launch(uri)
            } catch (_: Exception) { cameraUri?.let { runCatching { context.contentResolver.delete(android.net.Uri.parse(it), null, null) } }; cameraUri = null; vm.showAttachmentNote("无法启动系统相机，请使用相册选择图片。") }
        })
}

@Composable
private fun ChatModeTabs(
    mode: String,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .background(HarmonyTheme.background("#F4F6FA"), RoundedCornerShape(16.dp))
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        listOf(ChatMode.CLOUD_AGENT, ChatMode.LOCAL_INFERENCE).forEach { option ->
            Box(
                modifier = Modifier
                    .width(46.dp)
                    .height(28.dp)
                    .background(
                        HarmonyTheme.background(if (mode == option) "#111827" else "#F4F6FA"),
                        RoundedCornerShape(14.dp),
                    )
                    .clickable(enabled = enabled) { onSelect(option) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (option == ChatMode.LOCAL_INFERENCE) "本地" else "云端",
                    fontSize = 12.sp,
                    fontWeight = if (mode == option) FontWeight.Bold else FontWeight.Normal,
                    color = HarmonyTheme.text(if (mode == option) "#FFFFFF" else "#5D6472"),
                )
            }
        }
    }
}

@Composable
private fun MessageList(
    messages: List<ChatMessage>,
    state: ChatUiState,
    voice: VoiceSessionSnapshot,
    vm: ChatViewModel,
    listState: androidx.compose.foundation.lazy.LazyListState,
    modifier: Modifier = Modifier,
    insets: PaddingValues = PaddingValues(16.dp),
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
                HarmonyChatWelcome(vm::onInputChange, voice, insets, insets.calculateBottomPadding())
            }
        }
        return
    }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = insets,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items(messages, key = { it.id }) { message ->
            MessageBubble(
                message = message,
                voice = voice,
                vm = vm,
                mode = state.mode,
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
private fun MessageBubble(message: ChatMessage, voice: VoiceSessionSnapshot, vm: ChatViewModel, mode: String) {
    val isUser = message.role == ChatMessageRole.USER
    val shape = RoundedCornerShape(17.dp)
    val color = HarmonyTheme.background(when {
        isUser -> "#111827"
        message.kind == ChatMessageKind.CONTEXT_NOTICE -> "#FFFBEB"
        message.kind == ChatMessageKind.TASK_RESULT -> "#ECFDF5"
        message.kind == ChatMessageKind.TASK_TRACE -> "#F5F3FF"
        else -> "#F8FAFD"
    })
    val border = HarmonyTheme.border(when(message.kind) {
        ChatMessageKind.TASK_RESULT -> "#A7F3D0"
        ChatMessageKind.CONTEXT_NOTICE -> "#FDE68A"
        else -> "#E8ECF3"
    })
    val roleLabel = when {
        isUser -> "你"
        message.kind == ChatMessageKind.CONTEXT_NOTICE -> "上下文"
        message.kind == ChatMessageKind.TASK_RESULT -> "任务结果"
        message.kind == ChatMessageKind.TASK_TRACE -> "任务过程"
        message.role == ChatMessageRole.SYSTEM -> "系统"
        else -> ChatMode.label(mode)
    }
    val render = chatAvatarRenderModel(message, if (mode == ChatMode.LOCAL_INFERENCE) "本" else "云",
        visualStateForMessage(voice.status, voice.activeMessageId, message.id))
    val useCat = message.role == ChatMessageRole.ASSISTANT || message.role == ChatMessageRole.TASK
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
        Column(Modifier.fillMaxWidth(.96f)) {
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp),
                horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
                DigitalAvatar(size = DigitalAvatarSize.MESSAGE, useAssistantMascot = useCat,
                    visualState = render.visualState, useDigitalAvatar = useCat,
                    fallbackText = render.fallbackText, fallbackColorHex = render.fallbackColorHex)
            }
            Column(Modifier.fillMaxWidth().background(color, shape)
                .then(if (isUser) Modifier else Modifier.border(androidx.compose.foundation.BorderStroke(1.dp, border), shape))
                .padding(horizontal = 14.dp, vertical = 12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(roleLabel, fontSize = 11.sp, color = HarmonyTheme.text(if (isUser) "#E5E7EB" else "#6B7280"), maxLines = 1)
                    if (message.kind != ChatMessageKind.CONTEXT_NOTICE && (useCat || message.kind == ChatMessageKind.TASK_RESULT || message.kind == ChatMessageKind.TASK_TRACE))
                        HarmonyAiBadge(Modifier.padding(start = 6.dp), compact = true)
                    Text(java.text.DateFormat.getDateTimeInstance().format(java.util.Date(message.createdAt)),
                        fontSize = 10.sp, color = HarmonyTheme.text(if (isUser) "#D1D5DB" else "#9CA3AF"),
                        maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(start = 8.dp))
                    if (!message.isStreaming && message.content.isNotBlank()) CopyMessageButton(message.content, true, isUser)
                }
                if (message.trace.isNotEmpty()) Text("已执行 ${message.trace.size} 次工具调用", fontSize = 12.sp,
                    color = HarmonyTheme.text("#9CA3AF"), modifier = Modifier.padding(top = 6.dp))
                val foreground = HarmonyTheme.text(if (isUser) "#FFFFFF" else "#2F3645")
                if (!message.isStreaming) ChatMarkdownText(message.content, Modifier.padding(top = 8.dp), foreground, 15f)
                else Text(message.content + " ▌", fontSize = 15.sp, lineHeight = 22.sp, color = foreground, modifier = Modifier.padding(top = 8.dp))
                if (message.trace.isNotEmpty()) {
                    var expanded by remember(message.id) { androidx.compose.runtime.mutableStateOf(!message.collapsed) }
                    HarmonyButton(if (expanded) "收起任务过程" else "展开任务过程", { expanded = !expanded }, Modifier.padding(top = 8.dp))
                    if (expanded) Text(message.trace.joinToString("\n"), fontSize = 12.sp, lineHeight = 18.sp,
                        color = HarmonyTheme.secondaryText, modifier = Modifier.padding(top = 8.dp))
                }
                if (message.role == ChatMessageRole.ASSISTANT && message.kind == ChatMessageKind.CHAT && !message.isStreaming && message.content.isNotBlank())
                    VoiceBubbleControls(message.id, message.content, voice, vm)
                ChatStoredImages(message.imagePaths)
            }
        }
    }
}

/** Copy the stored complete original, independent of visual formatting/selection. */
@Composable
internal fun CopyMessageButton(text: String, compact: Boolean = false, isUser: Boolean = false) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val copy = {
        try {
            clipboard.setText(AnnotatedString(text))
            Toast.makeText(context, "已复制消息", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(context, "复制失败，请重试或选中部分文字复制", Toast.LENGTH_SHORT).show()
        }
    }
    if (compact) HarmonyButton("复制", copy,
        Modifier.padding(start = 8.dp).border(1.dp, Color(if (isUser) 0xFF41516A else 0xFFDCE4FF), RoundedCornerShape(13.dp)),
        height = 26.dp, radius = 13.dp, fontSize = 11.sp, color = Color(if (isUser) 0xFFDBEAFE else 0xFF5968FF),
        background = Color(if (isUser) 0xFF273449 else 0xFFEEF2FF), horizontalPadding = 8.dp)
    else TextButton(onClick = copy) {
        Icon(Icons.Filled.ContentCopy, contentDescription = "复制整条消息", modifier = Modifier.size(16.dp))
        Text("复制", modifier = Modifier.padding(start = 4.dp), style = MaterialTheme.typography.labelSmall)
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

    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(30.dp).background(HarmonyTheme.background(if (speaking) "#5968FF" else if (synthesizing) "#E0E7FF" else "#EEF2FF"), RoundedCornerShape(15.dp))
            .clickable(enabled = !synthesizing) { vm.speakMessage(messageId, text) }, contentAlignment = Alignment.Center) {
            androidx.compose.foundation.Image(painterResource(if (speaking) R.drawable.voice_pause_light else R.drawable.voice_speaker_blue),
                if (speaking) "停止朗读" else if (paused) "继续朗读" else "朗读", Modifier.size(18.dp))
        }
        Box(Modifier.size(30.dp).background(HarmonyTheme.background("#EEF2FF"), RoundedCornerShape(15.dp))
            .clickable(enabled = !synthesizing) { vm.replayMessage(messageId, text) }, contentAlignment = Alignment.Center) {
            androidx.compose.foundation.Image(painterResource(R.drawable.voice_replay_blue), "重播", Modifier.size(18.dp))
        }
    }
}

/** Live listening/transcribing status + the accumulated partial transcript. */
@Composable
private fun VoiceStatusBanner(voice: VoiceSessionSnapshot) {
    if (voice.status == VoiceInteractionStatus.IDLE || voice.status == VoiceInteractionStatus.SPEAKING) return
    val label = when(voice.status) {
        VoiceInteractionStatus.LISTENING -> "正在聆听…"
        VoiceInteractionStatus.TRANSCRIBING -> "正在转写…"
        VoiceInteractionStatus.THINKING -> "正在思考…"
        VoiceInteractionStatus.SYNTHESIZING -> "正在合成语音…"
        VoiceInteractionStatus.PAUSED -> "语音已暂停"
        else -> "语音不可用"
    }
    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 6.dp).height(26.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 11.sp, color = HarmonyTheme.text("#5968FF"), maxLines = 1)
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
