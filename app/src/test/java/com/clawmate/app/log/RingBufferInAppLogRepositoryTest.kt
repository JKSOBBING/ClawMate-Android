package com.clawmate.app.log

import com.clawmate.app.log.data.RingBufferInAppLogRepository
import com.clawmate.app.log.domain.LogLevel
import com.clawmate.app.log.domain.LogSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Deterministic tests for the bounded in-app log ring buffer. */
class RingBufferInAppLogRepositoryTest {

    private fun entry(seq: Long, level: LogLevel = LogLevel.INFO, source: LogSource = LogSource.APPLICATION, msg: String = "m$seq") =
        com.clawmate.app.log.domain.InAppLogEntry(
            sequence = seq,
            timestampMillis = RingBufferInAppLogRepository.DEFAULT_TIMESTAMP_BASE + seq,
            level = level,
            source = source,
            tag = "t$seq",
            message = msg,
        )

    @Test
    fun `retains newest entries up to capacity evicting oldest`() {
        val repo = RingBufferInAppLogRepository.createForTest(capacity = 5, sequence = 1L)
        repo.append(entry(1))
        repo.append(entry(2))
        repo.append(entry(3))
        repo.append(entry(4))
        repo.append(entry(5))
        repo.append(entry(6)) // evicts entry 1
        val snap = repo.snapshot()
        assertEquals(5, snap.size)
        assertEquals(listOf(6L, 5L, 4L, 3L, 2L), snap.map { it.sequence })
        assertTrue(snap.none { it.sequence == 1L })
    }

    @Test
    fun `snapshot returns newest first`() {
        val repo = RingBufferInAppLogRepository.createForTest(capacity = 10, sequence = 1L)
        repo.append(entry(1))
        repo.append(entry(2))
        repo.append(entry(3))
        assertEquals(listOf(3L, 2L, 1L), repo.snapshot().map { it.sequence })
    }

    @Test
    fun `clear scoped to one source keeps other sources`() {
        val repo = RingBufferInAppLogRepository.createForTest(capacity = 20, sequence = 1L)
        repo.append(entry(1, source = LogSource.APPLICATION))
        repo.append(entry(2, source = LogSource.CLOUD_AGENT))
        repo.append(entry(3, source = LogSource.GALLERY))
        repo.clear(LogSource.CLOUD_AGENT)
        val remaining = repo.snapshot().map { it.sequence }
        assertEquals(setOf(3L, 1L), remaining.toSet())
    }

    @Test
    fun `clear all empties the buffer`() {
        val repo = RingBufferInAppLogRepository.createForTest(capacity = 20, sequence = 1L)
        repo.append(entry(1))
        repo.append(entry(2))
        repo.clear()
        assertEquals(0, repo.snapshot().size)
        assertTrue(repo.snapshot().isEmpty())
    }

    @Test
    fun `entries at or above min level are retained`() {
        val repo = RingBufferInAppLogRepository.createForTest(capacity = 20, sequence = 1L)
        repo.append(entry(1, level = LogLevel.DEBUG))
        repo.append(entry(2, level = LogLevel.WARN))
        repo.append(entry(3, level = LogLevel.ERROR))
        val levels = repo.snapshot().map { it.level }.toSet()
        assertTrue(levels.contains(LogLevel.DEBUG))
        assertTrue(levels.contains(LogLevel.WARN))
        assertTrue(levels.contains(LogLevel.ERROR))
    }

    @Test
    fun `sensitive message is redacted on insert`() {
        val repo = RingBufferInAppLogRepository.createForTest(capacity = 20, sequence = 1L)
        repo.append(entry(1, msg = "key sk-1234567890abcdef leaked"))
        val stored = repo.snapshot().first().message
        assertTrue(!stored.contains("sk-1234567890abcdef"))
        assertTrue(stored.contains("[REDACTED]"))
    }

    @Test
    fun `long message is truncated to the scan bound`() {
        val repo = RingBufferInAppLogRepository(
            capacity = 20,
            minKeepLevel = LogLevel.DEBUG,
            maxMessageLength = 50,
            clock = { 1L },
        )
        val longMsg = "x".repeat(500)
        repo.append(entry(1, msg = longMsg))
        val stored = repo.snapshot().first().message
        assertTrue(stored.length <= 51) // 50 + ellipsis
        assertTrue(stored.endsWith("…"))
    }
}
