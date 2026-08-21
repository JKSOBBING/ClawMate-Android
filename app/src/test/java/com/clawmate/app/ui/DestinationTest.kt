package com.clawmate.app.ui

import com.clawmate.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Repository-level check on the navigation shell definition. Verifies the
 * five-tab structure mirrors the HarmonyOS main tab set
 * (entry/src/main/ets/pages/main/MainTabConfig.ets).
 */
class DestinationTest {

    @Test
    fun `five tabs in the correct order`() {
        val routes = Destination.items.map { it.route }
        assertEquals(
            listOf("home", "summary", "chat", "task", "settings"),
            routes,
        )
    }

    @Test
    fun `chat is the center tab`() {
        val items = Destination.items
        assertEquals(Destination.Chat, items[2])
    }

    @Test
    fun `routes are unique`() {
        val routes = Destination.items.map { it.route }
        assertEquals(routes.size, routes.distinct().size)
        assertTrue(routes.none { it.isBlank() })
    }

    @Test
    fun `all tab entries are initialized`() {
        assertEquals(5, Destination.items.size)
        assertTrue(Destination.items.all { it.route.isNotBlank() && it.label.isNotBlank() })
    }

    @Test
    fun `tab entries use the Harmony icon resources`() {
        assertEquals(
            listOf(
                R.drawable.tab_home,
                R.drawable.tab_summary,
                R.drawable.tab_chat,
                R.drawable.tab_task,
                R.drawable.tab_settings,
            ),
            Destination.items.map { it.icon },
        )
    }
}
