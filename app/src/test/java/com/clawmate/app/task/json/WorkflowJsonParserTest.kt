package com.clawmate.app.task.json

import com.clawmate.app.task.model.WorkflowStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic tests for the tolerant workflow JSON parser: metadata/defaults/
 * context/steps parsing, nested `if`/`loop` handling, unknown-step preservation,
 * and tolerance to malformed input.
 */
class WorkflowJsonParserTest {

    private val simple = """
        {
          "metadata": { "name": "basic-gui-task", "description": "查看饿了么上的用户订单信息" },
          "defaults": { "device": "Harmony", "use_qwen3": true },
          "context": { "contact_name": "小赵" },
          "steps": [
            { "id": 1, "type": "gui_task", "task_description": "打开饿了么", "on_error": "stop" }
          ]
        }
    """.trimIndent()

    @Test
    fun `parses metadata defaults context and flat steps`() {
        val result = WorkflowJsonParser.parse(simple)
        assertTrue("expected Ok, got $result", result is WorkflowJsonParser.Result.Ok)
        val def = (result as WorkflowJsonParser.Result.Ok).definition
        assertEquals("basic-gui-task", def.metadata?.name)
        assertEquals("查看饿了么上的用户订单信息", def.metadata?.description)
        assertEquals("Harmony", def.defaults?.device)
        assertEquals(true, def.defaults?.useQwen3)
        assertEquals("小赵", def.context["contact_name"]?.toString()?.trim('"'))
        assertEquals(1, def.steps.size)
        val step = def.steps[0]
        assertEquals("1", step.id)
        assertEquals("gui_task", step.type)
        assertEquals("打开饿了么", step.taskDescription)
    }

    @Test
    fun `preserves unknown step type with raw json`() {
        val json = """
            { "steps": [
               { "id": 1, "type": "future_step", "cool": 42 }
            ] }
        """.trimIndent()
        val def = WorkflowJsonParser.parseOrDefault(json)
        assertEquals(1, def.steps.size)
        val step = def.steps[0]
        assertEquals("future_step", step.type)
        assertEquals(42, step.raw["cool"]?.toString()?.let { if (it == "42") 42 else null })
        assertNotNull("unknown step raw json must be preserved", step.raw["cool"])
    }

    @Test
    fun `parses nested loop with inner if and branch steps`() {
        val json = """
            { "steps": [
                { "id": 3, "type": "loop", "times": 5, "steps": [
                    { "id": 1, "type": "gui_action", "action": "screenshot", "file_name": "chat_${'$'}{loop.index}.jpg" },
                    { "id": 3, "type": "if", "condition": { "left": "${'$'}{loop.last}", "operator": "==", "right": false },
                      "then_steps": [ { "id": 1, "type": "gui_action", "action": "swipe_with_coords", "start_x": 540 } ],
                      "else_steps": [] }
                ] }
            ] }
        """.trimIndent()
        val def = WorkflowJsonParser.parseOrDefault(json)
        assertEquals(1, def.steps.size)
        val loop = def.steps[0]
        assertEquals("loop", loop.type)
        assertEquals(5L, loop.times)
        assertEquals(2, loop.steps.size)
        val innerIf = loop.steps[1]
        assertEquals("if", innerIf.type)
        assertEquals("==", innerIf.condition?.operator)
        assertEquals("${'$'}{loop.last}", (innerIf.condition?.left as? kotlinx.serialization.json.JsonPrimitive)?.content)
        assertEquals(1, innerIf.thenSteps.size)
        assertEquals("swipe_with_coords", innerIf.thenSteps[0].action)
    }

    @Test
    fun `malformed json degrades to Err not crash`() {
        val result = WorkflowJsonParser.parse("{ not valid json at all ")
        assertTrue("expected Err, got $result", result is WorkflowJsonParser.Result.Err)
    }

    @Test
    fun `repairs missing braces and literal casing`() {
        val repaired = WorkflowJsonParser.parse("""{"steps":[{"id":1,"type":"gui_task","done":TRUE}]""")
        assertTrue("expected Ok, got $repaired", repaired is WorkflowJsonParser.Result.Ok)
        val def = (repaired as WorkflowJsonParser.Result.Ok).definition
        assertEquals(1, def.steps.size)
    }

    @Test
    fun `empty or non-object root degrades gracefully`() {
        assertTrue(WorkflowJsonParser.parse("") is WorkflowJsonParser.Result.Err)
        assertTrue(WorkflowJsonParser.parse("[]") is WorkflowJsonParser.Result.Err)
        val def = WorkflowJsonParser.parseOrDefault("")
        assertTrue(def.steps.isEmpty())
    }

    @Test
    fun `times may be string and condition may be boolean`() {
        val json = """
            { "steps": [ { "id": 1, "type": "loop", "times": "3", "steps": [] } ] }
        """.trimIndent()
        val def = WorkflowJsonParser.parseOrDefault(json)
        assertEquals(3L, def.steps[0].times)
    }
}
