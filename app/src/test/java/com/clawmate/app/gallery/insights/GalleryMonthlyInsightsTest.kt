package com.clawmate.app.gallery.insights

import com.clawmate.app.gallery.model.GalleryPhotoItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic tests for the portable Gallery monthly-insights builder.
 *
 * Verifies bucketing by month (from `date`, falling back to createdAt), tag
 * counting / ordering, representative-item selection, the local summary, sorting
 * newest-month-first, and the exact `buildLlmInput` text contract — all purely
 * over [GalleryPhotoItem] with no device or cloud call.
 */
class GalleryMonthlyInsightsTest {

    private fun item(
        id: String,
        date: String = "",
        createdAt: Long = 0L,
        category: String = "",
        tags: List<String> = emptyList(),
        title: String = "",
        summary: String = "",
        keywords: List<String> = emptyList(),
    ) = GalleryPhotoItem(
        id = id,
        uri = "content://media/picker/$id",
        date = date,
        title = title,
        category = category,
        summary = summary,
        tags = tags,
        keywords = keywords,
        createdAt = createdAt,
    )

    @Test
    fun emptyInputYieldsNoInsights() {
        assertTrue(GalleryMonthlyInsights.build(emptyList()).isEmpty())
    }

    @Test
    fun bucketsByDateYearMonth() {
        val items = listOf(
            item("a", date = "2026-06-15", createdAt = 1000L),
            item("b", date = "2026-06-20", createdAt = 2000L),
            item("c", date = "2026-07-01", createdAt = 3000L),
        )
        val insights = GalleryMonthlyInsights.build(items)
        assertEquals(2, insights.size)
        // Newest month first.
        assertEquals("2026-07", insights[0].key)
        assertEquals("2026年7月", insights[0].label)
        assertEquals(1, insights[0].totalCount)
        assertEquals("2026-06", insights[1].key)
        assertEquals("2026年6月", insights[1].label)
        assertEquals(2, insights[1].totalCount)
    }

    @Test
    fun dateBucketingFallsBackToCreatedAt() {
        val items = listOf(
            item("a", date = "", createdAt = 1780272000000L), // 2026-06-01 (UTC)
            item("b", date = "", createdAt = 1782864000000L), // 2026-07-01 (UTC)
        )
        val insights = GalleryMonthlyInsights.build(items)
        assertEquals(2, insights.size)
        val keys = insights.map { it.key }.sorted()
        assertEquals(listOf("2026-06", "2026-07"), keys)
    }

    @Test
    fun unknownDateAndTimestampBucketsToUnknownMonth() {
        val insights = GalleryMonthlyInsights.build(listOf(item("x", date = "", createdAt = 0L)))
        assertEquals(1, insights.size)
        assertEquals("0000-00", insights[0].key)
        assertEquals("时间未知", insights[0].label)
    }

    @Test
    fun monthInsightSortsNewestMonthFirst() {
        val items = listOf(
            item("a", date = "2025-12-01"),
            item("b", date = "2026-03-01"),
            item("c", date = "2026-01-01"),
        )
        val keys = GalleryMonthlyInsights.build(items).map { it.key }
        assertEquals(listOf("2026-03", "2026-01", "2025-12"), keys)
    }

    @Test
    fun withinMonthItemsSortNewestFirst() {
        val items = listOf(
            item("old", date = "2026-06-01", createdAt = 100L),
            item("new", date = "2026-06-10", createdAt = 200L),
        )
        val insight = GalleryMonthlyInsights.build(items).single()
        assertEquals("new", insight.entriesForLlm[0].id)
        assertEquals("new", insight.representativeItems[0].id)
    }

    @Test
    fun tagCountsAreAggregatedAndSortedDesc() {
        val items = listOf(
            item("a", date = "2026-06-01", tags = listOf("餐饮", "聚会")),
            item("b", date = "2026-06-02", tags = listOf("餐饮", "工作")),
            item("c", date = "2026-06-03", tags = listOf("餐饮")),
        )
        val insight = GalleryMonthlyInsights.build(items).single()
        val counts = insight.categoryCounts
        // Items are processed newest-first (c, b, a), so tag insertion order is
        // 餐饮 (c), 工作 (b), 聚会 (a); stable sort keeps that order on ties.
        assertEquals(listOf("餐饮", "工作", "聚会"), counts.map { it.label })
        assertEquals(3, counts[0].count)
        assertEquals(1, counts[1].count)
        assertEquals(1, counts[2].count)
    }

    @Test
    fun cleanTagsStripPunctuationAndAreDeduplicated() {
        val items = listOf(
            item("a", date = "2026-06-01", tags = listOf(" 餐饮！", " 餐饮，", "出行")),
        )
        val insight = GalleryMonthlyInsights.build(items).single()
        val labels = insight.categoryCounts.map { it.label }
        // " 餐饮！" and " 餐饮，" both clean to "餐饮" -> one bucket.
        assertEquals(listOf("餐饮", "出行"), labels)
        assertEquals(1, insight.categoryCounts[0].count)
    }

    @Test
    fun emptyTagsFallBackToCategoryLabel() {
        val items = listOf(
            item("a", date = "2026-06-01", category = "餐饮", tags = emptyList()),
            item("b", date = "2026-06-02", category = "未知东西", tags = emptyList()),
        )
        // b (category "未知东西" -> "其他") is newest, so "其他" is encountered
        // first and, on the tie (both count 1), stable sort keeps it first.
        val insight = GalleryMonthlyInsights.build(items).single()
        val labels = insight.categoryCounts.map { it.label }
        assertEquals(listOf("其他", "餐饮"), labels)
    }

    @Test
    fun representativeItemsAreCappedAtSix() {
        val items = (0 until 9).map { item("p$it", date = "2026-06-01", createdAt = it.toLong()) }
        val insight = GalleryMonthlyInsights.build(items).single()
        assertEquals(6, insight.representativeItems.size)
        assertEquals(9, insight.entriesForLlm.size)
    }

    @Test
    fun localSummaryMentionsMonthAndTotal() {
        val items = listOf(item("a", date = "2026-06-01", category = "餐饮"))
        val insight = GalleryMonthlyInsights.build(items).single()
        assertTrue(insight.summary.contains("2026年6月"))
        assertTrue(insight.summary.contains("共分析 1 张图片"))
    }

    @Test
    fun concentratedTagProducesConcentrationSuggestion() {
        val items = (0 until 4).map { item("p$it", date = "2026-06-01", category = "餐饮") }
        val insight = GalleryMonthlyInsights.build(items).single()
        assertTrue(insight.summary.contains("高频标签比较集中"))
    }

    @Test
    fun buildLlmInputIsDeterministicAndBounded() {
        val items = (0 until 100).map {
            item("p$it", date = "2026-06-01", createdAt = it.toLong(), category = "餐饮", title = "标题$it")
        }
        val insight = GalleryMonthlyInsights.build(items).single()
        val input = GalleryMonthlyInsights.buildLlmInput(insight)
        assertEquals(input, GalleryMonthlyInsights.buildLlmInput(insight))
        assertTrue(input.contains("月份：2026年6月"))
        assertTrue(input.contains("总数：100"))
        assertTrue(input.contains("标签分布：餐饮=100"))
        assertTrue(input.contains("本地初步摘要："))
        // Input only includes 80 lines of items (bounded), matching Harmony's 80 cap.
        val itemLines = input.lineSequence().count { it.startsWith("- ") }
        assertEquals(80, itemLines)
    }

    @Test
    fun buildLlmInputEmptyInsightSaysNone() {
        val insight = GalleryMonthInsight(
            key = "2026-06",
            label = "2026年6月",
            totalCount = 0,
            categoryCounts = emptyList(),
            representativeItems = emptyList(),
            entriesForLlm = emptyList(),
            summary = "2026年6月 共分析 0 张图片。",
        )
        val input = GalleryMonthlyInsights.buildLlmInput(insight)
        assertTrue(input.contains("无"))
    }
}
