package com.clawmate.app.chat.agent

import com.clawmate.app.chat.cloud.ToolCallFunctionWire
import com.clawmate.app.chat.cloud.ToolCallWire
import com.clawmate.app.datamanager.*
import com.clawmate.app.datamanager.LocalConsolidationTypes.EventPartition
import com.clawmate.app.datamanager.LocalConsolidationTypes.IndexedEvent
import com.clawmate.app.datamanager.LocalConsolidationTypes.QueryResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class QueryEventsToolTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun `arguments normalize and retain source contract defaults`() {
        val defaults = parseQueryEventsOptions("{}")
        assertEquals(20, defaults.limit)
        assertEquals("and", defaults.keywordOperator)
        assertTrue(defaults.includeTodoDeadlineInTimeRange)
        val options = parseQueryEventsOptions("""{"keywords":[" 咖啡 ","咖啡","会议"],"keyword_operator":"OR",
            "domains":["EAT"],"entities":[{"type":"PERSON","name":" Alice "}],"month":"2026-10","limit":0}""")
        assertEquals(listOf("咖啡", "会议"), options.keywords)
        assertEquals("or", options.keywordOperator)
        assertEquals(listOf("eat"), options.domains)
        assertEquals(listOf("person:Alice"), options.entities)
        assertEquals(Int.MAX_VALUE, options.limit)
    }

    @Test fun `invalid and conflicting arguments are rejected before reading data`() {
        listOf("[]", "{bad}", """{"path":"/private"}""", """{"keywords":"coffee"}""", """{"keywords":[2]}""",
            """{"keyword_operator":"xor"}""", """{"domains":["unknown"]}""", """{"entities":[{"type":"company","name":"x"}]}""",
            """{"entities":[{"type":"person","name":"x","path":"y"}]}""", """{"entities":[{}]}""",
            """{"date":"2026-02-30"}""", """{"month":"2026-13"}""", """{"date":"2026-10-01","month":"2026-10"}""",
            """{"month":"2026-10","start_date":"2026-10-01"}""", """{"start_date":"2026-10-02","end_date":"2026-10-01"}""",
            """{"limit":-1}""", """{"limit":1.5}""", """{"limit":"2"}""", """{"date":null}""",
            " ".repeat(16_001),
        ).forEach { assertTrue(it.take(100), runCatching { parseQueryEventsOptions(it) }.isFailure) }
        assertEquals("2024-02-29", parseQueryEventsOptions("""{"date":"2024-02-29"}""").date)
    }

    @Test fun `missing index and invalid arguments return actionable JSON without creating files`() = runBlocking {
        val tool = createQueryEventsTool(DailyLogLocalIndex(folder.root))
        for (arguments in listOf("{}", """{"date":"bad"}""")) {
            val response = Json.parseToJsonElement(tool.execute(arguments, ToolExecutionContext())).jsonObject
            assertEquals(false, response["ok"]!!.jsonPrimitive.boolean)
            assertTrue(response["error"]!!.jsonPrimitive.content.isNotBlank())
        }
        assertTrue(folder.root.listFiles()!!.isEmpty())
    }

    @Test fun `registered tool executes against the persisted index and hides internal references`() = runBlocking {
        val base = folder.root
        DataManagerPaths.eventsDir(base).mkdirs()
        DataManagerPaths.eventPartition(base, "2026-10").writeText(LocalConsolidationCodec.encodePartition(EventPartition(
            month = "2026-10", events = listOf(IndexedEvent(eventId = "private-internal-id", sourceStateFile = "/private/state.json",
                sourceRefs = listOf("/private/source.md#1"), eventDate = "2026-10-20", summary = "咖啡订单", normalizedFact = "花费20元",
                domains = listOf("eat"), sources = listOf("DailyLog"), todoType = "reminder", todoDeadline = "2026-10-23", todoText = "取咖啡")),
        )))
        val registry = createDefaultToolRegistry().apply { register(createQueryEventsTool(DailyLogLocalIndex(base))) }
        assertTrue(registry.openAiTools().any { it.function.name == "query_events" })
        val result = registry.execute(ToolCallWire(id = "query-1", function = ToolCallFunctionWire("query_events", """{"keywords":["咖啡"]}""")), ToolExecutionContext())
        assertTrue(result.success)
        val response = Json.parseToJsonElement(result.truncatedContent).jsonObject
        assertTrue(response["ok"]!!.jsonPrimitive.boolean)
        val event = response["events"]!!.jsonArray.single().jsonObject
        assertEquals(0, event["event_id"]!!.jsonPrimitive.int)
        assertEquals("咖啡订单", event["summary"]!!.jsonPrimitive.content)
        assertEquals("花费20元", event["detail"]!!.jsonPrimitive.content)
        assertEquals("2026-10-23", event["todo"]!!.jsonObject["deadline"]!!.jsonPrimitive.content)
        assertFalse(result.content.contains("/private"))
        assertFalse(result.content.contains("private-internal-id"))
    }

    @Test fun `large query stays valid JSON inside generic registry text cap`() {
        val events = (0..30).map { IndexedEvent(eventId = "id-$it", eventDate = "2026-10-01", summary = "\"长文\"".repeat(300)) }
        val response = queryEventsResponse(QueryResult(events.size, events, false))
        assertTrue(response.length <= QUERY_EVENTS_RESULT_BUDGET)
        assertEquals(response, truncateResult(response))
        val root = Json.parseToJsonElement(response).jsonObject
        assertTrue(root["events_truncated"]!!.jsonPrimitive.boolean)
        assertTrue(root["events"]!!.jsonArray.isNotEmpty())
        root["events"]!!.jsonArray.forEachIndexed { position, event ->
            assertEquals(position, event.jsonObject["event_id"]!!.jsonPrimitive.int)
            assertTrue(event.jsonObject["content_truncated"]!!.jsonPrimitive.boolean)
        }
    }
}
