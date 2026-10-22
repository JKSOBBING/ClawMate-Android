package com.clawmate.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.clawmate.app.datamanager.ConsolidationModelConfig
import com.clawmate.app.datamanager.chatmemory.ChatMemorySyncSummary
import com.clawmate.app.settings.ui.ChatMemoryCard
import com.clawmate.app.settings.ui.ChatMemoryViewModel
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real Compose controls with labelled injected processing; no cloud or private chat reads. */
@RunWith(AndroidJUnit4::class)
class ChatMemoryConsentDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun consentCancelAndConfirmGateProcessingAndIndexRefreshIsSeparate() {
        val syncs = AtomicInteger()
        val indexes = AtomicInteger()
        val vm = ChatMemoryViewModel(
            { ConsolidationModelConfig("https://example.invalid/v1", "fixture-key", "fixture-model") },
            { _, _ -> syncs.incrementAndGet(); ChatMemorySyncSummary(1, 1, 1, 1) },
            { indexes.incrementAndGet(); 1 },
        )
        compose.setContent { MaterialTheme { ChatMemoryCard(vm) } }
        compose.onNodeWithText("同步近 7 天聊天").performClick()
        compose.onNodeWithText("将聊天片段发送给云端模型？").assertIsDisplayed()
        assertEquals(0, syncs.get())
        compose.onNodeWithText("取消", useUnmergedTree = true).performClick()
        compose.onNodeWithText("将聊天片段发送给云端模型？").assertDoesNotExist()
        assertEquals(0, syncs.get())
        compose.onNodeWithText("同步近 7 天聊天").performClick()
        compose.onNodeWithText("确认并同步").performClick()
        compose.waitUntil(5000) { syncs.get() == 1 && !vm.state.value.busy }
        compose.onNodeWithText("新增 1 条事实", substring = true).assertIsDisplayed()
        assertEquals(0, indexes.get())
        compose.onNodeWithText("刷新本地记忆索引").performClick()
        compose.waitUntil(5000) { indexes.get() == 1 && !vm.state.value.busy }
        compose.onNodeWithText("本地索引已刷新", substring = true).assertIsDisplayed()
    }

    @Test fun missingSavedConfigShowsActionableErrorWithoutAConsentDialog() {
        val vm = ChatMemoryViewModel({ null }, { _, _ -> error("no chat access") }, { 0 })
        compose.setContent { MaterialTheme { ChatMemoryCard(vm) } }
        compose.onNodeWithText("同步近 7 天聊天").performClick()
        compose.onNodeWithText("填写并保存", substring = true).assertIsDisplayed()
        compose.onNodeWithText("将聊天片段发送给云端模型？").assertDoesNotExist()
    }
}
