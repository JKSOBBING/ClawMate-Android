package com.clawmate.app.gallery.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic tests for stable content-URI -> item-id mapping.
 */
class GalleryIdsTest {

    private fun st(id: String) = GalleryIds.stableIdForUri(id)

    @Test
    fun nonEmptyUriAlwaysMaps() {
        val id = st("content://media/picker/100000003")
        assertTrue(id.isNotEmpty())
        assertTrue(id.startsWith("gx_"))
    }

    @Test
    fun sameUriDeterministic() {
        val uri = "content://media/picker/100000003"
        assertEquals(st(uri), st(uri))
    }

    @Test
    fun trailingIdSegmentIsKeptAsStableSuffix() {
        val id = st("content://com.android.providers.media.photopicker/media/10000000")
        // The final path segment "10000000" is a stable asset id -> kept.
        assertTrue("actual=$id", id.endsWith("10000000") || id.contains("10000000"))
    }

    @Test
    fun differentUrisMapToDifferentIds() {
        val a = st("content://media/picker/100000003")
        val b = st("content://media/picker/100000004")
        assertTrue(a != b)
    }

    @Test
    fun blankUriMapsToEmpty() {
        assertEquals("", st("   "))
        assertEquals("", st(""))
    }
}
