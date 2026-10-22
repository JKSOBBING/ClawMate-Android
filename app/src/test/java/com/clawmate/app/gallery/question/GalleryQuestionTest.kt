package com.clawmate.app.gallery.question

import com.clawmate.app.gallery.model.*
import com.clawmate.app.gallery.vision.GalleryVisionConfig
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GalleryQuestionTest {
    private val dispatcher = StandardTestDispatcher()
    private val photo = GalleryPhotoItem("private-item-id", "content://private/picture", "2026-10-23", "会议通知", "工作", "会议安排在15:00",
        details = listOf("测试可见通知"), tags = listOf("会议"), ocrText = "会议15:00", latitude = 12.34567, longitude = 23.45678, createdAt = 1)
    private val hit = GallerySearchHit(photo, 10, "标题命中")
    private val config = GalleryVisionConfig.fromValues("https://example.invalid", "fixture-key", "fixture-model")!!
    private val input = GalleryQuestionProtocol.prepare("会议时间", listOf(hit))
    private val raw = """{"answer":"会议记录显示15:00。","matchedIds":["photo_1"],"confidence":0.8}"""
    private val answer = GalleryQuestionProtocol.parse(raw, input)
    @Before fun before() { Dispatchers.setMain(dispatcher) }
    @After fun after() { Dispatchers.resetMain() }

    @Test fun `context is bounded deduplicated structured text without paths GPS or persistent identities`() {
        val many = (1..30).map { hit.copy(item = photo.copy(id = "private-$it", summary = "长内容".repeat(2000), details = List(4) { "细节".repeat(300) })) }
        val prepared = GalleryQuestionProtocol.prepare("会议", listOf(hit, hit) + many)
        assertTrue(prepared.evidence.size in 1..10)
        assertEquals(prepared.evidence.size, prepared.evidence.map { it.itemId }.distinct().size)
        assertTrue(prepared.payload.length <= GalleryQuestionProtocol.MAX_CONTEXT)
        assertFalse(prepared.payload.contains("content://")); assertFalse(prepared.payload.contains("private-"))
        assertFalse(prepared.payload.contains("12.34567")); assertFalse(prepared.payload.contains("23.45678"))
        assertEquals("photo_1", Json.parseToJsonElement(prepared.payload).jsonArray.first().jsonObject["id"]!!.jsonPrimitive.content)
        assertTrue(runCatching { GalleryQuestionProtocol.prepare("x".repeat(401), listOf(hit)) }.isFailure)
    }

    @Test fun `OCR clipping preserves surrogate pairs and empty search input has no candidates`() {
        val bounded = GalleryQuestionProtocol.prepare("会议", listOf(hit.copy(item = photo.copy(ocrText = "O".repeat(239) + "😀tail"))))
        assertEquals("O".repeat(239), Json.parseToJsonElement(bounded.payload).jsonArray.single().jsonObject["ocr_text"]!!.jsonPrimitive.content)
        assertTrue(GalleryQuestionProtocol.prepare("会议", listOf(hit.copy(score = 0))).evidence.isEmpty())
    }

    @Test fun `only valid bounded answers with known citations resolve to a local image`() {
        assertEquals(photo.id, answer.matches.single().itemId)
        assertEquals(answer, GalleryQuestionProtocol.parse("```json\n$raw\n```", input))
        for (bad in listOf("plain answer", raw.replace("photo_1", "invented"), raw.replace("0.8", "2.0"),
            raw.replace("0.8", "\"0.8\""), raw.replace("会议记录显示15:00。", "x".repeat(2001)))) {
            assertTrue(runCatching { GalleryQuestionProtocol.parse(bad, input) }.isFailure)
        }
    }

    @Test fun `empty evidence responses cannot display unattributed factual claims`() {
        val empty = GalleryQuestionProtocol.parse("""{"answer":"未经引用的金额123元","matchedIds":[],"confidence":0}""", input)
        assertEquals(GalleryQuestionProtocol.INSUFFICIENT, empty.answer)
        assertTrue(empty.matches.isEmpty())
        assertTrue(runCatching { GalleryQuestionProtocol.parse(raw.replace("[\"photo_1\"]", "[]"), input) }.isFailure)
    }

    @Test fun `real question HTTP sends candidate text only and requires a complete answer`() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start()
            fun response(reason: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(buildJsonObject {
                putJsonArray("choices") { add(buildJsonObject {
                    putJsonObject("message") { put("role", "assistant"); put("content", raw) }; put("finish_reason", reason)
                }) }
            }.toString())
            server.enqueue(response("stop")); server.enqueue(response("length"))
            val client = GalleryQuestionClient.fromConfig(GalleryVisionConfig.fromValues(server.url("/").toString(), "fixture-key", "fixture-model")!!, OkHttpClient())
            assertEquals(answer, client.answer(input))
            val request = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("/v1/chat/completions", request.path)
            assertEquals("Bearer fixture-key", request.getHeader("Authorization"))
            val body = request.body.readUtf8()
            assertFalse(body.contains("data:image")); assertFalse(body.contains("content://")); assertFalse(body.contains(photo.id))
            val user = Json.parseToJsonElement(body).jsonObject["messages"]!!.jsonArray.last().jsonObject["content"]!!.jsonPrimitive.content
            assertEquals("会议时间", Json.parseToJsonElement(user).jsonObject["question"]!!.jsonPrimitive.content)
            assertTrue(runCatching { client.answer(input) }.isFailure)
        }
    }

    @Test fun `missing config empty hits and dismissed consent never call a model`() = runTest {
        val missing = GalleryQuestionViewModel({ null }, { error("no search") }, { _, _ -> error("no upload") })
        missing.updateQuestion("会议"); missing.request(); advanceUntilIdle(); assertNotNull(missing.state.value.error)
        val empty = GalleryQuestionViewModel({ config }, { emptyList() }, { _, _ -> error("no upload") })
        empty.updateQuestion("会议"); empty.request(); advanceUntilIdle(); assertNotNull(empty.state.value.notice); assertNull(empty.state.value.consent)
        val vm = GalleryQuestionViewModel({ config }, { listOf(hit) }, { _, _ -> error("no upload before confirm") })
        vm.updateQuestion("会议"); vm.request(); advanceUntilIdle(); assertNotNull(vm.state.value.consent)
        vm.dismiss(); vm.confirm(); advanceUntilIdle(); assertNull(vm.state.value.answer)
    }

    @Test fun `config changes require confirmation and failed refresh retains the prior answer and its question`() = runTest {
        var current = config; var fail = false; var calls = 0
        val vm = GalleryQuestionViewModel({ current }, { listOf(hit) }, { _, _ -> calls++; if (fail) error("fixture failure") else answer })
        vm.updateQuestion("会议时间"); vm.request(); advanceUntilIdle(); vm.confirm(); advanceUntilIdle()
        assertEquals(answer, vm.state.value.answer)
        vm.updateQuestion("新的会议问题"); vm.request(); advanceUntilIdle()
        current = config.copy(model = "changed-model")
        vm.confirm(); advanceUntilIdle(); assertEquals(1, calls)
        fail = true; vm.request(); advanceUntilIdle(); vm.confirm(); advanceUntilIdle()
        assertEquals(2, calls); assertEquals(answer, vm.state.value.answer)
        assertEquals("会议时间", vm.state.value.answeredQuestion)
        assertNotNull(vm.state.value.error)
    }

    @Test fun `late cancelled failure cannot overwrite a newer answer`() = runTest {
        val late = CompletableDeferred<Unit>(); var calls = 0
        val vm = GalleryQuestionViewModel({ config }, { listOf(hit) }, { _, _ ->
            if (calls++ == 0) withContext(NonCancellable) { late.await(); error("late old failure") }
            answer
        })
        vm.updateQuestion("旧问题"); vm.request(); advanceUntilIdle(); vm.confirm(); runCurrent()
        vm.stop(); vm.updateQuestion("新问题"); vm.request(); advanceUntilIdle(); vm.confirm(); advanceUntilIdle()
        late.complete(Unit); advanceUntilIdle()
        assertEquals(answer, vm.state.value.answer); assertEquals("新问题", vm.state.value.answeredQuestion)
        assertNull(vm.state.value.error); assertFalse(vm.state.value.busy)
    }

    @Test fun `editing and clearing discard pending consent without sending the old question`() = runTest {
        val vm = GalleryQuestionViewModel({ config }, { listOf(hit) }, { _, _ -> error("no upload") })
        vm.updateQuestion("会议"); vm.request(); advanceUntilIdle()
        vm.updateQuestion("改过的问题"); vm.confirm(); advanceUntilIdle(); assertNull(vm.state.value.answer)
        vm.request(); advanceUntilIdle(); vm.clear(); vm.confirm(); advanceUntilIdle()
        assertEquals(GalleryQuestionState(), vm.state.value)
    }
}
