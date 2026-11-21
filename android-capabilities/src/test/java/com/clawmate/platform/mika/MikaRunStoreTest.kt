package com.clawmate.platform.mika

import com.wangzan101.mika.core.record.RunEvent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The run-record port must keep what the kernel emitted, survive a missing/corrupt summary, and
 * never let a run id reach outside its own directory.
 */
class MikaRunStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `a finished run keeps its events, summary and outcome`() = runBlocking {
        val store = MikaRunStore(temp.root)
        val recorder = store.begin("run-1", "看看计算器", "TextModel", origin = "phone")
        recorder.event(RunEvent.TaskStarted(1_000, "看看计算器", "TextModel", origin = "phone", stepLimit = 12))
        recorder.event(RunEvent.PromptVersion(1_001, "abc"))
        recorder.close("success", "读到 3 行")

        val summary = store.summary("run-1")
        assertNotNull(summary)
        assertEquals("run-1", summary!!.id)
        assertEquals("看看计算器", summary.goal)
        assertEquals("success", summary.outcome)
        assertEquals("读到 3 行", summary.answerOrReason)
        assertEquals(12, summary.stepLimit)
        assertNotNull(summary.endedAtMs)

        val events = store.events("run-1")
        assertEquals(2, events.size)
        assertTrue(events[0] is RunEvent.TaskStarted)
        assertTrue(events[1] is RunEvent.PromptVersion)
    }

    @Test
    fun `a summary is rebuilt from events when run json is missing`() = runBlocking {
        val dir = File(temp.root, "run-2").apply { mkdirs() }
        val line = """{"type":"task_started","atMs":500,"goal":"打开设置","model":"TextModel","origin":"phone"}"""
        File(dir, "events.jsonl").writeText(line + "\n" + "{not json}\n")

        val store = MikaRunStore(temp.root)
        val summary = store.summary("run-2")
        assertNotNull(summary)
        assertEquals("打开设置", summary!!.goal)
        assertEquals("TextModel", summary.model)
        assertEquals(500, summary.startedAtMs)
        assertEquals("a malformed line is skipped, not fatal", 1, store.events("run-2").size)
    }

    @Test
    fun `an unknown or removed run reads as empty instead of throwing`() = runBlocking {
        val store = MikaRunStore(File(temp.root, "missing-root"))
        assertNull(store.summary("nobody"))
        assertTrue(store.events("nobody").isEmpty())
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun `a run id cannot escape the store directory`() = runBlocking {
        val store = MikaRunStore(temp.root)
        val recorder = store.begin("../../evil", "goal", "model")
        recorder.event(RunEvent.PromptVersion(1, "h"))
        recorder.close("success", "ok")

        val children = temp.root.listFiles().orEmpty()
        assertEquals(1, children.size)
        assertTrue("run id is sanitised", children[0].name.matches(Regex("[A-Za-z0-9._-]+")))
        assertTrue(store.summary("../../evil") != null)
    }

    @Test
    fun `dot run ids stay inside the store root`() = runBlocking {
        val root = temp.newFolder("records")
        val store = MikaRunStore(root)
        store.begin("..", "goal", "model").close("success", "ok")
        assertTrue(File(root, "run/run.json").isFile)
        assertTrue(!File(temp.root, "run.json").exists())
        assertEquals("success", store.summary("..")?.outcome)
    }

    @Test
    fun `media is stored beside the events`() {
        val store = MikaRunStore(temp.root)
        val recorder = store.begin("run-media", "goal", "model")
        recorder.media(3, byteArrayOf(1, 2, 3))
        recorder.close("success", "ok")

        val png = File(File(File(temp.root, "run-media"), "media"), "3.png")
        assertTrue(png.isFile)
        assertArrayEquals(byteArrayOf(1, 2, 3), png.readBytes())
    }

    @Test
    fun `events after close are ignored`() = runBlocking {
        val store = MikaRunStore(temp.root)
        val recorder = store.begin("run-closed", "goal", "model")
        recorder.close("failed", "boom")
        recorder.event(RunEvent.PromptVersion(9_999, "late"))

        assertEquals(0, store.events("run-closed").size)
        assertEquals("failed", store.summary("run-closed")!!.outcome)
    }

    @Test
    fun `list reports the newest run first`() = runBlocking {
        val store = MikaRunStore(temp.root)
        store.begin("older", "a", "m").apply { event(RunEvent.PromptVersion(1, "x")); close("success", "a") }
        Thread.sleep(5)
        store.begin("newer", "b", "m").apply { event(RunEvent.PromptVersion(2, "y")); close("success", "b") }

        val ids = store.list().map { it.id }
        assertEquals(listOf("newer", "older"), ids)
    }

    @Test
    fun `the plain brief carries what the host reads without kernel types`() {
        val store = MikaRunStore(temp.root)
        store.begin("run-brief", "打开计算器", "TextModel")
            .apply { event(RunEvent.TaskStarted(1_000, "打开计算器", "TextModel", stepLimit = 8)); close("success", "完成") }

        val brief = store.listBriefs().single()
        assertEquals("run-brief", brief.id)
        assertEquals("打开计算器", brief.goal)
        assertEquals("TextModel", brief.model)
        assertEquals("success", brief.outcome)
        assertEquals("完成", brief.answerOrReason)
        assertEquals(runBlocking { store.summary("run-brief")!!.startedAtMs }, brief.startedAtMs)
        assertNotNull(brief.endedAtMs)
    }
}
