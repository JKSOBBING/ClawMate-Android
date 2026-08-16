package com.clawmate.app.chat.data

import com.clawmate.app.chat.agent.AgentLoop
import com.clawmate.app.chat.agent.AgentTool
import com.clawmate.app.chat.agent.ToolRegistry
import com.clawmate.app.chat.cloud.ChatClient
import com.clawmate.app.chat.cloud.ChatCompletionsRequest
import com.clawmate.app.chat.cloud.ChatCompletionResult
import com.clawmate.app.chat.cloud.ChatServerConfig
import com.clawmate.app.chat.cloud.CloudStreamChunk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the agent path of [DefaultChatRepository.sendAgentMessage]: the user
 * message is persisted, the assistant message accumulates from the agent loop
 * (no fake reply), and tool traces are stored when tools run.
 */
class AgentRepositoryTest {

    private val dao = FakeEmptyDao()

    @Test
    fun `sendAgentMessage persists user and streamed assistant with tool trace`() = runBlocking {
        val registry = ToolRegistry().apply {
            register(AgentTool("echo", "echo") { args, _ -> "echo:$args" })
        }
        val client = object : ChatClient {
            override suspend fun completion(request: ChatCompletionsRequest): ChatCompletionResult =
                throw UnsupportedOperationException()

            override fun completionStream(request: ChatCompletionsRequest): Flow<CloudStreamChunk> = flow {
                val hasTool = request.messages.any { it.role == "tool" }
                if (hasTool) {
                    emit(CloudStreamChunk(contentDelta = "handler says done"))
                    emit(CloudStreamChunk(done = true))
                } else {
                    emit(CloudStreamChunk(toolCallDeltas = listOf(
                        com.clawmate.app.chat.cloud.StreamToolCallDelta(0, id = "c1", type = "function", name = "echo", arguments = "{}"),
                    )))
                    emit(CloudStreamChunk(done = true))
                }
            }
        }
        val repo = DefaultChatRepository(
            dao = dao,
            client = client,
            config = ChatServerConfig("https://planner.example.com", "k", "m"),
            agentLoop = AgentLoop(client, registry),
        )

        val events = repo.sendAgentMessage("please echo").toList()
        // UserMessageAccepted + ToolExecuted + AssistantDelta + Completed (+ maybe note)
        assertTrue(events.any { it is ChatSendEvent.ToolExecuted })

        val session = repo.session(repo.ensureActiveSession())!!
        assertEquals(listOf("user", "assistant"), session.messages.map { it.role.wire })
        assertEquals("please echo", session.messages[0].content)
        assertTrue(session.messages[1].content.contains("handler says done"))
        assertTrue(session.messages[1].trace.isNotEmpty())
    }

    @Test
    fun `sendAgentMessage persists user only on loop-level model failure`() = runBlocking {
        val client = object : ChatClient {
            override suspend fun completion(request: ChatCompletionsRequest): ChatCompletionResult =
                throw UnsupportedOperationException()

            override fun completionStream(request: ChatCompletionsRequest): Flow<CloudStreamChunk> =
                flow {
                    throw com.clawmate.app.chat.cloud.ChatException(
                        com.clawmate.app.chat.cloud.ChatErrorKind.IO, "network down",
                    )
                }
        }
        val repo = DefaultChatRepository(
            dao = dao,
            client = client,
            config = ChatServerConfig("https://planner.example.com", "k", "m"),
            agentLoop = AgentLoop(client, ToolRegistry()),
        )
        try {
            repo.sendAgentMessage("will fail").toList()
        } catch (_: Exception) {
            // expected
        }
        val session = repo.session(repo.ensureActiveSession())!!
        assertEquals(listOf("user"), session.messages.map { it.role.wire })
    }
}

/** Minimal in-memory [ChatDao] for agent repository tests. */
class FakeEmptyDao : ChatDao {
    private val sessions = mutableListOf<ChatSessionEntity>()
    private val messages = mutableMapOf<String, MutableList<ChatMessageEntity>>()

    override suspend fun upsertSession(session: ChatSessionEntity) {
        val idx = sessions.indexOfFirst { it.id == session.id }
        if (idx >= 0) sessions[idx] = session else sessions += session
    }

    override suspend fun upsertMessages(msg: List<ChatMessageEntity>) {
        if (msg.isEmpty()) return
        val list = messages.getOrPut(msg.first().sessionId) { mutableListOf() }
        msg.forEach { m ->
            val idx = list.indexOfFirst { it.id == m.id }
            if (idx >= 0) list[idx] = m else list += m
        }
    }

    override suspend fun observeSessions(): List<ChatSessionEntity> = sessions.sortedByDescending { it.updatedAt }
    override suspend fun session(sessionId: String): ChatSessionEntity? = sessions.firstOrNull { it.id == sessionId }
    override suspend fun messagesFor(sessionId: String): List<ChatMessageEntity> =
        messages[sessionId]?.sortedBy { it.createdAt } ?: emptyList()
    override suspend fun deleteMessagesFor(sessionId: String) { messages.remove(sessionId) }
    override suspend fun deleteSession(sessionId: String) {
        sessions.removeAll { it.id == sessionId }
        messages.remove(sessionId)
    }
}
