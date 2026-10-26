package com.clawmate.shared

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import android.os.Bundle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real local engine/bridge checks, using only generated text images and synthetic speech. */
@RunWith(AndroidJUnit4::class)
class NativeCapabilitiesDeviceTest {
    private fun evidence(name: String, value: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply { putString(name, value) })
    }
    private fun withBridge(test: (PlatformBridge) -> Unit) {
        ActivityScenario.launch(SharedActivity::class.java).use { scenario ->
            lateinit var bridge: PlatformBridge
            scenario.onActivity { activity ->
                val field = SharedActivity::class.java.getDeclaredField("platform")
                field.isAccessible = true
                bridge = field.get(activity) as PlatformBridge
            }
            test(bridge)
        }
    }

    private fun fixture(): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("migration-ocr-", ".png", context.cacheDir)
        val bitmap = Bitmap.createBitmap(1200, 400, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 90f }
        canvas.drawText("ClawMate 2026", 60f, 150f, paint)
        canvas.drawText("共享前端本地识别", 60f, 290f, paint)
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
        return file
    }

    @Test fun actualChineseOcrThroughNativeBridge() = withBridge { bridge ->
        val file = fixture()
        try {
            val id = bridge.ocrStart(file.toURI().toString())
            val deadline = System.nanoTime() + 45_000_000_000L
            var result: JSONObject
            do {
                result = JSONObject(bridge.ocrPoll(id))
                assertEquals(1, result.getInt("v"))
                if (result.getString("status") != "running") break
                Thread.sleep(100)
            } while (System.nanoTime() < deadline)
            assertEquals(result.toString(), "done", result.getString("status"))
            assertTrue(result.toString(), result.getString("text").contains("2026"))
            assertTrue(result.toString(), result.getString("text").contains("共享前端"))
            Log.i("ClawMateNativeTest", "OCR_REAL_TEXT=" + result.getString("text"))
            evidence("ocrRealText", result.getString("text"))
            assertEquals("missing", JSONObject(bridge.ocrPoll(id)).getString("status"))
        } finally { file.delete() }
    }

    @Test fun cancelDropsResultsAndRemoteInputsAreRefused() = withBridge { bridge ->
        assertThrows(IllegalArgumentException::class.java) { bridge.ocrStart("https://fixture.invalid/photo.png") }
        assertThrows(IllegalArgumentException::class.java) { bridge.ocrStart("file:///system/build.prop") }
        val file = fixture()
        try {
            val id = bridge.ocrStart(file.toURI().toString())
            assertTrue(bridge.ocrCancel(id))
            assertEquals("missing", JSONObject(bridge.ocrPoll(id)).getString("status"))
        } finally { file.delete() }
    }

    @Test fun speechUsesRealCallbacksOrReportsUnavailable() = withBridge { bridge ->
        assertTrue(bridge.ttsInitialize())
        var snapshot = JSONObject(bridge.runtimeSnapshot())
        val deadline = System.nanoTime() + 12_000_000_000L
        while (snapshot.getString("ttsEngineState") == "INITIALIZING" && System.nanoTime() < deadline) {
            Thread.sleep(100); snapshot = JSONObject(bridge.runtimeSnapshot())
        }
        if (snapshot.getString("ttsEngineState") != "READY") {
            assertEquals(snapshot.toString(), "error", snapshot.getString("voiceStatus"))
            assertTrue(snapshot.getString("voiceError").isNotBlank())
            Log.i("ClawMateNativeTest", "TTS_CONTROLLED_UNAVAILABLE=" + snapshot.getString("ttsEngineState"))
            evidence("ttsOutcome", "CONTROLLED_UNAVAILABLE: " + snapshot.getString("ttsEngineState"))
        } else {
            assertTrue(bridge.ttsSpeak("migration-speech-fixture", "这是共享前端本地语音测试，用于检查系统朗读回调与取消。"))
            val startedDeadline = System.nanoTime() + 8_000_000_000L
            var started = false
            while (System.nanoTime() < startedDeadline) {
                snapshot = JSONObject(bridge.runtimeSnapshot())
                if (snapshot.getString("voiceStatus") == "speaking") { started = true; break }
                if (snapshot.getString("voiceStatus") == "error") break
                Thread.sleep(40)
            }
            assertTrue(snapshot.toString(), started)
            assertTrue(bridge.ttsStop())
            assertEquals("paused", JSONObject(bridge.runtimeSnapshot()).getString("voiceStatus"))
            Log.i("ClawMateNativeTest", "TTS_REAL_START_AND_STOP=true")
            evidence("ttsOutcome", "REAL_START_AND_STOP")
        }
        assertEquals(1, snapshot.getInt("v"))
        assertTrue(snapshot.has("dark")); assertTrue(snapshot.has("keyboardHeight"))
    }
}
