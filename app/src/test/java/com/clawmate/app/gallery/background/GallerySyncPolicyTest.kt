package com.clawmate.app.gallery.background

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Device-free tests for the pure scheduling + access-policy layer of the
 * Phase 23 Gallery background boundary (rows D3: GalleryAutoScanner +
 * GalleryMemorySync scheduling gate + bounds).
 *
 * These assert the honest scheduling gate (NOT_CONFIGURED / BLOCKED / READY),
 * the coalescing/idempotent scheduling window, and the bounded-batch / bounded
 * scan-limit invariants — all with no Android framework and no device.
 */
class GallerySyncPolicyTest {

    // ------------------------------------------------------- scheduling gate

    @Test
    fun scheduling_refusesWhenNotConfigured() {
        val decision = GallerySyncPolicy.decideRun(
            nowMs = 1000,
            lastRunAtMs = null,
            gateStatus = GallerySyncGateStatus.NOT_CONFIGURED,
        )
        assertEquals(GallerySchedulingDecision.NotConfigured, decision)
    }

    @Test
    fun scheduling_refusesWhenBlocked() {
        val decision = GallerySyncPolicy.decideRun(
            nowMs = 1000,
            lastRunAtMs = null,
            gateStatus = GallerySyncGateStatus.BLOCKED,
        )
        assertEquals(GallerySchedulingDecision.Blocked, decision)
    }

    @Test
    fun scheduling_allowsFreshRunWhenReady() {
        val decision = GallerySyncPolicy.decideRun(
            nowMs = 1000,
            lastRunAtMs = null,
            gateStatus = GallerySyncGateStatus.READY,
        )
        assertEquals(GallerySchedulingDecision.Run, decision)
        // After an old completed run (well past the coalescing window) it also runs.
        val later = GallerySyncPolicy.decideRun(
            nowMs = GallerySyncPolicy.MIN_RUN_INTERVAL_MS + 1,
            lastRunAtMs = 0,
            gateStatus = GallerySyncGateStatus.READY,
        )
        assertEquals(GallerySchedulingDecision.Run, later)
    }

    @Test
    fun scheduling_coalescesRecentRun() {
        val now = 10_000L
        val decision = GallerySyncPolicy.decideRun(
            nowMs = now,
            lastRunAtMs = now - 1_000L, // inside the coalescing window
            gateStatus = GallerySyncGateStatus.READY,
        )
        assertTrue(decision is GallerySchedulingDecision.Coalesced)
        assertTrue((decision as GallerySchedulingDecision.Coalesced).delayMs > 0)
    }

    // ----------------------------------------------------------- bounds

    @Test
    fun scanLimit_isAlwaysClamped() {
        assertEquals(GallerySyncPolicy.MAX_ITEMS_PER_RUN, GallerySyncPolicy.clampScanLimit(10_000))
        assertEquals(1, GallerySyncPolicy.clampScanLimit(0))
        assertEquals(GallerySyncPolicy.DEFAULT_SCAN_LIMIT, GallerySyncPolicy.clampScanLimit(GallerySyncPolicy.DEFAULT_SCAN_LIMIT))
    }

    @Test
    fun batchSizes_neverExceedCap() {
        for (n in listOf(0, 1, 19, 20, 21, 100, 61)) {
            GallerySyncPolicy.batchSizes(n).forEach { assertTrue("batch size $it for $n", it <= GallerySyncPolicy.MAX_ITEMS_PER_BATCH) }
        }
    }

    @Test
    fun gate_statusLabel_isHonest() {
        val g = GallerySyncGate(optedIn = true, photoAccessGranted = false)
        assertTrue(g.statusLabel().contains("BLOCKED"))
        val notCfg = GallerySyncGate(optedIn = false, photoAccessGranted = true)
        assertTrue(notCfg.statusLabel().contains("NOT_CONFIGURED"))
        val ready = GallerySyncGate(optedIn = true, photoAccessGranted = true)
        assertTrue(ready.statusLabel().contains("READY"))
    }
}
