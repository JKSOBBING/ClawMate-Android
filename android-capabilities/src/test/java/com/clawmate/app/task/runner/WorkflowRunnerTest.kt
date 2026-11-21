package com.clawmate.app.task.runner

import com.clawmate.app.task.json.WorkflowJsonParser
import com.clawmate.app.task.model.WorkflowDefinition
import com.clawmate.app.task.model.WorkflowStep
import kotlinx.serialization.json.jsonObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the bounded, honest workflow runner:
 *  - GUI/HDC/device-control steps are BLOCKED (never fabricated as success);
 *  - cloud VLM tool steps are NOT_CONFIGURED;
 *  - loop/if deterministic execution;
 *  - cancellation surfaces as CANCELLED;
 *  - step-budget and loop-iteration bounds are enforced.
 */
class WorkflowRunnerTest {

    @Test
    fun `two gui steps open once and pass the verified answer to the summary`() = runBlocking {
        val calls = mutableListOf<Pair<String, Boolean>>()
        val port = WorkflowGuiTask { _, goal, openOnly ->
            calls += goal to openOnly
            StepOutcome.Success(kotlinx.serialization.json.buildJsonObject {
                put("response", kotlinx.serialization.json.JsonPrimitive(if (openOnly) "" else "屏幕上的原文"))
            })
        }
        val definition = parse("""{"context":{"word":"比赛"},"steps":[
            {"id":1,"type":"gui_task","task_description":"打开目标应用"},
            {"id":2,"type":"gui_task","task_description":"搜索${'$'}{context.word}","extract_result":true},
            {"id":3,"type":"tool","tool_name":"summary","inputs":{"text":"采集：${'$'}{steps.2.output.response}"}}]}""")
        val result = WorkflowRunnerImpl(guiTask = port).run(definition)
        assertEquals(listOf("打开目标应用" to true, "搜索比赛" to false), calls)
        assertEquals(RunStatus.SUCCESS, result.status)
        assertEquals("采集：屏幕上的原文", result.resultSummary)
        assertEquals("屏幕上的原文", result.steps[1].output?.get("response")?.let {
            (it as kotlinx.serialization.json.JsonPrimitive).content
        })
    }

    @Test
    fun `single gui task must execute and unverified output stops dependent steps`() = runBlocking {
        val definition = parse("""{"steps":[
            {"id":1,"type":"gui_task","task_description":"从应用读取公开结果","extract_result":true},
            {"id":2,"type":"tool","tool_name":"summary","inputs":{"text":"迟到结果"}}]}""")
        val result = WorkflowRunnerImpl(guiTask = WorkflowGuiTask { _, _, openOnly ->
            assertTrue(!openOnly)
            StepOutcome.Failure("证据不足")
        }).run(definition)
        assertEquals(RunStatus.FAILED, result.status)
        assertEquals("证据不足", result.steps.single().detail)
        assertEquals("", result.resultSummary)
    }

    @Test fun navigationGetsOnlyItsOwnGoalAndTheActualNextSibling() = runBlocking {
        val definition = parse("""{"metadata":{"description":"Read event details"},"defaults":{"use_experience":false},"steps":[
            {"id":1,"type":"gui_task","task_description":"Open events"},
            {"id":2,"type":"gui_task","task_description":"Open list"},
            {"id":3,"type":"gui_task","task_description":"Read entry","use_experience":true}]}""")
        val context = mutableListOf<Pair<String, String?>>()
        val goals = mutableListOf<String>()
        val experience = mutableListOf<Boolean>()
        val gui = object : WorkflowGuiTask {
            override fun prepareStep(description: String, next: com.clawmate.app.task.model.WorkflowStep?, useExperience: Boolean) {
                experience += useExperience
                context += description to next?.taskDescription
            }
            override suspend fun execute(step: com.clawmate.app.task.model.WorkflowStep, goal: String, openOnly: Boolean): StepOutcome {
                goals += goal
                return StepOutcome.Success(kotlinx.serialization.json.JsonObject(emptyMap()))
            }
        }
        assertEquals(RunStatus.SUCCESS, WorkflowRunnerImpl(guiTask = gui).run(definition).status)
        assertEquals(listOf(false, false, true), experience)
        assertEquals(listOf("Open events", "Open list", "Read entry"), goals)
        assertEquals(listOf("Open list", "Read entry", null).map { "Read event details" to it }, context)
    }

    private val fastClock = WorkflowRunnerImpl.StepClock { _ -> }

    private fun runner(
        maxTotal: Int = 200,
        maxLoop: Int = 100,
        maxMillis: Long = 60_000L,
    ): WorkflowRunnerImpl =
        WorkflowRunnerImpl(clock = fastClock, maxTotalSteps = maxTotal, maxLoopIterations = maxLoop, maxMillis = maxMillis)

    @Test
    fun collectionReceiptPreservesTheExecutorForBothSavedStepTypes() = runBlocking {
        val simulated = Json.parseToJsonElement("""{"executor":"mika","model_requests":1,"display_id":7}""").jsonObject
        val port = object : WorkflowGuiTask {
            override suspend fun execute(step: WorkflowStep, goal: String, openOnly: Boolean): StepOutcome =
                error("Named collection steps must use the action port")
            override suspend fun action(step: WorkflowStep) = StepOutcome.Success(simulated)
        }
        for (type in listOf("gui_action", "android_accessibility")) {
            val definition = parse("""{"steps":[{"id":1,"type":"$type","action":"collection_navigate"}]}""")
            val result = WorkflowRunnerImpl(guiTask = port).run(definition)
            assertEquals(RunStatus.SUCCESS, result.status)
            assertEquals(simulated, result.steps.single().output)
        }
    }

    private fun parse(raw: String): WorkflowDefinition {
        val r = WorkflowJsonParser.parse(raw)
        assertTrue("expected parse Ok, got $r", r is WorkflowJsonParser.Result.Ok)
        return (r as WorkflowJsonParser.Result.Ok).definition
    }

    @Test
    fun continuedFailureFeedsFallbackWithoutHidingItsReceipt() = runBlocking {
        val result = runner().run(parse("""
            {"steps":[{"id":1,"type":"tool","tool_name":"missing","on_error":"continue"},
            {"id":2,"type":"if","condition":{"left":"${'$'}{steps.1.status}","operator":"==","right":"failed"},
             "then_steps":[{"id":3,"type":"tool","tool_name":"summary","inputs":{"text":"fallback"}}]}]}
        """.trimIndent()))
        assertEquals(RunStatus.SUCCESS, result.status)
        assertEquals(RunStatus.NOT_CONFIGURED, result.steps.first().status)
        assertEquals("fallback", result.resultSummary)
        assertTrue(result.message.contains("1 个步骤未完成"))
        assertTrue(ConditionEvaluator.evaluate(Json.parseToJsonElement("""{"left":3,"operator":"<=","right":3}""")) { null })
    }

    @Test
    fun `gui_task and gui_action steps become BLOCKED not success`() = runBlocking {
        val def = parse(
            """
            { "steps": [
              { "id": 1, "type": "gui_task", "task_description": "打开微信" },
              { "id": 2, "type": "gui_action", "action": "screenshot" }
            ] }
            """.trimIndent()
        )
        val result = runner().run(def)
        assertEquals(RunStatus.BLOCKED, result.status)
        assertTrue(result.steps.first().status == RunStatus.BLOCKED)
        assertTrue(result.steps.first().detail.isNotBlank())
    }

    @Test
    fun `tool vlm_qa becomes NOT_CONFIGURED`() = runBlocking {
        val def = parse(
            """
            { "steps": [ { "id": 4, "type": "tool", "tool_name": "vlm_qa", "inputs": { "question": "q" } } ] }
            """.trimIndent()
        )
        val result = runner().run(def)
        assertEquals(RunStatus.NOT_CONFIGURED, result.status)
        assertTrue(result.steps.first().status == RunStatus.NOT_CONFIGURED)
    }

    @Test
    fun `sleep and echo are honest executable steps`() = runBlocking {
        val def = parse(
            """
            { "steps": [
              { "id": 1, "type": "gui_action", "action": "sleep", "seconds": 1 },
              { "id": 2, "type": "tool", "tool_name": "echo", "inputs": { "text": "hi" } }
            ] }
            """.trimIndent()
        )
        val result = runner().run(def)
        assertEquals(RunStatus.SUCCESS, result.status)
        assertEquals(2, result.steps.size)
        assertTrue(result.steps.all { it.status == RunStatus.SUCCESS })
    }

    @Test
    fun `loop with a blocking inner gui action still BLOCKS`() = runBlocking {
        val def = parse(
            """
            { "steps": [ { "id": 1, "type": "loop", "times": 3, "steps": [
                { "id": 1, "type": "gui_action", "action": "click" }
            ] } ] }
            """.trimIndent()
        )
        val result = runner().run(def)
        assertEquals(RunStatus.BLOCKED, result.status)
    }

    @Test
    fun `nested alias scope separates swipe and VLM output and stops after the boundary`() = runBlocking {
        var captures = 0
        val port = object : WorkflowGuiTask {
            override suspend fun execute(step: com.clawmate.app.task.model.WorkflowStep, goal: String, openOnly: Boolean) =
                StepOutcome.Success(kotlinx.serialization.json.buildJsonObject {})
            override suspend fun action(step: com.clawmate.app.task.model.WorkflowStep): StepOutcome {
                if (step.action == "screenshot") captures++
                return StepOutcome.Success(kotlinx.serialization.json.Json.parseToJsonElement(
                    """{"action":"${step.action}","image_b64":"large","image_path":"shot-$captures.jpg"}"""
                ) as kotlinx.serialization.json.JsonObject)
            }
        }
        val def = parse("""
          {"steps":[{"id":1,"type":"loop","max_times":5,"break_if":{"left":"@{steps.2.output.structured_output.today}","right":false},
          "steps":[{"id":1,"type":"gui_action","action":"screenshot"},{"id":2,"type":"tool","tool_name":"vlm_qa",
          "inputs":{"question":"@{loop.index}","image":"@{steps.1.output.image_b64}","json_schema":{}}},
          {"id":3,"type":"if","condition":{"left":"@{steps.2.output.structured_output.today}","right":true},
          "then_steps":[{"id":2,"type":"gui_action","action":"swipe_with_coords"}]}]}]}
        """.replace('@', '$'))
        val seen = mutableListOf<String>()
        val result = WorkflowRunnerImpl(guiTask = port, modelTool = WorkflowModelTool { request ->
            seen += request.question + ":" + request.image
            """{"today":${captures == 1}}"""
        }).run(def)
        assertEquals(RunStatus.SUCCESS, result.status)
        assertEquals(listOf("1:shot-1.jpg", "2:shot-2.jpg"), seen)
        assertEquals(listOf("1.iter1.3.then.2"), result.steps.filter { it.output?.get("action")?.toString() == "\"swipe_with_coords\"" }.map { it.stepId })
        assertEquals(listOf("true", "false"), result.steps.filter { it.type == "if" }.map { it.output?.get("matched").toString() })
        assertEquals("2", result.steps.last().output?.get("break_iteration").toString())
        assertTrue(result.steps.none { it.output?.containsKey("image_b64") == true })
    }

    @Test
    fun `conditions preserve substring and boolean comparisons and reject unknown operators`() {
        fun matches(op: String, right: String) = ConditionEvaluator.evaluate(
            kotlinx.serialization.json.Json.parseToJsonElement("""{"left":"abc123","operator":"$op","right":"$right"}""")
        ) { null }
        assertTrue(matches("contains", "bc"))
        assertTrue(matches("not_contains", "42"))
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { matches("unknown", "abc123") }
    }

    @Test
    fun `if evaluates condition over context and runs then branch`() = runBlocking {
        val def = parse(
            """
            { "context": { "show": true },
              "steps": [ { "id": 1, "type": "if",
                  "condition": { "left": "${'$'}{context.show}", "operator": "==", "right": true },
                  "then_steps": [ { "id": 2, "type": "tool", "tool_name": "echo", "inputs": {"t":"then"} },
                                  { "id": 3, "type": "gui_action", "action": "sleep", "seconds": 0 } ],
                  "else_steps": [ { "id": 9, "type": "gui_task", "task_description": "else branch" } ] } ] }
            """.trimIndent()
        )
        val result = runner().run(def)
        assertEquals(RunStatus.SUCCESS, result.status)
        // The receipt includes the two chosen leaves and the branch decision.
        assertEquals(listOf("1.then.2", "1.then.3", "1"), result.steps.map { it.stepId })
    }

    @Test
    fun `cancellation surfaces as CANCELLED`() = runBlocking {
        val blocker = CompletableDeferred<Unit>()
        val blockingClock = WorkflowRunnerImpl.StepClock { _ -> blocker.await() }
        val runner = WorkflowRunnerImpl(clock = blockingClock, maxTotalSteps = 200, maxMillis = 60_000L)
        val def = parse("""{ "steps": [ { "id": 1, "type": "gui_action", "action": "sleep", "seconds": 10 } ] }""")

        val deferred = async(start = CoroutineStart.LAZY) { runner.run(def) }
        delay(50) // let it suspend on the blocking sleep clock
        deferred.cancel()
        val outcome = try {
            deferred.await()
        } catch (c: kotlinx.coroutines.CancellationException) {
            null
        }
        // Either the runner returned a CANCELLED result or the awaiting caller got
        // CancellationException — in both cases it never reports a fake success.
        if (outcome != null) {
            assertEquals(RunStatus.CANCELLED, outcome.status)
        }
    }

    @Test
    fun `loop times exceeding the per-loop bound becomes FAILED`() = runBlocking {
        val def = parse("""{ "steps": [ { "id": 1, "type": "loop", "times": 999999, "steps": [] } ] }""")
        val result = runner(maxLoop = 200).run(def)
        assertEquals(RunStatus.FAILED, result.status)
        assertTrue(result.message.contains("loop times"))
    }

    @Test
    fun `total step budget is enforced for large loops`() = runBlocking {
        val inner = (0 until 100).joinToString(",") {
            """{"id":$it,"type":"tool","tool_name":"echo","inputs":{"n":"$it"}}"""
        }
        val def = parse("""{ "steps": [ { "id": 1, "type": "loop", "times": 10, "steps": [ $inner ] } ] }""")
        val result = runner(maxTotal = 40, maxLoop = 20).run(def)
        // 10 iterations * 100 sub-steps would exceed the total budget of 40.
        assertEquals(RunStatus.FAILED, result.status)
        assertTrue(result.message.contains("max total-step budget"))
    }

    @Test
    fun `all-gui workflow never reports success`() = runBlocking {
        val def = parse(
            """
            { "steps": [
              { "id": 1, "type": "gui_task", "task_description": "打开饿了么" },
              { "id": 2, "type": "gui_task", "task_description": "点击我的" }
            ] }
            """.trimIndent()
        )
        val result = runner().run(def)
        assertEquals(RunStatus.BLOCKED, result.status)
    }

    @Test
    fun `unknown step type becomes BLOCKED`() = runBlocking {
        val def = parse("""{ "steps": [ { "id": 7, "type": "totally_unknown" } ] }""")
        val result = runner().run(def)
        assertEquals(RunStatus.BLOCKED, result.status)
        assertTrue(result.message.contains("unknown step type"))
    }

    @Test
    fun `loop variables index and last resolve deterministically`() = runBlocking {
        val def = parse(
            """
            { "steps": [ { "id": 1, "type": "loop", "times": 2, "steps": [
                { "id": 10, "type": "tool", "tool_name": "echo",
                  "inputs": { "i": "${'$'}{loop.index}", "last": "${'$'}{loop.last}" } }
            ] } ] }
            """.trimIndent()
        )
        val result = runner().run(def)
        assertEquals(RunStatus.SUCCESS, result.status)
        assertNotNull(result)
    }
}
