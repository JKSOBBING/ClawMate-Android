package com.clawmate.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.clawmate.app.gallery.model.GalleryPhotoItem
import com.clawmate.app.gallery.ui.GalleryVisionPanel
import com.clawmate.app.gallery.vision.*
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GalleryVisionUiDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val image = GalleryPhotoItem("fixture", "content://fixture/image", "2026-10-23", "设备测试图", "", "", createdAt = 1)

    @Test fun consentPreviewAndSaveAreThreeSeparateRealControls() {
        val uploads = AtomicInteger(); val saves = AtomicInteger()
        val result = GalleryVisionAnalysis("设备测试杯子", "生活", "设备测试摘要", emptyList(), listOf("杯子"), listOf("杯子"), "{}")
        val vm = GalleryVisionViewModel({ GalleryVisionConfig.fromValues("https://example.invalid", "fixture-key", "fixture-model") },
            { _, _ -> uploads.incrementAndGet(); result }, { _, _ -> saves.incrementAndGet(); GalleryVisionSaveResult(true) })
        compose.setContent { MaterialTheme { GalleryVisionPanel(image, vm) {} } }
        compose.onNodeWithText("分析这张图片").performClick()
        compose.waitUntil(5000) { vm.state.value.consent != null }
        assertEquals(0, uploads.get())
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("分析这张图片").performClick()
        compose.waitUntil(5000) { vm.state.value.consent != null }
        compose.onNodeWithText("确认上传并分析").performClick()
        compose.waitUntil(5000) { vm.state.value.preview != null }
        compose.onNodeWithText("设备测试杯子", substring = true).assertIsDisplayed()
        assertEquals(1, uploads.get()); assertEquals(0, saves.get())
        compose.onNodeWithText("保存分析结果").performClick()
        compose.waitUntil(5000) { vm.state.value.saved }
        assertEquals(1, saves.get())
        compose.onNodeWithText("图库分组已刷新", substring = true).assertIsDisplayed()
    }

    @Test fun missingGalleryConfigurationNeverReadsOrUploadsTheImage() {
        val vm = GalleryVisionViewModel({ null }, { _, _ -> error("no image read") }, { _, _ -> error("no save") })
        compose.setContent { MaterialTheme { GalleryVisionPanel(image, vm) {} } }
        compose.onNodeWithText("分析这张图片").performClick()
        compose.waitUntil(5000) { vm.state.value.error != null }
        compose.onNodeWithText("图库 API 地址", substring = true).assertIsDisplayed()
        compose.onNodeWithText("上传这张图片进行分析？").assertDoesNotExist()
    }
}
