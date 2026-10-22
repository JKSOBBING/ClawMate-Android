package com.clawmate.app

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.clawmate.app.chat.data.*
import com.clawmate.app.chat.model.*
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatImageMigrationDeviceTest {
    @Test fun versionOneHistorySurvivesTheRealNonDestructiveMigration() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "chat-image-migration-${UUID.randomUUID()}.db"
        try {
            // Exact v1 DDL from the previously built ChatDatabase_Impl; test-owned database only.
            context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { db ->
                db.execSQL("CREATE TABLE chat_sessions (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, mode TEXT NOT NULL, contextSummary TEXT NOT NULL, customTitle TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE chat_messages (id TEXT NOT NULL PRIMARY KEY, sessionId TEXT NOT NULL, role TEXT NOT NULL, kind TEXT NOT NULL, content TEXT NOT NULL, trace TEXT NOT NULL, collapsed INTEGER NOT NULL, createdAt INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX index_chat_messages_sessionId ON chat_messages (sessionId)")
                db.execSQL("CREATE INDEX index_chat_messages_sessionId_createdAt ON chat_messages (sessionId, createdAt)")
                db.execSQL("INSERT INTO chat_sessions VALUES ('fixture', '旧会话', 'cloud_agent', '旧摘要', '自定义标题', 1, 2)")
                db.execSQL("INSERT INTO chat_messages VALUES ('old-message', 'fixture', 'user', 'chat', '旧版真实测试内容', 'trace-a\ntrace-b', 0, 3)")
                db.version = 1
            }
            var db = Room.databaseBuilder(context, ChatDatabase::class.java, name).addMigrations(ChatDatabase.MIGRATION_1_2).build()
            try {
                val old = db.chatDao().messagesFor("fixture").single()
                assertEquals("旧版真实测试内容", old.content)
                assertEquals(listOf("trace-a", "trace-b"), old.trace)
                assertTrue(old.imagePaths.isEmpty())
                assertEquals("自定义标题", db.chatDao().session("fixture")!!.customTitle)
                val imageMessage = ChatMappers.fromEntity(old).copy(id = "new-message", imagePaths = listOf("00000000-0000-0000-0000-000000000000.jpg"))
                db.chatDao().upsertMessages(listOf(ChatMappers.toEntity("fixture", imageMessage)))
                db.close()
                db = Room.databaseBuilder(context, ChatDatabase::class.java, name).addMigrations(ChatDatabase.MIGRATION_1_2).build()
                assertEquals(2, db.chatDao().messagesFor("fixture").size)
                assertEquals(imageMessage.imagePaths, db.chatDao().messagesFor("fixture").first { it.id == "new-message" }.imagePaths)
            } finally { db.close() }
        } finally { context.deleteDatabase(name) }
    }
}
