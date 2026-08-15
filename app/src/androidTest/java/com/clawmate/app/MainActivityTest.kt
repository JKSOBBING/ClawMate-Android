package com.clawmate.app

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented smoke test: launches MainActivity and verifies the bottom
 * navigation shell exposes all five tabs.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun launchesAndShowsAllTabs() {
        composeRule.onNodeWithText("首页").assertExists()
        composeRule.onNodeWithText("汇总").assertExists()
        composeRule.onNodeWithText("聊天").assertExists()
        composeRule.onNodeWithText("任务").assertExists()
        composeRule.onNodeWithText("设置").assertExists()
    }
}
