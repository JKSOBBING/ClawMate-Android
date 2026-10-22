package com.clawmate.app.gallery.ui

import androidx.compose.ui.graphics.Color
import com.clawmate.app.gallery.collection.IndexedEventCollectionService
import com.clawmate.app.ui.theme.ClawMateDesign
import org.junit.Assert.assertEquals
import org.junit.Test
import androidx.compose.ui.unit.dp

/** Locks the summary tab order and selected colors to the HarmonyOS reference. */
class CollectionSummaryUiContractTest {

    @Test
    fun `summary scenes retain harmony order and labels`() {
        assertEquals(
            listOf("购物", "外卖", "娱乐", "聊天", "生活", "社交", "差旅", "文档", "健康", "办公", "图库", "个人信息"),
            IndexedEventCollectionService.tabs().map { it.label },
        )
        assertEquals((0..6).toList() + (8..12).toList(), IndexedEventCollectionService.tabs().map { it.sceneId })
    }

    @Test
    fun `summary scenes retain harmony selected colors`() {
        assertEquals(Color(0xFF765BFF), summarySceneColor(0))
        assertEquals(Color(0xFF39BFA3), summarySceneColor(1))
        assertEquals(Color(0xFF8066FF), summarySceneColor(2))
        assertEquals(Color(0xFF4B8BFF), summarySceneColor(3))
        assertEquals(Color(0xFF4F6FA8), summarySceneColor(12))
        assertEquals(summarySceneColor(1), summarySceneColor(7))
    }

    @Test
    fun `summary action cards retain the shared white card contract`() {
        assertEquals(Color.White, ClawMateDesign.Card.Surface)
        assertEquals(18.dp, ClawMateDesign.Card.Radius)
        assertEquals(12.dp, ClawMateDesign.Card.Padding)
        assertEquals(1.dp, ClawMateDesign.Card.BorderWidth)
        assertEquals(3.dp, ClawMateDesign.Card.ShadowElevation)
    }
}
