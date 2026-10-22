package com.clawmate.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.clawmate.app.datamanager.ConsolidationModelConfig
import com.clawmate.app.datamanager.LocalConsolidationTypes.IndexedEvent
import com.clawmate.app.home.tasks.*
import com.clawmate.app.home.ui.HomeTaskSuggestionsCard
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real controls with explicit fixtures; free-form execution is correctly reported unavailable. */
@RunWith(AndroidJUnit4::class)
class HomeTaskSuggestionsDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun generatedSuggestionShowsRealEvidenceAndCanOpenSupportedWorkflows() {
        val openedTasks = AtomicInteger()
        val context = HomeTaskSuggestions.context(listOf(IndexedEvent(summary = "设备测试：咖啡偏好", eventDate = "2026-10-23")),
            emptyList(), emptyList(), "2026-10-23")
        val tasks = (1..3).map { HomeSuggestedTask("device-$it", "设备建议$it", "查询资讯$it", "设备测试依据", "life",
            context.evidence.values.toList(), false, "", "") }
        val vm = HomeTaskSuggestionViewModel({ ConsolidationModelConfig("https://example.invalid", "fixture-key", "fixture-model") },
            { _, _ -> context }, { _, _ -> tasks })
        compose.setContent { MaterialTheme { HomeTaskSuggestionsCard(vm, emptyList(), { openedTasks.incrementAndGet() }, {}) } }
        assertTrue(vm.state.value.tasks.isEmpty())
        compose.onNodeWithText("生成任务建议").performClick()
        compose.waitUntil(5000) { vm.state.value.tasks.size == 3 }
        compose.onNodeWithText("生活 · 设备建议1").performClick()
        compose.onNodeWithText("设备测试：咖啡偏好", substring = true).assertIsDisplayed()
        compose.onNodeWithText("尚未接入 Android", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNode(hasText("查看可用流程") and hasAnyAncestor(isDialog())).performClick()
        compose.runOnIdle {
            assertEquals(1, openedTasks.get())
            assertNull(vm.state.value.selected)
        }
    }

    @Test fun unconfiguredGenerateCanOpenSettingsWithoutReadingRecords() {
        val openedSettings = AtomicInteger()
        val vm = HomeTaskSuggestionViewModel({ null }, { _, _ -> error("no record read") }, { _, _ -> error("no network") })
        compose.setContent { MaterialTheme { HomeTaskSuggestionsCard(vm, emptyList(), {}, { openedSettings.incrementAndGet() }) } }
        compose.onNodeWithText("生成任务建议").performClick()
        compose.onNodeWithText("填写并保存", substring = true).assertIsDisplayed()
        compose.onNodeWithText("打开设置").performClick()
        compose.runOnIdle { assertEquals(1, openedSettings.get()) }
    }
}
