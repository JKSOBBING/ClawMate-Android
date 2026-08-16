package com.clawmate.app.home

import com.clawmate.app.home.domain.HomeRecommendationViewModel
import com.clawmate.app.home.domain.RecommendationListLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic checks over the recommendation pagination / completion /
 * filter reductions (mirrors `HomePage.ets` visibleRecommendations,
 * recommendationPageCount, recommendationPageItems).
 */
class RecommendationListLogicTest {

    private fun rec(id: String, completed: Boolean = false): HomeRecommendationViewModel =
        HomeRecommendationViewModel(id = id, domain = "eat", tag = "tag", title = id, desc = "", meta = "", color = "#39C2A2", completed = completed)

    @Test
    fun `empty list stays empty on any page`() {
        val page = RecommendationListLogic.pageItems(emptyList(), showAll = false, pageIndex = 0)
        assertTrue(page.items.isEmpty())
        assertEquals(1, page.pageCount)
        assertEquals("1/1", page.pageLabel)
        assertFalse(page.hasPrevious)
        assertFalse(page.hasNext)
    }

    @Test
    fun `pending-only filter hides completed`() {
        val order = listOf(rec("a", false), rec("b", true), rec("c", false))
        val visible = RecommendationListLogic.visible(order, showAll = false)
        assertEquals(listOf("a", "c"), visible.map { it.id })
    }

    @Test
    fun `show all orders pending first then completed`() {
        val order = listOf(rec("done1", true), rec("pending"), rec("done2", true), rec("pending2"))
        val visible = RecommendationListLogic.visible(order, showAll = true)
        assertEquals(listOf("pending", "pending2", "done1", "done2"), visible.map { it.id })
    }

    @Test
    fun `pagination caps at four items per page`() {
        val order = (1..9).map { rec("todo-$it") }
        val p0 = RecommendationListLogic.pageItems(order, showAll = false, pageIndex = 0)
        assertEquals(4, p0.items.size)
        assertEquals(3, p0.pageCount)
        val p2 = RecommendationListLogic.pageItems(order, showAll = false, pageIndex = 2)
        assertEquals(listOf("todo-9"), p2.items.map { it.id })
        assertEquals(true, p0.hasPrevious == false)
        assertTrue(p2.hasNext == false)
    }

    @Test
    fun `page index clamps out of range`() {
        val order = (1..5).map { rec("todo-$it") }
        val page = RecommendationListLogic.pageItems(order, showAll = false, pageIndex = 99)
        assertEquals(2, page.pageCount)
        assertEquals(1, page.pageIndex)
    }

    @Test
    fun `completion moves an item to the completed group when show all`() {
        val order = listOf(rec("a"), rec("b"))
        val before = RecommendationListLogic.visible(order, showAll = true)
        assertEquals(listOf("a", "b"), before.map { it.id })
        val toggled = order.map { if (it.id == "a") it.copy(completed = true) else it }
        val after = RecommendationListLogic.visible(toggled, showAll = true)
        assertEquals(listOf("b", "a"), after.map { it.id })
    }
}
