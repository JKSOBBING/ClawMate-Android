package com.clawmate.app.localmodel

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The single, process-wide owner of the local-model capability and its
 * "loaded" state.
 *
 * Why this exists: before the registry, the chat screen and the Settings
 * "本地模型" panel each constructed their own [LocalModelCapability]. The native
 * load itself is global (it lives in the [LocalModelNative] object), but the
 * Kotlin-side [LocalModelState] snapshot was NOT shared — so a model loaded in
 * Settings was invisible to the chat tab, and the chat tab had to either
 * re-load on every mode switch (a side effect: under a real MNN build, tapping
 * 本地 would load a hundreds-of-MB model) or report a stale "not loaded" state.
 *
 * The registry makes the loaded state a single source of truth:
 *  - [loadAndTest] performs the real, validated native load and, **only on a
 *    genuine Success**, publishes the READY [LocalModelState] to [state].
 *  - [refresh] re-scans disk and merges it with the current [state], so a
 *    component that only reads (the chat tab) sees the same loaded/unloaded
 *    picture as the one that loads (Settings).
 *  - [unload] clears the loaded state and releases native state.
 *
 * Honest boundary preserved: READY is published only when the native adapter
 * actually returns Success. The JNI stub always reports UNSUPPORTED, so the
 * state never fakes readiness. [state] starts at a safe NOT_CONFIGURED value.
 */
object LocalModelRegistry {

    private var capability: LocalModelCapability =
        LocalModelCapability(filesDir = null)

    private val _state = MutableStateFlow(
        LocalModelState(
            status = LocalModelStatus.NOT_CONFIGURED,
            errorKind = LocalModelErrorKind.NOT_CONFIGURED,
            message = "未在应用私有目录发现本地模型",
        ),
    )

    /** The shared, cross-component local-model state (loaded/unloaded/readiness). */
    val state: StateFlow<LocalModelState> = _state.asStateFlow()

    /**
     * Re-scans [filesDir] and updates [state] with the current disk picture
     * merged with the existing loaded state. Read-only with respect to native
     * loading: it never calls [LocalModelCapability.loadAndTest], so a
     * component that only calls [refresh] (the chat tab) has no load side
     * effect. If a model is already loaded (a prior [loadAndTest] succeeded)
     * and the inventory still contains it, the READY state is preserved;
     * otherwise the fresh scan result wins.
     */
    fun refresh(filesDir: String?) {
        val capabilityFor = bindFor(filesDir)
        val scanned = capabilityFor.refresh()
        val current = _state.value
        val merged = if (current.status == LocalModelStatus.READY
            && scanned.models.any { it.id == current.selectedModelId }
        ) {
            // Keep the loaded model visible to readers even after a re-scan.
            current.copy(models = scanned.models)
        } else {
            scanned
        }
        _state.value = merged
    }

    /**
     * Performs the real, validated native load of [modelId] under [filesDir].
     * READY is published to [state] **only** when the native adapter returns a
     * genuine Success; every other outcome (UNSUPPORTED / ERROR) is published
     * as-is so all components see the honest failure. This is the only place a
     * load is triggered.
     */
    fun loadAndTest(filesDir: String?, modelId: String): LocalModelState {
        val cap = bindFor(filesDir)
        val result = cap.loadAndTest(modelId)
        _state.value = result
        return result
    }

    /**
     * Returns the capability to use for [filesDir]. The installed [capability]
     * is reused when it already targets that dir (so a test-installed capability
     * with controlled seams keeps its scan/native/loadModel); otherwise a fresh
     * production capability is bound for the requested dir and remembered.
     */
    private fun bindFor(filesDir: String?): LocalModelCapability {
        if (capability.filesDir == filesDir) return capability
        if (filesDir.isNullOrBlank()) return capability
        val bound = LocalModelCapability(filesDir)
        capability = bound
        return bound
    }

    /** Releases native state and returns [state] to a clean NOT_CONFIGURED. */
    fun unload() {
        LocalModelNative.unload()
        _state.value = LocalModelState(
            status = LocalModelStatus.NOT_CONFIGURED,
            errorKind = null,
            message = "已卸载本地模型",
        )
    }

    // ------------------------------------------------------------------ tests

    /**
     * Test seam: install a capability whose seams (scan / native / loadModel)
     * are controlled by the test, and reset [state] to the safe default.
     * Production code must not call this.
     */
    fun installForTest(cap: LocalModelCapability) {
        capability = cap
        _state.value = LocalModelState(
            status = LocalModelStatus.NOT_CONFIGURED,
            errorKind = LocalModelErrorKind.NOT_CONFIGURED,
            message = "未在应用私有目录发现本地模型",
        )
    }

    /** Test seam: reset to a fresh default capability + state (production-safe). */
    fun resetForTest() {
        capability = LocalModelCapability(filesDir = null)
        _state.value = LocalModelState(
            status = LocalModelStatus.NOT_CONFIGURED,
            errorKind = LocalModelErrorKind.NOT_CONFIGURED,
            message = "未在应用私有目录发现本地模型",
        )
    }
}
