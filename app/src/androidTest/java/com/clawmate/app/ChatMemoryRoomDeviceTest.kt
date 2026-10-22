package com.clawmate.app

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.clawmate.app.chat.data.*
import com.clawmate.app.chat.model.*
import com.clawmate.app.datamanager.chatmemory.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated in-memory Room database; the user's persisted chat database is never opened. */
@RunWith(AndroidJUnit4::class)
class ChatMemoryRoomDeviceTest {
    @Test fun roomSnapshotFiltersDateAndMessageKindWithoutMutatingHistory() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, ChatDatabase::class.java).build()
        val now = 1_792_713_600_000L
        try {
            val dao = db.chatDao()
            val session = ChatSession("fixture", "测试数据库", ChatMode.LOCAL_INFERENCE, now, now)
            dao.upsertSession(ChatMappers.toEntity(session))
            val messages = listOf(
                ChatMessage("a-old", ChatMessageRole.USER, ChatMessageKind.CHAT, "过期消息", emptyList(), false, now - ChatMemoryExtraction.WINDOW_MILLIS - 1),
                ChatMessage("b-user", ChatMessageRole.USER, ChatMessageKind.CHAT, "测试本人资料", emptyList(), false, now - 10),
                ChatMessage("c-assistant", ChatMessageRole.ASSISTANT, ChatMessageKind.CHAT, "<think>推理</think>前文", emptyList(), false, now - 5),
                ChatMessage("d-tool", ChatMessageRole.TASK, ChatMessageKind.TASK_RESULT, "工具输出", emptyList(), false, now),
                ChatMessage("e-notice", ChatMessageRole.ASSISTANT, ChatMessageKind.CONTEXT_NOTICE, "系统提示", emptyList(), false, now),
                ChatMessage("f-future", ChatMessageRole.USER, ChatMessageKind.CHAT, "未来消息", emptyList(), false, now + 1),
            )
            dao.upsertMessages(messages.map { ChatMappers.toEntity(session.id, it) })
            val snapshot = RoomChatMemorySessionSource(db).loadRecent(now)
            assertEquals(listOf("b-user", "c-assistant"), snapshot.single().messages.map { it.id })
            assertEquals(ChatMode.LOCAL_INFERENCE, snapshot.single().mode)
            assertEquals(1, ChatMemoryExtraction.pendingInputs(snapshot, emptySet(), now).size)
            assertEquals(messages.size, dao.messagesFor(session.id).size)
        } finally {
            db.close()
        }
    }
}
