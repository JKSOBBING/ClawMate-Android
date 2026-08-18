package com.clawmate.app.gallery.collection

import com.clawmate.app.datamanager.LocalConsolidationTypes.IndexedEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic JVM tests for the Phase-44 index-backed collection grouping.
 * Pure — no Room, no network, no model.
 */
class IndexedEventCollectionServiceTest {

    @Test
    fun `empty input yields no groups (no fabricated data)`() {
        assertEquals(0, IndexedEventCollectionService.group(emptyList(), 3).size)
        assertEquals(0, IndexedEventCollectionService.group(emptyList(), 0).size)
    }

    @Test
    fun `no matching scene yields no groups for that tab`() {
        val events = listOf(event("e1", sceneIds = listOf(0), date = "2026-06-01"))
        assertEquals(0, IndexedEventCollectionService.group(events, 3).size) // chat tab
        assertEquals(1, IndexedEventCollectionService.group(events, 0).size) // shopping tab
    }

    @Test
    fun `grouping is deterministic across runs and input order`() {
        val a = event("a", sceneIds = listOf(3), date = "2026-06-01", summary = "与老张沟通周五会议")
        val b = event("b", sceneIds = listOf(3), date = "2026-06-02", summary = "与老张沟通项目排期")
        val c = event("c", sceneIds = listOf(3), date = "2026-06-03", summary = "与小李沟通报销")

        val base = listOf(a, b, c)
        val first = IndexedEventCollectionService.group(base, 3)
        val shuffled = IndexedEventCollectionService.group(listOf(c, a, b), 3)

        val sig = { g: List<IndexedCollectionGroup> ->
            g.map { it.groupId + "|" + it.title + "|" + it.eventIds.sorted() }
        }
        assertEquals(sig(first), sig(shuffled))
        assertEquals(first.map { it.groupId }, shuffled.map { it.groupId })
        // Every real event appears exactly once across the chat groups.
        val allIds = first.flatMap { it.eventIds }
        assertEquals(3, allIds.size)
        assertEquals(3, allIds.toSet().size)
        assertTrue(allIds.all { it in setOf("a", "b", "c") })
    }

    @Test
    fun `empty and unknown fields are tolerated but never fabricated`() {
        val blank = event("blank", sceneIds = listOf(0), date = "", summary = "", normalizedFact = "")
        val groups = IndexedEventCollectionService.group(listOf(blank), 0)
        // A real event with blank metadata still lands in a stable local bucket.
        assertEquals(1, groups.size)
        assertTrue(groups[0].eventIds.contains("blank"))
        assertTrue(groups[0].local)
    }

    @Test
    fun `groups carry stable local ids title source meta and local marker`() {
        val e = event("e1", sceneIds = listOf(2), date = "2026-06-01", summary = "看了电影", sources = listOf("娱乐"))
        val groups = IndexedEventCollectionService.group(listOf(e), 2)
        assertEquals(1, groups.size)
        val g = groups[0]
        assertTrue(g.groupId.startsWith("idx_entertainment_"))
        assertTrue(g.eventIds.contains("e1"))
        assertEquals("1 条", g.meta)
        assertEquals("已整理", g.status)
        assertTrue(g.local)
        assertTrue(g.title.isNotBlank())
    }

    @Test
    fun `detail resolves only the real events in the group`() {
        val a = event("a", sceneIds = listOf(3), date = "2026-06-01", summary = "与老张沟通周五会议")
        val b = event("b", sceneIds = listOf(3), date = "2026-06-02", summary = "与老张沟通项目排期")
        val d = event("d", sceneIds = listOf(3), date = "2026-06-03", summary = "与老张沟通下周")
        val other = event("x", sceneIds = listOf(0), date = "2026-06-04", summary = "买了商品")

        val groups = IndexedEventCollectionService.group(listOf(a, b, d, other), 3)
        val group = groups.first { it.eventIds.contains("a") }

        val resolved = IndexedEventCollectionService.eventsForGroup(group.eventIds, listOf(a, b, d, other))
        assertEquals(group.eventIds.sorted(), resolved.map { it.eventId }.sorted())
        // The other-scene event is never included in this group's detail.
        assertTrue(resolved.none { it.eventId == "x" })
    }

    @Test
    fun `tabs are a bounded ordered set with fallback`() {
        val tabs = IndexedEventCollectionService.tabs()
        assertEquals(4, tabs.size)
        assertEquals("shopping", tabs[0].tabId)
        assertEquals(IndexedEventCollectionService.tabForScene(0), tabs[0])
        assertEquals(tabs[0], IndexedEventCollectionService.tabForScene(99)) // fallback
    }

    private fun event(
        id: String,
        sceneIds: List<Int>,
        date: String,
        summary: String = "",
        normalizedFact: String = "",
        sources: List<String> = emptyList(),
    ) = IndexedEvent(
        eventId = id,
        eventDate = date,
        summary = summary,
        normalizedFact = normalizedFact,
        sources = if (sources.isEmpty()) listOf("DailyLog") else sources,
        workflowSceneIds = sceneIds,
    )
}
