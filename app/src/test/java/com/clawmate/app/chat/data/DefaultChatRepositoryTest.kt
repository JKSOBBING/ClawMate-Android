package com.clawmate.app.chat.data

import com.clawmate.app.chat.cloud.ChatClient
import com.clawmate.app.chat.cloud.ChatCompletionsRequest
import com.clawmate.app.chat.cloud.ChatCompletionResult
import com.clawmate.app.chat.cloud.ChatException
import com.clawmate.app.chat.cloud.ChatErrorKind
import com.clawmate.app.chat.cloud.ChatServerConfig
import com.clawmate.app.chat.cloud.CloudStreamChunk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Repository behavior tests using an in-memory [FakeChatDao] and a fake
 * streaming client facade. Verifies that messages are persisted (no fake
 * replies), sessions are created/titled, config errors surface, and failures do
 * NOT store an assistant message.
 */
class DefaultChatRepositoryTest {

    private data class Daos(
        val sessions: MutableList<ChatSessionEntity> = mutableListOf(),
        val messagesBySession: MutableMap<String, MutableList<ChatMessageEntity>> = mutableMapOf(),
    )

    private fun fakeDao(): ChatDao {
        val store = Daos()
        return FakeChatDao { op: Op ->
            when (op) {
                Op.OBserveAll -> store.sessions.sortedByDescending { it.updatedAt }
                is Op.OBserveSession -> store.sessions.firstOrNull { it.id == op.sessionId }
                is Op.OBserveMessages ->
                    store.messagesBySession[op.sessionId]?.sortedBy { it.createdAt }
                        ?: emptyList<ChatMessageEntity>()
                is Op.UpsertSession -> {
                    val idx = store.sessions.indexOfFirst { it.id == op.session.id }
                    if (idx >= 0) store.sessions[idx] = op.session
                    else store.sessions += op.session
                }
                is Op.UpsertMessages -> {
                    val list = store.messagesBySession.getOrPut(op.sessionId) { mutableListOf() }
                    op.messages.forEach { m ->
                        val idx = list.indexOfFirst { it.id == m.id }
                        if (idx >= 0) list[idx] = m else list += m
                    }
                }
                is Op.DeleteMessages -> store.messagesBySession.remove(op.sessionId)
                is Op.DeleteSession -> {
                    store.sessions.removeAll { it.id == op.sessionId }
                    store.messagesBySession.remove(op.sessionId)
                }
            }
        }
    }

    @Test
    fun `sendMessage persists user and streamed assistant message (no fake)`() = runBlocking {
        val repo = newRepo(fakeDao(), clientStreaming("Hello", " world"), configured = true)
        val events = repo.sendMessage("Hi there").toList()
        // UserMessageAccepted + AssistantDelta(Hello) + AssistantDelta( world) + Completed
        assertEquals(4, events.size)

        val session = repo.session(repo.ensureActiveSession())!!
        assertEquals(listOf("user", "assistant"), session.messages.map { it.role.wire })
        assertEquals("Hi there", session.messages[0].content)
        assertEquals("Hello world", session.messages[1].content)
    }

    @Test
    fun `first user message becomes the session title`() = runBlocking {
        val repo = newRepo(fakeDao(), clientStreaming("ok"), configured = true)
        repo.sendMessage("Hello world").toList()
        val session = repo.session(repo.ensureActiveSession())!!
        assertEquals("Hello world", session.title)
    }

    @Test
    fun `missing config throws ChatException and stores no assistant reply`() = runBlocking {
        val dao = fakeDao()
        val repo = newRepo(dao, clientStreaming("should not happen"), configured = false)

        val exception = try {
            repo.sendMessage("hi").toList()
            null
        } catch (e: ChatException) {
            e
        }
        assertTrue(exception != null)
        assertEquals(ChatErrorKind.CONFIG, exception!!.kind)

        // There must be no assistant (fake) reply stored.
        val messages = dao.observeSessions().flatMap {
            dao.messagesFor(it.id)
        }
        assertTrue(messages.isEmpty() || messages.none { it.role == "assistant" })
    }

    @Test
    fun `stream failure persists user but not assistant`() = runBlocking {
        val dao = fakeDao()
        val repo = newRepo(dao, failingClient(), configured = true)

        try {
            repo.sendMessage("will fail").toList()
            fail("expected stream failure")
        } catch (e: ChatException) {
            assertEquals(ChatErrorKind.IO, e.kind)
        }

        val session = repo.session(repo.ensureActiveSession())!!
        assertEquals(listOf("user"), session.messages.map { it.role.wire })
    }

    @Test
    fun `repeated sends accumulate in one active session`() = runBlocking {
        val dao = fakeDao()
        val repo = newRepo(dao, clientStreaming("ok"), configured = true)
        repo.sendMessage("first").toList()
        repo.sendMessage("second").toList()
        val sessions = repo.sessions()
        assertTrue(sessions.size == 1)
        val roles = sessions[0].messages.map { it.role.wire }
        assertEquals(listOf("user", "assistant", "user", "assistant"), roles)
        // The assistant reply after each send streamed from the client.
        assertEquals("ok", sessions[0].messages.last().content)
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun newRepo(dao: ChatDao, client: ChatClient, configured: Boolean) = DefaultChatRepository(
        dao = dao,
        client = client,
        config = ChatServerConfig(
            baseUrl = if (configured) "https://planner.example.com" else "",
            apiKey = "k",
            modelName = "m",
        ),
    )

    private fun clientStreaming(vararg deltas: String): ChatClient = object : ChatClient {
        override suspend fun completion(request: ChatCompletionsRequest): ChatCompletionResult =
            throw UnsupportedOperationException()

        override fun completionStream(request: ChatCompletionsRequest): Flow<CloudStreamChunk> =
            flow {
                deltas.forEach { d -> emit(CloudStreamChunk(contentDelta = d)) }
                emit(CloudStreamChunk(done = true))
            }
    }

    private fun failingClient(): ChatClient = object : ChatClient {
        override suspend fun completion(request: ChatCompletionsRequest): ChatCompletionResult =
            throw UnsupportedOperationException()

        override fun completionStream(request: ChatCompletionsRequest): Flow<CloudStreamChunk> =
            flow { throw ChatException(ChatErrorKind.IO, "network down") }
    }

    // ------------------------------------------------------------------
    // Fake DAO backing state machine
    // ------------------------------------------------------------------

    private sealed class Op {
        object OBserveAll : Op()
        data class OBserveSession(val sessionId: String) : Op()
        data class OBserveMessages(val sessionId: String) : Op()
        data class UpsertSession(val session: ChatSessionEntity) : Op()
        data class UpsertMessages(val sessionId: String, val messages: List<ChatMessageEntity>) : Op()
        data class DeleteMessages(val sessionId: String) : Op()
        data class DeleteSession(val sessionId: String) : Op()
    }

    private class FakeChatDao(private val handler: (Op) -> Any?) : ChatDao {

        override suspend fun upsertSession(session: ChatSessionEntity) {
            handler(Op.UpsertSession(session))
        }

        override suspend fun upsertMessages(messages: List<ChatMessageEntity>) {
            if (messages.isEmpty()) return
            handler(Op.UpsertMessages(messages.first().sessionId, messages))
        }

        override suspend fun observeSessions(): List<ChatSessionEntity> =
            (handler(Op.OBserveAll) as? List<*>)?.map { it as ChatSessionEntity } ?: emptyList()

        override suspend fun session(sessionId: String): ChatSessionEntity? =
            handler(Op.OBserveSession(sessionId)) as? ChatSessionEntity

        override suspend fun messagesFor(sessionId: String): List<ChatMessageEntity> =
            (handler(Op.OBserveMessages(sessionId)) as? List<*>)?.map { it as ChatMessageEntity }
                ?: emptyList()

        override suspend fun memoryMessagesFor(sessionId: String, cutoff: Long, now: Long): List<ChatMessageEntity> =
            messagesFor(sessionId).filter { it.createdAt in cutoff..now && it.kind == "chat" && it.role in listOf("user", "assistant") }
        override suspend fun deleteMessagesFor(sessionId: String) {
            handler(Op.DeleteMessages(sessionId))
        }

        override suspend fun deleteSession(sessionId: String) {
            handler(Op.DeleteSession(sessionId))
        }
    }
}
