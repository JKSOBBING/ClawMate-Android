package com.clawmate.app.task.runner

import com.clawmate.app.task.json.WorkflowJsonParser
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class WorkflowModelToolTest {
    private fun definition(inputs: String = """{"question":"q","image":"file:///fixture.jpg"}""", extra: String = "") =
        (WorkflowJsonParser.parse("""{"context":{"q":"resolved"},"steps":[{"id":1,"type":"tool","tool_name":"vlm_qa","inputs":$inputs}$extra]}""") as WorkflowJsonParser.Result.Ok).definition
    @Test fun disabledHostRemainsNotConfigured() = runBlocking {
        assertEquals(RunStatus.NOT_CONFIGURED, WorkflowRunnerImpl().run(definition()).status)
    }
    @Test fun answerResolvesInputsAndRetainsRealOutputWithoutDailyLog() = runBlocking {
        var writes = 0
        val result = WorkflowRunnerImpl(modelTool = WorkflowModelTool {
            assertEquals("resolved", it.question); assertEquals("answer", it.mode); "real answer"
        }, dailyLogSink = WorkflowDailyLogSink { _,_,_,_ -> writes++; "path" }).run(definition("""{"question":"${'$'}{context.q}","image":"file:///fixture.jpg"}"""))
        assertEquals(RunStatus.SUCCESS, result.status); assertEquals("", result.resultSummary)
        assertEquals("real answer", (result.steps[0].output?.get("response") as JsonPrimitive).content)
        assertEquals(0, writes)
    }
    @Test fun summaryWritesOnlyAfterEntireRunSucceeds() = runBlocking {
        var writes = 0
        val runner = WorkflowRunnerImpl(modelTool = WorkflowModelTool { "real summary" }, dailyLogSink = WorkflowDailyLogSink { _,_,text,_ ->
            assertEquals("real summary", text); writes++; "path"
        })
        val input = """{"mode":"summary","question":"q","image":"file:///fixture.jpg"}"""
        assertEquals(RunStatus.SUCCESS, runner.run(definition(input)).status); assertEquals(1, writes)
        assertEquals(RunStatus.BLOCKED, runner.run(definition(input, """,{"id":2,"type":"gui_action","action":"screenshot"}""")).status)
        assertEquals(1, writes)
    }
    @Test fun structuredResponseCanFeedLaterSummaryWithoutInterpretingReturnedExpressions() = runBlocking {
        val result = WorkflowRunnerImpl(modelTool = WorkflowModelTool { """{"summary":"literal ${'$'}{context.q}"}""" }).run(definition(
            """{"question":"q","image":"file:///fixture.jpg","json_schema":{"summary":{"type":"string"}}}""",
            """,{"id":2,"type":"tool","tool_name":"summary","inputs":{"text":"${'$'}{steps.1.output.structured_output.summary}"}}"""))
        assertEquals(RunStatus.SUCCESS, result.status); assertEquals("literal ${'$'}{context.q}", result.resultSummary)
    }
    @Test fun readingSchemaShapedTranscriptReturnsTextAndTypedBranchValue() = runBlocking {
        val input = """{"mode":"summary","question":"q","image":"x","json_schema":{"summary":{"type":"string","description":"描述截图"},"contains_today_chat":{"type":"boolean"}}}"""
        val reply = """{"result":{"summary":{"type":"私聊","description":"蓝色样卡已经归档。"},"containsTodayChat":"no"}}"""
        val run = WorkflowRunnerImpl(modelTool = WorkflowModelTool { reply }).run(definition(input))
        assertEquals(RunStatus.SUCCESS, run.status)
        assertEquals("蓝色样卡已经归档。", run.resultSummary)
        val output = requireNotNull(run.steps.single().output)
        assertEquals(run.resultSummary, output.getValue("response").jsonPrimitive.content)
        assertEquals(JsonPrimitive(false), output.getValue("structured_output").jsonObject["contains_today_chat"])
        val echo = reply.replace("蓝色样卡已经归档。", "描述截图")
        assertEquals(RunStatus.FAILED, WorkflowRunnerImpl(modelTool = WorkflowModelTool { echo }).run(definition(input)).status)
    }

    @Test fun captureOnlySummaryKeepsTheImageDateAndExcludesTheStoppingPage() = runBlocking {
        val inputs = """{"mode":"summary","time_context":"capture_only","question":"q","image":"chat.jpg","json_schema":{"summary":{"type":"string"},"contains_today_chat":{"type":"boolean"}}}"""
        for (today in listOf(true, false)) {
            val runner = WorkflowRunnerImpl(runDateProvider = { "2026-11-20" }, modelTool = WorkflowModelTool { request ->
                assertTrue(request.captureOnly)
                assertEquals("2026-11-20", request.captureDay)
                """{"summary":"合成记录","contains_today_chat":$today}"""
            })
            val result = runner.run(definition(inputs))
            assertEquals(RunStatus.SUCCESS, result.status)
            assertEquals(if (today) "合成记录" else "", result.resultSummary)
            val output = requireNotNull(result.steps.single().output)
            assertEquals("chat.jpg", output.getValue("image").jsonPrimitive.content)
            assertEquals("2026-11-20", output.getValue("capture_day").jsonPrimitive.content)
        }
    }

    @Test fun malformedRequestsNeverReachHost() = runBlocking {
        var calls = 0
        val runner = WorkflowRunnerImpl(modelTool = WorkflowModelTool { calls++; "answer" })
        for (input in listOf("{}", """{"question":4,"image":"x"}""", """{"question":"${'$'}{unknown}","image":"x"}""",
            """{"question":"q","image":"x","mode":"shell"}""", """{"question":"q","image":"x","json_schema":[]}""",
            """{"question":"q","image":"x","secret":"x"}""")) assertEquals(RunStatus.FAILED, runner.run(definition(input)).status)
        assertEquals(0, calls)
    }
    @Test fun errorsEmptyOversizeAndInvalidStructuredRepliesCannotWriteLog() = runBlocking {
        var writes = 0
        for (reply in listOf("", "x".repeat(16001), "not json")) {
            val result = WorkflowRunnerImpl(modelTool = WorkflowModelTool { reply }, dailyLogSink = WorkflowDailyLogSink { _,_,_,_ -> writes++; "path" })
                .run(definition("""{"question":"q","image":"x","mode":"summary","json_schema":{}}"""))
            assertEquals(RunStatus.FAILED, result.status)
        }
        assertEquals(RunStatus.FAILED, WorkflowRunnerImpl(modelTool = WorkflowModelTool { error("provider failed") }).run(definition()).status)
        assertEquals(0, writes)
    }
    @Test fun waitingForModelHonorsDeadlineAndCancellation() = runBlocking {
        assertEquals(RunStatus.FAILED, WorkflowRunnerImpl(maxMillis = 20, modelTool = WorkflowModelTool { delay(1000); "late" }).run(definition()).status)
        assertEquals(RunStatus.CANCELLED, WorkflowRunnerImpl(modelTool = WorkflowModelTool { throw CancellationException("cancelled") }).run(definition()).status)
    }
    @Test fun loopRequestsUseCurrentIterationAndRetainUnmodifiedResponse() = runBlocking {
        val questions = mutableListOf<String>()
        val def = (WorkflowJsonParser.parse("""{"steps":[{"id":1,"type":"loop","times":2,"steps":[{"id":2,"type":"tool","tool_name":"vlm_qa","inputs":{"question":"${'$'}{loop.index}","image":"x"}}]}]}""") as WorkflowJsonParser.Result.Ok).definition
        val result = WorkflowRunnerImpl(modelTool = WorkflowModelTool { questions.add(it.question); "${'$'}{context.private}" }).run(def)
        assertEquals(RunStatus.SUCCESS, result.status); assertEquals(listOf("1","2"), questions)
        assertEquals("${'$'}{context.private}", (result.steps.last { it.type == "tool" }.output?.get("response") as JsonPrimitive).content)
    }
    @Test fun deferredSummaryLetsNextStepRunAndKeepsCaptureDateAndFullResponse() = runBlocking {
        val nextStep = CompletableDeferred<Unit>()
        var day = "2026-11-19"
        val large = "真实文字".repeat(1200)
        val runner = WorkflowRunnerImpl(
            runDateProvider = { day },
            clock = WorkflowRunnerImpl.StepClock { day = "2026-11-20"; nextStep.complete(Unit) },
            modelTool = WorkflowModelTool { request ->
                assertEquals("2026-11-19", request.captureDay)
                withTimeout(2000) { nextStep.await() }
                large
            },
        )
        val run = runner.run(definition("""{"question":"q","image":"file:///fixture.jpg","mode":"summary"}""",
            """,{"id":2,"type":"gui_action","action":"sleep","seconds":1}"""))
        assertEquals(RunStatus.SUCCESS, run.status)
        assertEquals(listOf(WorkflowSummaryEntry("2026-11-19", large)), run.summaryEntries)
        assertEquals(large, run.resultSummary)
        assertEquals(large, run.steps.first().output?.get("response")?.jsonPrimitive?.content)
    }

    @Test fun guiDoesNotBecomeEnabledByModelPort() = runBlocking {
        var calls = 0
        val def = (WorkflowJsonParser.parse("""{"steps":[{"id":1,"type":"gui_action","action":"screenshot"}]}""") as WorkflowJsonParser.Result.Ok).definition
        assertEquals(RunStatus.BLOCKED, WorkflowRunnerImpl(modelTool = WorkflowModelTool { calls++; "answer" }).run(def).status)
        assertEquals(0, calls)
    }
    private fun request() = WorkflowModelRequest("caller-id", "answer", "q", "file:///fixture.jpg", null)
    @Test fun mailboxRejectsForeignAndDuplicateReplies() = runBlocking {
        val box = WorkflowModelMailbox()
        val work = async(start = CoroutineStart.UNDISPATCHED) { box.evaluate(request()) }
        val id = box.peek()!!.id
        assertNotEquals("caller-id", id); assertFalse(box.reply("foreign", "bad", ""))
        assertTrue(box.reply(id, "actual", "")); assertNull(box.peek()); assertFalse(box.reply(id, "replay", ""))
        assertEquals("actual", work.await()); assertNull(box.peek())
        box.close(); assertFalse(box.reply(id, "late", ""))
    }
    @Test fun closedMailboxCancelsWaitAndRejectsNextRequest() = runBlocking {
        val box = WorkflowModelMailbox()
        val work = async(start = CoroutineStart.UNDISPATCHED) { runCatching { box.evaluate(request()) } }
        box.close(); assertTrue(work.await().exceptionOrNull() is CancellationException)
        assertTrue(runCatching { box.evaluate(request()) }.isFailure)
    }
    @Test fun cancelledWaitRemovesPendingAndNewRequestGetsNewIdentity() = runBlocking {
        val box = WorkflowModelMailbox()
        val work = async(start = CoroutineStart.UNDISPATCHED) { box.evaluate(request()) }; val old = box.peek()!!.id
        work.cancelAndJoin(); assertNull(box.peek())
        val next = async(start = CoroutineStart.UNDISPATCHED) { box.evaluate(request()) }; val id = box.peek()!!.id
        assertNotEquals(old, id); assertFalse(box.reply(old,"late","")); box.reply(id,"new","")
        assertEquals("new", next.await()); box.close()
    }
}
