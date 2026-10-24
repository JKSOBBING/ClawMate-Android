package com.clawmate.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.clawmate.app.home.data.FakeOnboardingRepository
import com.clawmate.app.home.domain.FIRST_LAUNCH_ONBOARDING_VERSION
import com.clawmate.app.home.ui.onboarding.*
import com.clawmate.app.ui.theme.ClawMateTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class HarmonyOnboardingDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun nextAndSwipeUseTheSameRealPageIndex() {
        val repository = FakeOnboardingRepository()
        val vm = OnboardingViewModel(repository)
        compose.setContent { ClawMateTheme { FirstLaunchOnboardingScreen(repository, {}, vm) } }
        compose.onNodeWithText("1/4").assertIsDisplayed()
        compose.onNodeWithContentDescription("ClawMate功能预览").assertIsDisplayed()
        saveFixtureScreenshot("ui-onboarding-first-fixture")
        compose.onNodeWithText("下一页").performClick()
        compose.onNodeWithText("2/4").assertIsDisplayed()
        compose.onNodeWithTag("onboarding-pager").performTouchInput { swipeLeft() }
        compose.onNodeWithText("3/4").assertIsDisplayed()
        compose.onNodeWithTag("onboarding-pager").performTouchInput { swipeRight() }
        compose.onNodeWithText("2/4").assertIsDisplayed()
    }

    @Test fun completingTheFourthPagePersistsOnlyTheInMemoryFixtureVersion() {
        val repository = FakeOnboardingRepository()
        val vm = OnboardingViewModel(repository)
        var finished = 0
        compose.setContent { ClawMateTheme { FirstLaunchOnboardingScreen(repository, { finished++ }, vm) } }
        repeat(3) { compose.onNodeWithText("下一页").performClick(); compose.waitForIdle() }
        compose.onNodeWithText("4/4").assertIsDisplayed()
        saveFixtureScreenshot("ui-onboarding-guide-fixture")
        compose.onNodeWithText("进入首页").performClick()
        compose.runOnIdle { assertEquals(1, finished) }
        assertEquals(FIRST_LAUNCH_ONBOARDING_VERSION, runBlocking { repository.storedVersion() })
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
