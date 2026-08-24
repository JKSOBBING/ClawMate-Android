package com.clawmate.app.localmodel

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for the shared [LocalModelRegistry]: the single source of truth for the
 * cross-component local-model state. The invariant under test is that a model
 * loaded in one component (the Settings panel, via [LocalModelRegistry.loadAndTest])
 * is visible to another (the chat tab, via [LocalModelRegistry.refresh]) WITHOUT
 * the reader triggering a load, and that READY is published only on a genuine
 * native Success (never faked).
 */
class LocalModelRegistryTest {

    private val filesDir = "/data/files"

    private fun completeModel(id: String = "m1") = LocalModelInfo(
        id = id,
        label = "模型 $id",
        // Base dir must be one of ALLOWED_BASE_DIRS ("model"/"modelscope") for the
        // path validator to accept it; use "model" to mirror the inventory layout.
        path = "$filesDir/model/$id",
        sizeBytes = 1,
        statusText = "可加载",
        ready = true,
        hasConfig = true,
        hasWeightFile = true,
    )

    /**
     * Installs a controlled capability into the shared registry. If [loadCounter]
     * is provided, every native load increments [loadCounter] so a test can
     * assert that refresh does NOT load while loadAndTest does.
     */
    private fun install(
        models: List<LocalModelInfo>,
        nativeSupported: Boolean,
        loadResult: () -> LocalModelNative.NativeLoadResult,
        loadCounter: IntArray? = null,
    ) {
        LocalModelRegistry.installForTest(
            LocalModelCapability(
                filesDir = filesDir,
                scan = { models },
                native = object : LocalModelNativeAdapter {
                    override fun supported(): Boolean = nativeSupported
                },
                loadModel = {
                    if (loadCounter != null) loadCounter[0]++
                    loadResult()
                },
            ),
        )
    }

    @Before
    fun setUp() {
        LocalModelRegistry.resetForTest()
    }

    @After
    fun tearDown() {
        LocalModelRegistry.resetForTest()
    }

    @Test
    fun `state starts NOT_CONFIGURED and refresh never loads`() {
        val counter = intArrayOf(0)
        install(listOf(completeModel()), nativeSupported = true,
            loadResult = {
                counter[0]++
                LocalModelNative.NativeLoadResult.Success("ok")
            }, loadCounter = counter)
        LocalModelRegistry.refresh(filesDir)
        assertEquals(0, counter[0]) // refresh must not load
        assertEquals(LocalModelStatus.NOT_CONFIGURED, LocalModelRegistry.state.value.status)
        assertFalse(LocalModelRegistry.state.value.isReady)
    }

    @Test
    fun `loadAndTest publishes READY only on a genuine native success`() {
        install(listOf(completeModel()), nativeSupported = true,
            loadResult = { LocalModelNative.NativeLoadResult.Success("ok") })
        val result = LocalModelRegistry.loadAndTest(filesDir, "m1")
        assertEquals(LocalModelStatus.READY, result.status)
        assertEquals(LocalModelStatus.READY, LocalModelRegistry.state.value.status)
        assertTrue(LocalModelRegistry.state.value.isReady)
    }

    @Test
    fun `loadAndTest with missing native lib never reaches READY`() {
        install(listOf(completeModel()), nativeSupported = false,
            loadResult = {
                LocalModelNative.NativeLoadResult.Unsupported(
                    LocalModelErrorKind.NATIVE_LIBRARY_MISSING,
                    "no lib",
                )
            })
        val result = LocalModelRegistry.loadAndTest(filesDir, "m1")
        assertEquals(LocalModelStatus.UNSUPPORTED, result.status)
        assertFalse(LocalModelRegistry.state.value.isReady)
    }

    @Test
    fun loadedModelStaysVisibleAcrossRefresh_cross_component() {
        // Component A (Settings) loads the model through the shared registry.
        install(listOf(completeModel()), nativeSupported = true,
            loadResult = { LocalModelNative.NativeLoadResult.Success("ok") })
        LocalModelRegistry.loadAndTest(filesDir, "m1")
        assertEquals(LocalModelStatus.READY, LocalModelRegistry.state.value.status)

        // Component B (chat) refreshes; the loaded model must remain visible.
        // refresh() only scans disk and merges with the shared loaded state, so a
        // READY state is preserved as long as the model is still in the inventory.
        LocalModelRegistry.refresh(filesDir)
        assertEquals(LocalModelStatus.READY, LocalModelRegistry.state.value.status)
    }

    @Test
    fun `unload clears the loaded state`() {
        install(listOf(completeModel()), nativeSupported = true,
            loadResult = { LocalModelNative.NativeLoadResult.Success("ok") })
        LocalModelRegistry.loadAndTest(filesDir, "m1")
        assertTrue(LocalModelRegistry.state.value.isReady)

        LocalModelRegistry.unload()
        assertFalse(LocalModelRegistry.state.value.isReady)
        assertEquals(LocalModelStatus.NOT_CONFIGURED, LocalModelRegistry.state.value.status)
    }

    @Test
    fun `a stale READY is dropped when the model disappears from the inventory`() {
        // First the model exists and is loaded.
        var current = listOf(completeModel())
        install(current, nativeSupported = true,
            loadResult = { LocalModelNative.NativeLoadResult.Success("ok") })
        LocalModelRegistry.loadAndTest(filesDir, "m1")
        assertTrue(LocalModelRegistry.state.value.isReady)

        // The inventory no longer contains it (e.g. user deleted the dir). A
        // refresh must drop the stale READY rather than keep reporting it.
        current = emptyList()
        LocalModelRegistry.installForTest(
            LocalModelCapability(
                filesDir = filesDir,
                scan = { current },
                native = object : LocalModelNativeAdapter {
                    override fun supported(): Boolean = true
                },
                loadModel = { LocalModelNative.NativeLoadResult.Success("unused") },
            ),
        )
        LocalModelRegistry.refresh(filesDir)
        assertFalse(LocalModelRegistry.state.value.isReady)
        assertEquals(LocalModelStatus.NOT_CONFIGURED, LocalModelRegistry.state.value.status)
    }
}
