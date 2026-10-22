package com.clawmate.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.clawmate.app.chat.ChatComponent
import com.clawmate.app.chat.agent.*
import com.clawmate.app.datamanager.*
import com.clawmate.app.datamanager.LocalConsolidationTypes.EventPartition
import com.clawmate.app.datamanager.LocalConsolidationTypes.IndexedEvent
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Executes the real Android storage/tool path over test-owned cache fixtures. */
@RunWith(AndroidJUnit4::class)
class QueryEventsDeviceTest {
    @Test fun productionChatRegistersMemoryQuery() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertNotNull(ChatComponent.tools(context).find("query_events"))
    }

    @Test fun queryReadsRealAndroidFilesAndReturnsStructuredFacts() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val base = Files.createTempDirectory(context.cacheDir.toPath(), "query-events-test-").toFile()
        try {
            val tool = createQueryEventsTool(DailyLogLocalIndex(base))
            val missing = Json.parseToJsonElement(tool.execute("{}", ToolExecutionContext())).jsonObject
            assertFalse(missing["ok"]!!.jsonPrimitive.boolean)
            DataManagerPaths.eventsDir(base).mkdirs()
            DataManagerPaths.eventPartition(base, "2026-10").writeText(LocalConsolidationCodec.encodePartition(EventPartition(
                month = "2026-10", events = listOf(IndexedEvent(eventId = "fixture-internal", eventDate = "2026-10-20",
                    summary = "设备测试：咖啡订单", domains = listOf("eat"), sources = listOf("DeviceTest"))),
            )))
            val result = Json.parseToJsonElement(tool.execute("""{"keywords":["咖啡"],"domains":["eat"],"month":"2026-10"}""", ToolExecutionContext())).jsonObject
            assertTrue(result["ok"]!!.jsonPrimitive.boolean)
            val event = result["events"]!!.jsonArray.single().jsonObject
            assertEquals("设备测试：咖啡订单", event["summary"]!!.jsonPrimitive.content)
            assertEquals(0, event["event_id"]!!.jsonPrimitive.int)
            assertFalse(result.toString().contains("fixture-internal"))
        } finally {
            base.deleteRecursively()
        }
    }
}
