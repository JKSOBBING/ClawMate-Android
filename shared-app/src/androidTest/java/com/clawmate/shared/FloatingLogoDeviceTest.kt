@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package com.clawmate.shared

import android.graphics.PixelFormat
import android.graphics.Outline
import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.clawmate.platform.gui.FloatingPosition
import com.clawmate.platform.gui.floatingLogo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class FloatingLogoDeviceTest {
    @Test fun dragSingleAndDoubleTapRemainDistinctInAttachedWindow() {
        val singles = AtomicInteger()
        val doubles = AtomicInteger()
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val testStore = "floating-logo-gesture-test"
        val prefs = context.getSharedPreferences(testStore, Context.MODE_PRIVATE)
        prefs.edit().clear().putFloat("x", .3f).commit()
        try {
            ActivityScenario.launch(SharedActivity::class.java).use { scenario ->
                lateinit var logo: android.view.View
                lateinit var params: WindowManager.LayoutParams
                fun gesture(a: Float, b: Float, dx: Float, dy: Float) {
                    val down = SystemClock.uptimeMillis()
                    scenario.onActivity {
                        val steps = listOf(Triple(MotionEvent.ACTION_DOWN, a, b), Triple(MotionEvent.ACTION_MOVE, a + dx, b + dy),
                            Triple(MotionEvent.ACTION_UP, a + dx, b + dy))
                        steps.forEachIndexed { index, (type, x, y) ->
                            val event = MotionEvent.obtain(down, down + index, type, x, y, 0)
                            try { logo.dispatchTouchEvent(event) } finally { event.recycle() }
                        }
                    }
                }
                val delay = ViewConfiguration.getDoubleTapTimeout().toLong() + 150
                scenario.onActivity { activity ->
                    val size = (48 * activity.resources.displayMetrics.density).toInt()
                    params = WindowManager.LayoutParams(size, size, WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply { token = activity.window.decorView.windowToken }
                    val testContext = object : ContextWrapper(activity) {
                        override fun getSharedPreferences(name: String, mode: Int) =
                            if (name == "floating-logo") prefs else super.getSharedPreferences(name, mode)
                    }
                    val position = FloatingPosition(testContext)
                    logo = floatingLogo(activity, "合成手势测试").apply { setOnClickListener { singles.incrementAndGet() } }
                    position.place(params)
                    position.gestures(logo, logo, params) { doubles.incrementAndGet() }
                    activity.windowManager.addView(logo, params)
                }
                try {
                    Thread.sleep(2000)
                    scenario.onActivity {
                        assertEquals(0, params.x)
                        assertEquals(1f, logo.alpha, .001f)
                        assertTrue(logo.clipToOutline)
                        val outline = Outline()
                        logo.outlineProvider.getOutline(logo, outline)
                        assertEquals(logo.width / 2f, outline.radius, .01f)
                    }
                    Thread.sleep(1500)
                    scenario.onActivity { assertEquals(.5f, logo.alpha, .001f) }
                    gesture(10f, 10f, 0f, 0f)
                    scenario.onActivity { assertEquals(1f, logo.alpha, .001f) }
                    Thread.sleep(delay)
                    assertEquals(1, singles.get())
                    gesture(10f, 10f, 0f, 0f); gesture(10f, 10f, 0f, 0f)
                    Thread.sleep(delay)
                    assertEquals(1, doubles.get()); assertEquals(1, singles.get())
                    val startY = params.y
                    val dragDistance = logo.resources.displayMetrics.widthPixels * .68f
                    gesture(10f, 10f, dragDistance, if (startY > 200) -130f else 130f)
                    Thread.sleep(delay)
                    assertNotEquals(startY, params.y)
                    assertEquals(android.view.Gravity.RIGHT, params.gravity and android.view.Gravity.HORIZONTAL_GRAVITY_MASK)
                    assertEquals(0, params.x)
                    assertEquals(1f, prefs.getFloat("x", -1f), 0f)
                    gesture(10f, 10f, -dragDistance, 0f)
                    Thread.sleep(delay)
                    assertEquals(android.view.Gravity.LEFT, params.gravity and android.view.Gravity.HORIZONTAL_GRAVITY_MASK)
                    assertEquals(0, params.x)
                    assertEquals(0f, prefs.getFloat("x", -1f), 0f)
                    assertEquals(1, doubles.get()); assertEquals(1, singles.get())
                } finally { scenario.onActivity { it.windowManager.removeViewImmediate(logo) } }
            }
        } finally {
            context.deleteSharedPreferences(testStore)
        }
    }
}
