package com.clawmate.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.clawmate.app.gallery.model.GalleryPhotoItem
import com.clawmate.app.gallery.question.*
import com.clawmate.app.gallery.search.GallerySearchService
import com.clawmate.app.gallery.ui.GalleryQuestionPanel
import com.clawmate.app.gallery.vision.GalleryVisionConfig
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GalleryQuestionDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val config = GalleryVisionConfig.fromValues("https://example.invalid", "fixture-key", "fixture-model")!!
    private val photo = GalleryPhotoItem("actual-local-fixture-id", "content://fixture/image", "2026-10-23", "会议通知", "工作", "会议安排15:00", createdAt = 1)

    @Test fun consentAnswerAndCitationOpenUseTheRealLocalImageIdentity() {
        val calls = AtomicInteger(); var opened: String? = null
        val vm = GalleryQuestionViewModel({ config }, { GallerySearchService.search(it, listOf(photo)) }, { _, input ->
            calls.incrementAndGet()
            GalleryQuestionProtocol.parse("""{"answer":"会议记录显示15:00。","matchedIds":["photo_1"],"confidence":0.8}""", input)
        })
        compose.setContent { MaterialTheme { GalleryQuestionPanel(vm, { opened = it }, {}) } }
        compose.onNodeWithText("询问图库记录").performTextInput("会议时间")
        compose.onNodeWithText("提问").performClick()
        compose.waitUntil(5000) { vm.state.value.consent != null }
        assertEquals(0, calls.get())
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("提问").performClick()
        compose.waitUntil(5000) { vm.state.value.consent != null }
        compose.onNodeWithText("确认发送记录").performClick()
        compose.waitUntil(5000) { vm.state.value.answer != null }
        compose.onNodeWithText("会议记录显示15:00。").assertIsDisplayed()
        compose.onNodeWithText("查看图片：会议通知").performClick()
        assertEquals(photo.id, opened); assertEquals(1, calls.get())
    }

    @Test fun emptySearchAndMissingConfigurationDoNotSendAnyRecord() {
        var configured = true; val settings = AtomicInteger()
        val vm = GalleryQuestionViewModel({ if (configured) config else null }, { emptyList() }, { _, _ -> error("no upload") })
        compose.setContent { MaterialTheme { GalleryQuestionPanel(vm, {}, { settings.incrementAndGet() }) } }
        compose.onNodeWithText("询问图库记录").performTextInput("会议")
        compose.onNodeWithText("提问").performClick()
        compose.waitUntil(5000) { vm.state.value.notice != null }
        compose.onNodeWithText("没有找到明确相关", substring = true).assertIsDisplayed()
        compose.onNodeWithText("发送候选记录进行问答？").assertDoesNotExist()
        configured = false
        compose.onNodeWithText("提问").performClick()
        compose.waitUntil(5000) { vm.state.value.error != null }
        compose.onNodeWithText("打开设置").performClick()
        assertEquals(1, settings.get())
        assertEquals("会议", vm.state.value.question)
    }
}
