package com.clawmate.app.gallery.collection

import com.clawmate.app.gallery.model.GalleryPhotoItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic tests for the collection grouping heuristic.
 */
class CollectionGroupingServiceTest {

    private fun item(
        id: String,
        category: String,
        date: String,
        keywords: List<String> = emptyList(),
        tags: List<String> = emptyList(),
    ) = GalleryPhotoItem(
        id = id,
        uri = "content://media/picker/$id",
        date = date,
        title = "",
        category = category,
        summary = "",
        keywords = keywords,
        tags = tags,
        createdAt = 0L,
    )

    @Test
    fun emptyInputYieldsNoGroups() {
        assertEquals(0, CollectionGroupingService.groupPhotos("gallery", emptyList()).size)
    }

    @Test
    fun eachItemAppearsExactlyOnceAcrossGroups() {
        val items = listOf(
            item("a", "餐饮", "2026-06-01", keywords = listOf("早餐")),
            item("b", "餐饮", "2026-06-01", keywords = listOf("早餐")),
            item("c", "工作", "2026-06-02", keywords = listOf("会议")),
        )
        val groups = CollectionGroupingService.groupPhotos("gallery", items)
        val allIds = groups.flatMap { it.eventIds }
        assertEquals(items.size, allIds.size)
        assertEquals(items.size, allIds.toSet().size)
    }

    @Test
    fun sameKeyItemsGroupTogether() {
        val items = listOf(
            item("a", "餐饮", "2026-06-01", keywords = listOf("早餐")),
            item("b", "餐饮", "2026-06-01", keywords = listOf("早餐")),
        )
        val groups = CollectionGroupingService.groupPhotos("gallery", items)
        assertEquals(1, groups.size)
        assertEquals(2, groups[0].eventIds.size)
    }

    @Test
    fun groupsAreSortedByTimeDesc() {
        val items = listOf(
            item("old", "餐饮", "2026-05-01"),
            item("new", "餐饮", "2026-06-01"),
        )
        val groups = CollectionGroupingService.groupPhotos("gallery", items)
        assertEquals("2026-06-01", groups[0].time)
    }

    @Test
    fun groupCarriesDeterministicTitleAndMeta() {
        val items = listOf(item("x", "餐饮", "2026-06-01", keywords = listOf("早餐")))
        val groups = CollectionGroupingService.groupPhotos("gallery", items)
        assertEquals(1, groups.size)
        assertEquals("1 条", groups[0].meta)
        assertTrue(groups[0].status.isNotEmpty())
        assertTrue(groups[0].groupId.startsWith("grp_"))
    }

    @Test
    fun chatTabDetectsGalleryChat() {
        val items = listOf(
            item("c", "chat", "2026-06-01", tags = listOf("微信")),
        )
        val groups = CollectionGroupingService.groupPhotos("chat", items)
        assertEquals(1, groups.size)
        assertTrue(groups[0].eventIds.contains("c"))
    }
}
