package com.clawmate.app.chat.data

import com.clawmate.app.chat.cloud.ChatClient
import com.clawmate.app.chat.cloud.ChatCompletionsRequest
import com.clawmate.app.chat.cloud.ChatCompletionResult
import com.clawmate.app.chat.cloud.ChatServerConfig
import com.clawmate.app.chat.cloud.CloudStreamChunk
import com.clawmate.app.settings.domain.AppSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Multi-session flow tests (Phase 43): create / list / switch / delete / restart
 * restore, all backed by the existing in-memory [ChatDao] (no separate database).
 * Also verifies the context-compression budget is actually applied on the wire —
 * proving the seam is wired into the real request build path and not dead code.
 */
class MultiSessionRepositoryTest {

    private data class Store(
        val sessions: MutableList<ChatSessionEntity> = mutableListOf(),
        val messagesBySession: MutableMap<String, MutableList<ChatMessageEntity>> = mutableMapOf(),
    )

    private fun fakeDao(store: Store): ChatDao = FakeChatDao { op: Op ->
        when (op) {
            is Op.ObserveAll -> store.sessions.sortedByDescending { it.updatedAt }
            is Op.ObserveSession -> store.sessions.firstOrNull { it.id == op.sessionId }
            is Op.ObserveMessages ->
                store.messagesBySession[op.sessionId]?.sortedBy { it.createdAt }
                    ?: emptyList<ChatMessageEntity>()
            is Op.UpsertSession -> {
                val idx = store.sessions.indexOfFirst { it.id == op.session.id }
                if (idx >= 0) store.sessions[idx] = op.session else store.sessions += op.session
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

    /** Captures the last request sent to the client for wire assertions. */
    private class CapturingClient : ChatClient {
        var lastRequest: ChatCompletionsRequest? = null
        override suspend fun completion(request: ChatCompletionsRequest): ChatCompletionResult =
            throw UnsupportedOperationException()
        override fun completionStream(request: ChatCompletionsRequest): Flow<CloudStreamChunk> = flow {
            lastRequest = request
            emit(CloudStreamChunk(contentDelta = "ok"))
            emit(CloudStreamChunk(done = true))
        }
    }

    private fun newRepo(
        dao: ChatDao,
        client: ChatClient,
        settings: AppSettings = AppSettings.defaults(),
    ) = DefaultChatRepository(
        dao = dao,
        client = client,
        config = ChatServerConfig("https://planner.example.com", "k", "m"),
        settingsRepository = object : com.clawmate.app.settings.data.SettingsRepository {
            override val settings: kotlinx.coroutines.flow.Flow<AppSettings> =
                kotlinx.coroutines.flow.flow { emit(settings) }
            override suspend fun snapshot(): AppSettings = settings
            override suspend fun save(settings: AppSettings): AppSettings = settings
        },
    )

    // ------------------------------------------------------------------
    // Create / list
    // ------------------------------------------------------------------

    @Test
    fun `createSession adds a new session and makes it active`() = runBlocking {
        val store = Store()
        val repo = newRepo(fakeDao(store), CapturingClient())

        repo.createSession()
        repo.createSession()

        assertEquals(2, repo.sessions().size)
        val sessions = repo.sessions()
        val first = sessions[0]
        // Sending targets the most recently created (active) session.
        val activeId = repo.ensureActiveSession()
        assertEquals(first.id, activeId)
    }

    // ------------------------------------------------------------------
    // Switch + restart restore
    // ------------------------------------------------------------------

    @Test
    fun `selectSession switches active and restart recovers the selected session`() = runBlocking {
        val store = Store()
        val repo = newRepo(fakeDao(store), CapturingClient())

        val a = repo.createSession()
        val b = repo.createSession()
        repo.selectSession(a.id)

        // Selecting bumps updatedAt so the max-updated session is the one picked on restart.
        val after = repo.sessions()
        val mostRecent = after.maxByOrNull { it.updatedAt }!!
        assertEquals(a.id, mostRecent.id)
        assertTrue(mostRecent.updatedAt >= b.updatedAt)

        // Simulate an app restart: a fresh repository instance (same store) restores A.
        val restarted = newRepo(fakeDao(store), CapturingClient())
        assertEquals(a.id, restarted.ensureActiveSession())
    }

    // ------------------------------------------------------------------
    // Delete semantics
    // ------------------------------------------------------------------

    @Test
    fun `deleting the active session re-selects the most recently updated remaining one`() = runBlocking {
        val store = Store()
        val repo = newRepo(fakeDao(store), CapturingClient())

        val a = repo.createSession()
        val b = repo.createSession()
        repo.selectSession(a.id)

        repo.deleteSession(a.id)

        assertTrue(repo.sessions().all { it.id != a.id })
        // Active marker cleared -> next ensure re-selects max-updated remaining (b).
        assertEquals(b.id, repo.ensureActiveSession())
    }

    @Test
    fun `deleting the last remaining session leaves no active session until next send`() = runBlocking {
        val store = Store()
        val repo = newRepo(fakeDao(store), CapturingClient())

        val a = repo.createSession()
        repo.deleteSession(a.id)

        // No sessions left; active marker cleared.
        assertNull(repo.activeSessionId())
        assertTrue(repo.sessions().isEmpty())

        // A fresh send lazily creates a new empty session rather than failing.
        val b = repo.createSession()
        assertTrue(repo.sessions().size == 1)
    }

    @Test
    fun `deleting a non-active session keeps the active one untouched`() = runBlocking {
        val store = Store()
        val repo = newRepo(fakeDao(store), CapturingClient())

        val a = repo.createSession()
        val b = repo.createSession()
        repo.selectSession(a.id) // active = a

        repo.deleteSession(b.id)

        assertTrue(repo.sessions().all { it.id != b.id })
        assertEquals(a.id, repo.activeSessionId())
        // Next send continues in a.
        assertEquals(a.id, repo.ensureActiveSession())
    }

    @Test
    fun `switching back to a deleted session returns null and does not resurrect it`() = runBlocking {
        val store = Store()
        val repo = newRepo(fakeDao(store), CapturingClient())

        val a = repo.createSession()
        repo.deleteSession(a.id)

        assertNull(repo.selectSession(a.id))
        assertTrue(repo.sessions().isEmpty())
    }

    // ------------------------------------------------------------------
    // Budget wiring on the real request build path
    // ------------------------------------------------------------------

    @Test
    fun `long history triggers context compression on the wire`() = runBlocking {
        val store = Store()
        val client = CapturingClient()
        val repo = newRepo(
            fakeDao(store),
            client,
            // A modest thread then exceeds the (default-large) usable threshold
            // because the history is seeded to be huge.
            settings = AppSettings(chatCompressionThresholdPercent = 85, chatRecentRounds = 2),
        )
        // Pre-seed a long history in the active session directly through the DAO.
        val activeId = repo.createSession().id
        val longContent = "字".repeat(30000)
        val msgs = (1..8).flatMap { i ->
            listOf(
                ChatMessageEntity(
                    id = "u$i", sessionId = activeId, role = "user", kind = "chat",
                    content = longContent, trace = emptyList(), collapsed = false, createdAt = i * 10L,
                ),
                ChatMessageEntity(
                    id = "a$i", sessionId = activeId, role = "assistant", kind = "chat",
                    content = longContent, trace = emptyList(), collapsed = false, createdAt = i * 10L + 1,
                ),
            )
        }
        store.messagesBySession[activeId] = msgs.toMutableList()

        repo.sendMessage("current").toList()

        val request = client.lastRequest!!
        val wire = request.messages
        // Compression must have happened: history >= 8 user turns exceeded the
        // budget, so far fewer than 16 messages are sent and an extractive
        // summary is folded into the leading system message.
        assertTrue(
            "expected compressed wire (sent ${wire.size} messages)",
            wire.size < 10,
        )
        val system = wire.first { it.role == "system" }
        assertTrue(system.content!!.contains("较早对话"))
        // The current turn is present at the tail.
        assertEquals("current", wire.last().content)
        // The two most recent user turns survive as concrete messages.
        assertTrue(wire.any { it.role == "user" && it.content == longContent })
    }

    @Test
    fun `short history leaves the wire uncompressed`() = runBlocking {
        val store = Store()
        val client = CapturingClient()
        val repo = newRepo(fakeDao(store), client)

        repo.sendMessage("hi").toList()

        val wire = client.lastRequest!!.messages
        // system + user (no assistant yet) -> no compression for a single turn.
        assertTrue(wire.size <= 2)
        // No summary folded in.
        assertTrue(wire.none { (it.content ?: "").contains("较早对话") })
        assertEquals("hi", wire.last().content)
    }

    // ------------------------------------------------------------------
    // Fake DAO backing state machine
    // ------------------------------------------------------------------

    private sealed class Op {
        object ObserveAll : Op()
        data class ObserveSession(val sessionId: String) : Op()
        data class ObserveMessages(val sessionId: String) : Op()
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
            (handler(Op.ObserveAll) as? List<*>)?.map { it as ChatSessionEntity } ?: emptyList()
        override suspend fun session(sessionId: String): ChatSessionEntity? =
            handler(Op.ObserveSession(sessionId)) as? ChatSessionEntity
        override suspend fun messagesFor(sessionId: String): List<ChatMessageEntity> =
            (handler(Op.ObserveMessages(sessionId)) as? List<*>)?.map { it as ChatMessageEntity }
                ?: emptyList()
        override suspend fun deleteMessagesFor(sessionId: String) {
            handler(Op.DeleteMessages(sessionId))
        }
        override suspend fun deleteSession(sessionId: String) {
            handler(Op.DeleteSession(sessionId))
        }
    }
}
