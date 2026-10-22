package com.clawmate.app.gallery.vision

import com.clawmate.app.image.PreparedChatImage
import com.clawmate.app.gallery.model.GalleryPhotoItem
import java.util.Base64
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
class GalleryVisionTest {
    private val dispatcher = StandardTestDispatcher()
    private val config = GalleryVisionConfig.fromValues("https://example.invalid/v1", "fixture-key", "fixture-model")!!
    private val raw = """{"title":"咖啡杯","category":"生活","summary":"杯子上有“我的”字样","details":["绿色 杯子"],"tags":["咖啡"," 咖啡 ","杯子"]}"""
    private val result = GalleryVisionResponse.parse(raw)
    private val photo = GalleryPhotoItem("id", "content://fixture/image", "2026-10-23", "原图", "", "", createdAt = 1)
    @Before fun before() { Dispatchers.setMain(dispatcher) }
    @After fun after() { Dispatchers.resetMain() }

    @Test fun `strict response preserves quoted content and source compatible lists`() {
        assertEquals("杯子上有“我的”字样", result.summary)
        assertEquals(listOf("咖啡", "杯子"), result.tags)
        assertEquals(result.tags, result.keywords)
        assertEquals(listOf("绿色 杯子"), result.details)
        assertEquals(result, GalleryVisionResponse.parse("```json\n$raw\n```"))
    }

    @Test fun `missing malformed error and oversized fields never become successful analysis`() {
        for (input in listOf("broken", "[]", "{}", """{"error":"failed"}""", raw.replace("咖啡杯", ""),
            raw.replace("\"咖啡\",\" 咖啡 \",\"杯子\"", "123"), raw.replace("咖啡杯", "x".repeat(81)))) {
            assertTrue(input.take(60), runCatching { GalleryVisionResponse.parse(input) }.isFailure)
        }
    }

    @Test fun `gallery config validates independently of planner and redacts its key`() {
        assertNull(GalleryVisionConfig.fromValues("", "key", "model"))
        assertNull(GalleryVisionConfig.fromValues("https://example.invalid", "", "model"))
        assertNull(GalleryVisionConfig.fromValues("https://user:pass@example.invalid", "key", "model"))
        assertFalse(config.toString().contains("fixture-key"))
    }

    @Test fun `real vision HTTP contains only prepared pixels and bounded OCR context`() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(buildJsonObject {
                putJsonArray("choices") { add(buildJsonObject {
                    putJsonObject("message") { put("role", "assistant"); put("content", raw) }
                    put("finish_reason", "stop")
                }) }
            }.toString()))
            val cfg = GalleryVisionConfig.fromValues(server.url("/").toString(), "fixture-key", "fixture-model")!!
            val data = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))
            val actual = GalleryVisionClient.fromConfig(cfg, OkHttpClient()).analyze(PreparedChatImage(data, 1, 1, 3), "O".repeat(2999) + "😀tail")
            assertEquals(result, actual)
            val request = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("/v1/chat/completions", request.path)
            assertEquals("Bearer fixture-key", request.getHeader("Authorization"))
            val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
            val parts = body["messages"]!!.jsonArray.last().jsonObject["content"]!!.jsonArray
            val ocr = Json.parseToJsonElement(parts[0].jsonObject["text"]!!.jsonPrimitive.content).jsonObject["ocr_text"]!!.jsonPrimitive.content
            assertEquals("O".repeat(2999), ocr)
            assertEquals(data, parts[1].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content)
            assertFalse(body.toString().contains("content://"))
            assertFalse(body.toString().contains("fixture-key"))
        }
    }

    @Test fun `configuration and cancelled consent do not read an image or call a model`() = runTest {
        val missing = GalleryVisionViewModel({ null }, { _, _ -> error("no image read") }, { _, _ -> error("no save") })
        missing.request(photo); advanceUntilIdle()
        assertNotNull(missing.state.value.error)
        assertNull(missing.state.value.consent)
        val vm = GalleryVisionViewModel({ config }, { _, _ -> error("no upload before confirmation") }, { _, _ -> error("no save") })
        vm.request(photo); advanceUntilIdle()
        assertEquals("id", vm.state.value.consent!!.itemId)
        vm.dismiss(); vm.confirm(); advanceUntilIdle()
        assertNull(vm.state.value.preview)
        assertFalse(vm.state.value.busy)
    }

    @Test fun `changed destination requires fresh confirmation and keeps prior saved data untouched`() = runTest {
        var current = config
        val vm = GalleryVisionViewModel({ current }, { _, _ -> error("no upload to changed endpoint") }, { _, _ -> error("no save") })
        vm.request(photo); advanceUntilIdle()
        current = config.copy(model = "new-model")
        vm.confirm(); advanceUntilIdle()
        assertNotNull(vm.state.value.error)
        assertNull(vm.state.value.preview)
    }

    @Test fun `successful analysis is preview only until an explicit save`() = runTest {
        var saves = 0
        val vm = GalleryVisionViewModel({ config }, { _, item -> assertEquals(photo.uri, item.uri); result },
            { item, analysis -> saves++; assertEquals(photo.id, item.id); assertEquals(result, analysis); GalleryVisionSaveResult(true, false) })
        vm.request(photo); advanceUntilIdle(); vm.confirm(); advanceUntilIdle()
        assertEquals(0, saves)
        assertEquals(result, vm.state.value.preview)
        vm.request(photo); advanceUntilIdle(); vm.dismiss()
        assertEquals(result, vm.state.value.preview) // Opening/cancelling another attempt retains this preview.
        vm.savePreview(); advanceUntilIdle()
        assertEquals(1, saves)
        assertTrue(vm.state.value.saved)
        assertTrue(vm.state.value.notice!!.contains("已保存，分组暂未刷新"))
    }

    @Test fun `failed persistence never reports saved and can be retried without a second upload`() = runTest {
        var calls = 0
        val vm = GalleryVisionViewModel({ config }, { _, _ -> calls++; result }, { _, _ -> GalleryVisionSaveResult(false) })
        vm.request(photo); advanceUntilIdle(); vm.confirm(); advanceUntilIdle(); vm.savePreview(); advanceUntilIdle()
        assertFalse(vm.state.value.saved)
        assertNotNull(vm.state.value.error)
        assertEquals(result, vm.state.value.preview)
        assertEquals(1, calls)
    }

    @Test fun `stopping ignores a late old result and does not block a new picture`() = runTest {
        val release = CompletableDeferred<Unit>()
        val vm = GalleryVisionViewModel({ config }, { _, item ->
            if (item.id == "id") withContext(NonCancellable) { release.await(); result.copy(title = "旧结果") }
            else result.copy(title = "新结果")
        }, { _, _ -> GalleryVisionSaveResult(true) })
        vm.request(photo); advanceUntilIdle(); vm.confirm(); runCurrent()
        vm.stop()
        assertFalse(vm.state.value.busy)
        vm.request(photo.copy(id = "new", uri = "content://fixture/new")); runCurrent(); vm.confirm(); runCurrent()
        release.complete(Unit); advanceUntilIdle()
        assertEquals("new", vm.state.value.itemId)
        assertEquals("新结果", vm.state.value.preview!!.title)
    }
}
