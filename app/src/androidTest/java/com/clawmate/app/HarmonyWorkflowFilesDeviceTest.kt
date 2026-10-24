package com.clawmate.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.clawmate.app.task.data.*
import com.clawmate.app.task.ui.*
import com.clawmate.app.ui.theme.ClawMateTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class HarmonyWorkflowFilesDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val file = ManagedWorkflowFile(WorkflowFileCategory.CONFIG, "configs/fixture.json", "fixture.json", 2, 0)

    @Test fun categoryButtonsKeepTheirOwnCallbackAndCleanupShowsTheBoundary() {
        var selected: WorkflowFileCategory? = null
        compose.setContent { ClawMateTheme {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                HarmonyWorkflowFilesContent(WorkflowFilesSnapshot(listOf(file)), null, null, null, {}, { selected = it }, {}, {})
            }
        } }
        compose.onAllNodesWithText("查看")[0].performScrollTo().performClick()
        compose.runOnIdle { assertEquals(WorkflowFileCategory.CONFIG, selected) }
        compose.onAllNodesWithText("清理")[0].performScrollTo().performClick()
        compose.onNodeWithText("Android 暂未支持按类别清理", substring = true).assertIsDisplayed()
        compose.onNodeWithText("知道了").performClick()
    }

    @Test fun rawPreviewCanBeRestoredAfterMarkdownModeWithoutChangingItsText() {
        val text = "**设备预览**\n设备原文"
        compose.setContent { ClawMateTheme {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                HarmonyWorkflowFilesContent(WorkflowFilesSnapshot(listOf(file)), WorkflowFileCategory.CONFIG, file.relativePath,
                    WorkflowFilePreview(text), {}, {}, {}, {})
            }
        } }
        compose.onNodeWithText(text).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("预览").performScrollTo().performClick()
        compose.onNodeWithText("原文").performScrollTo().performClick()
        compose.onNodeWithText(text).performScrollTo().assertIsDisplayed()
    }

    @Test fun actualPhoneFileCanBeListedAndReadThroughTheProductionAdapter() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val base = java.nio.file.Files.createTempDirectory(context.cacheDir.toPath(), "workflow-file-ui-test-").toFile()
        java.io.File(base, "workflows/configs/fixture.json").apply { parentFile.mkdirs(); writeText("设备文件实际内容") }
        val store = LocalWorkflowFiles(base)
        compose.setContent { ClawMateTheme {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { HarmonyWorkflowFiles(store) }
        } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Workflow JSON · 1 个", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("查看")[0].performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("设备文件实际内容").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("设备文件实际内容").performScrollTo().assertIsDisplayed()
    }
}
