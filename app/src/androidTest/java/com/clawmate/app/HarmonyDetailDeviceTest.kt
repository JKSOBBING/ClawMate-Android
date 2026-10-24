package com.clawmate.app

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.clawmate.app.gallery.model.GalleryPhotoItem
import com.clawmate.app.gallery.ui.*
import com.clawmate.app.gallery.collection.*
import com.clawmate.app.ui.theme.ClawMateTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class HarmonyDetailDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun galleryDetailReadsRealFieldsAndKeepsManagementReachable() {
        var openedManagement = 0; var closed = 0
        val fixture = GalleryPhotoItem("device-detail-fixture", "", "2026-10-24", "设备图片详情", "工作", "设备真实字段摘要",
            ocrText = "设备 OCR 内容", createdAt = 0, analysisState = GalleryPhotoItem.ANALYSIS_STATE_ANALYZED)
        compose.setContent { ClawMateTheme {
            HarmonyGalleryDetail(fixture, { closed++ }, { openedManagement++ })
        } }
        compose.onNodeWithText("设备图片详情").assertIsDisplayed()
        saveFixtureScreenshot("ui-gallery-detail-fixture")
        compose.onNodeWithText("设备真实字段摘要").performClick()
        compose.runOnIdle { assertEquals(0, closed) }
        compose.onNodeWithText("设备 OCR 内容").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("编辑与分析").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, openedManagement) }
        compose.onNodeWithText("关闭").performClick()
        compose.runOnIdle { assertEquals(1, closed) }
    }

    @Test fun indexedEventOpensItsOwnDetailWithoutInventingImageData() {
        val event = IndexedCollectionEvent("device-event-fixture", "2026-10-24", "设备事件标题", "设备事件完整事实", "DailyLog")
        val group = IndexedCollectionGroup("device-group-fixture", "设备汇总组", "DailyLog", "2026-10-24", "1 条", "已整理", "work", listOf(event.eventId))
        compose.setContent { ClawMateTheme { IndexedCollectionGroupDetailScreen(IndexedGroupDetailState(group, listOf(event)), {}) } }
        compose.onNodeWithText("设备事件标题").performClick()
        compose.onNodeWithText("设备事件完整事实").assertIsDisplayed()
        compose.onNodeWithText("当前索引未关联来源图片").assertIsDisplayed()
        saveFixtureScreenshot("ui-indexed-detail-fixture")
        compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithText("设备事件完整事实").assertDoesNotExist()
        compose.onNodeWithText("设备汇总组").assertIsDisplayed()
    }

    private fun saveFixtureScreenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        android.os.SystemClock.sleep(400) // SurfaceFlinger/window transactions are outside Compose's clock.
        val directory = checkNotNull(instrumentation.targetContext.getExternalFilesDir("ui-validation"))
        java.io.FileOutputStream(java.io.File(directory, "$name.png")).use { stream ->
            checkNotNull(instrumentation.uiAutomation.takeScreenshot()).compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)
        }
    }
}
