package com.clawmate.app.datamanager

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Phase 41: tests for the Harmony-format append writer ([DailyLogWorkspace.appendEntry]),
 * the single file-backed writer the Android workflow runner uses. Verifies the exact
 * format round-trips through [DailyLogParser] / [DailyLogEventSourceFactory] (the same
 * path the Home「generation」button consumes end-to-end), plus repeat-run append
 * semantics and every bound.
 */
class DailyLogWorkspaceAppendTest {

    @get:Rule
    val tmp: TemporaryFolder = TemporaryFolder()

    private fun metadata() = mapOf(
        "name" to "daily-summary-workflow",
        "description" to "查看每日新闻要点",
    )

    @Test
    fun `appendEntry writes a parseable harmony metadata header with latest_entry_index`() {
        val written = DailyLogWorkspace.appendEntry(
            tmp.root, "2026-08-17", "daily-summary-workflow.md", "今天完成了季度报告的整理。", metadata()
        )
        assertNotNull(written)
        val text = written!!.readText()
        assertTrue(text.contains("<!-- DAILY_LOG_METADATA"))
        assertTrue(text.contains("\"workflow_metadata\""))
        assertTrue(text.contains("\"name\": \"daily-summary-workflow\""))
        assertTrue(text.contains("\"latest_entry_index\": 1"))

        // Round-trips through the exact reader the Home pipeline uses.
        val source = DailyLogEventSourceFactory.overDirectory(tmp.root)
        val scan = source.load()
        assertEquals(0, scan.errorCount)
        assertEquals(1, scan.entries.size)
        assertEquals("今天完成了季度报告的整理。", scan.entries[0].text)
        assertEquals("2026-08-17", scan.entries[0].sourceDate)
        assertEquals("2026-08-17/daily-summary-workflow.md#1", scan.entries[0].sourceRef)
        assertEquals("daily-summary-workflow", scan.entries[0].workflowMetadata["name"])
        assertEquals(1, scan.entries[0].latestEntryIndex)
    }

    @Test
    fun `repeat append preserves stable indices and climbs latest_entry_index`() {
        DailyLogWorkspace.appendEntry(tmp.root, "2026-08-17", "s.md", "第一条", metadata())
        DailyLogWorkspace.appendEntry(tmp.root, "2026-08-17", "s.md", "第二条", metadata())

        val scan = DailyLogEventSourceFactory.overDirectory(tmp.root).load()
        assertEquals(0, scan.errorCount)
        assertEquals(2, scan.entries.size)
        // Stable source refs: first entry keeps #1.
        assertEquals("2026-08-17/s.md#1", scan.entries[0].sourceRef)
        assertEquals("2026-08-17/s.md#2", scan.entries[1].sourceRef)
        assertEquals("第一条", scan.entries[0].text)
        assertEquals("第二条", scan.entries[1].text)
        assertEquals(2, scan.entries[1].latestEntryIndex)
    }

    @Test
    fun `blank or whitespace entry is rejected`() {
        assertNull(DailyLogWorkspace.appendEntry(tmp.root, "2026-08-17", "s.md", "   "))
        assertNull(DailyLogWorkspace.appendEntry(tmp.root, "2026-08-17", "s.md", ""))
    }

    @Test
    fun `invalid day or file name is rejected`() {
        assertNull(DailyLogWorkspace.appendEntry(tmp.root, "2026-8-17", "s.md", "内容"))
        assertNull(DailyLogWorkspace.appendEntry(tmp.root, "2026-08-17", "s.txt", "内容"))
    }

    @Test
    fun `entry over the cap is bounded to the entry limit not rejected`() {
        val long = "字".repeat(DailyLogBounds.MAX_ENTRY_CHARACTERS + 20)
        val written = DailyLogWorkspace.appendEntry(tmp.root, "2026-08-17", "s.md", long, metadata())
        assertNotNull(written)
        val scan = DailyLogEventSourceFactory.overDirectory(tmp.root).load()
        assertEquals(1, scan.entries.size)
        assertTrue(scan.entries[0].text.length <= DailyLogBounds.MAX_ENTRY_CHARACTERS)
    }

    @Test
    fun `append beyond the entries-per-day cap is refused`() {
        repeat(DailyLogBounds.MAX_ENTRIES_PER_DAY) { i ->
            assertNotNull(DailyLogWorkspace.appendEntry(tmp.root, "2026-08-17", "s.md", "条目$i", metadata()))
        }
        // The MAX_ENTRIES_PER_DAY+1 append is refused (bounded, no unbounded growth).
        assertNull(DailyLogWorkspace.appendEntry(tmp.root, "2026-08-17", "s.md", "overflow", metadata()))
        val scan = DailyLogEventSourceFactory.overDirectory(tmp.root).load()
        assertTrue(scan.entries.size <= DailyLogBounds.MAX_ENTRIES_PER_DAY)
    }

    @Test
    fun `different workflow names land in different files`() {
        DailyLogWorkspace.appendEntry(tmp.root, "2026-08-17", "a.md", "A 内容", metadata())
        DailyLogWorkspace.appendEntry(tmp.root, "2026-08-17", "b.md", "B 内容", metadata())
        val scan = DailyLogEventSourceFactory.overDirectory(tmp.root).load()
        assertEquals(2, scan.entries.size)
        assertEquals(setOf("2026-08-17/a.md#1", "2026-08-17/b.md#1"), scan.entries.map { it.sourceRef }.toSet())
    }

    @Test
    fun `file lives under app-private workflows daily-log day tree`() {
        val written = DailyLogWorkspace.appendEntry(tmp.root, "2026-08-17", "s.md", "内容", metadata())
        assertTrue(written!!.path.replace('\\', '/').endsWith("workflows/daily-log/2026-08-17/s.md"))
        val dayDir = File(File(tmp.root, DailyLogWorkspace.DAILY_LOG_ROOT_RELATIVE), "2026-08-17")
        assertTrue(dayDir.isDirectory)
    }
}
