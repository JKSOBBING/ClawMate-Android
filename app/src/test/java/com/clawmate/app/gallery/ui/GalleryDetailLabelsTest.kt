package com.clawmate.app.gallery.ui

import com.clawmate.app.gallery.geocode.ReverseGeocodeError
import com.clawmate.app.gallery.geocode.ReverseGeocodeErrorKind
import com.clawmate.app.gallery.model.GalleryExifMetadata
import com.clawmate.app.gallery.model.GalleryPhotoItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Deterministic tests for detail-panel label mapping (no Android deps). */
class GalleryDetailLabelsTest {

    private fun item(state: String = GalleryPhotoItem.ANALYSIS_STATE_NONE) = GalleryPhotoItem(
        id = "gx_1",
        uri = "content://media/picker/1000001",
        date = "2026-06-01",
        title = "",
        category = "",
        summary = "",
        createdAt = 0L,
        analysisState = state,
    )

    @Test
    fun analysisStatesMapToHonestLabels() {
        assertTrue(GalleryDetailLabels.analysisStateLabel("none").contains("未分析"))
        assertTrue(GalleryDetailLabels.analysisStateLabel("unsupported").contains("不支持"))
        assertTrue(GalleryDetailLabels.analysisStateLabel("analyzed").contains("已分析"))
        assertEquals("已分析（云端视觉）", GalleryDetailLabels.analysisStateLabel("cloud_analyzed"))
        assertEquals("分析中", GalleryDetailLabels.analysisStateLabel("analyzing"))
        assertEquals("状态未知", GalleryDetailLabels.analysisStateLabel("mystery"))
    }

    @Test
    fun onlyAnalyzedCountsAsRealAnalysis() {
        assertTrue(GalleryDetailLabels.isAnalyzed("analyzed"))
        assertTrue(GalleryDetailLabels.isAnalyzed("cloud_analyzed"))
        assertFalse(GalleryDetailLabels.isAnalyzed("none"))
        assertFalse(GalleryDetailLabels.isAnalyzed(""))
    }

    @Test
    fun locationFailureTextExposesTypedError() {
        val err = ReverseGeocodeError(ReverseGeocodeErrorKind.UNAVAILABLE)
        assertTrue(GalleryDetailLabels.locationFailureText(err).isNotEmpty())
    }

    @Test
    fun tagsTextJoinsWithPunctuationAndHandlesEmpty() {
        assertEquals("", GalleryDetailLabels.tagsText(emptyList()))
        assertEquals("早餐、微信", GalleryDetailLabels.tagsText(listOf("早餐", "微信")))
    }

    @Test
    fun displayTitleFallsBackToIdWhenBlank() {
        assertEquals("gx_1", GalleryDetailLabels.displayTitle(item()))
        assertEquals("我的标题", GalleryDetailLabels.displayTitle(item().copy(title = "我的标题")))
    }

    @Test
    fun contentUriIsShownRawOrPlaceholder() {
        assertEquals("content://media/picker/1000001", GalleryDetailLabels.contentUriLine(item().uri))
        assertEquals("（无内容 URI）", GalleryDetailLabels.contentUriLine("  "))
    }

    @Test
    fun captureTimeFromExifOrPlaceholder() {
        val withTime = item().copy(exif = GalleryExifMetadata(dateTimeOriginal = "2026:06:01 08:12:20"))
        assertEquals("2026:06:01 08:12:20", GalleryDetailLabels.captureTimeLine(withTime))
        assertEquals("（无拍摄时间）", GalleryDetailLabels.captureTimeLine(item()))
    }
}
