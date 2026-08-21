package com.clawmate.app.gallery.ui

import androidx.compose.ui.graphics.Color
import com.clawmate.app.gallery.collection.IndexedEventCollectionService
import org.junit.Assert.assertEquals
import org.junit.Test

/** Locks the summary tab order and selected colors to the HarmonyOS reference. */
class CollectionSummaryUiContractTest {

    @Test
    fun `summary scenes retain harmony order and labels`() {
        assertEquals(
            listOf("购物", "外卖", "娱乐", "聊天"),
            IndexedEventCollectionService.tabs().map { it.label },
        )
        assertEquals(listOf(0, 1, 2, 3), IndexedEventCollectionService.tabs().map { it.sceneId })
    }

    @Test
    fun `summary scenes retain harmony selected colors`() {
        assertEquals(Color(0xFF765BFF), summarySceneColor(0))
        assertEquals(Color(0xFF39BFA3), summarySceneColor(1))
        assertEquals(Color(0xFF8066FF), summarySceneColor(2))
        assertEquals(Color(0xFF4B8BFF), summarySceneColor(3))
    }
}
