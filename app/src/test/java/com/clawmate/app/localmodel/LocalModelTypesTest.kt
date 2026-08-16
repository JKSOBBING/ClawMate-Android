package com.clawmate.app.localmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Deterministic tests for local-model typed-state normalization and labels.
 * No device, no real files, no native calls — pure mapping.
 */
class LocalModelTypesTest {

    @Test
    fun `no model folder is honestly NOT_CONFIGURED`() {
        val s = normalizeModelCapabilityState(
            statusText = "",
            hasConfig = false,
            hasWeightFile = false,
            nativeLibrarySupported = true,
            hasModelFolder = false,
        )
        assertEquals(LocalModelStatus.NOT_CONFIGURED, s.status)
        assertEquals(LocalModelErrorKind.NOT_CONFIGURED, s.errorKind)
        assertFalse(s.isReady)
    }

    @Test
    fun `model folder present but native library absent maps to UNSUPPORTED`() {
        val s = normalizeModelCapabilityState(
            statusText = "可加载",
            hasConfig = true,
            hasWeightFile = true,
            nativeLibrarySupported = false,
            hasModelFolder = true,
        )
        assertEquals(LocalModelStatus.UNSUPPORTED, s.status)
        assertEquals(LocalModelErrorKind.NATIVE_LIBRARY_MISSING, s.errorKind)
        assertFalse(s.isReady)
    }

    @Test
    fun `complete model folder never reports READY before a native load`() {
        // A folder with config + weight yields a NOT_CONFIGURED "awaiting load"
        // state even when the native library is supported — READY only follows a
        // native load success, never assumed from disk state alone.
        val s = normalizeModelCapabilityState(
            statusText = "可加载",
            hasConfig = true,
            hasWeightFile = true,
            nativeLibrarySupported = true,
            hasModelFolder = true,
        )
        assertEquals(LocalModelStatus.NOT_CONFIGURED, s.status)
        assertNull(s.errorKind)
        assertFalse(s.isReady)
    }

    @Test
    fun `missing config maps to ERROR with MISSING_CONFIG`() {
        val s = normalizeModelCapabilityState(
            statusText = "缺少 config.json",
            hasConfig = false,
            hasWeightFile = true,
            nativeLibrarySupported = true,
            hasModelFolder = true,
        )
        assertEquals(LocalModelStatus.ERROR, s.status)
        assertEquals(LocalModelErrorKind.MISSING_CONFIG, s.errorKind)
        assertFalse(s.isReady)
    }

    @Test
    fun `missing weight file maps to ERROR with MISSING_MODEL_FILE`() {
        val s = normalizeModelCapabilityState(
            statusText = "缺少模型权重文件",
            hasConfig = true,
            hasWeightFile = false,
            nativeLibrarySupported = true,
            hasModelFolder = true,
        )
        assertEquals(LocalModelStatus.ERROR, s.status)
        assertEquals(LocalModelErrorKind.MISSING_MODEL_FILE, s.errorKind)
        assertFalse(s.isReady)
    }

    @Test
    fun `non complete status maps to ERROR with NOT_READY`() {
        val s = normalizeModelCapabilityState(
            statusText = "下载未完成",
            hasConfig = true,
            hasWeightFile = true,
            nativeLibrarySupported = true,
            hasModelFolder = true,
        )
        assertEquals(LocalModelStatus.ERROR, s.status)
        assertEquals(LocalModelErrorKind.NOT_READY, s.errorKind)
    }

    @Test
    fun `status and error labels are deterministic and non empty`() {
        LocalModelStatus.entries.forEach { assertEquals(it, it.displayLabel().let { _ -> it }) }
        assertFalse(LocalModelStatus.READY.displayLabel().isEmpty())
        assertFalse(LocalModelErrorKind.NATIVE_LIBRARY_MISSING.displayLabel().isEmpty())
        assertEquals("未配置", LocalModelStatus.NOT_CONFIGURED.displayLabel())
        assertEquals("已就绪", LocalModelStatus.READY.displayLabel())
    }

    @Test
    fun `LocalModelState default is honest NOT_CONFIGURED`() {
        val s = LocalModelState()
        assertEquals(LocalModelStatus.NOT_CONFIGURED, s.status)
        assertNull(s.errorKind)
        assertFalse(s.isReady)
        assertEquals(0, s.selectedModelId.length)
    }
}
