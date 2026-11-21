package com.clawmate.platform.mika.memory

import com.wangzan101.mika.core.memory.*
import com.wangzan101.mika.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.crypto.KeyGenerator

class MikaMemoryIntegrationTest {
    @get:Rule val temp = TemporaryFolder()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private fun runtime() = MikaMemoryRuntime(temp.root, AesGcmKeyCipher { key })
    private fun id(raw: String) = Json.parseToJsonElement(raw).jsonObject.getValue("id").jsonPrimitive.content
    private fun args(vararg pairs: Pair<String, String>) = buildJsonObject { pairs.forEach { put(it.first, it.second) } }.toString()
    private fun ok(raw: String) = Json.parseToJsonElement(raw).jsonObject["ok"]?.jsonPrimitive?.boolean == true
    private val quote = "请记住，我喜欢简洁的回答"
    private val save = args("kind" to "preference", "content" to "用户喜欢简洁的回答", "quote" to quote)

    @Test fun originalToolsSaveThenNewConversationAndNewProcessRecall() = runTest {
        val r = runtime(); val s = MikaMemorySessions(r)
        assertTrue(ok(s.call(id(s.begin(quote, "chat-a")), "memory_save", save)))
        assertEquals(1, r.store.items.value.size)
        val again = MikaMemorySessions(runtime()).begin("我的回答偏好是什么？", "chat-b")
        assertTrue(again.contains("用户喜欢简洁的回答"))
        assertFalse(temp.root.resolve("memory/memory.bin").readText().contains("简洁"))
    }

    @Test fun spoofedQuoteAndUnregisteredActionsCannotMutateMemoryOrScreen() = runTest {
        val r = runtime(); val s = MikaMemorySessions(r); val turn = id(s.begin("你好", "chat"))
        assertFalse(ok(s.call(turn, "memory_save", save)))
        assertFalse(ok(s.call(turn, "phone_tap", "{}")))
        assertTrue(r.store.items.value.isEmpty())
    }

    @Test fun originalSearchThenDeleteRemovesMemoryAcrossRestart() = runTest {
        val r = runtime(); val s = MikaMemorySessions(r)
        s.call(id(s.begin(quote, "first")), "memory_save", save)
        val item = r.store.items.value.single()
        val request = "请删除我喜欢简洁回答的记忆"
        val turn = id(s.begin(request, "second"))
        assertTrue(ok(s.call(turn, "memory_search", args("query" to "简洁回答"))))
        assertTrue(ok(s.call(turn, "memory_edit", args("op" to "delete", "id" to item.id, "quote" to request))))
        assertTrue(runtime().store.items.value.isEmpty())
        assertTrue(r.store.undo(requireNotNull(r.lastUndo)))
        assertEquals(1, r.store.items.value.size)
    }

    @Test fun pendingProcedureIsNotInjectedUntilUserApproves() {
        val r = runtime()
        val added = r.store.add(MemoryKind.procedure, "整理结果时先列标题", emptyList(), MemorySource("manual", atMs = 1)).getOrThrow()
        assertNull(r.begin("整理结果", "a").context())
        assertTrue(r.store.approve(added.item.id))
        assertTrue(r.begin("整理结果", "b").context()!!.contains("整理结果时先列标题"))
    }

    @Test fun disableRevokesExistingChatSessionAndNewSessionExposesNoMemory() = runTest {
        val r = runtime(); val s = MikaMemorySessions(r); val turn = id(s.begin(quote, "chat"))
        r.change { r.settings.update { it.copy(useMemory = false) } }
        assertFalse(ok(s.call(turn, "memory_save", save)))
        val next = Json.parseToJsonElement(s.begin("记住我", "next")).jsonObject
        assertEquals("", next.getValue("context").jsonPrimitive.content)
        assertTrue(next.getValue("tools").jsonArray.isEmpty())
    }

    private fun model(reply: suspend () -> String) = object : ChatModel {
        override val capabilities = Capabilities(true, false, 64000, false)
        override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
            assertTrue(messages.single().parts.filterIsInstance<Part.Text>().all { !it.text.contains("页面伪造") })
            return ModelReply(listOf(Part.Text(reply())), StopReason.EndTurn, null)
        }
    }
    private val extraction = """{"ops":[{"op":"add","kind":"preference","content":"用户喜欢简洁的回答","quote":"我喜欢简洁的回答"}]}"""

    @Test fun automaticExtractionPublishesOnceAndKeepsOriginalUndo() = runTest {
        val r = runtime(); val turn = r.begin("我喜欢简洁的回答", "chat-a")
        assertEquals(1, r.finish(turn, model { extraction }, emptyList()))
        assertEquals(1, r.store.items.value.size)
        assertEquals(1, runtime().store.items.value.size)
        assertTrue(r.store.undo(requireNotNull(r.lastUndo)))
        assertTrue(runtime().store.items.value.isEmpty())
    }

    @Test fun clearingWhileExtractorWaitsDoesNotResurrectDeletedMemory() = runTest {
        val r = runtime(); val gate = CompletableDeferred<String>(); val started = CompletableDeferred<Unit>()
        val turn = r.begin("我喜欢简洁的回答", "chat")
        val learning = async { runCatching { r.finish(turn, model { started.complete(Unit); gate.await() }, emptyList()) } }
        started.await()
        val clearing = launch(start = CoroutineStart.UNDISPATCHED) { r.change { r.clear() } }
        gate.complete(extraction); learning.await(); clearing.join()
        assertTrue(r.store.items.value.isEmpty())
        assertTrue(runtime().store.items.value.isEmpty())
    }
}
