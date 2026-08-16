package com.clawmate.app.gallery.background

/**
 * Bounds and pure scheduling/batching policy for the Gallery background
 * scan/sync coordinator (migration row D3).
 *
 * Everything here is pure and device-free so the coordinator proves: it always
 * runs serially, never scans more than a bounded number of items per run, never
 * lets a single work slice exceed a bounded wall-clock duration, and coalesces
 * repeated "run now" requests so a run cannot be silently re-fired too often.
 */
object GallerySyncPolicy {

    // ------------------------------------------------------------------ bounds
    /** Hard cap on items a single coordinated run may touch. */
    const val MAX_ITEMS_PER_RUN = 60

    /** Hard cap on a single sequential batch size (bounded work slice). */
    const val MAX_ITEMS_PER_BATCH = 20

    /** Hard cap on wall-clock work for a single run (graceful preemption). */
    const val MAX_WORK_DURATION_MS = 60_000L

    /** Coalescing floor: "run now" requests within this window become no-ops. */
    const val MIN_RUN_INTERVAL_MS = 10 * 60_000L

    /** Default newest-N scan limit when the user has not bounded it further. */
    const val DEFAULT_SCAN_LIMIT = MAX_ITEMS_PER_RUN

    /**
     * How many newest items the media source is asked for. Clamped into
     * `1..[MAX_ITEMS_PER_RUN]` so callers can never request an unbounded scan.
     */
    fun clampScanLimit(requested: Int): Int =
        requested.coerceIn(1, MAX_ITEMS_PER_RUN)

    /**
     * Splits [itemCount] candidates into sequential batch sizes, each no larger
     * than [MAX_ITEMS_PER_BATCH]. Returns an empty list when there is nothing to
     * do. Pure — used to prove batches are always bounded.
     */
    fun batchSizes(itemCount: Int): List<Int> {
        if (itemCount <= 0) return emptyList()
        val batches = ArrayList<Int>()
        var remaining = itemCount
        while (remaining > 0) {
            batches.add(remaining.coerceAtMost(MAX_ITEMS_PER_BATCH))
            remaining -= MAX_ITEMS_PER_BATCH
        }
        return batches
    }

    /**
     * Pure scheduling gate: whether an explicit "run now" may start at time
     * [nowMs] given the previous run's [lastRunAtMs] and the live [gate].
     *
     *  - [GallerySyncGateStatus.NOT_CONFIGURED] -> not armed.
     *  - [GallerySyncGateStatus.BLOCKED] -> armed but permission missing.
     *  - [GallerySyncGateStatus.READY] -> may run, but coalesce if the last run
     *    finished inside [MIN_RUN_INTERVAL_MS] (idempotent scheduling).
     *
     * Returns a pure [GallerySchedulingDecision] so tests assert the gate and
     * the coalescing window independently of any Android scheduler.
     */
    fun decideRun(
        nowMs: Long,
        lastRunAtMs: Long?,
        gateStatus: GallerySyncGateStatus,
    ): GallerySchedulingDecision = when (gateStatus) {
        GallerySyncGateStatus.NOT_CONFIGURED -> GallerySchedulingDecision.NotConfigured
        GallerySyncGateStatus.BLOCKED -> GallerySchedulingDecision.Blocked
        GallerySyncGateStatus.READY -> {
            if (lastRunAtMs != null && nowMs - lastRunAtMs < MIN_RUN_INTERVAL_MS) {
                GallerySchedulingDecision.Coalesced(delayMs = MIN_RUN_INTERVAL_MS - (nowMs - lastRunAtMs))
            } else {
                GallerySchedulingDecision.Run
            }
        }
    }
}

/** Pure outcome of a scheduling decision (mirrors the honest state vocabulary). */
sealed interface GallerySchedulingDecision {
    /** Not armed — do nothing, report NOT_CONFIGURED. */
    data object NotConfigured : GallerySchedulingDecision

    /** Armed but permission missing — do nothing, report BLOCKED. */
    data object Blocked : GallerySchedulingDecision

    /** Within the coalescing window — do nothing (idempotent); [delayMs] until free. */
    data class Coalesced(val delayMs: Long) : GallerySchedulingDecision

    /** May start a serial run now. */
    data object Run : GallerySchedulingDecision
}
