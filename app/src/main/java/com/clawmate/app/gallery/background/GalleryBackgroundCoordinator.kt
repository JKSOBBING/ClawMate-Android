package com.clawmate.app.gallery.background

import com.clawmate.app.gallery.cloud.GalleryCloudAdapter
import com.clawmate.app.gallery.data.GalleryRepository
import com.clawmate.app.gallery.insights.GalleryMonthlyInsightCloudState
import com.clawmate.app.gallery.model.GalleryPhotoItem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * THE serial, opt-in, cancellable, idempotent background gallery scan/sync
 * coordinator (migration row D3).
 *
 * This is the realisation of the Harmony `GalleryAutoScanner` + `GalleryMemorySync`
 * boundary on Android. It lives entirely on the existing coroutine + Room stack
 * (no WorkManager / JobScheduler / Service dependency is added — the smallest
 * platform-compatible mechanism): callers invoke [runOnce], which is guarded so
 * at most one run executes at a time, and each run is fully cooperative on a
 * single coroutine.
 *
 * Safety contract (mirrors the Harmony semantics without the silent parts):
 *  - **Never silent**: [runOnce] first evaluates the live [GallerySyncGate]
 *    (explicit user opt-in + runtime photo-access grant). If the gate is not
 *    READY the run returns NOT_CONFIGURED / BLOCKED and no media is touched.
 *  - **Bounded**: it asks the media source for at most
 *    [GallerySyncPolicy.clampScanLimit] items and folds them in bounded batches
 *    ([GallerySyncPolicy.batchSizes]); wall-clock work is capped at
 *    [GallerySyncPolicy.MAX_WORK_DURATION_MS].
 *  - **Cancellable / idempotent**: [cancelActive] flags a cooperative stop checked
 *    between batches; a cancelled run returns an honest PARTIAL. Re-running the
 *    same URIs is a no-op because the Room repository upserts by content URI
 *    (nullable, deterministic — never duplicates).
 *  - **No secret/cloud upload**: the optional "memory sync" step calls **only**
 *    the explicitly configured [GalleryCloudAdapter] (`generateMonthlySummary`
 *    on bounded aggregate text). If no adapter is configured or its summary
 *    state is not READY, NO bytes are ever sent
 *    ([GalleryCloudSyncStatus.SKIPPED_NOT_CONFIGURED]). The coordinator never
 *    invents embeddings or AI summaries.
 *
 * @param scanLimit how many newest items one run may touch (clamped internally).
 * @param clockMs injectable wall clock so tests can drive work-duration bounds.
 */
class GalleryBackgroundCoordinator(
    private val gate: () -> GallerySyncGate,
    private val mediaSource: GalleryMediaSource,
    private val repository: GalleryRepository,
    private val cloudAdapterProvider: () -> GalleryCloudAdapter? = { null },
    private val scanLimit: Int = GallerySyncPolicy.DEFAULT_SCAN_LIMIT,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
    private val serialMutex = Mutex()
    private val cancelFlag = AtomicBoolean(false)
    private val activeRun = AtomicBoolean(false)

    @Volatile
    private var lastRunCompletedAtMs: Long? = null

    /** True when a run is currently executing (serial, single-flight). */
    val isRunning: Boolean get() = activeRun.get()

    /** Flag a cooperative cancellation request; the next batch boundary honours it. */
    fun cancelActive() {
        cancelFlag.set(true)
    }

    /**
     * Whether a "run now" may start, purely (no in-flight work). Used so the UI
     * surfaces the gate / coalescing decision before invoking a run.
     */
    fun schedulingDecision(nowMs: Long = clockMs()): GallerySchedulingDecision =
        GallerySyncPolicy.decideRun(
            nowMs = nowMs,
            lastRunAtMs = lastRunCompletedAtMs,
            gateStatus = gate().status,
        )

    /**
     * Runs one serial, cooperative, bounded scan/sync. Safe from any thread: if
     * a run is already in flight this returns [GallerySyncState.Partial] with a
     * truthful "already running" note rather than starting a second scan.
     *
     * If the scheduling gate is not READY the run short-circuits with the honest
     * gate state and does not touch the media library.
     */
    suspend fun runOnce(): GallerySyncState {
        if (!serialMutex.tryLock()) {
            return GallerySyncState.Partial(runSummary(0, 0, 0, 0, 0, skippedReason = "任务已在执行"))
        }
        return try {
            activeRun.set(true)
            runLocked()
        } finally {
            activeRun.set(false)
            cancelFlag.set(false)
            serialMutex.unlock()
        }
    }

    private suspend fun runLocked(): GallerySyncState {
        val g = gate()
        if (g.status == GallerySyncGateStatus.NOT_CONFIGURED) return GallerySyncState.NotConfigured
        if (g.status == GallerySyncGateStatus.BLOCKED) return GallerySyncState.Blocked(g.statusLabel())

        val started = clockMs()
        val deadline = started + GallerySyncPolicy.MAX_WORK_DURATION_MS

        // Bounded scan: never ask the media library for more than the cap.
        val limit = GallerySyncPolicy.clampScanLimit(scanLimit)
        val inputs = runCatching { mediaSource.scanNewest(limit) }
            .getOrElse { e ->
                return GallerySyncState.Error(
                    "媒体扫描失败（未发生任何上传）：${e.message ?: e.javaClass.simpleName}",
                )
            }
        val candidates = inputs.take(limit).map { it.toItem() }

        // Fold in bounded sequential batches, checking cancel + work-duration
        // between batches. Dedup is enforced by the Room URI-unique upsert.
        val batches = GallerySyncPolicy.batchSizes(candidates.size)
        var processed = 0
        val storedCount = AtomicInteger(0)

        for ((index, batchSize) in batches.withIndex()) {
            if (cancelFlag.get() || clockMs() >= deadline) {
                val skipped = remainingFor(index, batches)
                return GallerySyncState.Partial(
                    runSummary(
                        candidateCount = candidates.size,
                        scannedNewCount = processed,
                        skippedCount = skipped,
                        localStored = storedCount.get(),
                        elapsedMs = clockMs() - started,
                        skippedReason = if (cancelFlag.get()) "已取消" else "达到单次工作时长上限",
                    ),
                )
            }
            val chunk = candidates.subList(processed, processed + batchSize)
            val stored = runCatching { repository.importItems(chunk) }
                .getOrElse { e ->
                    return GallerySyncState.Error(
                        "本地入库失败（未发生任何上传）：${e.message ?: e.javaClass.simpleName}",
                    )
                }
            storedCount.addAndGet(stored.size)
            processed += batchSize
        }

        lastRunCompletedAtMs = clockMs()

        val cloud = syncCloudIfConfigured()
        val touched = processed
        val summary = runSummary(
            candidateCount = candidates.size,
            scannedNewCount = touched,
            skippedCount = 0,
            localStored = storedCount.get(),
            elapsedMs = clockMs() - started,
            cloudSync = cloud,
        )
        // Honest mapping: a completed local scan is SUCCESS even when the cloud
        // step was SKIPPED_NOT_CONFIGURED (nothing was sent, nothing to report as
        // failure); a FAILED cloud step makes the run PARTIAL.
        return if (cloud == GalleryCloudSyncStatus.FAILED) {
            GallerySyncState.Partial(summary)
        } else {
            GallerySyncState.Success(summary)
        }
    }

    private fun remainingFor(startIndex: Int, batches: List<Int>): Int =
        batches.drop(startIndex).sum()

    /**
     * The only code path that can touch the network in this coordinator. It
     * short-circuits to [GalleryCloudSyncStatus.SKIPPED_NOT_CONFIGURED] unless an
     * explicitly configured [GalleryCloudAdapter] exists AND reports a READY
     * summary state. The aggregate is derived from what is *actually stored*
     * (previously analysed items), never from a blank scan cursor. It never
     * invents embeddings / AI summaries and never sends gallery payloads when
     * unconfigured.
     */
    private suspend fun syncCloudIfConfigured(): GalleryCloudSyncStatus {
        // The D2 "memory sync" step routes ONLY through an explicitly configured
        // cloud adapter; there is no other outbound path in this coordinator.
        val adapter = cloudAdapterProvider() ?: return GalleryCloudSyncStatus.SKIPPED_NOT_CONFIGURED
        if (adapter.summaryState !is GalleryMonthlyInsightCloudState.Ready) {
            return GalleryCloudSyncStatus.SKIPPED_NOT_CONFIGURED
        }
        val stored = runCatching { repository.observeItems().first() }.getOrDefault(emptyList())
        if (stored.isEmpty()) return GalleryCloudSyncStatus.SKIPPED_NOT_CONFIGURED
        val aggregate = buildAggregate(stored)
        if (aggregate.isBlank()) return GalleryCloudSyncStatus.SKIPPED_NOT_CONFIGURED
        return try {
            // Only summary generation is invoked (D2). Embeddings are never
            // auto-invoked here — no invented embeddings.
            @Suppress("UNUSED_VARIABLE")
            val result = adapter.generateMonthlySummary(aggregate)
            GalleryCloudSyncStatus.RUN
        } catch (t: Throwable) {
            GalleryCloudSyncStatus.FAILED
        }
    }

    /** A bounded, deterministic aggregate over the stored items. Only items
     * that actually carry content are included; a blank aggregate means there
     * is nothing meaningful to sync (never invented summaries). */
    private suspend fun buildAggregate(stored: List<GalleryPhotoItem>): String = buildString {
        for (item in stored.take(60)) {
            if (item.uri.isBlank()) continue
            val summary = item.summary.trim()
            if (summary.isEmpty()) continue
            append(item.date).append(' ').append(item.title).append('：').append(summary).append('\n')
        }
    }.take(com.clawmate.app.gallery.cloud.GalleryCloudBounds.MAX_SUMMARY_CHARACTERS).trim()

    private fun GalleryPhotoInput.toItem(): GalleryPhotoItem = GalleryPhotoItem(
        id = id,
        uri = uri,
        date = date,
        title = "图库图片",
        category = "",
        summary = "",
        details = emptyList(),
        tags = emptyList(),
        ocrText = "",
        rawModelText = "",
        keywords = emptyList(),
        latitude = latitude,
        longitude = longitude,
        exif = null,
        createdAt = clockMs(),
        analysisState = GalleryPhotoItem.ANALYSIS_STATE_NONE,
    )

    private fun runSummary(
        candidateCount: Int,
        scannedNewCount: Int,
        skippedCount: Int,
        localStored: Int,
        elapsedMs: Long,
        cloudSync: GalleryCloudSyncStatus = GalleryCloudSyncStatus.SKIPPED_NOT_CONFIGURED,
        skippedReason: String? = null,
    ): GallerySyncRunSummary = GallerySyncRunSummary(
        candidateCount = candidateCount,
        scannedNewCount = scannedNewCount,
        skippedCount = skippedCount,
        localStored = localStored,
        cloudSync = cloudSync,
        elapsedMs = elapsedMs,
        skippedReason = skippedReason,
    )
}
