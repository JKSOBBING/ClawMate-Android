package com.clawmate.platform.gui

import com.clawmate.platform.mika.MikaVisionConfig
import com.clawmate.platform.mika.mikaVisionModel
import com.wangzan101.mika.core.model.*
import com.wangzan101.mika.core.uistate.UiElement
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class WorkflowDeciderTest {
    private val entries = listOf(DeciderEntry("优惠券", listOf(0, 10, 999, 200)),
        DeciderEntry("购买记录", listOf(0, 220, 999, 400)))
    private fun click(box: String) = """{"action":"click","parameters":{"target_element":"购买记录","bbox":$box}}"""

    @Test fun shiftedHttpsModelBoxNeedsAUniqueMatchingLabelAndOverlappingNode() {
        val options = listOf(DeciderEntry("Time", listOf(78,174,922,306)),
            DeciderEntry("Mass", listOf(78,417,922,549)), DeciderEntry("Length", listOf(78,660,922,792)))
        val reply = """{"action":"click","parameters":{"target_element":"Length","bbox":[77,762,913,898]}}"""
        assertEquals(2, WorkflowDecider.resolve(reply, 540, 720, options))
        for (other in listOf(reply.replace("Length", "Mass"), reply.replace("762", "820"),
            reply.replace("[77,762,913,898]", "[78,400,910,550]"))) {
            assertThrows(Exception::class.java) { WorkflowDecider.resolve(other, 540, 720, options) }
        }
        assertThrows(Exception::class.java) { WorkflowDecider.resolve(reply, 540, 720, options + options.last()) }
    }

    @Test fun proposedClickMustResolveToExactlyOneCandidateInTheEncodedImage() {
        assertEquals(1, WorkflowDecider.resolve(click("[200,230,800,330]"), 100, 200, entries))
        assertEquals(1, WorkflowDecider.resolve("""{"action":"click","parameters":{"coords":[500,300]}}""", 720, 1280, entries))
        for (invalid in listOf(click("[900,0,1200,880]"), click("[10,700,100,850]"), click("[1,1,5,0]"),
                click("[0,0,99,\"100\"]"), """{"action":"done","parameters":{"status":"suspended"}}""")) {
            assertThrows(Exception::class.java) { WorkflowDecider.resolve(invalid, 100, 200, entries) }
        }
        assertThrows(Exception::class.java) { WorkflowDecider.resolve(click("[200,230,800,330]"), 100, 200, entries + entries.last()) }
    }

    @Test fun acceptsHarmonyParameterLayoutsAndStillChecksTheTarget() {
        for (fields in listOf(""""bbox":[200,230,800,330]""", """"coordinates":{"coords":[500,300]}""",
                """"parameters":{},"bbox":[200,230,800,330]""")) {
            assertEquals(1, WorkflowDecider.resolve("""{"action":"click",$fields}""", 582, 1280, entries))
        }
        val terminal = Json.parseToJsonElement("""{"action":"done","status":"success"}""").jsonObject
        assertEquals(JsonPrimitive("success"), WorkflowDecider.parameters(terminal)["status"])
        for (invalid in listOf("""{"action":"click","target_element":"购买记录"}""",
                """{"action":"click","parameters":["103,135,444,287","购买记录"]}""",
                """{"action":"click","parameters":"bad","bbox":[200,230,800,330]}""")) {
            assertThrows(Exception::class.java) { WorkflowDecider.resolve(invalid, 582, 1280, entries) }
        }
    }

    @Test fun onlyAnUnambiguousVisibleLabelEndsAGuiStepLocally() {
        val title = UiElement("@e1", "TextView", "Length conversion", 500, 80, listOf(100, 60, 900, 100),
            null, clickable = false, editable = false, scrollable = false)
        assertTrue(WorkflowDeciderStep.hasArrivalMarker(title.name, listOf(title)))
        assertFalse(WorkflowDeciderStep.hasArrivalMarker("", listOf(title)))
        assertFalse(WorkflowDeciderStep.hasArrivalMarker("Length", listOf(title)))
        assertFalse(WorkflowDeciderStep.hasArrivalMarker(title.name, listOf(title, title.copy(clickable = true))))
        for (element in listOf(title.copy(clickable = true), title.copy(editable = true), title.copy(hint = true), title.copy(secret = true))) {
            assertFalse(WorkflowDeciderStep.hasArrivalMarker(title.name, listOf(element)))
        }
    }

    @Test fun wireOmitsEmptyModelAndKeepsExplicitOverride() = runBlocking {
        MockWebServer().use { server ->
            for (name in listOf("", "custom-decider")) {
                server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}"""))
                val config = MikaVisionConfig(server.url("/v1").toString(), name, "decider-key")
                val message = Message(Role.User, listOf(Part.Text("goal"), Part.Image("/9j/2Q==", "image/jpeg"),
                    Part.Text("next action")), Author.Mika)
                mikaVisionModel(config, OkHttpClient(), guiAction = true).complete("one action", listOf(message), emptyList())
                val request = server.takeRequest()
                assertEquals("/v1/chat/completions", request.path)
                assertEquals("Bearer decider-key", request.getHeader("Authorization"))
                val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                assertEquals(name.takeIf(String::isNotEmpty), body["model"]?.jsonPrimitive?.content)
                assertFalse("tools" in body)
                assertEquals(256, body.getValue("max_tokens").jsonPrimitive.int)
                assertEquals(0.0, body.getValue("temperature").jsonPrimitive.double, 0.0)
                val parts = body.getValue("messages").jsonArray.last().jsonObject.getValue("content").jsonArray
                assertEquals(listOf("text", "image_url", "text"), parts.map { it.jsonObject.getValue("type").jsonPrimitive.content })
            }
        }
    }
}
