package com.clawmate.app

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.clawmate.app.datamanager.ConsolidationModelConfig
import com.clawmate.app.home.comfort.*
import com.clawmate.app.home.ui.HomeComfortCard
import com.clawmate.app.home.ui.HomeComfortTopicsCard
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeComfortTopicsDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val config = ConsolidationModelConfig("https://example.invalid", "fixture-key", "fixture-model")
    private val evidence = HomeComfortEvidence("record-1", "2026-10-23", "设备测试：最近工作很累")
    private val topic = HomeComfortTopic("device-topic", "device-batch", "设备话题", "设备测试概述", "设备模型开场", "work", listOf(evidence))
    private val context = HomeComfortTopicContext(buildJsonObject {}, mapOf(evidence.ref to evidence))

    @Test fun groundedTopicOpensItsOwnConversationWithVisibleEvidence() {
        val conversations = HomeComfortViewModel({ config }, { _, _, _ -> HomeComfortReply("设备回复", false) })
        val topics = HomeComfortTopicsViewModel({ config }, { context }, { _, _ -> listOf(topic) })
        compose.setContent { MaterialTheme { Column {
            HomeComfortCard(conversations) {}
            HomeComfortTopicsCard(topics, { conversations.open(it) }, {})
        } } }
        compose.onNodeWithText("重新看看").performClick()
        compose.waitUntil(5000) { topics.state.value.topics.size == 1 }
        compose.onNodeWithText("设备话题").performClick()
        compose.onNode(hasText("设备话题") and hasAnyAncestor(isDialog())).performClick()
        compose.onNodeWithText("设备测试：最近工作很累", substring = true).assertIsDisplayed()
        compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithText("设备模型开场").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(topic.id, conversations.state.value.topic!!.id) }
    }

    @Test fun emptyResultShowsAnHonestNoTopicState() {
        val vm = HomeComfortTopicsViewModel({ config }, { context }, { _, _ -> emptyList() })
        compose.setContent { MaterialTheme { HomeComfortTopicsCard(vm, { error("no topic") }, {}) } }
        compose.onNodeWithText("重新看看").performClick()
        compose.waitUntil(5000) { vm.state.value.generated }
        compose.onNodeWithText("和我随便聊聊，我会读心术").assertIsDisplayed()
    }
}
