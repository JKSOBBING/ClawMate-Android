package com.clawmate.app.localmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Deterministic tests for the local-model capability boundary. These exercise
 * the no-fake-output guarantee: READY is produced **only** when a real native
 * load returns Success, and a missing native library always maps to
 * UNSUPPORTED — never READY, never fabricated model output.
 */
class LocalModelCapabilityTest {

    private val filesDir = "/data/files"

    // ---------------------------------------------------------------- helpers

    private fun fakeInventory(
        models: List<LocalModelInfo>,
    ): (String?) -> List<LocalModelInfo> = { models }

    private fun modelInfo(
        id: String,
        label: String,
        hasConfig: Boolean,
        hasWeight: Boolean,
        ready: Boolean = hasConfig && hasWeight,
    ) = LocalModelInfo(
        id = id,
        label = label,
        path = "$filesDir/model",
        sizeBytes = 1024,
        statusText = if (ready) "可加载" else "缺少文件",
        ready = ready,
        hasConfig = hasConfig,
        hasWeightFile = hasWeight,
    )

    // ------------------------------------------------------------------- no-fixture native adapters

    private class NativeMissingAdapter : LocalModelNativeAdapter {
        override fun supported(): Boolean = false
    }

    private class NativePresentAdapter : LocalModelNativeAdapter {
        override fun supported(): Boolean = true
    }

    // ------------------------------------------------------------------ tests

    @Test
    fun `no filesDir reports NOT_CONFIGURED`() {
        val cap = LocalModelCapability(
            filesDir = null,
            scan = { emptyList() },
            native = NativePresentAdapter(),
            loadModel = { LocalModelNative.NativeLoadResult.Failed(LocalModelErrorKind.LOAD_FAILED, "unused") },
        )
        val s = cap.refresh()
        assertEquals(LocalModelStatus.NOT_CONFIGURED, s.status)
        assertEquals(LocalModelErrorKind.NOT_CONFIGURED, s.errorKind)
        assertFalse(s.isReady)
    }

    @Test
    fun `model folder present plus missing native library maps to UNSUPPORTED on refresh`() {
        val cap = LocalModelCapability(
            filesDir = filesDir,
            scan = fakeInventory(listOf(modelInfo("legacy:model", "默认 model", true, true, ready = true))),
            native = NativeMissingAdapter(),
            loadModel = { LocalModelNative.NativeLoadResult.Unsupported(LocalModelErrorKind.NATIVE_LIBRARY_MISSING, "no lib") },
        )
        val s = cap.refresh()
        assertEquals(LocalModelStatus.UNSUPPORTED, s.status)
        assertEquals(LocalModelErrorKind.NATIVE_LIBRARY_MISSING, s.errorKind)
        assertFalse(s.isReady)
    }

    @Test
    fun `loadTest with a real folder but missing native library never reaches READY`() {
        val cap = LocalModelCapability(
            filesDir = filesDir,
            scan = fakeInventory(listOf(modelInfo("legacy:model", "默认 model", true, true, ready = true))),
            native = NativeMissingAdapter(),
            loadModel = { LocalModelNative.NativeLoadResult.Unsupported(LocalModelErrorKind.NATIVE_LIBRARY_MISSING, "no lib") },
        )
        val s = cap.loadAndTest("legacy:model")
        assertEquals(LocalModelStatus.UNSUPPORTED, s.status)
        assertEquals(LocalModelErrorKind.NATIVE_LIBRARY_MISSING, s.errorKind)
        assertFalse(s.isReady)
    }

    @Test
    fun `loadTest of an unknown model reports honest NOT_CONFIGURED error`() {
        val cap = LocalModelCapability(
            filesDir = filesDir,
            scan = fakeInventory(listOf(modelInfo("legacy:model", "默认 model", true, true, ready = true))),
            native = NativePresentAdapter(),
            loadModel = { LocalModelNative.NativeLoadResult.Success() },
        )
        val s = cap.loadAndTest("does-not-exist")
        assertEquals(LocalModelStatus.ERROR, s.status)
        assertEquals(LocalModelErrorKind.NOT_CONFIGURED, s.errorKind)
    }

    @Test
    fun `loadTest on a model missing config maps to ERROR MISSING_CONFIG`() {
        val cap = LocalModelCapability(
            filesDir = filesDir,
            scan = fakeInventory(listOf(modelInfo("legacy:model", "默认 model", hasConfig = false, hasWeight = true, ready = false))),
            native = NativePresentAdapter(),
            loadModel = { LocalModelNative.NativeLoadResult.Success() },
        )
        val s = cap.loadAndTest("legacy:model")
        assertEquals(LocalModelStatus.ERROR, s.status)
        assertEquals(LocalModelErrorKind.MISSING_CONFIG, s.errorKind)
        assertFalse(s.isReady)
    }

    @Test
    fun `loadTest on a model missing weight maps to ERROR MISSING_MODEL_FILE`() {
        val cap = LocalModelCapability(
            filesDir = filesDir,
            scan = fakeInventory(listOf(modelInfo("legacy:model", "默认 model", hasConfig = true, hasWeight = false, ready = false))),
            native = NativePresentAdapter(),
            loadModel = { LocalModelNative.NativeLoadResult.Success() },
        )
        val s = cap.loadAndTest("legacy:model")
        assertEquals(LocalModelStatus.ERROR, s.status)
        assertEquals(LocalModelErrorKind.MISSING_MODEL_FILE, s.errorKind)
    }

    @Test
    fun `loadTest rejects a path that escapes filesDir with INVALID_PATH`() {
        val escaping = LocalModelInfo(
            id = "legacy:model",
            label = "默认 model",
            path = "/data/files/../../evil",   // invalid
            sizeBytes = 0,
            statusText = "可加载",
            ready = true,
            hasConfig = true,
            hasWeightFile = true,
        )
        val cap = LocalModelCapability(
            filesDir = filesDir,
            scan = fakeInventory(listOf(escaping)),
            native = NativePresentAdapter(),
            loadModel = { LocalModelNative.NativeLoadResult.Success() },
        )
        val s = cap.loadAndTest("legacy:model")
        assertEquals(LocalModelStatus.ERROR, s.status)
        assertEquals(LocalModelErrorKind.INVALID_PATH, s.errorKind)
    }

    @Test
    fun `loadTest returns READY only on a genuine native success`() {
        var loadedConfigPath: String? = null
        val cap = LocalModelCapability(
            filesDir = filesDir,
            scan = fakeInventory(listOf(modelInfo("legacy:model", "默认 model", true, true, ready = true))),
            native = NativePresentAdapter(),
            loadModel = { configPath ->
                loadedConfigPath = configPath
                LocalModelNative.NativeLoadResult.Success("ok")
            },
        )
        val s = cap.loadAndTest("legacy:model")
        assertEquals(LocalModelStatus.READY, s.status)
        assertNullError(s)
        assertTrue(s.isReady)
        // The native load must have been invoked with the real config path.
        assertEquals("$filesDir/model/config.json", loadedConfigPath)
    }

    @Test
    fun `loadTest maps a native rejection to UNSUPPORTED not READY`() {
        val cap = LocalModelCapability(
            filesDir = filesDir,
            scan = fakeInventory(listOf(modelInfo("legacy:model", "默认 model", true, true, ready = true))),
            native = NativePresentAdapter(),
            loadModel = {
                LocalModelNative.NativeLoadResult.Unsupported(LocalModelErrorKind.NATIVE_LIBRARY_MISSING, "stub result")
            },
        )
        val s = cap.loadAndTest("legacy:model")
        assertEquals(LocalModelStatus.UNSUPPORTED, s.status)
        assertFalse(s.isReady)
    }

    private fun assertNullError(s: LocalModelState) {
        assertTrue(s.errorKind == null)
    }
}
