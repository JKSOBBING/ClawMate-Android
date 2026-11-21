package com.clawmate.platform.gui

import android.app.Activity
import com.clawmate.app.task.model.WorkflowStep
import com.clawmate.app.task.runner.StepOutcome
import com.clawmate.app.task.runner.WorkflowGuiTask
import com.clawmate.platform.display.BackgroundDisplay
import com.clawmate.platform.mika.MikaRunStore
import com.clawmate.platform.mika.MikaVisionConfig
import java.io.Closeable
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One imported workflow owns its approved app and display until the last step or cancellation. */
class MikaWorkflowSession(
    private val activity: Activity,
    private val url: String,
    private val model: String,
    private val key: String,
    private val vision: MikaVisionConfig?,
    runName: String,
    modelAccess: com.clawmate.app.task.runner.WorkflowModelTool? = null,
) : WorkflowGuiTask, Closeable {
    internal var purpose: String? = null
        private set
    internal var navigationTaps: Int? = null
        private set
    private var followedByGui = false
    internal var useExperience = true
        private set

    override fun prepareStep(description: String, next: WorkflowStep?, useExperience: Boolean) {
        this.useExperience = useExperience
        followedByGui = next?.type == "gui_task"
        purpose = buildJsonObject {
            if (description.isNotBlank()) put("workflowDescription", description)
            if (followedByGui) put("nextStepKind", "separate_gui_task")
            if (next?.type == "gui_action") put("nextAction", next.action)
        }.takeUnless { it.isEmpty() }?.toString()
    }

    internal val collection = modelAccess?.let { VirtualWorkflowCollection(activity, it) }
    internal val previewState = MikaPreviewState()
    internal var packageName = ""
    internal var appName = ""
    internal var runId = ""
    internal var displayId = 0
    private var retained: BackgroundDisplay? = null
    private var onRetentionEnded: (() -> Unit)? = null

    private fun endRetention() {
        retained = null
        onRetentionEnded?.invoke()
        onRetentionEnded = null
    }
    private var closed = false
    private val images = WorkflowResultImages(File(activity.filesDir, "files"), runName)
    internal val collectingImages: Boolean get() = images.collecting
    internal fun observedImage(jpeg: ByteArray?) = images.observe(jpeg)

    @Synchronized internal fun takeDisplay(target: String, required: Boolean = false): BackgroundDisplay? {
        check(!closed && packageName == target) { "工作流会话已结束或目标应用已变化" }
        val display = retained ?: run {
            check(!required) { "工作流原虚拟屏已释放" }
            return null
        }
        endRetention()
        if (display.connected) return display
        display.close()
        error("工作流虚拟屏已断开")
    }

    @Synchronized internal fun retainDisplay(target: String, display: BackgroundDisplay, released: () -> Unit): Boolean {
        if (closed || target != packageName) { display.close(); return false }
        retained = display
        onRetentionEnded = released
        return true
    }

    @Synchronized override fun close() {
        closed = true
        retained?.close()
        endRetention()
    }

    override suspend fun action(step: WorkflowStep): StepOutcome {
        if (collection != null && step.action in VirtualWorkflowCollection.actions) {
            val before = collection.requests.get()
            if (step.action == "collection_open_app") {
                val target = step.raw["package_name"]?.let { it as? JsonPrimitive }?.content.orEmpty()
                require(target.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+"))) { "请选择目标应用" }
                require(packageName.isEmpty() || packageName == target) { "工作流不能切换到其他应用" }
                packageName = target
            }
            val opening = step.action == "collection_open_app" && displayId == 0
            val result = if (opening) MikaPhoneEntry.runLocal(activity, this, step, true) else null
            if (result != null && result.status != "success") return localOutcome(result, before)
            return localOutcome(MikaPhoneEntry.runLocal(activity, this, step, false), before)
        }
        if (packageName.isEmpty() || displayId <= 0) return StepOutcome.Failure("独立动作需要已打开的工作流虚拟屏")
        val result = if (collection != null) MikaPhoneEntry.runLocal(activity, this, step, false)
            else MikaPhoneEntry.runCloud(activity, "工作流 " + step.action, url, model, key, workflow = this, workflowAction = step)
        return result.workflowOutput?.let(StepOutcome::Success) ?: when (result.status) {
            "cancelled" -> StepOutcome.Cancelled(result.message)
            else -> StepOutcome.Failure(result.message)
        }
    }

    private fun localOutcome(result: MikaPhoneResult, before: Int): StepOutcome {
        val count = requireNotNull(collection).requests.get() - before
        return when (result.status) {
            "success" -> StepOutcome.Success(buildJsonObject {
                result.workflowOutput?.forEach { (key, value) -> put(key, value) }
                put("display_id", displayId)
                put("package_name", packageName)
                put("model_requests", count)
                put("executor", if (count == 0) "workflow" else "mika")
            })
            "cancelled" -> StepOutcome.Cancelled(result.message)
            else -> StepOutcome.Failure(result.message)
        }
    }

    internal suspend fun performAction(step: WorkflowStep, port: MikaScreenPort) = when (step.action) {
        "screenshot" -> {
            val (jpeg, width, height) = port.standaloneScreenshot()
            val requested = (step.raw["file_name"] as? JsonPrimitive)?.content ?: "screenshot.jpg"
            val stem = requested.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9_-]"), "_").take(64).ifEmpty { "screenshot" }
            val name = "$stem.jpg"
            withContext(Dispatchers.IO) {
                images.begin(1)
                images.observe(jpeg)
                val path = images.publish(name).single()
                buildJsonObject {
                    put("action", "screenshot"); put("display_id", displayId); put("package_name", packageName)
                    put("file_name", name); put("width", width); put("height", height)
                    put("image_path", path); put("image_b64", android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP))
                    put("reused_screenshot", false); put("source_screenshot", "mika_window")
                    put("result_screenshot_path", path); put("result_screenshot_paths", buildJsonArray { add(JsonPrimitive(path)) })
                }
            }
        }
        "swipe_with_coords" -> {
            fun coord(key: String) = (step.raw[key] as? JsonPrimitive)?.intOrNull ?: error("滑动缺少坐标 $key")
            val result = port.workflowSwipe(coord("start_x"), coord("start_y"), coord("end_x"), coord("end_y"))
            check(result is com.wangzan101.mika.core.uistate.ActionResult.Ok) { "滑动未完成: $result" }
            buildJsonObject {
                put("action", "swipe_with_coords"); put("display_id", displayId); put("package_name", packageName)
                put("result", "ok"); put("changed", result.changed)
            }
        }
        else -> {
            val runner = requireNotNull(collection) { "不支持的独立界面动作" }
            if (step.type == "gui_task" && step.action !in VirtualWorkflowCollection.actions) {
                val recorder = MikaRunStore(File(activity.filesDir, "mika-runs"))
                    .begin(runId, step.taskDescription.orEmpty(), "Decider", origin = "workflow")
                try {
                    WorkflowDeciderStep.run(runner, port, packageName, appName, step, recorder).also {
                        recorder.close("success", "已将 GUI 步骤交回工作流")
                    }
                } catch (failure: Exception) {
                    val cancelled = failure is kotlinx.coroutines.CancellationException || failure is CollectionDeclined
                    recorder.close(if (cancelled) "cancelled" else "failed", if (cancelled) "工作流已停止" else "GUI 步骤未完成")
                    throw failure
                }
            } else runner.perform(step, port, packageName, appName)
        }
    }

    override suspend fun execute(step: WorkflowStep, goal: String, openOnly: Boolean): StepOutcome {
        runId = ""
        val extract = !openOnly && (step.raw["extract_result"] as? JsonPrimitive)?.booleanOrNull == true
        navigationTaps = if (!openOnly && !extract && followedByGui) navigationTapCount(goal) else null
        val count = (step.raw["extract_result_screenshot_count"] as? JsonPrimitive)?.intOrNull ?: 2
        if (extract && count > WorkflowResultImages.MAX_COUNT) return StepOutcome.Failure("每个 GUI 步骤最多保存 8 张结果截图")
        images.begin(if (extract) count.takeIf { it > 0 } ?: 2 else 0)
        val before = collection?.requests?.get()
        val result = if (collection != null) {
            val resolved = step.copy(taskDescription = goal)
            val opened = if (openOnly || displayId <= 0) MikaPhoneEntry.runLocal(activity, this, resolved, true) else null
            if (openOnly || opened != null && opened.status != "success") requireNotNull(opened)
            else {
                runId = "decider-" + System.nanoTime()
                MikaPhoneEntry.runLocal(activity, this, resolved, false)
            }
        } else {
            MikaPhoneEntry.runCloud(activity, goal, url, model, key,
                visionConfig = vision, workflow = this, openOnly = openOnly, maxSteps = step.maxSteps?.toInt())
        }
        val paths = if (result.status == "success" && extract) {
            try { withContext(Dispatchers.IO) { images.publish() } }
            catch (error: IOException) { return StepOutcome.Failure("无法保存本次工作流截图") }
        } else emptyList()
        return when (result.status) {
            "success" -> StepOutcome.Success(buildJsonObject {
                if (before != null) put("model_requests", requireNotNull(collection).requests.get() - before)
                put("task_description", goal)
                put("app_name", appName)
                put("package_name", packageName)
                put("device", "Android")
                put("display_id", displayId)
                put("mode", if (openOnly) "planner_only" else "decider_only")
                if (openOnly) put("planner_task_description", goal) else {
                    put("response", (result.workflowOutput?.get("response") as? JsonPrimitive)?.content ?: result.message)
                    put("run_id", runId)
                    if (paths.isNotEmpty()) {
                        put("result_screenshot_path", paths.last())
                        put("result_screenshot_paths", buildJsonArray { paths.forEach { add(JsonPrimitive(it)) } })
                        put("result_extraction_screenshot_count", paths.size)
                    } else if (extract) put("result_extraction_error", "当前目标窗口无法提供可核验的结果截图")
                }
            })
            "cancelled" -> StepOutcome.Cancelled(result.message)
            "unverified" -> StepOutcome.Failure("Mika 未核实采集结果。\n" + result.message)
            "blocked", "needs_user" -> StepOutcome.Unsupported(result.message)
            else -> StepOutcome.Failure(result.message)
        }
    }
}

// Only plain, explicit sequences of taps are eligible for an early navigation check.
// The count permits a verifier call; it never certifies the destination by itself.
internal fun navigationTapCount(goal: String): Int? {
    val clauses = goal.trim().trimEnd('。', '.').split(Regex("[，,；;。\\n]+|然后|接着|\\s+then\\s+", RegexOption.IGNORE_CASE))
    val tap = Regex("^(点击|点按|轻点|单击|tap\\s+|click\\s+).+", RegexOption.IGNORE_CASE)
    return clauses.size.takeIf { clauses.isNotEmpty() && clauses.all { tap.matches(it.trim()) } }
}
