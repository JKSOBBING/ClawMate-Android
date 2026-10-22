package com.clawmate.app.task.runner

import com.clawmate.app.datamanager.DailyLogEventSourceFactory
import com.clawmate.app.task.json.WorkflowJsonParser
import com.clawmate.app.task.model.WorkflowDefinition
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Phase 41 integration: the bound runner writes a real, parseable daily-log entry
 * only when a workflow succeeds AND exposes a real structured `summary` output.
 * Failed / UNSUPPORTED / NOT_CONFIGURED / no-summary / no-sink runs write nothing —
 * staying honest (a plain `echo` or an error is never promoted to a daily-log
 * summary). Repeat runs append with stable numbered indices.
 */
class WorkflowRunnerDailyLogWriteTest {

    @get:Rule
    val tmp: TemporaryFolder = TemporaryFolder()

    private val fastClock = WorkflowRunnerImpl.StepClock { _ -> }

    @After
    fun tearDown() {
        com.clawmate.app.config.EndpointConfig.load(java.util.Properties())
    }

    private fun parse(raw: String): WorkflowDefinition {
        val r = WorkflowJsonParser.parse(raw)
        assertTrue("expected parse Ok, got $r", r is WorkflowJsonParser.Result.Ok)
        return (r as WorkflowJsonParser.Result.Ok).definition
    }

    /** A runner bound to a temp baseDir + fixed day, so writes land on disk. */
    private fun fileRunner(day: String = "2026-08-17"): WorkflowRunnerImpl =
        WorkflowRunnerImpl(
            clock = fastClock,
            baseDir = tmp.root,
            runDateProvider = { day },
        )

    @Test
    fun `successful run with a real summary writes a parseable entry and returns path`() = runBlocking {
        // Authored punctuation is data: both execution and daily-log storage
        // must preserve it rather than applying syntax repair inside the text.
        val original = "今日新闻要点：AI 峰会召开，行业关注模型落地。"
        val def = parse(
            """
            { "metadata": { "name": "daily-news-summary", "description": "查看每日新闻要点" },
              "steps": [
                { "id": 1, "type": "tool", "tool_name": "summary",
                  "inputs": { "text": "今日新闻要点：AI 峰会召开，行业关注模型落地。" } }
              ] }
            """.trimIndent()
        )
        val result = fileRunner().run(def)
        assertEquals(RunStatus.SUCCESS, result.status)
        assertEquals(original, result.resultSummary)
        assertTrue(result.dailyLogPath.isNotEmpty())

        // Round-trips through the exact reader the Home「generation」button uses.
        val scan = DailyLogEventSourceFactory.overDirectory(tmp.root).load()
        assertEquals(0, scan.errorCount)
        assertEquals(1, scan.entries.size)
        assertEquals(original, scan.entries[0].text)
        assertEquals("daily-news-summary", scan.entries[0].workflowMetadata["name"])
        assertEquals("查看每日新闻要点", scan.entries[0].workflowMetadata["description"])
        assertEquals("2026-08-17", scan.entries[0].sourceDate)
        assertEquals("2026-08-17/daily-news-summary.md#1", scan.entries[0].sourceRef)
    }

    @Test
    fun `summary is bounded to the entry cap and real`() = runBlocking {
        val long = "字".repeat(2_500)
        val def = parse(
            """{ "metadata": { "name": "long-summary" },
                "steps": [ { "id": 1, "type": "tool", "tool_name": "summary", "inputs": { "text": "$long" } } ] }"""
        )
        val result = fileRunner().run(def)
        assertEquals(RunStatus.SUCCESS, result.status)
        assertTrue(result.resultSummary.length <= com.clawmate.app.datamanager.DailyLogBounds.MAX_ENTRY_CHARACTERS)
        val scan = DailyLogEventSourceFactory.overDirectory(tmp.root).load()
        assertEquals(1, scan.entries.size)
        assertTrue(scan.entries[0].text.length <= com.clawmate.app.datamanager.DailyLogBounds.MAX_ENTRY_CHARACTERS)
    }

    @Test
    fun `repeat run appends with stable numbered indices`() = runBlocking {
        val def = parse(
            """{ "metadata": { "name": "repeat-wf" },
                "steps": [ { "id": 1, "type": "tool", "tool_name": "summary", "inputs": { "text": "完成一次运行。" } } ] }"""
        )
        fileRunner().run(def)
        fileRunner().run(def)
        val scan = DailyLogEventSourceFactory.overDirectory(tmp.root).load()
        assertEquals(2, scan.entries.size)
        assertEquals("2026-08-17/repeat-wf.md#1", scan.entries[0].sourceRef)
        assertEquals("2026-08-17/repeat-wf.md#2", scan.entries[1].sourceRef)
        assertEquals(2, scan.entries[1].latestEntryIndex)
    }

    @Test
    fun `summary step resolves context into genuine text`() = runBlocking {
        val def = parse(
            """{ "context": { "topic": "量子计算" },
                "metadata": { "name": "ctx-wf" },
                "steps": [ { "id": 1, "type": "tool", "tool_name": "summary",
                              "inputs": { "text": "今日要点:${'$'}{context.topic} 迎来新进展。" } } ] }"""
        )
        val result = fileRunner().run(def)
        assertEquals("今日要点:量子计算 迎来新进展。", result.resultSummary)
    }

    @Test
    fun `unsupported GUI workflow writes nothing even though it has a summary step`() = runBlocking {
        val def = parse(
            """{ "metadata": { "name": "gui-wf" },
                "steps": [
                  { "id": 1, "type": "gui_task", "task_description": "打开微信" },
                  { "id": 2, "type": "tool", "tool_name": "summary", "inputs": { "text": "不应写入" } }
                ] }"""
        )
        val result = fileRunner().run(def)
        assertEquals(RunStatus.BLOCKED, result.status)
        assertTrue(result.dailyLogPath.isNullOrEmpty())
        assertEquals(0, DailyLogEventSourceFactory.overDirectory(tmp.root).load().entries.size)
    }

    @Test
    fun `not-configured VLM tool writes nothing`() = runBlocking {
        val def = parse(
            """{ "metadata": { "name": "vlm-wf" },
                "steps": [ { "id": 1, "type": "tool", "tool_name": "vlm_qa", "inputs": { "question": "q" } } ] }"""
        )
        val result = fileRunner().run(def)
        assertEquals(RunStatus.NOT_CONFIGURED, result.status)
        assertEquals(0, DailyLogEventSourceFactory.overDirectory(tmp.root).load().entries.size)
    }

    @Test
    fun `success with only echo writes nothing - echo is not a summary`() = runBlocking {
        val def = parse(
            """{ "metadata": { "name": "echo-only" },
                "steps": [ { "id": 1, "type": "tool", "tool_name": "echo", "inputs": { "text": "hi" } } ] }"""
        )
        val result = fileRunner().run(def)
        assertEquals(RunStatus.SUCCESS, result.status)
        // No explicit summary step => no daily-log entry (echo is honest debug, not a summary).
        assertTrue(result.dailyLogPath.isNullOrEmpty())
        assertEquals(0, DailyLogEventSourceFactory.overDirectory(tmp.root).load().entries.size)
    }

    @Test
    fun `blank summary text writes nothing`() = runBlocking {
        val def = parse(
            """{ "metadata": { "name": "blank-wf" },
                "steps": [ { "id": 1, "type": "tool", "tool_name": "summary", "inputs": { "text": "   " } } ] }"""
        )
        val result = fileRunner().run(def)
        assertEquals(RunStatus.SUCCESS, result.status)
        assertEquals(0, DailyLogEventSourceFactory.overDirectory(tmp.root).load().entries.size)
    }

    @Test
    fun `runner without a baseDir never writes (pure)`() = runBlocking {
        val spy = RecordingSink()
        val def = parse(
            """{ "metadata": { "name": "no-dir" },
                "steps": [ { "id": 1, "type": "tool", "tool_name": "summary", "inputs": { "text": "真实输出" } } ] }"""
        )
        val runner = WorkflowRunnerImpl(clock = fastClock, dailyLogSink = spy, runDateProvider = { "2026-08-17" })
        val result = runner.run(def)
        assertEquals(RunStatus.SUCCESS, result.status)
        assertEquals(listOf("真实输出"), spy.appendTexts)
        assertNotNull(spy.lastMetadata)
        assertEquals("no-dir", spy.lastMetadata?.get("name"))
    }
}

/** Captures sink calls so tests can assert write intent without disk. */
private class RecordingSink : WorkflowDailyLogSink {
    val appendTexts = mutableListOf<String>()
    var lastMetadata: Map<String, String>? = null
    var lastDay: String? = null
    var lastFileName: String? = null
    override fun append(day: String, fileName: String, text: String, metadata: Map<String, String>): String? {
        lastDay = day
        lastFileName = fileName
        lastMetadata = metadata
        appendTexts += text
        return File("/tmp/recording/" + fileName).absolutePath
    }
}
