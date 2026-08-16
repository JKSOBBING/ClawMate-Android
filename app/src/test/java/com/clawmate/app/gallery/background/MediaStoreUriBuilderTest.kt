package com.clawmate.app.gallery.background

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Device-free tests for the pure content-URI builder used by
 * [MediaStoreGalleryMediaSource].
 *
 * On modern SDKs the scoped-storage `MediaStore` may omit the filesystem
 * `DATA` path for a row, so a usable content URI must be derivable from the row
 * `_ID` alone. These tests assert that derivation (and its defensive refusal of
 * malformed ids) with no Android framework and no device.
 */
class MediaStoreUriBuilderTest {

    @Test
    fun contentUri_buildsValidUriFromRowIdWithoutDataPath() {
        // A plain numeric MediaStore row _ID must yield a usable content URI even
        // when there is no filesystem DATA column/path to fall back on.
        val uri = MediaStoreUriBuilder.contentUriFor(
            "content://media/external/images/media",
            "12345",
        )
        assertEquals("content://media/external/images/media/12345", uri)
    }

    @Test
    fun contentUri_ignoresTrailingSlashOnBase() {
        val uri = MediaStoreUriBuilder.contentUriFor(
            "content://media/external/images/media/",
            "42",
        )
        assertEquals("content://media/external/images/media/42", uri)
    }

    @Test
    fun contentUri_blankId_yieldsEmpty() {
        assertEquals("", MediaStoreUriBuilder.contentUriFor("content://base", ""))
        assertEquals("", MediaStoreUriBuilder.contentUriFor("content://base", "   "))
    }

    @Test
    fun contentUri_nonNumericId_yieldsEmpty() {
        // Defensive: refuse malformed / traversal-like ids instead of fabricating a URI.
        assertEquals("", MediaStoreUriBuilder.contentUriFor("content://base", "x/../y"))
        assertEquals("", MediaStoreUriBuilder.contentUriFor("content://base", "12abc"))
        assertEquals("", MediaStoreUriBuilder.contentUriFor("content://base", "-1"))
    }

    @Test
    fun contentUri_blankBase_yieldsEmpty() {
        assertEquals("", MediaStoreUriBuilder.contentUriFor("", "1"))
        assertEquals("", MediaStoreUriBuilder.contentUriFor("   ", "1"))
    }
}
