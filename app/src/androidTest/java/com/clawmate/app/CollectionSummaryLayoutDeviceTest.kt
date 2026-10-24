package com.clawmate.app

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.clawmate.app.gallery.collection.IndexedCollectionGroup
import com.clawmate.app.gallery.ui.SummaryCollectionGroups
import com.clawmate.app.ui.theme.ClawMateTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class CollectionSummaryLayoutDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun timelinePaginatesFiveGroupsAndOpensTheSelectedRealGroupId() {
        val groups = (1..6).map { index -> IndexedCollectionGroup(
            groupId = "fixture-$index", title = "设备分组$index", source = "设备测试", time = "2026-10-24",
            meta = "1 条", status = "已整理", domain = "work", eventIds = listOf("event-$index"), local = true,
        ) }
        var selected = ""
        compose.setContent { ClawMateTheme { Column { SummaryCollectionGroups("设备记录", groups, 0) { selected = it.groupId } } } }
        compose.onNodeWithText("设备分组1").assertIsDisplayed()
        compose.onNodeWithText("设备分组6").assertDoesNotExist()
        compose.onNodeWithText("下一页").performClick()
        compose.onNodeWithText("2/2").assertIsDisplayed()
        compose.onNodeWithText("设备分组6").performClick()
        compose.runOnIdle { assertEquals("fixture-6", selected) }
        compose.onNodeWithText("上一页").performClick()
        compose.onNodeWithText("设备分组1").assertIsDisplayed()
    }

    @Test fun emptyTimelineDoesNotInventGroups() {
        compose.setContent { ClawMateTheme { SummaryCollectionGroups("购物记录", emptyList(), 0) { error("no group") } } }
        compose.onNodeWithText("共0组").assertIsDisplayed()
        compose.onNodeWithText("暂无已整理记录").assertIsDisplayed()
        compose.onNodeWithText("下一页").assertDoesNotExist()
    }
}
