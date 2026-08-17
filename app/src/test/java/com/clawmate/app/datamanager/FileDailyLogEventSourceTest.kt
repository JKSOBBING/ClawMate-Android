package com.clawmate.app.datamanager

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Device-free tests for the app-private [DailyLogEventSource] file backing and
 * the [DailyLogWorkspace] external-importer write API. Uses a temp dir as the
 * `filesDir` stand-in, then reads the written daily-log tree back through
 * [DailyLogEventSourceFactory.overDirectory] exactly as the Home button does
 * (but over the temp root).
 */
class DailyLogEventSourceTest {

    @get:Rule
    val tmp: TemporaryFolder = TemporaryFolder()

    private fun writeDoc(
        base: File = tmp.root,
        day: String,
        file: String,
        entries: List<String>,
        meta: Map<String, String> = emptyMap(),
    ): File? = DailyLogWorkspace.writeDocument(base, day, file, entries, meta)

    @Test
    fun `empty source returns no entries`() {
        val source = DailyLogEventSourceFactory.overDirectory(tmp.root)
        assertTrue(source.isEmpty())
        val scan = source.load()
        assertTrue(scan.entries.isEmpty())
        assertEquals(0, scan.errorCount)
    }

    @Test
    fun `file source reads written documents in day then file order`() {
        // Write two days to prove ordering (day asc) and two files per day.
        writeDoc(tmp.root, "2026-08-01", "b.md", listOf("day1 file b"))
        writeDoc(tmp.root, "2026-08-01", "a.md", listOf("day1 file a"))
        writeDoc(tmp.root, "2026-08-02", "a.md", listOf("day2 file a"))

        val source = DailyLogEventSourceFactory.overDirectory(tmp.root)
        assertFalse(source.isEmpty())
        val scan = source.load()
        // Ordering: day 01 before day 02; within a day, file names asc.
        assertEquals(3, scan.entries.size)
        assertEquals("2026-08-01", scan.entries[0].sourceDate)
        assertEquals("a.md", scan.entries[0].fileName)
        assertEquals("2026-08-01", scan.entries[1].sourceDate)
        assertEquals("b.md", scan.entries[1].fileName)
        assertEquals("2026-08-02", scan.entries[2].sourceDate)
        assertEquals(0, scan.errorCount)
    }

    @Test
    fun `source honours entry cap and reports countable errors for bad files`() {
        val many = (1..(DailyLogBounds.MAX_ENTRIES_PER_DAY + 20)).map { "条目$it" }
        val written = writeDoc(tmp.root, "2026-08-01", "big.md", many.take(DailyLogBounds.MAX_ENTRIES_PER_DAY))
        assertTrue(written != null)
        written!!.appendText("\n\n61. overflow")
        // A malformed day directory is ignored by the reader (not a valid day).
        writeDoc(tmp.root, "not-a-day", "x.md", listOf("ignored"))

        val source = DailyLogEventSourceFactory.overDirectory(tmp.root)
        val scan = source.load()
        assertEquals("2026-08-01", scan.entries.first().sourceDate)
        assertTrue(scan.entries.size <= DailyLogBounds.MAX_ENTRIES_PER_DAY)
        assertTrue(scan.errorCount >= 1) // over-ceiling file reported, not thrown
    }

    @Test
    fun `source never creates directories or files (read-only)`() {
        val source = DailyLogEventSourceFactory.overDirectory(tmp.root)
        source.load()
        // No daily-log root should have been created by reading.
        val root = File(tmp.root, DailyLogWorkspace.DAILY_LOG_ROOT_RELATIVE)
        assertFalse("reader must not create the daily-log tree", root.exists())
    }

    @Test
    fun `workspace write follows the layout the reader expects`() {
        val file = writeDoc(
            tmp.root,
            "2026-08-01",
            "wechat.logs.md",
            listOf("第一条", "第二条"),
            meta = mapOf("workflow" to "wechat"),
        )
        assertTrue(file!!.exists())
        assertTrue(file.path.replace('\\', '/').endsWith("workflows/daily-log/2026-08-01/wechat.logs.md"))
        // Round-trip through the reader.
        val scan = DailyLogEventSourceFactory.overDirectory(tmp.root).load()
        assertEquals(2, scan.entries.size)
        assertTrue(scan.entries[0].text == "第一条")
        assertEquals("2026-08-01/wechat.logs.md#1", scan.entries[0].sourceRef)
    }

    @Test
    fun `workspace rejects invalid day file and blank entries`() {
        assertTrue(DailyLogWorkspace.writeDocument(tmp.root, "bad-day", "x.md", listOf("a")) == null)
        assertTrue(DailyLogWorkspace.writeDocument(tmp.root, "2026-08-01", "x.txt", listOf("a")) == null)
        assertTrue(DailyLogWorkspace.writeDocument(tmp.root, "2026-08-01", "x.md", listOf("   ")) == null)
    }
}
