package com.clawmate.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.clawmate.app.chat.agent.ToolExecutionContext
import com.clawmate.app.chat.agent.createQueryEventsTool
import com.clawmate.app.chat.model.*
import com.clawmate.app.datamanager.DailyLogLocalIndex
import com.clawmate.app.datamanager.LocalConsolidationIndexRunner
import com.clawmate.app.datamanager.chatmemory.*
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Test-owned cache and labelled extractor fixture; no real model call or user database mutation. */
@RunWith(AndroidJUnit4::class)
class ChatMemoryDeviceTest {
    @Test fun chatFactsPersistAcrossRecreationAndBecomeQueryableAfterIndexRefresh() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val base = Files.createTempDirectory(context.cacheDir.toPath(), "chat-memory-test-").toFile()
        val now = Instant.parse("2026-10-23T00:00:00Z").toEpochMilli()
        val message = ChatMessage("device-fixture", ChatMessageRole.USER, ChatMessageKind.CHAT,
            "设备测试：我喜欢无糖咖啡", emptyList(), false, now)
        val session = ChatSession("device-fixture-session", "设备测试", ChatMode.CLOUD_AGENT, now, now, listOf(message))
        var calls = 0
        try {
            val source = ChatMemorySessionSource { listOf(session) }
            val fixture = ChatMemoryExtractor { inputs ->
                calls++
                inputs.map { ChatMemoryResult(it.sourceRef, listOf(ChatMemoryFact(12, it.userText))) }
            }
            val first = ChatMemorySync(base, source, fixture, zone = ZoneId.of("UTC")).sync(true, now)
            assertEquals(1, first.factCount)
            val recreated = ChatMemorySync(base, source, fixture, zone = ZoneId.of("UTC")).sync(true, now)
            assertEquals(0, recreated.processedParts)
            assertEquals(1, calls)
            assertEquals(1, FileChatMemoryStorage(base).readState().receipts.size)
            val index = LocalConsolidationIndexRunner(base).run()
            assertEquals(1, index.indexedEvents)
            val tool = createQueryEventsTool(DailyLogLocalIndex(base))
            val result = Json.parseToJsonElement(tool.execute("""{"keywords":["无糖咖啡"]}""", ToolExecutionContext())).jsonObject
            assertTrue(result["ok"]!!.jsonPrimitive.boolean)
            assertEquals(1, result["events"]!!.jsonArray.size)
            assertTrue(result.toString().contains("无糖咖啡"))
        } finally {
            base.deleteRecursively()
        }
    }
}
