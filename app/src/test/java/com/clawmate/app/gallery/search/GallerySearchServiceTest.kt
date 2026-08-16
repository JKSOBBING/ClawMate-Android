package com.clawmate.app.gallery.search

import com.clawmate.app.gallery.model.GalleryPhotoItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic tests for the gallery search scoring.
 */
class GallerySearchServiceTest {

    private fun item(
        id: String,
        title: String = "",
        category: String = "",
        summary: String = "",
        tags: List<String> = emptyList(),
        date: String = "2026-06-01",
        createdAt: Long = 0L,
    ) = GalleryPhotoItem(
        id = id,
        uri = "content://media/picker/$id",
        date = date,
        title = title,
        category = category,
        summary = summary,
        tags = tags,
        createdAt = createdAt,
    )

    @Test
    fun emptyQueryReturnsNoHits() {
        assertEquals(0, GallerySearchService.search("  ", emptyList()).size)
    }

    @Test
    fun titleMatchIsRankedAboveNonMatch() {
        val hit = item("a", title = "早餐截图")
        val miss = item("b", title = "会议记录")
        val results = GallerySearchService.search("早餐", listOf(miss, hit))
        assertEquals("a", results.first().item.id)
    }

    @Test
    fun tagMatchProducesReason() {
        val it = item("a", title = "x", tags = listOf("微信"))
        val results = GallerySearchService.search("微信", listOf(it))
        assertEquals(1, results.size)
        assertTrue(results.first().score > 0)
        assertTrue(results.first().reason.isNotEmpty())
    }

    @Test
    fun limitIsRespected() {
        val items = (1..10).map { item("id$it", title = "聊天记录") }
        assertEquals(3, GallerySearchService.search("聊天", items, limit = 3).size)
    }

    @Test
    fun resultsAreSortedByScoreDesc() {
        val title = item("t", title = "发票报销")
        val summary = item("s", title = "x", summary = "发票 报销 流程")
        // title weight(10) beats summary weight(6) for the token "发票".
        val results = GallerySearchService.search("发票", listOf(summary, title))
        assertEquals("t", results.first().item.id)
    }
}
