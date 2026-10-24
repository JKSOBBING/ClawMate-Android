package com.clawmate.app

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.clawmate.app.home.domain.HomeRecommendationViewModel
import com.clawmate.app.home.ui.*
import com.clawmate.app.ui.theme.ClawMateTheme
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Explicit UI fixtures: no production records or provider requests are used. */
class HomeRemindersDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun completingFromDetailRevealsCompletedFilterWithoutLosingTheRow() {
        val initial = listOf(
            HomeRecommendationViewModel("pending", "eat", "餐饮", "设备待办", "设备说明", "今天", "#39C2A2"),
            HomeRecommendationViewModel("done", "wear", "穿搭", "设备已完成", "设备说明", "今天", "#7669FF", completed = true),
        )
        compose.setContent {
            ClawMateTheme {
                var items by remember { mutableStateOf(initial) }
                var all by remember { mutableStateOf(false) }
                Column { HomeRemindersSection(RecommendationListUi(items, all), { all = it }, {}, {}, { id, value ->
                    items = items.map { if (it.id == id) it.copy(completed = value) else it }
                }, {}) }
            }
        }
        compose.onNodeWithText("设备已完成").assertDoesNotExist()
        compose.onNodeWithText("设备待办").performClick()
        compose.onNode(isToggleable()).performClick()
        compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithText("提醒都处理好啦，歇一会儿～").assertIsDisplayed()
        compose.onNodeWithText("查看已完成 ›").performClick()
        compose.onNodeWithText("设备待办").assertIsDisplayed()
        compose.onNodeWithText("设备已完成").assertIsDisplayed()
    }

    @Test fun emptyStateOpensTasksOnlyOnTap() {
        val opened = AtomicInteger()
        compose.setContent { ClawMateTheme { HomeRemindersSection(RecommendationListUi(), {}, {}, {}, { _, _ -> }, { opened.incrementAndGet() }) } }
        assertEquals(0, opened.get())
        compose.onNodeWithText("去派任务 ›").performClick()
        compose.runOnIdle { assertEquals(1, opened.get()) }
    }
}
