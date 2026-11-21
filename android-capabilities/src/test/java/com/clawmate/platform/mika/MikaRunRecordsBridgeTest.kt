package com.clawmate.platform.mika

import com.wangzan101.mika.core.record.RunEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The per-step projection must show what a person can act on, drop kernel bookkeeping, and stay
 * readable when the record is missing. (`org.json` is a stub in JVM tests, so the JSON wrapper
 * itself is not exercised here; it only serialises these plain rows.)
 */
class MikaRunRecordsBridgeTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `rows keep the human steps and skip bookkeeping`() {
        val store = MikaRunStore(temp.root)
        val recorder = store.begin("run-steps", "打开计算器", "TextModel")
        recorder.event(RunEvent.TaskStarted(1_000, "打开计算器", "TextModel", stepLimit = 8))
        recorder.event(RunEvent.PromptVersion(1_001, "abc")) // bookkeeping: no human row
        recorder.event(
            RunEvent.ModelReply(
                atMs = 2_000, step = 1, text = "我先看看当前界面", tool = "phone_observe", argsJson = "{}",
                inputTokens = null, outputTokens = null, cachedTokens = null,
            ),
        )
        recorder.event(RunEvent.TaskEnded(3_000, "success", "已打开计算器"))
        recorder.close("success", "已打开计算器")

        val rows = MikaRunRecordsBridge.rows(temp.root, "run-steps")
        assertEquals(3, rows.size)
        assertTrue(rows[0].text.startsWith("开始"))
        assertTrue(rows[1].text.contains("phone_observe"))
        assertEquals(1, rows[1].step)
        assertEquals("ModelReply", rows[1].type)
        assertTrue(rows[2].text.startsWith("结束"))
        assertTrue(rows[2].text.contains("已打开计算器"))
    }

    @Test
    fun `an unknown run yields no rows instead of throwing`() {
        assertTrue(MikaRunRecordsBridge.rows(temp.root, "nobody").isEmpty())
        assertTrue(MikaRunRecordsBridge.briefs(temp.root, 10).isEmpty())
    }

    @Test
    fun `briefs report outcome and answer for the list view`() {
        val store = MikaRunStore(temp.root)
        store.begin("run-list", "看看设置", "TextModel")
            .apply { event(RunEvent.TaskStarted(1, "看看设置", "TextModel")); close("success", "已看到设置页") }

        val brief = MikaRunRecordsBridge.briefs(temp.root, 10).single()
        assertEquals("run-list", brief.id)
        assertEquals("看看设置", brief.goal)
        assertEquals("success", brief.outcome)
        assertEquals("已看到设置页", brief.answerOrReason)
    }
}
