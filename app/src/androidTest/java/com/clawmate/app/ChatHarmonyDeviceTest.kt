package com.clawmate.app

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import com.clawmate.app.chat.model.ChatMode
import com.clawmate.app.chat.model.createEmptyChatSession
import com.clawmate.app.chat.ui.*
import com.clawmate.app.chat.voice.VoiceSessionSnapshot
import com.clawmate.app.ui.theme.ClawMateTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ChatHarmonyDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun welcomeCardWritesExactPromptWithoutSending() {
        var selected = ""
        compose.setContent { ClawMateTheme {
            HarmonyChatWelcome({ selected = it }, VoiceSessionSnapshot(), PaddingValues(), 0.dp)
        } }
        compose.onNodeWithText("回顾上周的记录与进展").assertIsDisplayed()
        compose.onNodeWithText("淘宝搜索华为 Mate 80 Pro").performClick()
        compose.runOnIdle { assertEquals("淘宝上搜索华为Mate 80 Pro手机", selected) }
    }

    @Test fun composerExpandsAndTapStillSendsWithAnEmptyDraft() {
        var sends = 0
        var starts = 0
        compose.setContent { ClawMateTheme {
            var draft by remember { mutableStateOf("") }
            HarmonyChatComposer(draft, { draft = it }, ChatMode.CLOUD_AGENT, false, false,
                { sends++ }, { starts++ }, {}, {})
        } }
        compose.onNodeWithTag("chat-composer").assertHeightIsEqualTo(58.dp)
        compose.onNodeWithContentDescription("发送，长按语音").performClick()
        compose.runOnIdle { assertEquals(1, sends); assertEquals(0, starts) }
        compose.onNode(hasSetTextAction()).performTextReplacement("12345678901")
        compose.onNodeWithTag("chat-composer").assertHeightIsEqualTo(80.dp)
        compose.onNode(hasSetTextAction()).performTextReplacement("1\n2\n3\n4\n5")
        compose.onNodeWithTag("chat-composer").assertHeightIsEqualTo(124.dp)
    }

    @Test fun holdStartsVoiceAndReleaseStopsWithoutSending() {
        var sends = 0; var starts = 0; var stops = 0
        compose.setContent { ClawMateTheme {
            HarmonyChatComposer("", {}, ChatMode.CLOUD_AGENT, false, false, { sends++ }, { starts++ }, { stops++ }, {})
        } }
        compose.onNodeWithContentDescription("发送，长按语音").performTouchInput { longClick(durationMillis = 500) }
        compose.runOnIdle { assertEquals(0, sends); assertEquals(1, starts); assertEquals(1, stops) }
    }

    @Test fun busyActionIsClickableAndShowsTheModeSpecificStopLabel() {
        var stops = 0
        compose.setContent { ClawMateTheme {
            HarmonyChatComposer("", {}, ChatMode.LOCAL_INFERENCE, true, false, { stops++ }, {}, {}, {})
        } }
        compose.onNodeWithText("终止").assertIsDisplayed()
        compose.onNodeWithContentDescription("终止，长按语音").performClick()
        compose.runOnIdle { assertEquals(1, stops) }
    }

    @Test fun drawerRenameAndPinPersistOnlyLocalPresentationMetadata() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("chat-presentation", android.content.Context.MODE_PRIVATE)
        val id = "device-ui-fixture-session"
        prefs.edit().remove("title:$id").remove("pin:$id").commit()
        try {
            compose.setContent { ClawMateTheme {
                HarmonySessionDrawer(listOf(createEmptyChatSession(id, ChatMode.CLOUD_AGENT)), id, {}, {}, {}, {})
            } }
            compose.onNodeWithText("云端智能体新会话").performTouchInput { longClick() }
            compose.onNodeWithText("重命名").performClick()
            compose.onNode(hasSetTextAction()).performTextReplacement("设备会话名称")
            compose.onNodeWithText("保存").performClick()
            compose.onNodeWithText("设备会话名称").assertIsDisplayed().performTouchInput { longClick() }
            compose.onNodeWithText("置顶").performClick()
            compose.runOnIdle {
                assertEquals("设备会话名称", prefs.getString("title:$id", null))
                assertTrue(prefs.getBoolean("pin:$id", false))
            }
        } finally { prefs.edit().remove("title:$id").remove("pin:$id").commit() }
    }

    @Test fun mascotTapAnimatesInsideStableSourceContainerThenSettles() {
        compose.mainClock.autoAdvance = false
        compose.setContent { ClawMateTheme {
            DigitalAvatar(Modifier.testTag("mascot"), size = com.clawmate.app.chat.voice.DigitalAvatarSize.HERO,
                useAssistantMascot = true)
        } }
        compose.mainClock.advanceTimeByFrame()
        val node = compose.onNodeWithTag("mascot")
        node.assertWidthIsEqualTo(150.dp).assertHeightIsEqualTo(150.dp)
        val before = node.captureToImage().toPixelMap()
        node.performClick()
        compose.mainClock.advanceTimeBy(140)
        val lifted = node.captureToImage().toPixelMap()
        var changed = 0
        for (y in 0 until before.height step 3) for (x in 0 until before.width step 3)
            if (before[x, y] != lifted[x, y]) changed++
        assertTrue("Tap should move real image pixels", changed > 10)
        compose.mainClock.advanceTimeBy(800)
        node.assertWidthIsEqualTo(150.dp).assertHeightIsEqualTo(150.dp)
        compose.mainClock.autoAdvance = true
    }
}
