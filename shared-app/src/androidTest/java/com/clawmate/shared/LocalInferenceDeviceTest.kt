package com.clawmate.shared

import android.os.Bundle
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.clawmate.inference.MobiInferTextEngine
import com.clawmate.platform.localmodel.LocalModelManager
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt in with gguf-fixture.gguf in the disposable app's cache or external files directory. */
@RunWith(AndroidJUnit4::class)
class LocalInferenceDeviceTest {
    private fun fixture(): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return File(context.cacheDir, "gguf-fixture.gguf").takeIf { it.isFile }
            ?: File(requireNotNull(context.getExternalFilesDir(null)), "gguf-fixture.gguf")
    }

    @Test fun oversizedHistoryDropsAWholeTurnButOversizedQuestionIsRejected() {
        val file = fixture()
        assumeTrue("Requires a real GGUF fixture", file.isFile)
        val oversized = "hello ".repeat(10_000)
        try {
            MobiInferTextEngine.generate(file, listOf("user" to oversized), 16, 2, { false }) {
                fail("Generated text for a question that does not fit")
            }
            fail("An oversized current question should have been rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("超过模型上下文"))
        }

        val history = listOf("user" to oversized, "assistant" to "OK.", "user" to "Hello! Say hello back.")
        val answer = StringBuilder()
        MobiInferTextEngine.generate(file, history, 16, 2, { false }) { answer.append(it) }
        assertTrue("Should complete after trimming the old turn", answer.isNotBlank())
    }

    @Test fun cancelsDuringNativeModelLoad() {
        val file = fixture()
        assumeTrue("Requires a real GGUF fixture", file.isFile)
        var cancelledDuringLoad = false
        try {
            MobiInferTextEngine.generate(file, listOf("user" to "Hello"), 8, 2, cancelled = {
                // Cancel only inside the upstream tensor-loading callback. An entry-point
                // or token-loop check alone should not make this test pass.
                cancelledDuringLoad = Thread.currentThread().stackTrace.any {
                    it.isNativeMethod && it.className == "com.clawmate.inference.NativeGguf" && it.methodName == "open"
                }
                cancelledDuringLoad
            }) { fail("Generated text after cancelling during model load") }
            fail("The model load should have been cancelled")
        } catch (expected: java.util.concurrent.CancellationException) {
            assertTrue("Cancellation must originate from the model loader", cancelledDuringLoad)
        }
    }

    @Test fun importedModelGeneratesThroughChatBridge() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val file = fixture()
        val manager = LocalModelManager(context.filesDir)
        assumeTrue("Requires a GGUF fixture and a fresh model catalog", file.isFile && manager.list().isEmpty())
        val modelId = file.inputStream().use { manager.importGguf(file.name, it, file.length()) }
        try {
            ActivityScenario.launch(SharedActivity::class.java).use { scenario ->
                lateinit var bridge: PlatformBridge
                scenario.onActivity { activity ->
                    bridge = SharedActivity::class.java.getDeclaredField("platform").apply { isAccessible = true }
                        .get(activity) as PlatformBridge
                }
                val model = JSONArray(bridge.localModelList()).getJSONObject(0)
                assertTrue(model.getBoolean("runnable") && model.getBoolean("selected"))
                val request = """[{"role":"user","content":"What is the capital of France? Answer in one word."}]"""
                val id = bridge.localInferenceStart("gguf-test", modelId, request, 4)
                val deadline = SystemClock.elapsedRealtime() + 120_000
                var result: JSONObject
                do {
                    result = JSONObject(bridge.localInferencePoll(id))
                    if (result.getString("state") != "running") break
                    Thread.sleep(50)
                } while (SystemClock.elapsedRealtime() < deadline)
                assertEquals(result.optString("error"), "done", result.getString("state"))
                val answer = result.getString("text")
                assertTrue(answer.isNotBlank())
                instrumentation.sendStatus(2, Bundle().apply { putString("localModelAnswer", answer.take(300)) })
                val second = bridge.localInferenceStart("gguf-cancel-test", modelId, request, 2)
                assertTrue(bridge.localInferenceCancel(second))
                assertEquals("cancelled", JSONObject(bridge.localInferencePoll(second)).getString("state"))
            }
        } finally {
            manager.delete(modelId)
        }
    }
}
