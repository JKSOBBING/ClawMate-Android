@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package com.clawmate.shared

import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.util.Xml
import android.view.InputDevice
import android.view.MotionEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.clawmate.platform.gui.DeciderEntry
import com.clawmate.platform.gui.WorkflowDecider
import com.clawmate.platform.mika.MikaVisionConfig
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ConfiguredDeciderDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun configuredService(): MikaVisionConfig {
        val wanted = setOf("cloudDeciderBaseUrl", "cloudDeciderModel", "cloudDeciderApiKey")
        val settings = mutableMapOf<String, String>()
        File(instrumentation.targetContext.filesDir, "preference/shared_frontend_settings").inputStream().use { stream ->
            val parser = Xml.newPullParser()
            parser.setInput(stream, "UTF-8")
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType != XmlPullParser.START_TAG) continue
                val key = parser.getAttributeValue(null, "key") ?: parser.getAttributeValue(null, "name") ?: continue
                if (key in wanted) settings[key] = parser.getAttributeValue(null, "value") ?: parser.nextText()
            }
        }
        val url = settings["cloudDeciderBaseUrl"].orEmpty()
        require(url.startsWith("https://")) { "Configure an HTTPS Decider in this installation" }
        return requireNotNull(MikaVisionConfig.parse(url, settings["cloudDeciderModel"].orEmpty(), settings["cloudDeciderApiKey"].orEmpty()))
    }

    @Test fun configuredModelSelectsAndOpensLength() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("useConfiguredDecider") == "true")
        val service = configuredService()
        val labels = listOf("Time", "Mass", "Length")
        ActivityScenario.launch(SharedActivity::class.java).use { scenario ->
            lateinit var panel: LinearLayout
            lateinit var dialog: AlertDialog
            val buttons = mutableListOf<Button>()
            var selected = ""
            scenario.onActivity { activity ->
                panel = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(24, 24, 24, 24)
                    setBackgroundColor(Color.WHITE)
                }
                panel.addView(TextView(activity).apply { text = "Unit converter"; textSize = 26f; setTextColor(Color.BLACK) })
                for (label in labels) {
                    val button = Button(activity).apply {
                        text = label
                        isAllCaps = false
                        setOnClickListener { selected = label; text = "Selected: $label" }
                    }
                    panel.addView(button, LinearLayout.LayoutParams(-1, (90 * activity.resources.displayMetrics.density).toInt()))
                    buttons.add(button)
                }
                dialog = AlertDialog.Builder(activity).setView(panel).create()
                dialog.show()
            }
            try {
                instrumentation.waitForIdleSync()
                val entries = mutableListOf<DeciderEntry>()
                val jpeg = ByteArrayOutputStream()
                scenario.onActivity {
                    val width = panel.width
                    val height = panel.height
                    val image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    try {
                        // Only this three-button test view goes to the opted-in provider.
                        panel.draw(Canvas(image))
                        check(image.compress(Bitmap.CompressFormat.JPEG, 85, jpeg))
                    } finally { image.recycle() }
                    buttons.forEach { b ->
                        entries.add(DeciderEntry(b.text.toString(), listOf(b.left * 1000 / width, b.top * 1000 / height,
                            b.right * 1000 / width, b.bottom * 1000 / height)))
                    }
                }
                val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
                    .readTimeout(45, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS).build()
                val choice = runBlocking {
                    WorkflowDecider.choose(service, http, "Open the length unit conversion page.", entries, jpeg.toByteArray())
                }
                assertEquals("Length", entries[choice].label)
                val point = IntArray(2)
                scenario.onActivity {
                    val button = buttons[choice]
                    button.getLocationOnScreen(point)
                    point[0] += button.width / 2
                    point[1] += button.height / 2
                }
                val down = SystemClock.uptimeMillis()
                for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                    val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, point[0].toFloat(), point[1].toFloat(), 0)
                    event.source = InputDevice.SOURCE_TOUCHSCREEN
                    try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
                }
                instrumentation.waitForIdleSync()
                scenario.onActivity {
                    assertEquals("Length", selected)
                    assertEquals("Selected: Length", buttons[choice].text.toString())
                }
                instrumentation.sendStatus(2, Bundle().apply { putString("selected", selected) })
            } finally { scenario.onActivity { dialog.dismiss() } }
        }
    }
}
