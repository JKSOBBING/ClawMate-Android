package com.clawmate.platform.mika.memory

import com.clawmate.platform.mika.MikaEndpoints
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.model.*
import com.wangzan101.mika.core.uistate.*
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

val MIKA_MEMORY_TOOLS = setOf("memory_search", "memory_save", "memory_edit",
    "task_memory_search", "task_memory_timeline", "task_memory_step", "profile_edit", "skill_search", "skill_read", "skill_save")

/** Only original Mika memory tools cross this bridge. No screen/OS actions are exposed. */
class MikaMemorySessions(private val runtime: MikaMemoryRuntime) {
    private class Session(val turn: MikaMemoryTurn) {
        val created = System.nanoTime()
        val tools = toolDispatcher(MemoryOnlyScreen, memory = turn.memory, taskMemory = turn.tasks,
            profile = turn.runtime.profile.takeIf { turn.memory != null },
            skills = turn.runtime.catalog.takeIf { turn.memory != null }, personalSkills = turn.runtime.skills.takeIf { turn.memory != null },
            utterances = turn.utterances, conversationId = turn.conversation, allowedToolNames = MIKA_MEMORY_TOOLS)
    }
    private val sessions = ConcurrentHashMap<String, Session>()

    fun turn(id: String, goal: String): MikaMemoryTurn? = sessions[id]?.turn?.takeIf { it.goal == goal && runtime.valid(it) }
    suspend fun begin(goal: String, conversation: String, prior: List<com.wangzan101.mika.core.agent.Turn> = emptyList()): String = runtime.mutex.withLock {
        require(goal.length in 1..32_000 && conversation.length <= 200)
        sessions.entries.removeIf { System.nanoTime() - it.value.created > TimeUnit.MINUTES.toNanos(15) }
        check(sessions.size < 16) { "too_many_memory_turns" }
        val id = UUID.randomUUID().toString()
        val session = Session(runtime.begin(goal, conversation, prior))
        sessions[id] = session
        buildJsonObject {
            put("id", id); put("context", session.turn.context().orEmpty())
            put("tools", JsonArray(session.tools.definitions().map { spec -> buildJsonObject {
                put("type", "function"); put("function", buildJsonObject {
                    put("name", spec.name); put("description", spec.description)
                    put("parameters", Json.parseToJsonElement(spec.parametersJsonSchema))
                })
            } }))
        }.toString()
    }

    suspend fun call(id: String, name: String, args: String): String = runtime.mutex.withLock {
        val s = sessions[id]
        if (s == null || !runtime.valid(s.turn)) return@withLock error("memory_session_expired")
        if (name !in MIKA_MEMORY_TOOLS || args.length > 16_000 ||
            name.startsWith("task_memory_") && !runtime.settings.switches.value.taskMemoryEnabled) return@withLock error("tool_unavailable")
        val result = s.tools.executeRead(Part.ToolCall(UUID.randomUUID().toString(), name, args))
        runtime.note(s.tools.savedChanges)
        result.content.toString()
    }

    suspend fun finish(id: String, url: String, model: String, key: String): Int {
        val s = sessions.remove(id) ?: return 0
        if (s.turn.memory == null || !runtime.valid(s.turn)) return 0
        require(url.length <= 2048 && model.length in 1..180 && key.length <= 4096)
        val endpoint = requireNotNull(MikaEndpoints.parse(url)) { "invalid_model_endpoint" }
        val chat = OpenAiCompatModel(endpoint.second, key.ifBlank { null }, model,
            Capabilities(true, false, 64_000, false), http)
        return runtime.finish(s.turn, chat, s.tools.savedChanges)
    }

    fun close(id: String) { sessions.remove(id) }
    private fun error(code: String) = buildJsonObject { put("ok", false); put("error", code) }.toString()
    companion object {
        private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false).callTimeout(25, TimeUnit.SECONDS).build()
    }
}

internal object MemoryOnlyScreen : ScreenPort {
    override suspend fun snapshot() = ScreenSnapshot("p0", null, emptyList(), 1, 1, hasWindow = false)
    override suspend fun screenshotPng(): ByteArray? = null
    override suspend fun listApps(): List<Pair<String, String>> = emptyList()
    override suspend fun waitStable(timeoutMs: Long) = false
    override suspend fun tapRef(ref: String, snapshotId: String) = ActionResult.Failed("no_screen")
    override suspend fun tapXY(x: Int, y: Int) = ActionResult.Failed("no_screen")
    override suspend fun typeText(ref: String?, snapshotId: String?, text: String) = ActionResult.Failed("no_screen")
    override suspend fun clearText(ref: String?, snapshotId: String?) = ActionResult.Failed("no_screen")
    override suspend fun scroll(ref: String?, snapshotId: String?, down: Boolean) = ActionResult.Failed("no_screen")
    override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) = ActionResult.Failed("no_screen")
    override suspend fun back() = ActionResult.Failed("no_screen")
    override suspend fun pressKey(keyCode: Int) = ActionResult.Failed("no_screen")
    override suspend fun openApp(packageName: String, cold: Boolean) = ActionResult.Failed("no_screen")
}
