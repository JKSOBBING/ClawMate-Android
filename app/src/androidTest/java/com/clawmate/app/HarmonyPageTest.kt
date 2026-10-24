package com.clawmate.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.clawmate.app.ui.ClawMateHeader
import com.clawmate.app.ui.HarmonyPage
import com.clawmate.app.ui.theme.ClawMateTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Exercises real GPU drawing, scroll invalidation and fixed-header input bounds. */
class HarmonyPageTest {
    @get:Rule val compose = createComposeRule()

    @Test fun headerRemainsFixedAndRenderingSettlesAfterScroll() {
        compose.setContent {
            ClawMateTheme {
                val scroll = rememberScrollState()
                HarmonyPage(scrollOffset = { scroll.value.toFloat() }, header = { ClawMateHeader("固定标题") }) { insets ->
                    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(insets)) {
                        repeat(30) { Text("滚动项目 $it", Modifier.fillMaxWidth().height(80.dp)) }
                    }
                }
            }
        }
        val before = compose.onNodeWithText("固定标题").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithText("滚动项目 20").performScrollTo().assertIsDisplayed()
        compose.waitForIdle()
        val after = compose.onNodeWithText("固定标题").fetchSemanticsNode().boundsInRoot
        assertEquals(before, after)
    }
}
