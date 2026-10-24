package com.clawmate.app

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.clawmate.app.task.model.*
import com.clawmate.app.task.ui.*
import com.clawmate.app.ui.theme.ClawMateTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class HarmonyTaskDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val scene = WorkflowSceneConfig.scenes.first()
    private val fixture = WorkflowTask("device-ui-task", scene.sceneId, "测", "#276EF1", "设备任务",
        "设备测试", false, "device-fixture.json", "未运行", "从未", "")

    @Test fun taskConfigButtonOpensTaskConfigurationRatherThanSettings() {
        var settings = 0; var documents = 0
        compose.setContent { ClawMateTheme {
            HarmonyTaskPage(TaskUiState(isLoading = false, tasks = listOf(fixture)), {}, { documents++ }, {}, {}, {}, {}, { settings++ })
        } }
        compose.onNodeWithText("任务执行").assertIsDisplayed()
        compose.onNodeWithText("配置任务").performClick()
        compose.onNodeWithText("新建任务").assertIsDisplayed()
        compose.onAllNodesWithText("JSON")[1].performScrollTo().performClick()
        compose.runOnIdle { assertEquals(0, settings); assertEquals(1, documents) }
    }

    @Test fun taskSwitchDoesNotTriggerTheSeparateConfigurationTapRegion() {
        var toggles = 0; var opens = 0
        compose.setContent { ClawMateTheme {
            HarmonySimpleTaskRow(fixture, scene, false, true, { toggles++ }, { opens++ })
        } }
        compose.onNode(isToggleable()).performClick()
        compose.runOnIdle { assertEquals(1, toggles); assertEquals(0, opens) }
        compose.onNodeWithContentDescription("配置任务：设备任务").performClick()
        compose.runOnIdle { assertEquals(1, opens) }
    }

    @Test fun unavailableReminderNeverShowsAnEnabledSchedule() {
        compose.setContent { ClawMateTheme {
            HarmonyTaskPage(TaskUiState(isLoading = false), {}, {}, {}, {}, {}, {}, {})
        } }
        compose.onNode(isToggleable()).performClick()
        compose.onNodeWithText("提醒没有启用", substring = true).assertIsDisplayed()
        compose.onNodeWithText("知道了").performClick()
        compose.onNode(isToggleable()).assertIsOff()
    }
}
