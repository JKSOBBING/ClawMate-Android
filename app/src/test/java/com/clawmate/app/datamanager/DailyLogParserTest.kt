package com.clawmate.app.datamanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Device-free tests for the pure [DailyLogParser]: header + numbered-entry
 * parsing, stable source refs, and the strict malformed/oversized limits that
 * skip bad files with honest, countable errors (never thrown).
 */
class DailyLogParserTest {

    private val document = """
        <!-- DAILY_LOG_METADATA
        {"workflow_metadata":{"app":"wechat","workflow":"auto"},"latest_entry_index":3}
        -->
        1. 今天去便利店买了日用品。

        2. 计划周末去郊游，准备预订酒店。

        3. 提交了季度工作报告。
    """.trimIndent()

    @Test
    fun `parses header and numbered entries with stable source ref`() {
        val result = DailyLogParser.parse("2026-08-01", "wechat.logs.md", document)
        assertEquals(0, result.errorCount)
        assertEquals(3, result.entries.size)
        assertEquals(3, result.latestEntryIndex)
        assertEquals("wechat", result.entries[0].workflowMetadata["app"])
        assertEquals("auto", result.entries[0].workflowMetadata["workflow"])

        val first = result.entries.first()
        assertEquals("2026-08-01", first.sourceDate)
        assertEquals("wechat.logs.md", first.fileName)
        assertEquals(1, first.entryIndex)
        assertTrue(first.text.startsWith("今天去便利店"))
        // Stable source ref: sourceDate/fileName#index.
        assertEquals("2026-08-01/wechat.logs.md#1", first.sourceRef)
        assertEquals("2026-08-01/wechat.logs.md#2", result.entries[1].sourceRef)
        assertEquals("2026-08-01/wechat.logs.md#3", result.entries[2].sourceRef)
    }

    @Test
    fun `source ref is deterministic and stable`() {
        // Repeated parses must produce identical ids.
        val a = DailyLogParser.parse("2026-08-01", "x.md", "1. abc\n\n2. def")
        val b = DailyLogParser.parse("2026-08-01", "x.md", "1. abc\n\n2. def")
        assertEquals(a.entries.map { it.sourceRef }, b.entries.map { it.sourceRef })
        assertEquals(
            "2026-08-01/x.md#2",
            DailyLogParser.sourceRef("2026-08-01", "x.md", 2),
        )
    }

    @Test
    fun `document without header still parses numbered entries`() {
        val result = DailyLogParser.parse("2026-08-01", "x.md", "1. a\n\n2. b")
        assertEquals(2, result.entries.size)
        assertTrue(result.metadata.isEmpty())
        assertEquals(2, result.latestEntryIndex)
    }

    @Test
    fun `empty body yields empty entries not an error`() {
        val result = DailyLogParser.parse("2026-08-01", "x.md", "")
        assertEquals(0, result.entries.size)
        assertEquals(0, result.errorCount)
    }

    @Test
    fun `blank text entries are skipped`() {
        val result = DailyLogParser.parse("2026-08-01", "x.md", "1.   \n\n2. kept")
        assertEquals(1, result.entries.size)
        assertEquals("kept", result.entries[0].text)
    }

    @Test
    fun `invalid day directory is a countable error and no entries`() {
        val result = DailyLogParser.parse("2026-8-1", "x.md", "1. a")
        assertEquals(1, result.errorCount)
        assertEquals(DailyLogErrorKind.INVALID_DAY_DIRECTORY, result.errors[0].kind)
        assertTrue(result.entries.isEmpty())
    }

    @Test
    fun `invalid file name is a countable error`() {
        val result = DailyLogParser.parse("2026-08-01", "x.txt", "1. a")
        assertEquals(1, result.errorCount)
        assertEquals(DailyLogErrorKind.INVALID_FILE_NAME, result.errors[0].kind)
    }

    @Test
    fun `oversized document is a countable error and skipped`() {
        val huge = "1. " + "x".repeat(DailyLogBounds.MAX_DOCUMENT_CHARACTERS + 5)
        val result = DailyLogParser.parse("2026-08-01", "x.md", huge)
        assertEquals(1, result.errorCount)
        assertEquals(DailyLogErrorKind.OVERSIZED_DOCUMENT, result.errors[0].kind)
        assertTrue(result.entries.isEmpty())
    }

    @Test
    fun `oversized metadata header is a countable error but entries still parse`() {
        val oversizedHeader = "<!-- DAILY_LOG_METADATA\n" +
            "\"" + "k".repeat(DailyLogBounds.MAX_METADATA_CHARACTERS + 10) + "\"\n-->\n1. a"
        val result = DailyLogParser.parse("2026-08-01", "x.md", oversizedHeader)
        assertEquals(1, result.errorCount)
        assertEquals(DailyLogErrorKind.OVERSIZED_METADATA, result.errors[0].kind)
        assertEquals(1, result.entries.size)
    }

    @Test
    fun `oversized single entry is a countable error and skipped`() {
        val longEntry = "1. " + "长".repeat(DailyLogBounds.MAX_ENTRY_CHARACTERS + 5) +
            "\n\n2. normal"
        val result = DailyLogParser.parse("2026-08-01", "x.md", longEntry)
        assertEquals(1, result.errorCount)
        assertEquals(DailyLogErrorKind.OVERSIZED_ENTRY, result.errors[0].kind)
        assertEquals(1, result.entries.size)
        assertEquals("normal", result.entries[0].text)
    }

    @Test
    fun `entry count over the cap is a countable error and capped`() {
        val huge = (1..(DailyLogBounds.MAX_ENTRIES_PER_DAY + 10))
            .joinToString("\n\n") { "$it. 条目$it" }
        val result = DailyLogParser.parse("2026-08-01", "x.md", huge)
        assertTrue(result.errorCount >= 1)
        assertTrue(result.entries.size <= DailyLogBounds.MAX_ENTRIES_PER_DAY)
    }

    @Test
    fun `malformed metadata is tolerated with empty metadata not a fatal error`() {
        // A garbage (non-JSON) header block must not crash the parse.
        val result = DailyLogParser.parse(
            "2026-08-01",
            "x.md",
            "<!-- DAILY_LOG_METADATA\nthis is not json\n-->\n1. a",
        )
        assertTrue(result.metadata.isEmpty())
        assertEquals(1, result.entries.size)
    }
}
