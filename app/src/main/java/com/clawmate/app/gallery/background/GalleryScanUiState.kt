package com.clawmate.app.gallery.background

/**
 * Typed, honest UI state for the explicit "扫描/同步图库" entry (Phase 42).
 *
 * This is a thin, presenter-level projection of the coordinator's own honest
 * vocabulary ([GallerySyncState] / [GallerySyncGateStatus]) so the Gallery
 * screen never has to interpret raw states and never invents a success or a
 * fabricated "云记忆同步成功". A badge/outcome is only ever derived from a real
 * coordinator result.
 */
sealed interface GalleryScanUiState {

    /** No explicit action has been performed yet; [gate] is the live precondition. */
    data class Idle(val gate: GallerySyncGate) : GalleryScanUiState

    /** The explicit action decided the run may start and is now in flight. */
    data object Running : GalleryScanUiState

    /** A run finished successfully (local scan/storage done). */
    data class Succeeded(val summary: GallerySyncRunSummary) : GalleryScanUiState

    /** A run finished but only part of the work happened (bound/cancel/cloud-fail). */
    data class Partial(val summary: GallerySyncRunSummary) : GalleryScanUiState

    /** A run failed honestly; [reason] is never a fabricated success. */
    data class Failed(val reason: String) : GalleryScanUiState

    companion object {
        /** Map a coordinator result onto the UI vocabulary. */
        fun of(result: GallerySyncState): GalleryScanUiState = when (result) {
            is GallerySyncState.Success -> Succeeded(result.summary)
            is GallerySyncState.Partial -> Partial(result.summary)
            is GallerySyncState.Error -> Failed(result.reason)
            is GallerySyncState.Blocked -> Failed(result.reason)
            GallerySyncState.NotConfigured -> Idle(GallerySyncGate(false, false))
            GallerySyncState.Ready -> Idle(GallerySyncGate(true, true))
            is GallerySyncState.Running -> Running
        }
    }
}
