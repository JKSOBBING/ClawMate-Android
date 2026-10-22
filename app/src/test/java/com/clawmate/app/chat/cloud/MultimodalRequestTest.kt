package com.clawmate.app.chat.cloud

import java.util.Base64
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

/** Tiny byte fixtures verify wire encoding only, not visual recognition. Real JPEGs are tested on-device. */
class MultimodalRequestTest {
    private val data = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))
    private fun request(images: List<String> = listOf(data)) = ChatCompletionsRequest("fixture-model",
        listOf(ChatMessageWire("system", "policy"), ChatMessageWire("user", "看看这张图", imageUrls = images)), false)

    @Test fun `ordinary text and tool serialization remains byte for byte unchanged`() {
        val request = ChatCompletionsRequest("model", listOf(ChatMessageWire("assistant", null,
            toolCalls = listOf(ToolCallWire("id", function = ToolCallFunctionWire("query_events", "{}")))),
            ChatMessageWire("tool", "result", toolCallId = "id")), true)
        assertEquals(WireJson.json.encodeToString(ChatCompletionsRequest.serializer(), request), encodeChatCompletionsRequest(request))
    }

    @Test fun `prepared image data becomes typed image content without exposing adapter fields`() {
        val body = Json.parseToJsonElement(encodeChatCompletionsRequest(request())).jsonObject
        val message = body["messages"]!!.jsonArray.last().jsonObject
        val parts = message["content"]!!.jsonArray
        assertEquals("text", parts[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("看看这张图", parts[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals(data, parts[1].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content)
        assertFalse(body.toString().contains("imageUrls"))
        assertEquals("policy", body["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test fun `unsupported paths encodings sizes roles and image counts fail before sending`() {
        val invalid = listOf("https://example.invalid/image.jpg", "content://media/private", "file:///private/photo.jpg",
            "data:text/html;base64,AQID", "data:image/jpeg;base64,@@@", "data:image/jpeg;base64,",
            "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(ByteArray(ChatImageBounds.MAX_IMAGE_BYTES + 1)))
        invalid.forEach { assertTrue(it.take(50), runCatching { encodeChatCompletionsRequest(request(listOf(it))) }.isFailure) }
        assertTrue(runCatching { encodeChatCompletionsRequest(request(List(5) { data })) }.isFailure)
        assertTrue(runCatching { encodeChatCompletionsRequest(request().copy(messages = listOf(ChatMessageWire("assistant", "text", imageUrls = listOf(data))))) }.isFailure)
    }

    @Test fun `real completion and streaming clients both transmit multimodal content`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""{"choices":[{"message":{"role":"assistant","content":"fixture response"},"finish_reason":"stop"}]}"""))
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"stream\"},\"finish_reason\":null}]}\n\n" +
                    "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n" + "data: [DONE]\n\n"))
            val client = ChatCompletionsClient(OkHttpClient(), ChatServerConfig(server.url("/").toString(), "fixture-key", "fixture-model"))
            assertEquals("fixture response", client.completion(request()).content)
            assertEquals("stream", client.completionStream(request()).toList().joinToString("") { it.contentDelta })
            repeat(2) { index ->
                val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
                assertEquals(index == 1, body["stream"]!!.jsonPrimitive.boolean)
                assertEquals(data, body["messages"]!!.jsonArray.last().jsonObject["content"]!!.jsonArray[1]
                    .jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content)
            }
        }
    }
}
