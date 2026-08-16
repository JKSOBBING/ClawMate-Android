package com.clawmate.app.localmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic tests for the native-bridge result mapping and its
 * missing-natives handling. These run on the JVM (no Android .so loaded), so
 * they verify the typed fallback always maps a missing native runtime to
 * [LocalModelErrorKind.NATIVE_LIBRARY_MISSING] and never throws.
 */
class LocalModelNativeTest {

    @Test
    fun `parse maps a genuine success token to Success`() {
        val r = LocalModelNative.NativeLoadResult.parse("READY:loaded model x")
        assertTrue(r is LocalModelNative.NativeLoadResult.Success)
    }

    @Test
    fun `parse maps short OK token to Success`() {
        val r = LocalModelNative.NativeLoadResult.parse("OK")
        assertTrue(r is LocalModelNative.NativeLoadResult.Success)
    }

    @Test
    fun `parse maps unsupported and missing-library tokens to Unsupported`() {
        assertTrue(LocalModelNative.NativeLoadResult.parse("UNSUPPORTED:...") is LocalModelNative.NativeLoadResult.Unsupported)
        val missing = LocalModelNative.NativeLoadResult.parse("NATIVE_LIBRARY_MISSING:no_mnn")
        assertTrue(missing is LocalModelNative.NativeLoadResult.Unsupported)
        assertEquals(LocalModelErrorKind.NATIVE_LIBRARY_MISSING, (missing as LocalModelNative.NativeLoadResult.Unsupported).kind)
    }

    @Test
    fun `parse maps an unknown raw failure to Failed`() {
        val r = LocalModelNative.NativeLoadResult.parse("something unexpected")
        assertTrue(r is LocalModelNative.NativeLoadResult.Failed)
        assertEquals(LocalModelErrorKind.LOAD_FAILED, (r as LocalModelNative.NativeLoadResult.Failed).kind)
    }

    @Test
    fun `parse maps a blank result to Unsupported not Success`() {
        val r = LocalModelNative.NativeLoadResult.parse("")
        assertTrue(r is LocalModelNative.NativeLoadResult.Unsupported)
        assertFalse(r is LocalModelNative.NativeLoadResult.Success)
    }

    @Test
    fun `blank path is rejected as invalid before any native call`() {
        val r = LocalModelNative.loadModel("")
        assertTrue(r is LocalModelNative.NativeLoadResult.Unsupported)
        assertEquals(LocalModelErrorKind.INVALID_PATH, (r as LocalModelNative.NativeLoadResult.Unsupported).kind)
    }

    @Test
    fun `missing native runtime maps to UNSUPPORTED never READY and never throws`() {
        // On the plain JVM the .so is absent, so the binding must degrade to a
        // typed UNSUPPORTED / NATIVE_LIBRARY_MISSING result — never a crash and
        // never a fabricated READY.
        assertFalse(LocalModelNative.nativeSupported)
        assertEquals(LocalModelStatus.UNSUPPORTED, LocalModelNative.status())

        val r = LocalModelNative.loadModel("/data/model/config.json")
        assertNotNull(r)
        assertFalse(r is LocalModelNative.NativeLoadResult.Success)
        assertTrue(r is LocalModelNative.NativeLoadResult.Unsupported)
    }
}
