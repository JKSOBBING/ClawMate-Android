package com.clawmate.platform.mika

import com.wangzan101.mika.core.record.RunEvent
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Export reuses upstream `RunRecordExport`, so the header contract and the `partial` marker are
 * what this port has to get right; the redaction itself stays upstream's.
 */
class MikaRunExportTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `a finished run exports a header line plus one line per event`() {
        val store = MikaRunStore(temp.root)
        store.begin("run-export", "打开设置", "TextModel").apply {
            event(RunEvent.TaskStarted(1_000, "打开设置", "TextModel"))
            event(RunEvent.PromptVersion(1_001, "abc"))
            close("success", "已打开设置")
        }

        val jsonl = MikaRunExport.payloadJsonl(temp.root, "run-export")
        assertTrue(jsonl != null)
        val lines = jsonl!!.trim().lines()
        assertTrue("header carries the format version", lines.first().contains("\"exportFormat\":1"))
        assertTrue("header carries the run id", lines.first().contains("run-export"))
        assertTrue("a closed run is not partial", lines.first().contains("\"partial\":false"))
        assertTrue("header plus two events", lines.size >= 3)
    }

    @Test
    fun `an unknown run has nothing to export`() {
        assertNull(MikaRunExport.payloadJsonl(temp.root, "nobody"))
    }

    @Test
    fun `a run that never finished is marked partial`() {
        val store = MikaRunStore(temp.root)
        store.begin("run-open", "目标", "TextModel").apply {
            event(RunEvent.TaskStarted(1_000, "目标", "TextModel"))
            // No close(): no run.json is written, so the summary is rebuilt from events.
        }

        val jsonl = MikaRunExport.payloadJsonl(temp.root, "run-open")
        assertTrue(jsonl != null)
        assertTrue(jsonl!!.lines().first().contains("\"partial\":true"))
    }
}
