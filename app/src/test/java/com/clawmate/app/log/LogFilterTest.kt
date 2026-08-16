package com.clawmate.app.log

import com.clawmate.app.log.domain.InAppLogEntry
import com.clawmate.app.log.domain.LogFilter
import com.clawmate.app.log.domain.LogLevel
import com.clawmate.app.log.domain.LogSource
import com.clawmate.app.log.domain.filterLogs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Deterministic tests for the pure [filterLogs] projection. */
class LogFilterTest {

    private fun e(
        seq: Long,
        level: LogLevel = LogLevel.INFO,
        source: LogSource = LogSource.APPLICATION,
        tag: String = "t$seq",
        msg: String = "message $seq",
    ) = InAppLogEntry(seq, 1_700_000_000_000L + seq, level, source, tag, msg)

    // Input is newest-first (the exact order RingBufferInAppLogRepository.snapshot() returns).
    private val samples = listOf(
        e(5, LogLevel.INFO, LogSource.GALLERY, tag = "Gallery", msg = "ocr done"),
        e(4, LogLevel.WARN, LogSource.CLOUD_AGENT, tag = "Agent", msg = "retry"),
        e(3, LogLevel.DEBUG, LogSource.APPLICATION, tag = "Cache", msg = "hit miss"),
        e(2, LogLevel.ERROR, LogSource.APPLICATION, tag = "App", msg = "network timeout"),
        e(1, LogLevel.INFO, LogSource.APPLICATION, tag = "App", msg = "launch ok"),
    )

    @Test
    fun `filter by source only`() {
        val out = filterLogs(samples, LogFilter(source = LogSource.APPLICATION))
        assertEquals(listOf(3L, 2L, 1L), out.map { it.sequence })
    }

    @Test
    fun `filter by minimum level`() {
        val out = filterLogs(samples, LogFilter(source = LogSource.APPLICATION, minLevel = LogLevel.WARN))
        // WARN/ERROR only for APPLICATION: seq 2 (ERROR).
        assertEquals(listOf(2L), out.map { it.sequence })
    }

    @Test
    fun `filter by query on message`() {
        val out = filterLogs(samples, LogFilter(source = LogSource.APPLICATION, query = "timeout"))
        assertEquals(listOf(2L), out.map { it.sequence })
    }

    @Test
    fun `query is case insensitive`() {
        val out = filterLogs(samples, LogFilter(source = LogSource.APPLICATION, query = "LAUNCH"))
        assertEquals(listOf(1L), out.map { it.sequence })
    }

    @Test
    fun `filter by query on tag`() {
        val out = filterLogs(samples, LogFilter(source = LogSource.APPLICATION, query = "cache"))
        assertEquals(listOf(3L), out.map { it.sequence })
    }

    @Test
    fun `limit bounds results`() {
        val out = filterLogs(samples, LogFilter(source = LogSource.APPLICATION, limit = 2))
        assertEquals(listOf(3L, 2L), out.map { it.sequence })
    }

    @Test
    fun `other sources are never leaked into a filter`() {
        val out = filterLogs(samples, LogFilter(source = LogSource.GALLERY))
        assertTrue(out.all { it.source == LogSource.GALLERY })
        assertEquals(listOf(5L), out.map { it.sequence })
    }
}
