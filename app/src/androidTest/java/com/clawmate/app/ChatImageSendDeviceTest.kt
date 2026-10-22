package com.clawmate.app

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.clawmate.app.chat.cloud.*
import com.clawmate.app.chat.data.*
import com.clawmate.app.chat.model.*
import com.clawmate.app.chat.ui.*
import com.clawmate.app.chat.voice.*
import com.clawmate.app.image.AndroidChatImagePreparer
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatImageSendDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun nativeConsentPreparesAPhotoStreamsAndRetainsAnActualLocalThumbnail() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File(context.cacheDir, "chat-image-source-${UUID.randomUUID()}.png")
        val bitmap = Bitmap.createBitmap(12, 8, Bitmap.Config.ARGB_8888)
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        val db = Room.inMemoryDatabaseBuilder(context, ChatDatabase::class.java).build()
        val uploads = AtomicInteger(); val reads = AtomicInteger()
        val client = object : ChatClient {
            override suspend fun completion(request: ChatCompletionsRequest): ChatCompletionResult = error("ordinary stream only")
            override fun completionStream(request: ChatCompletionsRequest): Flow<CloudStreamChunk> = flow {
                assertEquals(1, request.messages.sumOf { it.imageUrls.size }); assertNull(request.tools)
                uploads.incrementAndGet()
                emit(CloudStreamChunk(contentDelta = "设备测试图片回复"))
                emit(CloudStreamChunk(finishReason = "stop", done = true))
            }
        }
        val store = ChatImageFileStore(context.filesDir)
        val repo = DefaultChatRepository(db.chatDao(), client, ChatServerConfig("https://example.invalid", "fixture-key", "fixture-model"), imageStore = store)
        lateinit var vm: ChatViewModel
        try {
            compose.setContent {
                MaterialTheme {
                    val model = remember { ChatViewModel(repo, QuietVoice(), prepareImage = { reads.incrementAndGet(); AndroidChatImagePreparer(context).prepare(it) }) }
                    vm = model
                    val state by model.state.collectAsState()
                    Column {
                        ChatImageConsentDialog(model)
                        state.messages.forEach { message ->
                            Text(message.content)
                            ChatStoredImages(message.imagePaths)
                        }
                    }
                }
            }
            compose.runOnIdle { vm.addPendingAttachments(listOf(source.toURI().toString()), AttachmentSource.ALBUM); vm.send() }
            compose.onNodeWithText("发送图片到云端聊天？").assertIsDisplayed()
            assertEquals(0, reads.get()); assertEquals(0, uploads.get())
            compose.onNodeWithText("取消").performClick()
            compose.runOnIdle { vm.send() }
            compose.onNodeWithText("确认发送图片").performClick()
            compose.waitUntil(10000) { vm.state.value.messages.any { it.content == "设备测试图片回复" } && !vm.state.value.isSending }
            assertEquals(1, reads.get()); assertEquals(1, uploads.get())
            compose.onNodeWithText("设备测试图片回复").assertIsDisplayed()
            compose.onNodeWithContentDescription("历史图片 1（本机）").assertIsDisplayed()
            val stored = runBlocking { repo.session(repo.ensureActiveSession())!!.messages.first() }
            assertTrue(store.file(stored.imagePaths.single()).isFile)
            runBlocking { repo.deleteSession(repo.ensureActiveSession()) }
            assertFalse(store.file(stored.imagePaths.single()).exists())
        } finally {
            runBlocking { repo.sessions().forEach { repo.deleteSession(it.id) } }
            db.close(); source.delete()
        }
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
