package com.clawmate.shared

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.clawmate.app.task.model.WorkflowStep
import com.clawmate.app.task.runner.RunStatus
import com.clawmate.app.task.runner.StepOutcome
import com.clawmate.app.task.runner.WorkflowGuiTask
import com.clawmate.app.task.runner.WorkflowModelTool
import com.clawmate.platform.SharedNativeWork
import com.clawmate.platform.display.BackgroundDisplay
import com.clawmate.platform.gui.MikaWorkflowSession
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Operator imports the original today-chat JSON before running this optional device regression. */
class WorkflowBranchDeviceTest {
    @Test fun importedTodayChatScrollsOnceAndReturnsTwoScreens() {
        val config = InstrumentationRegistry.getArguments().getString("workflowConfig").orEmpty()
        assumeTrue("Requires an imported today-chat task and the disposable GUI fixture", config.isNotEmpty())
        require(config.matches(Regex("""task_[0-9]+\.json""")))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val root = File(context.filesDir, "files")
        val raw = File(root, "workflows/configs/$config").readText()
        val activity = instrumentation.startActivitySync(Intent(context, SharedActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val run = "android-${System.currentTimeMillis()}-1"
        val hashes = mutableListOf<String>()
        val session = MikaWorkflowSession(activity, "https://example.invalid/v1", "fixture", "", null, run)
        try {
            val deadline = SystemClock.elapsedRealtime() + 35000
            while (!BackgroundDisplay.available && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(250)
            assertTrue("Virtual display helper", BackgroundDisplay.available)
            // Only replace external dependencies: the private conversation and the remote VLM.
            val gui = object : WorkflowGuiTask {
                override suspend fun execute(step: WorkflowStep, goal: String, openOnly: Boolean): StepOutcome {
                    if (openOnly) return session.execute(step, "打开ClawMate GUI Fixture", true)
                    // Start below the top so the upstream downward swipe has visible content to reveal.
                    return session.action(WorkflowStep("setup", "gui_action", action = "swipe_with_coords", raw = buildJsonObject {
                        put("start_x", 540)
                        put("start_y", 2000)
                        put("end_x", 540)
                        put("end_y", 650)
                    }))
                }
                override suspend fun action(step: WorkflowStep) = session.action(step)
            }
            val model = WorkflowModelTool { request ->
                val jpeg = File(root, request.image)
                val image = BitmapFactory.decodeFile(jpeg.path) ?: error("Unreadable workflow JPEG")
                try { require(image.width > 0 && image.height > 0) } finally { image.recycle() }
                val hash = MessageDigest.getInstance("SHA-256").digest(jpeg.readBytes()).joinToString("") { "%02x".format(it) }
                hashes += hash
                buildJsonObject {
                    put("contains_today_chat", hash == hashes.first())
                    put("summary", "测试截图 ${hash.take(12)}")
                }.toString()
            }
            val result = SharedNativeWork.runWorkflow(raw, model, gui)
            assertEquals(result.message, RunStatus.SUCCESS, result.status)
            val steps = result.steps.associateBy { it.stepId }
            fun output(id: String) = requireNotNull(steps[id]?.output) { "No receipt for $id" }
            assertEquals("then", output("3.iter1.3")["branch"]?.jsonPrimitive?.content)
            assertEquals("else", output("3.iter2.3")["branch"]?.jsonPrimitive?.content)
            assertEquals(2, output("3")["break_iteration"]?.jsonPrimitive?.int)
            assertEquals(2, hashes.distinct().size)
            assertEquals(2, hashes.size)
            val pages = listOf(output("3.iter1.1"), output("3.iter2.1"))
            val swipes = result.steps.filter { it.type == "gui_action" && it.output?.get("action")?.jsonPrimitive?.content == "swipe_with_coords" }
            assertEquals(1, swipes.size)
            val swipe = requireNotNull(swipes.single().output)
            assertTrue(swipe.getValue("changed").jsonPrimitive.boolean)
            val display = output("1").getValue("display_id").jsonPrimitive.int
            assertTrue(display > 0)
            for (receipt in pages + swipe) assertEquals(display, receipt.getValue("display_id").jsonPrimitive.int)
            for (hash in hashes) assertTrue(result.resultSummary.contains(hash.take(12)))
            File(context.cacheDir, "workflow-p0-device-receipt.json").writeText(buildJsonObject {
                put("config", config)
                put("display_id", display)
                put("loop", output("3"))
                put("steps", buildJsonObject { for ((id, step) in steps) step.output?.let { put(id, it) } })
                put("sha256", JsonArray(hashes.map(::JsonPrimitive)))
                put("result_summary", result.resultSummary)
            }.toString())
        } finally {
            session.close()
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
