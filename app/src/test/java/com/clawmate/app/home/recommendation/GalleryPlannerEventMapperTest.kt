package com.clawmate.app.home.recommendation

import com.clawmate.app.gallery.model.GalleryExifMetadata
import com.clawmate.app.gallery.model.GalleryPhotoItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Device-free tests for [GalleryPlannerEventMapper]: converting real gallery items
 * into bounded planner events that never carry a raw URI, image bytes, tokens or
 * fabricated analysis.
 */
class GalleryPlannerEventMapperTest {

    private fun item(
        id: String = "gx_1",
        title: String = "收据",
        category: String = "购物",
        summary: String = "便利店购物小票",
        details: List<String> = listOf("共35元"),
        tags: List<String> = listOf("购物"),
        keywords: List<String> = listOf("便利店", "35元"),
        date: String = "2026-08-01",
        uri: String = "content://media/1",
    ): GalleryPhotoItem =
        GalleryPhotoItem(
            id = id,
            uri = uri,
            date = date,
            title = title,
            category = category,
            summary = summary,
            details = details,
            tags = tags,
            keywords = keywords,
            createdAt = 0L,
            exif = GalleryExifMetadata(),
        )

    @Test
    fun `blank item maps to null - nothing to send`() {
        val blank = GalleryPhotoItem(
            id = "gx_blank",
            uri = "content://media/x",
            date = "2026-08-01",
            title = "",
            category = "",
            summary = "",
            createdAt = 0L,
        )
        assertNull(GalleryPlannerEventMapper.toPlannerEvent(blank))
    }

    @Test
    fun `empty items produce no planner events (no-call source)`() {
        assertEquals(0, GalleryPlannerEventMapper.toPlannerEvents(emptyList()).size)
    }

    @Test
    fun `real item maps to a bounded event with real text only`() {
        val event = GalleryPlannerEventMapper.toPlannerEvent(item())
        assertNotNull(event)
        val e = event!!
        assertEquals("gx_1", e.eventId)
        assertEquals("2026-08-01", e.date)
        // event_id, date pass through; no fabricated todo type/deadline.
        assertEquals("", e.todoType)
        assertEquals("", e.todoDeadline)
        // Real text surfaces in todoText/summary/normalizedFact.
        assertTrue(e.todoText.isNotBlank())
        assertTrue(e.summary.isNotBlank())
        assertTrue(e.normalizedFact.isNotBlank())
        assertEquals(listOf(GalleryPlannerEventMapper.SOURCE_GALLERY), e.sources)
        // Category "购物" maps to the eat/wear... none → Empty after curated map (see below).
        assertTrue(e.keywords.contains("便利店"))
        assertTrue(e.keywords.size <= HomeRecommendationBounds.MAX_EVENT_KEYWORDS)
        // No raw image references or bytes are ever transmitted.
        assertTrue(e.imagePaths.isEmpty())
    }

    @Test
    fun `planner events never contain the raw uri or image tokens`() {
        val event = GalleryPlannerEventMapper.toPlannerEvent(item(uri = "content://media/com.example/123"))
        val e = event!!
        val flat = listOf(e.eventId, e.todoText, e.summary, e.normalizedFact) + e.sources + e.keywords + e.domains
        assertTrue("no content:// in any planner field", flat.none { it.contains("content://") })
        assertTrue("no uri in normalizedFact", !e.normalizedFact.contains("media"))
        assertTrue("imagePaths is always empty", e.imagePaths.isEmpty())
    }

    @Test
    fun `planner events are capped by the per-request bound`() {
        val many = List(200) { i -> item(id = "gx_$i", title = "物品$i", summary = "摘要$i") }
        val events = GalleryPlannerEventMapper.toPlannerEvents(many)
        assertTrue(events.size <= HomeRecommendationBounds.MAX_EVENTS_PER_REQUEST)
        assertEquals(HomeRecommendationBounds.MAX_EVENTS_PER_REQUEST, events.size)
    }

    @Test
    fun `long real text is bounded per field`() {
        val longSummary = "x".repeat(2000)
        val event = GalleryPlannerEventMapper.toPlannerEvent(
            item(summary = longSummary, title = "y".repeat(2000)),
        )
        val e = event!!
        assertTrue(e.summary.length <= HomeRecommendationBounds.MAX_EVENT_SUMMARY)
        assertTrue(e.todoText.length <= HomeRecommendationBounds.MAX_EVENT_TODO_TEXT)
        assertTrue(e.normalizedFact.length <= HomeRecommendationBounds.MAX_EVENT_NORMALIZED_FACT)
        // Even when truncated, real content is preserved (no empty string).
        assertTrue(e.summary.isNotEmpty())
    }
}
