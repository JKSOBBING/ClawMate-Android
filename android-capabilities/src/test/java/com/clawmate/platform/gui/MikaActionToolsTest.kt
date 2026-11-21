package com.clawmate.platform.gui

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.cap.phone.PHONE
import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.uistate.ScreenPort
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.UiElement
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A phone task can only act if the acting tools reach the model. A field run showed the agent
 * reading the tree over and over and never calling `phone_tap`, so this pins the tool list for the
 * exact wiring `MikaPhoneRun` uses (SCREEN mode: skills, inline screen, adaptive vision, no
 * whitelist). If this fails, the problem is the host wiring, not the model's choices.
 */
class MikaActionToolsTest {

    @Test
    fun `acting tools reach the model in the screen wiring`() {
        val names = toolDispatcher(
            FakeScreen(),
            skills = com.wangzan101.mika.core.knowledge.GuideCatalog.loadBundled(),
            inlineScreen = true,
            adaptiveVision = true,
            allowedToolNames = null,
        ).apply { expand(PHONE) }.definitions().map { it.name }.toSet()

        for (tool in listOf(
            "phone_observe", "phone_list_elements",
            "phone_tap", "phone_scroll", "phone_swipe", "phone_gesture",
            "phone_type_text", "phone_clear_text", "phone_back", "phone_press_key", "phone_diagnose",
        )) {
            assertTrue("$tool is offered to the model", tool in names)
        }
    }

    @Test
    fun `the same wiring hides nothing it needs for a first tap`() {
        val names = toolDispatcher(FakeScreen(), inlineScreen = true, adaptiveVision = true)
            .apply { expand(PHONE) }.definitions().map { it.name }.toSet()
        // Vision tools stay out without a vision port, but the plain tap path must stay in.
        assertTrue("no vision port still offers tap", "phone_tap" in names)
    }

    private class FakeScreen : ScreenPort {
        override suspend fun snapshot() = ScreenSnapshot("p1", "example.fixture",
            listOf(UiElement("@e1", "TextView", "Fixture", 5, 5, listOf(0, 0, 10, 10), null, false, false, false)), 10, 10)
        override suspend fun screenshotPng(): ByteArray? = null
        override suspend fun tapRef(ref: String, snapshotId: String) = ActionResult.Failed("disabled")
        override suspend fun tapXY(x: Int, y: Int) = ActionResult.Failed("disabled")
        override suspend fun typeText(ref: String?, snapshotId: String?, text: String) = ActionResult.Failed("disabled")
        override suspend fun clearText(ref: String?, snapshotId: String?) = ActionResult.Failed("disabled")
        override suspend fun scroll(ref: String?, snapshotId: String?, down: Boolean) = ActionResult.Failed("disabled")
        override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) = ActionResult.Failed("disabled")
        override suspend fun back() = ActionResult.Failed("disabled")
        override suspend fun pressKey(keyCode: Int) = ActionResult.Failed("disabled")
        override suspend fun openApp(packageName: String, cold: Boolean) = ActionResult.Failed("disabled")
        override suspend fun waitStable(timeoutMs: Long) = true
        override suspend fun listApps() = listOf("example.fixture" to "Fixture")
    }
}
