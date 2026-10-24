package com.clawmate.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.clawmate.app.profile.avatar.data.*
import com.clawmate.app.profile.avatar.data.asset.AvatarVersionRecord
import com.clawmate.app.profile.avatar.ui.*
import com.clawmate.app.settings.data.FakeSettingsRepository
import com.clawmate.app.ui.theme.ClawMateTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class HarmonyAvatarDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun sourceGenerationPanelKeepsSeparatePhotoAndHistoryCallbacks() {
        var photo = 0; var history = 0; var generation = 0
        compose.setContent { ClawMateTheme {
            HarmonyAvatarGeneration(ProfileAvatarUiState(), null, {}, { photo++ }, {}, { generation++ }, { history++ }, {})
        } }
        compose.onNodeWithText("画像生成").assertIsDisplayed()
        compose.onNodeWithText("照片参考").performClick()
        compose.onNodeWithText("历史记录 / 生成依据").performClick()
        compose.runOnIdle { assertEquals(1, photo); assertEquals(1, history); assertEquals(0, generation) }
        saveFixtureScreenshot("ui-avatar-generation-fixture")
    }

    @Test fun actualHostCanReturnFromHistoryAndManagementWithoutClosingHome() {
        val vm = ProfileAvatarViewModel(FakeProfileAvatarRepository(), FakeSettingsRepository(), FakeAvatarAssetRepository())
        var closed = 0
        compose.setContent { ClawMateTheme { ProfileAvatarScreen(vm, { closed++ }) } }
        compose.onNodeWithText("历史记录 / 生成依据").performClick()
        compose.onNodeWithText("暂无历史数字分身").assertIsDisplayed()
        compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithText("本地画像与照片管理").performClick()
        compose.onNodeWithText("保存本地画像").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("返回数字分身").performScrollTo().performClick()
        compose.onNodeWithText("画像生成").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, closed) }
        compose.onNodeWithText("关闭").performClick()
        compose.runOnIdle { assertEquals(1, closed) }
    }

    @Test fun localImportVersionDoesNotInventAiParametersOrProfileEvidence() {
        val fixture = AvatarVersionRecord("device-avatar-fixture", "2026-10-25", provider = "local-import", promptSnapshot = "设备导入照片")
        compose.setContent { ClawMateTheme { HarmonyAvatarVersion(fixture, { null }, {}) } }
        compose.onNodeWithText("此版本没有画像快照").assertIsDisplayed()
        compose.onNodeWithText("设备导入照片").assertIsDisplayed()
        compose.onNodeWithText("AI生成").assertDoesNotExist()
        saveFixtureScreenshot("ui-avatar-version-fixture")
    }

    private fun saveFixtureScreenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        android.os.SystemClock.sleep(400)
        val directory = checkNotNull(instrumentation.targetContext.getExternalFilesDir("ui-validation"))
        java.io.FileOutputStream(java.io.File(directory, "$name.png")).use { stream ->
            checkNotNull(instrumentation.uiAutomation.takeScreenshot()).compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)
        }
    }
}
