package com.clawmate.shared

import androidx.test.platform.app.InstrumentationRegistry
import com.clawmate.platform.display.BackgroundDisplay
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Device regression for the authorized Weibo test task. Requires the USB display helper. */
class MikaDisplayRecoveryTest {
    @Test fun coldLaunchStaysOnTheExistingVirtualDisplay() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val deadline = android.os.SystemClock.elapsedRealtime() + 10_000
        while (!BackgroundDisplay.available && android.os.SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(100)
        }
        assertTrue("display helper connected", BackgroundDisplay.available)
        val intent = requireNotNull(context.packageManager.getLaunchIntentForPackage("com.sina.weibo"))
        val display = BackgroundDisplay.open(intent)
        try {
            assertTrue(display.id > 0)
            assertFalse(display.relaunch("com.android.settings"))
            assertTrue(display.relaunch("com.sina.weibo"))
            val descriptor = instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
                .executeShellCommand("dumpsys activity activities")
            val activity = android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
            val section = activity.substringAfter("Display #${display.id} ").substringBefore("Display #")
            assertTrue("Weibo task remains on the same display", section.contains("com.sina.weibo"))
        } finally {
            display.close()
        }
    }
}
