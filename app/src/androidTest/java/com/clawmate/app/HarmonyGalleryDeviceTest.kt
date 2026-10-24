package com.clawmate.app

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.clawmate.app.gallery.model.GalleryPhotoItem
import com.clawmate.app.gallery.ui.*
import com.clawmate.app.gallery.question.GalleryQuestionViewModel
import com.clawmate.app.ui.theme.ClawMateTheme
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class HarmonyGalleryDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val old = GalleryPhotoItem("old", "", "2025-10-24", "去年图片记录", "购物", "旧摘要", createdAt = 0)
    private val recent = GalleryPhotoItem("recent", "", "2026-10-24", "新图片记录", "工作", "新摘要", createdAt = 0)

    @Test fun selectingTimeReallyFiltersRecordsAndResetsAnUnavailableCategory() {
        compose.setContent { ClawMateTheme {
            var category by remember { mutableIntStateOf(0) }
            var time by remember { mutableIntStateOf(0) }
            HarmonyGalleryResults(listOf(old, recent), category, time, { category = it }, { time = it }, {},
                Instant.parse("2026-10-24T04:00:00Z").toEpochMilli())
        } }
        compose.onNodeWithText("购物（1）").performClick()
        compose.onNodeWithText("去年图片记录").assertIsDisplayed()
        compose.onNodeWithText("近7天").performClick()
        compose.onNodeWithText("全部（1）").assertIsDisplayed()
        compose.onNodeWithText("去年图片记录").assertDoesNotExist()
        compose.onNodeWithText("新图片记录").assertIsDisplayed()
    }

    @Test fun selectingKeywordSearchDoesNotTouchCloudConfigurationOrSend() {
        var searched = ""
        val vm = GalleryQuestionViewModel({ error("local search must not read cloud config") },
            { error("host owns keyword search") }, { _, _ -> error("local search must not send") })
        compose.setContent { ClawMateTheme { GalleryQuestionPanel(vm, {}, {}, onSearch = { searched = it }) } }
        compose.onNode(hasSetTextAction()).performTextInput("会议安排")
        compose.onNodeWithText("检索").performClick()
        compose.runOnIdle { assertEquals("会议安排", searched) }
    }

    @Test fun toolbarCountsOnlyCompletedAnalysis() {
        compose.setContent { ClawMateTheme {
            HarmonyGalleryToolbar(listOf(old, recent.copy(analysisState = GalleryPhotoItem.ANALYSIS_STATE_ANALYZED)), false, {}, {}, {}, {})
        } }
        compose.onNodeWithText("已分析").assertIsDisplayed()
        compose.onNodeWithText("1").assertIsDisplayed()
        compose.onNodeWithText("未记录").assertIsDisplayed()
    }
}
