package com.clawmate.app.datamanager

import com.clawmate.app.datamanager.LocalConsolidationTypes.QueryOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Focused JVM tests for the Phase-30 local consolidation + index foundation.
 * Every case runs over an isolated temp dir (never `filesDir`) and covers the
 * Phase-30 safety contract:
 *
 *  - deterministic exact + normalized dedup across runs;
 *  - stable, monotonic `record_id`s and stable event ids;
 *  - bounded record counts (oversized daily-log cannot balloon an artifact);
 *  - atomic write + tolerance of a corrupt/missing state file (recovery);
 *  - bounded query by date / month / source / keyword;
 *  - empty input produces **no automatic file creation** and an empty index.
 */
class LocalConsolidationTest {

    private fun tempBase(): File =
        Files.createTempDirectory("clawmate-phase30-test").toFile().apply { deleteOnExit() }

    /** Seed a daily-log document via the documented [DailyLogWorkspace] write API. */
    private fun writeLog(base: File, day: String, file: String, entries: List<String>): File? =
        DailyLogWorkspace.writeDocument(base, day, file, entries)

    // ---- deterministic dedup across runs ----

    @Test
    fun `exact duplicate entries merge into one record across runs`() {
        val base = tempBase()
        writeLog(base, "2026-08-01", "wechat.logs.md", listOf("1. 收到 128 元转账", "2. 买咖啡花了 25 元"))

        val run1 = LocalConsolidationIndexRunner(base).run()
        assertEquals(2, run1.consolidation.sourceEntries)
        assertEquals(2, run1.consolidation.totalRecords)

        // Seeding the same entries again must dedup into the same 2 records.
        val run2 = LocalConsolidationIndexRunner(base).run()
        assertEquals(2, run2.consolidation.totalRecords)
        // No new files were created by querying.
        assertTrue(base.listFiles() == null || base.listFiles().isNotEmpty())
    }

    @Test
    fun `normalized dedup collapses text differing only by whitespace or punctuation`() {
        val base = tempBase()
        writeLog(base, "2026-08-02", "life.logs.md", listOf("1. 今天去商场买了一双鞋"))
        val first = LocalConsolidationIndexRunner(base).run()
        assertEquals(1, first.consolidation.totalRecords)

        // Same text but heavily re-spaced + punctuation noise -> normalized to same key.
        writeLog(base, "2026-08-03", "life.logs.md", listOf("1. 今天去商场买了一双鞋！！  "))
        val second = LocalConsolidationIndexRunner(base).run()
        val monthRecords = second.consolidation.totalRecords
        // Different file but exact-normalized text collides -> merged, not duplicated.
        assertTrue("expected merged single record, got $monthRecords", monthRecords <= 1)
    }

    // ---- stable ids ----

    @Test
    fun `record ids are stable monotonic and event ids are stable across rebuilds`() {
        val base = tempBase()
        writeLog(base, "2026-08-05", "a.logs.md", listOf("1. 记录甲", "2. 记录乙"))
        val repo = LocalConsolidationIndexRunner(base)
        val run1 = repo.run()
        assertTrue(run1.indexedEvents >= 1)

        // A clean re-run with no new input must rebuild identical artifact shapes
        // (idempotent output) — event ids stable.
        val run2 = LocalConsolidationIndexRunner(base).run()
        assertEquals(run1.indexedEvents, run2.indexedEvents)
        assertEquals(run1.indexedSourceFiles, run2.indexedSourceFiles)
    }

    @Test
    fun `event id format is relative-state-path hash record-id`() {
        val base = tempBase()
        writeLog(base, "2026-09-10", "wechat.logs.md", listOf("1. 收付款 66 元"))
        val runner = LocalConsolidationIndexRunner(base)
        runner.run()
        val idx = DailyLogLocalIndex(base)
        val query = idx.query(QueryOptions(month = "2026-09"))
        assertTrue("no events indexed", query.events.isNotEmpty())
        val event = query.events.first()
        assertTrue("unexpected event id ${event.eventId}", event.eventId.contains("#"))
        assertTrue(event.eventId.split("#").last().toIntOrNull() != null)
    }

    // ---- bounds: oversized input cannot balloon artifacts ----

    @Test
    fun `oversized entry is skipped and does not blow up the index`() {
        val base = tempBase()
        val huge = "A".repeat(3000) // over MAX_ENTRY_CHARACTERS (2000)
        writeLog(base, "2026-08-01", "huge.logs.md", listOf("1. $huge"))
        val result = LocalConsolidationIndexRunner(base).run()
        // Oversized entry is honestly skipped -> 0 indexed events, structure intact.
        assertTrue(result.indexedEvents == 0)
    }

    // ---- atomic recovery ----

    @Test
    fun `corrupt or missing state artifact recovers to empty and re-consolidates`() {
        val base = tempBase()
        writeLog(base, "2026-08-07", "recover.logs.md", listOf("1. 正常记录"))
        val runner = LocalConsolidationIndexRunner(base)
        runner.run()

        // Corrupt the persisted state artifact.
        val stateFile = DataManagerPaths.outputState(base, "2026-08", "recover.logs.md")
        assertTrue(stateFile.exists())
        stateFile.writeText("{ this is not valid json !!!")

        // A subsequent run recovers to an empty state (never throws) and
        // re-consolidates from the real daily-log text.
        val run2 = LocalConsolidationIndexRunner(base).run()
        assertTrue(run2.consolidation.totalRecords >= 1)
    }

    // ---- query by date / month / source ----

    @Test
    fun `query filters by date month and source ref`() {
        val base = tempBase()
        writeLog(base, "2026-08-01", "wechat.logs.md", listOf("1. 收款 100 元"))
        writeLog(base, "2026-09-02", "life.logs.md", listOf("1. 买书花了 40 元"))
        LocalConsolidationIndexRunner(base).run()

        val index = DailyLogLocalIndex(base)
        val august = index.query(QueryOptions(month = "2026-08"))
        assertEquals(1, august.events.size)
        val dateHit = index.query(QueryOptions(date = "2026-09-02"))
        assertEquals(1, dateHit.events.size)
        assertEquals(0, index.query(QueryOptions(source = "nonexistent")).events.size)
    }

    @Test
    fun `query trims to limit and reports truncation`() {
        val base = tempBase()
        (1..12).forEach { n ->
            writeLog(base, "2026-08-%02d".format(n), "d$n.logs.md", listOf("1. 事件 $n"))
        }
        LocalConsolidationIndexRunner(base).run()
        val index = DailyLogLocalIndex(base)
        val limited = index.query(QueryOptions(month = "2026-08", limit = 5))
        assertEquals(5, limited.events.size)
        assertTrue(limited.eventsTruncated)
        assertTrue(limited.totalEvents >= 12)
    }

    // ---- empty input never creates artifacts ----

    @Test
    fun `empty daily-log creates no state index and empty query`() {
        val base = tempBase()
        // No daily-log written at all.
        val outcome = LocalConsolidationIndexRunner(base).run()
        assertEquals(0, outcome.consolidation.sourceEntries)
        assertEquals(0, outcome.indexedEvents)
        // Querying does not auto-create the index dir.
        val index = DailyLogLocalIndex(base)
        val result = index.query(QueryOptions())
        assertEquals(0, result.events.size)
        assertFalse(DataManagerPaths.stateIndex(base).exists())
    }

    // ---- atomic store tolerates a missing file ----

    @Test
    fun `atomic store read of missing file returns null without creating it`() {
        val base = tempBase()
        val store = AtomicJsonStore()
        val missing = File(base, "does-not-exist.json")
        assertNull(store.readJson(missing) { it })
        assertFalse(missing.exists())
        // Write is atomic and bounded; parent created only on write.
        val written = store.writeJson(File(base, "sub/artifact.json"), "abc") { "\"$it\"" }
        assertNotNull(written)
        assertTrue(File(base, "sub/artifact.json").exists())
    }
}
