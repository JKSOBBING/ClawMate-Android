package com.clawmate.app.datamanager.chatmemory

import androidx.room.withTransaction
import com.clawmate.app.chat.data.ChatDatabase
import com.clawmate.app.chat.data.ChatMappers
import com.clawmate.app.chat.model.ChatSession

/** Read-only snapshot of completed Room chat; no network or history mutation. */
class RoomChatMemorySessionSource(private val database: ChatDatabase) : ChatMemorySessionSource {
    override suspend fun loadRecent(now: Long): List<ChatSession> = database.withTransaction {
        val cutoff = now - ChatMemoryExtraction.WINDOW_MILLIS
        val dao = database.chatDao()
        dao.observeSessions().filter { it.updatedAt >= cutoff }.map { session ->
            ChatMappers.fromEntity(session, dao.memoryMessagesFor(session.id, cutoff, now).map(ChatMappers::fromEntity))
        }
    }
}
