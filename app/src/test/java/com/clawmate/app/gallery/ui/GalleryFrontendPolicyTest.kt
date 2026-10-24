package com.clawmate.app.gallery.ui

import com.clawmate.app.gallery.model.GalleryPhotoItem
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class GalleryFrontendPolicyTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = Instant.parse("2026-10-24T04:00:00Z").toEpochMilli()
    private fun item(id: String, date: String, category: String = "工作", created: Long = 0) =
        GalleryPhotoItem(id, "", date, id, category, "", createdAt = created)

    @Test fun rollingDayBoundaryUsesLocalMidnightRatherThanSevenCalendarLabels() {
        val items = listOf(item("edge-before-noon", "2026-10-17"), item("inside", "2026-10-18"), item("today", "2026-10-24"))
        assertEquals(listOf("today", "inside"), galleryFrontendTimeResults(items, 1, now, zone).map { it.id })
        assertEquals(3, galleryFrontendTimeResults(items, 2, now, zone).size)
    }
    @Test fun invalidDateFallsBackToCreatedTimestampAndYearFilterKeepsTheCurrentYear() {
        val items = listOf(item("fallback", "unknown", created = now), item("old-year", "2025-12-31"), item("year-start", "2026-01-01"))
        assertEquals(listOf("fallback", "year-start"), galleryFrontendTimeResults(items, 3, now, zone).map { it.id })
        assertEquals(now, galleryFrontendTime(items.first(), zone))
    }
    @Test fun categoryCountsUseStableSourceIndexesAndFoldUnknownValuesIntoOther() {
        val filters = galleryFrontendCategories(listOf(item("a", "", "购物"), item("b", "", "陌生分类"), item("c", "", "购物")))
        assertEquals(listOf(GalleryFrontendCategory(0, "全部", 3), GalleryFrontendCategory(4, "购物", 2), GalleryFrontendCategory(8, "其他", 1)), filters)
    }
    @Test fun importingAnImageDoesNotCountAsCompletedAnalysis() {
        val imported = item("import", "")
        assertFalse(imported.hasCompletedAnalysis())
        assertTrue(imported.copy(analysisState = GalleryPhotoItem.ANALYSIS_STATE_ANALYZED).hasCompletedAnalysis())
        assertTrue(imported.copy(analysisState = GalleryPhotoItem.ANALYSIS_STATE_CLOUD_ANALYZED).hasCompletedAnalysis())
        assertFalse(imported.copy(analysisState = GalleryPhotoItem.ANALYSIS_STATE_UNSUPPORTED).hasCompletedAnalysis())
    }
    @Test fun tinyNonzeroMonthlyBucketsKeepTheSourceSixPercentMinimum() {
        assertEquals(.06f, galleryMonthBarFraction(1, 100), .0001f)
        assertEquals(.33f, galleryMonthBarFraction(1, 3), .0001f)
        assertEquals(0f, galleryMonthBarFraction(1, 0), .0001f)
        assertEquals(GalleryFrontendTone("#FF8A3D", "#FFF1E7"), galleryCategoryTone("购物"))
    }
}
