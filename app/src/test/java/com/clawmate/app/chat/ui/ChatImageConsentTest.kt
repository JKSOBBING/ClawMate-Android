package com.clawmate.app.chat.ui

import com.clawmate.app.chat.data.*
import com.clawmate.app.chat.model.*
import com.clawmate.app.chat.voice.*
import com.clawmate.app.image.PreparedChatImage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatImageConsentTest {
    private val dispatcher = StandardTestDispatcher()
    private val image = PreparedChatImage("data:image/jpeg;base64,AQID", 1, 1, 3)
    @Before fun before() { Dispatchers.setMain(dispatcher) }
    @After fun after() { Dispatchers.resetMain() }

    @Test fun `cancelled consent never reads or uploads while confirmation clears only accepted attachments`() = runTest {
        val repo = ImageRepo(); var reads = 0
        val vm = ChatViewModel(repo, QuietVoice(), prepareImage = { reads++; image })
        advanceUntilIdle()
        vm.addPendingAttachments(listOf("content://fixture/photo"), AttachmentSource.ALBUM)
        vm.send(); assertNotNull(vm.state.value.imageConsent); assertEquals(0, reads)
        vm.dismissImageConsent(); vm.confirmImageSend(); advanceUntilIdle()
        assertEquals(0, reads); assertEquals(0, repo.sends)
        vm.send(); vm.confirmImageSend(); advanceUntilIdle()
        assertEquals(1, reads); assertEquals(1, repo.sends)
        assertTrue(vm.state.value.pendingAttachments.isEmpty())
        assertEquals("请分析这张图片。", repo.sentText)
        assertEquals("测试流式回复", vm.state.value.messages.last().content)
        assertFalse(vm.state.value.isSending)
    }

    @Test fun `destination changes and preparation failure keep draft and selected image`() = runTest {
        val repo = ImageRepo()
        val vm = ChatViewModel(repo, QuietVoice(), prepareImage = { error("fixture unreadable") })
        advanceUntilIdle(); vm.onInputChange("我的草稿")
        vm.addPendingAttachments(listOf("content://fixture/photo"), AttachmentSource.ALBUM)
        vm.send(); repo.destination = ChatImageUploadInfo("changed.invalid:443", "model")
        vm.confirmImageSend(); advanceUntilIdle()
        assertEquals(0, repo.sends); assertEquals("我的草稿", vm.state.value.inputText)
        assertEquals(1, vm.state.value.pendingAttachments.size)
        vm.send(); vm.confirmImageSend(); advanceUntilIdle()
        assertEquals(0, repo.sends); assertEquals("我的草稿", vm.state.value.inputText)
        assertEquals(1, vm.state.value.pendingAttachments.size)
        assertNotNull(vm.state.value.error)
    }

    @Test fun `missing config too many images and local mode do not prepare an image`() = runTest {
        val repo = ImageRepo()
        val vm = ChatViewModel(repo, QuietVoice(), prepareImage = { error("no read") })
        advanceUntilIdle()
        vm.addPendingAttachments((1..5).map { "content://fixture/$it" }, AttachmentSource.ALBUM)
        vm.send(); assertNull(vm.state.value.imageConsent)
        assertTrue(vm.state.value.attachmentNote!!.contains("最多发送4张"))
        vm.clearPendingAttachments()
        vm.addPendingAttachments(listOf("content://fixture/photo"), AttachmentSource.ALBUM)
        repo.destination = null
        vm.send(); assertNull(vm.state.value.imageConsent)
        repo.destination = ChatImageUploadInfo("example.invalid:443", "model")
        vm.newSession(ChatMode.LOCAL_INFERENCE); advanceUntilIdle()
        vm.addPendingAttachments(listOf("content://fixture/photo"), AttachmentSource.ALBUM)
        vm.send(); assertNull(vm.state.value.imageConsent)
        assertTrue(vm.state.value.attachmentNote!!.contains("本地推理"))
        assertEquals(0, repo.sends)
    }

    @Test fun `stop during image preparation leaves draft and ignores its late result`() = runTest {
        val repo = ImageRepo(); val ready = CompletableDeferred<PreparedChatImage>()
        val vm = ChatViewModel(repo, QuietVoice(), prepareImage = { withContext(NonCancellable) { ready.await() } })
        advanceUntilIdle(); vm.onInputChange("等待读取的草稿")
        vm.addPendingAttachments(listOf("content://fixture/photo"), AttachmentSource.ALBUM)
        vm.send(); vm.confirmImageSend(); runCurrent(); assertTrue(vm.state.value.isSending)
        vm.cancelSend(); ready.complete(image); advanceUntilIdle()
        assertEquals(0, repo.sends)
        assertEquals("等待读取的草稿", vm.state.value.inputText)
        assertEquals(1, vm.state.value.pendingAttachments.size)
        assertFalse(vm.state.value.isSending)
        assertNull(vm.state.value.error)
    }

    @Test fun `busy session deletion is blocked and stop removes an unpersisted partial reply`() = runTest {
        val repo = ImageRepo(); val gate = CompletableDeferred<Unit>(); repo.gate = gate
        val vm = ChatViewModel(repo, QuietVoice(), prepareImage = { image })
        advanceUntilIdle()
        vm.addPendingAttachments(listOf("content://fixture/photo"), AttachmentSource.ALBUM)
        vm.send(); vm.confirmImageSend(); advanceUntilIdle()
        assertTrue(vm.state.value.messages.last().isStreaming)
        vm.deleteSession(vm.sessionId); advanceUntilIdle()
        assertEquals(0, repo.deletes)
        assertTrue(vm.state.value.error!!.contains("先停止"))
        vm.cancelSend(); gate.complete(Unit); advanceUntilIdle()
        assertEquals(listOf(ChatMessageRole.USER), vm.state.value.messages.map { it.role })
        assertFalse(vm.state.value.isSending)
        assertNull(vm.state.value.error)
    }

    @Test fun `late cancelled image reader failure cannot overwrite a newer successful conversation`() = runTest {
        val repo = ImageRepo(); val late = CompletableDeferred<Unit>(); var reads = 0
        val vm = ChatViewModel(repo, QuietVoice(), prepareImage = {
            if (reads++ == 0) withContext(NonCancellable) { late.await(); error("late old reader failure") }
            image
        })
        advanceUntilIdle()
        vm.addPendingAttachments(listOf("content://fixture/photo"), AttachmentSource.ALBUM)
        vm.send(); vm.confirmImageSend(); runCurrent()
        vm.cancelSend(); vm.send(); vm.confirmImageSend(); advanceUntilIdle()
        assertEquals(1, repo.sends)
        late.complete(Unit); advanceUntilIdle()
        assertNull(vm.state.value.error)
        assertEquals("测试流式回复", vm.state.value.messages.last().content)
        assertFalse(vm.state.value.isSending)
    }

    private class ImageRepo : ChatRepository {
        var destination: ChatImageUploadInfo? = ChatImageUploadInfo("example.invalid:443", "fixture-model")
        var sends = 0; var sentText = ""
        var deletes = 0
        var gate: CompletableDeferred<Unit>? = null
        private var current = ChatSession("fixture", "测试会话", ChatMode.CLOUD_AGENT, 1, 1)
        override fun imageUploadInfo() = destination
        override suspend fun sendImageMessage(userText: String, images: List<PreparedChatImage>): Flow<ChatSendEvent> = flow {
            sends++; sentText = userText
            emit(ChatSendEvent.UserMessageAccepted(ChatMessage("user", ChatMessageRole.USER, ChatMessageKind.CHAT, userText, emptyList(), false, 1)))
            emit(ChatSendEvent.AssistantDelta("测试流式回复")); gate?.await(); emit(ChatSendEvent.Completed("测试流式回复"))
        }
        override suspend fun sessions() = listOf(current)
        override suspend fun session(sessionId: String) = current
        override suspend fun ensureActiveSession() = current.id
        override fun activeSessionId() = current.id
        override suspend fun createSession(mode: String?): ChatSession { current = current.copy(mode = ChatMode.normalize(mode)); return current }
        override suspend fun selectSession(sessionId: String) = current
        override suspend fun deleteSession(sessionId: String) { deletes++ }
        override suspend fun sendMessage(userText: String): Flow<ChatSendEvent> = error("no text fallback")
        override suspend fun sendAgentMessage(userText: String): Flow<ChatSendEvent> = error("no agent task")
    }

    private class QuietVoice : ChatVoiceControllerApi {
        override val state = MutableStateFlow(VoiceControllerState())
        override fun setHost(activeInputProvider: () -> String, setInputSink: (String) -> Unit, isBusyProvider: () -> Boolean) = Unit
        override fun toggleVoiceInput() = VoiceActionResult.Ok
        override fun stopVoiceInput() = VoiceActionResult.Ok
        override fun speakMessage(messageId: String, text: String) = VoiceActionResult.Ok
        override fun replayMessage(messageId: String, text: String) = VoiceActionResult.Ok
        override fun stopPlayback() = Unit
        override fun dispose() = Unit
    }
}
