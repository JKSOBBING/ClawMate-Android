package com.clawmate.app.gallery.background

/**
 * Typed, honest state set for the Gallery background scan/sync coordinator
 * (migration row D3: `GalleryAutoScanner.ets` + `GalleryMemorySync.ets`).
 *
 * The Harmony source scans the whole media library and writes into the
 * DataManager memory pipeline as background work. On Android that must never
 * happen silently: it requires an explicit user opt-in **and** a runtime
 * media/photo-access grant, runs serially with bounded batches and work
 * duration, is cancellable/idempotent, and never uploads anything unless an
 * explicitly configured cloud adapter is present. This sealed hierarchy is the
 * single honest vocabulary every caller (Settings status, coordinator result,
 * tests) speaks.
 *
 * The requested state set is exactly:
 *   NOT_CONFIGURED / BLOCKED / READY / RUNNING / SUCCESS / PARTIAL / ERROR
 */
sealed interface GallerySyncState {
    /** Boundary not armed yet — the user has not explicitly opted in. */
    data object NotConfigured : GallerySyncState

    /** Opted in but a runtime precondition is missing (media permission). */
    data class Blocked(val reason: String) : GallerySyncState

    /** Armed and permitted — a run may proceed (no run has started). */
    data object Ready : GallerySyncState

    /** A run is in flight; [processedCount] / [totalCount] are honest progress. */
    data class Running(val processedCount: Int, val totalCount: Int) : GallerySyncState

    /** A run finished; every candidate was scanned/stored (cloud may be off). */
    data class Success(val summary: GallerySyncRunSummary) : GallerySyncState

    /**
     * A run finished but only part of the work happened — e.g. some items were
     * skipped by a bound or cancellation, or the optional cloud step did not
     * run because it was not configured. Never claims a full sync.
     */
    data class Partial(val summary: GallerySyncRunSummary) : GallerySyncState

    /** A run failed; [reason] is honest (never a fabricated success). */
    data class Error(val reason: String) : GallerySyncState

    companion object {
        /** Highest-status precedence helper for honest aggregate reporting. */
        fun notConfigured(): GallerySyncState = NotConfigured
    }
}

/**
 * Honest, machine-readable summary of one coordinator run.
 *
 * [scannedNewCount] counts genuinely new items folded into the local store;
 * [candidateCount] is how many eligible items the media source reported;
 * [skippedCount] is how many were intentionally not processed (bound/preempted);
 * [localStored] is how many item rows the run upserted deterministically.
 * [cloudSync] describes what (if anything) the run sent over the network — it
 * is [GalleryCloudSyncStatus.SKIPPED_NOT_CONFIGURED] when no explicitly
 * configured cloud adapter was present, in which case **no** bytes were ever
 * transmitted.
 */
data class GallerySyncRunSummary(
    val candidateCount: Int,
    val scannedNewCount: Int,
    val skippedCount: Int,
    val localStored: Int,
    val cloudSync: GalleryCloudSyncStatus,
    val elapsedMs: Long,
    /** Honest reason when [skippedCount] is non-zero (cancel/time-bound). */
    val skippedReason: String? = null,
)

/** What the optional cloud memory-sync step did (never invents a call). */
enum class GalleryCloudSyncStatus {
    /** Ran through the explicitly configured [com.clawmate.app.gallery.cloud.GalleryCloudAdapter]. */
    RUN,
    /** No explicitly configured cloud adapter — nothing was sent. */
    SKIPPED_NOT_CONFIGURED,
    /** A configured adapter was present but the call failed honestly. */
    FAILED,
}
