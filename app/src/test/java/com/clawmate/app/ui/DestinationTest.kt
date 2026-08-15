package com.clawmate.app.ui

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
}
