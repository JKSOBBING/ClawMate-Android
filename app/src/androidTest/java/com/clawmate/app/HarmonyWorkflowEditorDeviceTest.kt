package com.clawmate.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.clawmate.app.task.json.WorkflowGraphDraft
import com.clawmate.app.task.model.WorkflowTask
import com.clawmate.app.task.ui.*
import com.clawmate.app.ui.theme.ClawMateTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class HarmonyWorkflowEditorDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val fixture = WorkflowTask("device-workflow-fixture", 0, "测", "#276EF1", "设备任务", "测试", false, "fixture.json", "未运行", "从未", "")

    @Test fun graphChangesReachTheSameJsonAndSaveOnlyCallsTheSaveAction() {
        var saves = 0
        setEditor(WorkflowGraphDraft.empty(), { saves++ })
        compose.onNodeWithContentDescription("任务标题").performTextReplacement("设备编排标题")
        compose.onNodeWithText("GUI 任务").performScrollTo().performClick()
        compose.onNodeWithText("选中").performScrollTo().performClick()
        compose.onNodeWithContentDescription("GUI 任务描述").performScrollTo().performTextReplacement("完成设备测试操作")
        compose.onNodeWithText("JSON").performScrollTo().performClick()
        compose.onNodeWithContentDescription("工作流 JSON").assertTextContains("设备编排标题", substring = true)
        compose.onNodeWithContentDescription("工作流 JSON").assertTextContains("完成设备测试操作", substring = true)
        compose.onNodeWithText("保存 Workflow").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, saves) }
    }

    @Test fun invalidContextIsKeptAndBlocksSavingUntilCorrected() {
        setEditor(WorkflowGraphDraft.empty())
        compose.onNodeWithContentDescription("上下文变量").performTextReplacement("{broken}")
        compose.onNodeWithText("保存 Workflow").assertIsNotEnabled()
        compose.onNodeWithContentDescription("上下文变量").assertTextEquals("{broken}")
        compose.onNodeWithContentDescription("上下文变量").performTextReplacement("{\"contact_name\":\"设备联系人\"}")
        compose.onNodeWithText("保存 Workflow").assertIsEnabled()
    }

    @Test fun malformedJsonCanReturnFromGraphWithoutRepairOrDataLoss() {
        setEditor("{broken}", mode = "json")
        compose.onNodeWithText("图编辑").performClick()
        compose.onNodeWithText("当前 JSON 无法转换为流程图", substring = true).assertIsDisplayed()
        compose.onNodeWithText("JSON").performClick()
        compose.onNodeWithContentDescription("工作流 JSON").assertTextEquals("{broken}")
    }

    private fun setEditor(raw: String, save: () -> Unit = {}, mode: String = "graph") {
        compose.setContent { ClawMateTheme {
            var state by remember { mutableStateOf(WorkflowConfigUiState(fixture, isLoading = false, text = raw,
                draft = raw, isNew = true, editorMode = mode)) }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                HarmonyWorkflowEditor(state, true, {}, {}, { state = state.copy(draft = it) },
                    { state = state.copy(task = state.task.copy(sceneId = it)) }, save, {}, {}, {})
            }
        } }
    }
}
