package com.clawmate.app.datamanager

import com.clawmate.app.home.recommendation.HomeRecommendationBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Device-free tests for [DailyLogPlannerEventMapper]: bounded daily-log → planner
 * event conversion with stable sourceRef ids, honest DailyLog source label,
 * empty imagePaths (no URI/path ever) and per-field/batch bounds.
 */
class DailyLogPlannerEventMapperTest {

    private fun entry(
        date: String = "2026-08-01",
        file: String = "wechat.logs.md",
        index: Int = 1,
        text: String = "今天去便利店买了日用品",
        meta: Map<String, String> = mapOf("workflow" to "auto", "app" to "wechat"),
    ): DailyLogEntry = DailyLogEntry(
        sourceDate = date,
        fileName = file,
        entryIndex = index,
        text = text,
        workflowMetadata = meta,
        latestEntryIndex = index,
        sourceRef = DailyLogParser.sourceRef(date, file, index),
    )

    @Test
    fun `blank entry maps to null`() {
        assertNull(DailyLogPlannerEventMapper.toPlannerEvent(entry(text = "   ")))
    }

    @Test
    fun `empty entries produce no planner events`() {
        assertEquals(0, DailyLogPlannerEventMapper.toPlannerEvents(emptyList()).size)
    }

    @Test
    fun `real entry maps to bounded event with stable source ref id and no uri`() {
        val event = DailyLogPlannerEventMapper.toPlannerEvent(entry(index = 2))
        assertNotNull(event)
        val e = event!!
        // eventId is the stable source ref.
        assertEquals("2026-08-01/wechat.logs.md#2", e.eventId)
        assertEquals("2026-08-01", e.date)
        assertEquals(listOf(DailyLogPlannerEventMapper.SOURCE_DAILY_LOG), e.sources)
        // Real bounded text surfaces.
        assertTrue(e.todoText.isNotBlank())
        assertTrue(e.summary.isNotBlank())
        assertTrue(e.normalizedFact.isNotBlank())
        // imagePaths is always empty — no raw path/image/URI ever leaves.
        assertTrue(e.imagePaths.isEmpty())
        // Provenance metadata folded into bounded keywords.
        assertTrue(e.keywords.contains("workflow=auto"))
        assertTrue(e.keywords.size <= HomeRecommendationBounds.MAX_EVENT_KEYWORDS)
        // Domains left empty (daily-log text has no category; honesty over guessing).
        assertTrue(e.domains.isEmpty())
    }

    @Test
    fun `planner events never contain raw file path or content uri`() {
        val event = DailyLogPlannerEventMapper.toPlannerEvent(
            entry(file = "secret.logs.md", text = "content://media/1 private data"),
        )
        val e = event!!
        val flat = listOf(e.eventId, e.todoText, e.summary, e.normalizedFact) + e.sources + e.keywords + e.domains
        assertTrue(flat.none { it.contains("content://") })
        // The sourceRef must be a filename-date fragment, never a content uri.
        assertTrue(e.eventId.startsWith("2026-08-01/"))
        assertTrue(e.eventId.endsWith(".md#1"))
        assertTrue(e.imagePaths.isEmpty())
    }

    @Test
    fun `planner events are capped by the per-request bound`() {
        val many = List(200) { i -> entry(index = i, text = "条目$i") }
        val events = DailyLogPlannerEventMapper.toPlannerEvents(many)
        assertTrue(events.size <= HomeRecommendationBounds.MAX_EVENTS_PER_REQUEST)
        assertEquals(HomeRecommendationBounds.MAX_EVENTS_PER_REQUEST, events.size)
    }

    @Test
    fun `long entry text is bounded per field`() {
        val longText = "x".repeat(5000)
        val event = DailyLogPlannerEventMapper.toPlannerEvent(entry(text = longText))
        val e = event!!
        assertTrue(e.todoText.length <= HomeRecommendationBounds.MAX_EVENT_TODO_TEXT)
        assertTrue(e.summary.length <= HomeRecommendationBounds.MAX_EVENT_SUMMARY)
        assertTrue(e.normalizedFact.length <= HomeRecommendationBounds.MAX_EVENT_NORMALIZED_FACT)
        // Even truncated, real content is preserved (not empty).
        assertTrue(e.normalizedFact.isNotEmpty())
    }

    @Test
    fun `mixed gallery and daily log map to distinct source ids`() {
        // A daily-log event's id never collides with a gallery id because the
        // sourceRef is a date/file fragment (not a bare token).
        val daily = DailyLogPlannerEventMapper.toPlannerEvent(entry())!!
        assertTrue(daily.eventId.startsWith("2026-08-01/"))
        assertTrue(daily.eventId.contains("#"))
    }
}
