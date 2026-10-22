package com.clawmate.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.clawmate.app.datamanager.ConsolidationModelConfig
import com.clawmate.app.home.comfort.*
import com.clawmate.app.home.ui.HomeComfortCard
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Native UI with labelled reply fixtures; no real provider call or private-history read. */
@RunWith(AndroidJUnit4::class)
class HomeComfortDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun casualChatSendsDisplaysReplyAndCanRestartWithoutAStaleReply() {
        val vm = HomeComfortViewModel({ ConsolidationModelConfig("https://example.invalid", "fixture-key", "fixture-model") },
            { _, _, _ -> HomeComfortReply("设备测试：我听到了，想从哪件事开始聊？", false) })
        compose.setContent { MaterialTheme { HomeComfortCard(vm) {} } }
        compose.onNodeWithText("打开陪伴聊天").performClick()
        compose.onNodeWithText("想说的话（最多700字）").performTextInput("设备测试：今天有点累")
        compose.onNodeWithText("发送给 AI 陪伴").performScrollTo().performClick()
        compose.waitUntil(5000) { vm.state.value.messages.size == 2 }
        compose.onNodeWithText("设备测试：我听到了，想从哪件事开始聊？").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("重新开始陪伴聊天").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(vm.state.value.messages.isEmpty()) }
        compose.onNodeWithText("今天想聊点什么？").performScrollTo().assertIsDisplayed()
    }

    @Test fun unconfiguredSendPreservesTextAndCanOpenSettings() {
        val settings = AtomicInteger()
        val vm = HomeComfortViewModel({ null }, { _, _, _ -> error("must not call") })
        compose.setContent { MaterialTheme { HomeComfortCard(vm) { settings.incrementAndGet() } } }
        compose.onNodeWithText("打开陪伴聊天").performClick()
        compose.onNodeWithText("想说的话（最多700字）").performTextInput("保留这条草稿")
        compose.onNodeWithText("发送给 AI 陪伴").performScrollTo().performClick()
        compose.onNodeWithText("填写并保存", substring = true).performScrollTo().assertIsDisplayed()
        compose.runOnIdle {
            assertEquals("保留这条草稿", vm.state.value.input)
            assertTrue(vm.state.value.messages.isEmpty())
        }
        compose.onNodeWithText("打开设置").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, settings.get()); assertFalse(vm.state.value.open) }
    }
}
