package com.clawmate.app

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.clawmate.app.settings.ui.*
import com.clawmate.app.ui.theme.ClawMateTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SettingsHarmonyControlsDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun sourceFieldHeightsAndSaveOnFocusLoss() {
        var saves = 0
        compose.setContent { ClawMateTheme {
            var first by remember { mutableStateOf("设备值") }
            CompositionLocalProvider(LocalSettingsSaveRequest provides { saves++ }) {
                Column {
                    SettingInput("字段一", first) { first = it }
                    SettingInput("字段二", "") {}
                    SettingInput("长字段", "device-fixture-long-field-over-thirty-four-characters") {}
                }
            }
        } }
        compose.onNodeWithTag("clawmate-field:字段一").assertHeightIsEqualTo(42.dp)
        compose.onNodeWithTag("clawmate-field:长字段").assertHeightIsEqualTo(86.dp)
        compose.onAllNodes(hasSetTextAction())[0].performClick().performTextReplacement("设备新值")
        compose.onAllNodes(hasSetTextAction())[1].performClick()
        compose.runOnIdle { assertTrue(saves > 0) }
    }

    @Test fun sensitiveValueIsMaskedAgainWhenHidden() {
        compose.setContent { ClawMateTheme {
            var keys by remember { mutableStateOf(emptySet<String>()) }
            SensitiveSettingInput("fixture", keys, "设备密钥", "device-secret-fixture", "", { keys = setOf("fixture") },
                { keys = emptySet() }, {}, "device-secret-fixture")
        } }
        compose.onNodeWithText("device-secret-fixture").assertDoesNotExist()
        compose.onNodeWithText("显示").performClick()
        compose.onNodeWithText("device-secret-fixture").assertIsDisplayed()
        compose.onNodeWithText("隐藏").performClick()
        compose.onNodeWithText("device-secret-fixture").assertDoesNotExist()
    }
}
