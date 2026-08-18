package com.clawmate.app.gallery.background

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Presenter for the explicit "扫描/同步图库" user action (Phase 42, work
 * package B).
 *
 * The only entry is an explicit [scanNow] call — never on launch, onCreate,
 * recomposition or page load. It hands the coordinator's own serial, opt-in,
 * cancellable, idempotent boundary to the UI and projects each real result onto
 * [GalleryScanUiState]. It performs **no** network call by itself: the optional
 * cloud memory-sync step lives entirely inside the injected
 * [GalleryBackgroundCoordinator] and only ever runs through an explicitly
 * configured cloud adapter; a completed local scan is reported as a local
 * success and is never passed off as a "云记忆同步成功".
 *
 * [cancelActive] forwards the coordinator's cooperative cancellation, and a
 * cancelled run surfaces an honest PARTIAL.
 */
class GalleryScanViewModel(
    private val coordinator: GalleryBackgroundCoordinator,
    private val gateProvider: () -> GallerySyncGate,
) : ViewModel() {

    private val _state = MutableStateFlow<GalleryScanUiState>(GalleryScanUiState.Idle(gateProvider()))
    val state: StateFlow<GalleryScanUiState> = _state

    /** Re-derive the idle precondition (after a permission result or a settings change). */
    fun refreshGate() {
        _state.value = when (val s = _state.value) {
            is GalleryScanUiState.Idle -> GalleryScanUiState.Idle(gateProvider())
            else -> s
        }
    }

    /** True while a coordinated run is in flight (single-flight). */
    val isRunning: Boolean get() = coordinator.isRunning

    /** Forward a cooperative cancellation request to the coordinator. */
    fun cancelActive() {
        coordinator.cancelActive()
    }

    /**
     * Execute one explicit scan/sync run. This is the single production entry:
     * it first consults the coordinator's honest scheduling decision (opt-in
     * gate + permission grant + coalescing window) and only starts a real,
     * bounded, serial scan when the decision is [GallerySchedulingDecision.Run].
     * Any other decision surfaces the honest precondition without touching the
     * media library.
     */
    fun scanNow() {
        // Single-flight at the coordination layer is already enforced by the
        // coordinator's mutex; guard here so the UI does not even dispatch when
        // a run is in flight.
        if (coordinator.isRunning) {
            _state.value = GalleryScanUiState.Partial(
                GallerySyncRunSummary(
                    candidateCount = 0,
                    scannedNewCount = 0,
                    skippedCount = 0,
                    localStored = 0,
                    cloudSync = GalleryCloudSyncStatus.SKIPPED_NOT_CONFIGURED,
                    elapsedMs = 0L,
                    skippedReason = "任务已在执行",
                ),
            )
            return
        }
        val decision = coordinator.schedulingDecision()
        when (decision) {
            is GallerySchedulingDecision.NotConfigured -> {
                _state.value = GalleryScanUiState.Idle(gateProvider())
            }
            is GallerySchedulingDecision.Blocked -> {
                _state.value = GalleryScanUiState.Failed("未授予图库读取权限 — BLOCKED")
            }
            is GallerySchedulingDecision.Coalesced -> {
                _state.value = GalleryScanUiState.Partial(
                    GallerySyncRunSummary(
                        candidateCount = 0,
                        scannedNewCount = 0,
                        skippedCount = 0,
                        localStored = 0,
                        cloudSync = GalleryCloudSyncStatus.SKIPPED_NOT_CONFIGURED,
                        elapsedMs = 0L,
                        skippedReason = "距上次同步过近，本次跳过（幂等）",
                    ),
                )
            }
            GallerySchedulingDecision.Run -> {
                _state.value = GalleryScanUiState.Running
                viewModelScope.launch {
                    val result: GallerySyncState = runCatching { coordinator.runOnce() }
                        .getOrElse { e ->
                            GallerySyncState.Error(
                                "扫描失败：${e.message ?: e.javaClass.simpleName}",
                            )
                        }
                    // Only overwrite the presented state with a real coordinator
                    // outcome; a cancellation that races a refresh is handled by
                    // the coordinator returning PARTIAL.
                    _state.value = GalleryScanUiState.of(result)
                }
            }
        }
    }

    class Factory(
        private val coordinator: GalleryBackgroundCoordinator,
        private val gateProvider: () -> GallerySyncGate,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(GalleryScanViewModel::class.java)) {
                return GalleryScanViewModel(coordinator, gateProvider) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}
