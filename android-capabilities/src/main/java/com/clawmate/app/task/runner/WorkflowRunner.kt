package com.clawmate.app.task.runner

import com.clawmate.app.datamanager.DailyLogWorkspace
import com.clawmate.app.task.model.WorkflowDefinition
import com.clawmate.app.task.model.WorkflowStep
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * A bounded, honest workflow runner for the Android Task slice.
 *
 * Executes bounded control flow, summaries and optional host tools. A GUI task uses
 * [WorkflowGuiTask] and a discrete device action uses the configured device adapter.
 * When the corresponding host is unavailable, the step reports a blocked result.
 * A verified extraction or explicit summary is available to the daily-log writer.
 *
 * Bounds: total step budget, per-loop iteration cap, and a wall-clock timeout are
 * enforced; coroutine cancellation surfaces as [RunStatus.CANCELLED].
 */
interface WorkflowRunner {
    suspend fun run(definition: WorkflowDefinition): WorkflowRunResult
}

/**
 * Minimal daily-log write boundary the runner needs (Phase 41). Defaults to a
 * real file-backed writer over the app-private filesDir; tests inject a spy.
 */
fun interface WorkflowDailyLogSink {
    /** Append the real summary [text] for [day] as a numbered daily-log entry.
     * @return the absolute path written (handed back on the run result), or null
     *   when nothing was written (invalid/bounded out). */
    fun append(day: String, fileName: String, text: String, metadata: Map<String, String>): String?
}

/** File-backed [WorkflowDailyLogSink] over the app-private base dir. */
class FileWorkflowDailyLogSink(private val baseDir: File) : WorkflowDailyLogSink {
    override fun append(
        day: String,
        fileName: String,
        text: String,
        metadata: Map<String, String>,
    ): String? = DailyLogWorkspace.appendEntry(
        baseDir = baseDir,
        day = day,
        fileName = fileName,
        entryText = text,
        workflowMetadata = metadata,
    )?.absolutePath
}

fun interface WorkflowGuiTask {
    fun prepareStep(description: String, next: WorkflowStep?, useExperience: Boolean) {}
    suspend fun execute(step: WorkflowStep, goal: String, openOnly: Boolean): StepOutcome
    suspend fun action(step: WorkflowStep): StepOutcome = StepOutcome.Unsupported("当前 GUI 端口未实现独立动作")
}

class WorkflowRunnerImpl(
    private val clock: StepClock = RealStepClock,
    private val maxTotalSteps: Int = 200,
    private val maxLoopIterations: Int = 100,
    private val maxMillis: Long = 60_000L,
    /**
     * Optional device-control boundary. The default [DisabledWorkflowDeviceAdapter]
     * keeps every GUI/HDC step UNSUPPORTED/BLOCKED. A caller may supply a
     * configured bridge-backed adapter so `gui_action` steps return honest
     * typed bridge errors/results; the runner never fabricates device control
     * and only checks `isConfigured` at run time.
     */
    private val deviceAdapter: com.clawmate.app.task.hdc.WorkflowDeviceAdapter =
        com.clawmate.app.task.hdc.DisabledWorkflowDeviceAdapter,
    /**
     * App-private files dir enabling the real file-backed daily-log writer
     * (Phase 41). Null = no daily-log write (runner stays pure/callable without
     * storage). Production wires `context.applicationContext.filesDir`.
     */
    private val baseDir: File? = null,
    /**
     * Overridable daily-log write boundary. Defaults to [FileWorkflowDailyLogSink]
     * bound to [baseDir] when [baseDir] is provided, else a no-op.
     */
    private val dailyLogSink: WorkflowDailyLogSink? = null,
    /** Date provider for the `YYYY-MM-DD` run day (injectable for tests). */
    private val runDateProvider: () -> String = { LazyIsoDate.now() },
    private val modelTool: WorkflowModelTool? = null,
    private val guiTask: WorkflowGuiTask? = null,
) : WorkflowRunner {

    /** Injectable time source so tests avoid real delays. */
    fun interface StepClock {
        suspend fun sleep(millis: Long): Unit
    }

    private class Session(
        val scope: CoroutineScope,
        val context: JsonObject,
        val defaults: JsonObject,
        val openingStep: WorkflowStep?,
        val purpose: String,
        val results: MutableList<StepResult> = mutableListOf(),
        var stepOutputs: MutableMap<String, JsonObject> = mutableMapOf(),
        var stepReceipts: MutableMap<String, StepResult> = mutableMapOf(),
        var stepCount: Int = 0,
        var blocked: RunStatus? = null,
        var blockedMessage: String = "",
        /** Phase 41: real structured summary output exposed by the workflow. */
        var resultSummary: String = "",
        var prefix: String = "",
        var loopIndex: Int = 0,
        var loopCount: Int = 0,
        val pending: MutableList<PendingSummary> = mutableListOf(),
        val summaryEntries: java.util.SortedMap<Int, WorkflowSummaryEntry> = sortedMapOf(),
        var limitExceeded: Boolean = false,
    )

    private data class PendingSummary(
        val id: String,
        val day: String,
        val order: Int,
        val result: Deferred<StepOutcome>,
    )

    private fun Session.lookup(expression: String): JsonElement? {
        val parts = expression.split('.')
        if (parts.size == 3 && parts[0] == "steps") {
            val result = stepReceipts[parts[1]] ?: return null
            when (parts[2]) {
                "status" -> return JsonPrimitive(when (result.status) {
                    RunStatus.SUCCESS -> "success"
                    RunStatus.CANCELLED -> "cancelled"
                    else -> "failed"
                })
                "error" -> return JsonPrimitive(result.detail)
                "type" -> return JsonPrimitive(result.type)
                "id" -> return JsonPrimitive(result.stepId)
            }
        }
        return WorkflowVariableResolver.lookup(stepOutputs, context, defaults, loopIndex, loopIndex > 0 && loopIndex == loopCount,
            expression, loopCount)
    }

    private fun Session.condition(raw: JsonElement?): Boolean = ConditionEvaluator.evaluate(raw) { expression ->
        val parts = expression.split('.')
        if (parts.first() == "steps") require(parts.getOrNull(1) in stepReceipts) { "条件引用的步骤尚未执行: $expression" }
        require(parts.first() in setOf("steps", "context", "defaults", "loop")) { "不支持的条件变量: $expression" }
        lookup(expression) ?: JsonNull
    }

    private suspend fun <T> Session.scoped(path: String, index: Int = loopIndex, count: Int = loopCount, body: suspend () -> T): T {
        val previousReceipts = stepReceipts
        stepReceipts = previousReceipts.toMutableMap()
        val previous = stepOutputs
        val previousPath = prefix
        val previousIndex = loopIndex
        val previousCount = loopCount
        stepOutputs = previous.toMutableMap()
        prefix = path
        loopIndex = index
        loopCount = count
        return try { body() } finally {
            stepReceipts = previousReceipts
            stepOutputs = previous
            prefix = previousPath
            loopIndex = previousIndex
            loopCount = previousCount
        }
    }

    private val sink: WorkflowDailyLogSink =
        dailyLogSink ?: (baseDir?.let { FileWorkflowDailyLogSink(it) } ?: NoOpDailyLogSink)

    override suspend fun run(definition: WorkflowDefinition): WorkflowRunResult = coroutineScope {
        val deadline = if (maxMillis > 0) nowNanos() + maxMillis * 1_000_000L else Long.MAX_VALUE
        try {
            val session = Session(
                scope = this, context = definition.context,
                purpose = definition.metadata?.description.orEmpty().trim().take(300),
                defaults = definition.defaults.toJson(),
                openingStep = definition.steps.firstOrNull()?.takeIf {
                    it.id == "1" && it.type == "gui_task" && definition.steps.drop(1).any(::containsGuiTask)
                },
            )
            executeSteps(session, definition.steps, deadline)
            for (job in session.pending) {
                if (session.blocked == RunStatus.CANCELLED) { job.result.cancel(); continue }
                val outcome = job.result.await()
                val index = session.results.indexOfFirst { it.stepId == job.id }
                if (outcome is StepOutcome.Success) {
                    val summary = stringOf(outcome.output["response"]).orEmpty()
                    appendSummary(session, job.day, summary, job.order)
                    if (index >= 0) session.results[index] = session.results[index].copy(output = outcome.output)
                } else {
                    val reason = (outcome as? StepOutcome.Failure)?.message ?: "视觉摘要未完成"
                    if (index >= 0) session.results[index] = session.results[index].copy(status = RunStatus.FAILED, detail = reason, output = null)
                    block(session, RunStatus.FAILED, "第 ${job.id} 步摘要失败：$reason")
                }
            }
            val status = session.blocked ?: RunStatus.SUCCESS
            val message = when (status) {
                RunStatus.SUCCESS -> {
                    val skipped = session.results.count { it.status != RunStatus.SUCCESS }
                    if (skipped == 0) "工作流已完成" else "工作流已完成，$skipped 个步骤未完成，已按配置继续"
                }
                RunStatus.BLOCKED -> session.blockedMessage
                RunStatus.NOT_CONFIGURED -> session.blockedMessage
                RunStatus.CANCELLED -> session.blockedMessage
                RunStatus.FAILED -> session.blockedMessage
            }
            // Phase 41: only a genuinely successful run with a real structured
            // summary output writes a daily-log entry. Failed / UNSUPPORTED /
            // NOT_CONFIGURED / cancelled runs write nothing (no fabricated log).
            val summary = session.resultSummary.trim()
            val dailyLogPath = if (status == RunStatus.SUCCESS && summary.isNotEmpty()) {
                writeDailyLog(definition, session)
            } else {
                ""
            }
            WorkflowRunResult(
                status = status,
                message = message,
                steps = session.results.toList(),
                resultSummary = summary,
                dailyLogPath = dailyLogPath.orEmpty(),
                summaryEntries = session.summaryEntries.values.toList(),
            )
        } catch (c: CancellationException) {
            // Cooperative cancellation: surface an honest CANCELLED result.
            WorkflowRunResult(status = RunStatus.CANCELLED, message = "Workflow cancelled", steps = emptyList())
        }
    }

    /**
     * Append the real summary output as a numbered daily-log entry through the
     * single [DailyLogWorkspace] writer. Real, bounded metadata (name/description
     * from the workflow definition) preserve provenance so downstream
     * consolidation/enrichment/Home see genuine workflow input. Returns the
     * absolute written path, or null when the writer refused (bounds/IO).
     */
    private fun writeDailyLog(definition: WorkflowDefinition, session: Session): String? {
        val title = definition.title
        val fileName = dailyLogFileName(title)
        val metadata = buildMap {
            definition.metadata?.name?.takeIf { it.isNotBlank() }?.let { put("name", it) }
            definition.metadata?.description?.takeIf { it.isNotBlank() }?.let { put("description", it) }
            // scene id is not carried by WorkflowDefinition; keep honest real fields only.
        }
        return session.summaryEntries.values.mapNotNull { entry -> sink.append(entry.day, fileName, entry.text, metadata) }.lastOrNull()
    }

    private suspend fun executeSteps(session: Session, steps: List<WorkflowStep>, deadline: Long) {
        for ((index, step) in steps.withIndex()) {
            if (session.blocked != null) break
            if (step.type == "gui_task") {
                val useExperience = boolOf(step.raw["use_experience"]) ?: boolOf(session.defaults["use_experience"]) ?: true
                guiTask?.prepareStep(session.purpose, steps.getOrNull(index + 1), useExperience)
            }
            executeStep(session, step, deadline)
        }
    }

    private suspend fun executeStep(
        session: Session,
        step: WorkflowStep,
        deadlineNanos: Long,
    ) {
        if (session.blocked != null) return
        if (nowNanos() >= deadlineNanos) {
            session.limitExceeded = true
            block(session, RunStatus.FAILED, "工作流超过执行时限")
            session.results += StepResult(session.prefix + step.id, step.type, RunStatus.FAILED, "timeout")
            return
        }
        if (!ensureBudget(session, step, "top-level step")) return
        val outcome = try { execute(session, step, deadlineNanos) }
            catch (e: IllegalArgumentException) { StepOutcome.Failure(e.message ?: "工作流参数无效") }
        if (session.blocked == null) {
            applyOutcome(session, step, outcome)
        } else if (step.type == "if" || step.type == "loop") {
            session.results += StepResult(session.prefix + step.id, step.type, requireNotNull(session.blocked), step.escapedName, session.blockedMessage)
        }
        session.results.lastOrNull { it.stepId == session.prefix + step.id }?.let { session.stepReceipts[step.id] = it }
        if (step.onError == "continue" && !session.limitExceeded && session.blocked != RunStatus.CANCELLED) {
            session.blocked = null
            session.blockedMessage = ""
        }
    }

    private suspend fun execute(session: Session, step: WorkflowStep, deadlineNanos: Long): StepOutcome {
        if (step.type in setOf("gui_action", "gui_task", "android_accessibility") && step.action in
            setOf("collection_open_app", "collection_navigate", "collection_read_pages", "collection_model_read", "collection_preview")) {
            val raw = step.raw.mapValues { (_, value) ->
                if (value is JsonPrimitive && value.isString) JsonPrimitive(WorkflowVariableResolver.resolve(value.content) { session.lookup(it) }) else value
            }.toMutableMap()
            session.context["contact_name"]?.let { raw["contact_name"] = it }
            val result = guiTask?.action(step.copy(raw = JsonObject(raw))) ?: StepOutcome.Unsupported("此采集需要虚拟屏")
            if (result is StepOutcome.Success) {
                stringOf(result.output["saved_summary"])?.takeIf { it.isNotBlank() }?.let { replaceSummary(session, it) }
            }
            return result
        }
        return when (step.type) {
            "gui_task" -> executeGuiTask(session, step, deadlineNanos)
            "gui_action" -> executeGuiAction(session, step, deadlineNanos)
            "tool" -> executeTool(session, step, deadlineNanos)
            "loop" -> executeLoop(session, step, deadlineNanos)
            "if" -> executeIf(session, step, deadlineNanos)
            else -> StepOutcome.Unsupported("unknown step type '${step.type}' cannot be executed by this bounded runner.")
        }
    }

    private fun containsGuiTask(step: WorkflowStep): Boolean =
        step.type == "gui_task" || (step.steps + step.thenSteps + step.elseSteps).any(::containsGuiTask)

    private suspend fun executeGuiTask(session: Session, step: WorkflowStep, deadline: Long): StepOutcome {
        val port = guiTask ?: return StepOutcome.Unsupported("此运行入口未连接 Mika 虚拟屏")
        val goal = WorkflowVariableResolver.resolve(step.taskDescription.orEmpty()) { expression ->
            session.lookup(expression)
        }.trim()
        if (goal.length !in 1..1000 || goal.contains("\${")) return StepOutcome.Failure("GUI 任务描述为空、过长或含未解析变量")
        if (step.maxSteps != null && step.maxSteps !in 1..Int.MAX_VALUE.toLong()) return StepOutcome.Failure("max_steps 必须为正整数")
        val openOnly = step === session.openingStep
        val captureDay = runDateProvider()
        val result = try {
            withTimeout(((deadline - nowNanos()) / 1_000_000L).coerceAtLeast(1)) { port.execute(step, goal, openOnly) }
        } catch (timeout: TimeoutCancellationException) { return StepOutcome.Failure("GUI 工作流已超时") }
        if (!openOnly && result is StepOutcome.Success && boolOf(step.raw["extract_result"]) == true) {
            val answer = stringOf(result.output["response"]).orEmpty().trim()
            if (answer.isEmpty()) return StepOutcome.Failure("Mika 未返回采集结果")
            replaceSummary(session, answer, captureDay)
        }
        return result
    }

    private suspend fun executeGuiAction(session: Session, step: WorkflowStep, deadlineNanos: Long): StepOutcome {
        val action = step.action ?: return StepOutcome.Unsupported("gui_action without an action field.")
        return when (action) {
            // `sleep` is a deterministic non-GUI op: bounded real delay.
            "sleep" -> {
                val millis = (step.seconds ?: 1L).coerceAtLeast(0L) * 1000L
                if (awaitDeadlineSleep(millis, deadlineNanos)) StepOutcome.Success(JsonObject(emptyMap()))
                else { session.limitExceeded = true; StepOutcome.Failure("等待步骤超出工作流时限") }
            }
            "screenshot", "swipe_with_coords" -> if (guiTask == null) executeConfiguredGui(step, action) else {
                val resolved = step.raw.mapValues { (_, value) ->
                    if (value is JsonPrimitive && value.isString) JsonPrimitive(WorkflowVariableResolver.resolve(value.content) { session.lookup(it) })
                    else value
                }
                try {
                    withTimeout(((deadlineNanos - nowNanos()) / 1_000_000L).coerceAtLeast(1)) {
                        guiTask.action(step.copy(raw = JsonObject(resolved)))
                    }
                } catch (timeout: TimeoutCancellationException) {
                    StepOutcome.Failure("GUI 工作流已超时")
                }
            }
            else -> executeConfiguredGui(step, action)
        }
    }

    /**
     * Routes a device-control gui_action through [deviceAdapter] — but only when
     * the adapter is actually configured (health-verified). With the default
     * disabled adapter this remains UNSUPPORTED/BLOCKED; a configured
     * bridge-backed adapter returns honest bridge errors/results. Never executes
     * a command locally and never fabricates success.
     */
    private suspend fun executeConfiguredGui(step: WorkflowStep, action: String): StepOutcome {
        if (!deviceAdapter.isConfigured) {
            return StepOutcome.Unsupported(
                "gui_action '$action' is a device-control step that requires a configured PC HDC bridge adapter; none is configured on Android."
            )
        }
        val payload = step.raw.filterKeys { it != "id" && it != "type" && it != "action" }
        val factor = ((step.raw["factor"] as? JsonPrimitive)?.floatOrNull)?.toDouble()
            ?: step.raw["factor"]?.toString()?.toDoubleOrNull()
        val result = try {
            when (action) {
                "screenshot" -> deviceAdapter.screenshot(factor ?: 0.5)
                "app_start" -> deviceAdapter.startApp(
                    appName = stringOf(step.raw["app_name"]),
                    packageName = stringOf(step.raw["package_name"]),
                    resetFirst = boolOf(step.raw["reset_first"]) ?: true,
                )
                else -> deviceAdapter.guiAction(payload)
            }
        } catch (t: kotlinx.coroutines.CancellationException) {
            throw t
        } catch (t: Throwable) {
            return StepOutcome.Failure("gui_action '$action' failed locally: ${t.message}")
        }
        return when (result) {
            is com.clawmate.app.task.hdc.HdcActionResult.Success -> {
                StepOutcome.Success(bridgeOutput(result.response))
            }
            is com.clawmate.app.task.hdc.HdcActionResult.BridgeError -> {
                val reason = result.response.message?.takeIf { it.isNotBlank() }
                    ?: "bridge reported ${result.response.status}"
                StepOutcome.Failure("gui_action '$action' bridge error: $reason")
            }
            is com.clawmate.app.task.hdc.HdcActionResult.TransportError -> {
                StepOutcome.Failure("gui_action '$action' transport ${result.kind.name.lowercase()}: ${result.message}")
            }
            is com.clawmate.app.task.hdc.HdcActionResult.Unsupported -> StepOutcome.Unsupported(result.reason)
        }
    }

    private fun stringOf(element: JsonElement?): String? = (element as? JsonPrimitive)?.contentOrNull

    private fun boolOf(element: JsonElement?): Boolean? {
        val prim = element as? JsonPrimitive ?: return null
        val b = prim.booleanOrNull
        if (b != null) return b
        return when (prim.contentOrNull?.trim()?.lowercase()) {
            "true", "1" -> true
            "false", "0" -> false
            else -> null
        }
    }

    private fun bridgeOutput(response: com.clawmate.app.task.hdc.HdcBridgeResponse): JsonObject =
        buildJsonObject {
            put("status", response.status)
            response.message?.let { put("message", it) }
            response.result?.let { put("result", it) }
            response.imageB64?.let { put("image_b64", it) }
            response.action?.let { put("action", it) }
            response.packageName?.let { put("package_name", it) }
            response.currentPackageName?.let { put("current_package_name", it) }
            response.width?.let { put("width", JsonPrimitive(it)) }
            response.height?.let { put("height", JsonPrimitive(it)) }
        }

    private suspend fun executeTool(session: Session, step: WorkflowStep, deadlineNanos: Long): StepOutcome {
        val toolName = step.toolName ?: return StepOutcome.Unsupported("tool step without tool_name.")
        return when (toolName) {
            // Deterministic no-op used to exercise the runner in tests.
            "echo" -> StepOutcome.Success(buildJsonObject { put("echo", step.raw["inputs"]?.toString() ?: "") })
            // Phase 41: a workflow may expose a real structured summary output step.
            // The text is resolved from the workflow's own inputs/context (real
            // content, resolved over real context/step outputs) — never fabricated
            // and never inferred from bare `echo`. A blank/missing text keeps the
            // summary empty so nothing is written.
            "summary" -> executeSummaryTool(session, step)
            "vlm_qa" -> executeModelTool(session, step, deadlineNanos)
            else -> StepOutcome.NotConfigured(
                "当前版本未提供工具 $toolName，请检查任务配置"
            )
        }
    }

    private suspend fun executeModelTool(session: Session, step: WorkflowStep, deadline: Long): StepOutcome {
        val port = modelTool ?: return StepOutcome.NotConfigured("vlm_qa未配置；请使用共享版并在发送前确认视觉模型及图片")
        return try {
            val inputs = step.raw["inputs"] as? JsonObject ?: error("vlm_qa需要inputs")
            require(inputs.keys.all { it in setOf("mode", "question", "image", "json_schema", "blocking", "time_context") }) { "vlm_qa包含不支持的输入字段" }
            fun text(key: String, fallback: String = ""): String {
                val raw = inputs[key] ?: return fallback
                require(raw is JsonPrimitive && raw.isString) { "vlm_qa的${key}必须是文本" }
                val value = WorkflowVariableResolver.resolve(raw.content) { expression ->
                    session.lookup(expression)
                }.trim()
                require(!value.contains("\u0024{")) { "vlm_qa的${key}存在未解析变量" }
                return value
            }
            val mode = text("mode", "answer")
            val question = text("question")
            val imageExpression = (inputs["image"] as? JsonPrimitive)?.content.orEmpty()
            val screenshot = Regex("""^\$\{steps\.([^.{}]+)\.output\.image_b64\}$""").matchEntire(imageExpression)
                ?.groupValues?.get(1)?.let(session.stepOutputs::get)
            val image = (screenshot?.get("image_path") as? JsonPrimitive)?.content ?: text("image")
            require(mode in setOf("answer", "summary")) { "vlm_qa仅支持answer和summary" }
            require(question.length in 1..8000 && image.length in 1..2000) { "vlm_qa需要有界question及本机图片引用" }
            val schema = inputs["json_schema"]?.let { require(it is JsonObject && it.toString().length <= 8000) { "json_schema必须是有界JSON对象" }; it }
            val day = runDateProvider()
            val timeContext = text("time_context")
            require(timeContext.isEmpty() || timeContext == "capture_only" && mode == "summary") { "不支持的摘要时间上下文" }
            val request = WorkflowModelRequest("", mode, question, image, schema, day, timeContext == "capture_only")
            if (mode == "summary" && schema == null && boolOf(inputs["blocking"]) != true) {
                val deferred = session.scope.async(start = CoroutineStart.UNDISPATCHED) { evaluateModel(port, request, deadline) }
                session.pending += PendingSummary(session.prefix + step.id, day, session.stepCount, deferred)
                return StepOutcome.Success(buildJsonObject {
                    put("tool_name", "vlm_qa"); put("mode", mode); put("deferred", true); put("response", "summary_deferred")
                })
            }
            val outcome = evaluateModel(port, request, deadline)
            if (mode == "summary" && outcome is StepOutcome.Success &&
                (!request.captureOnly || (outcome.output["structured_output"] as? JsonObject)?.get("contains_today_chat") != JsonPrimitive(false))) {
                appendSummary(session, day, stringOf(outcome.output["response"]).orEmpty())
            }
            outcome
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (error: Exception) { StepOutcome.Failure(error.message ?: "视觉模型参数无效") }
    }

    private suspend fun evaluateModel(port: WorkflowModelTool, request: WorkflowModelRequest, deadline: Long): StepOutcome {
        return try {
            val remaining = ((deadline - nowNanos()) / 1_000_000L).coerceAtMost(60_000)
            require(remaining > 0) { "工作流已超时" }
            val response = withTimeout(remaining) { port.evaluate(request) }.trim()
            require(response.length in 1..16000) { "模型返回为空或超出限制" }
            val structured = request.schema?.let { workflowStructuredOutput(response, it) }
            val text = if (request.mode == "summary") stringOf(structured?.get("summary")) ?: response else response
            StepOutcome.Success(buildJsonObject {
                put("tool_name", "vlm_qa"); put("mode", request.mode); put("question", request.question); put("response", text)
                if (request.mode == "summary") {
                    put("capture_day", request.captureDay)
                    put("image", request.image)
                }
                structured?.let { put("structured_output", it) }
            })
        } catch (timeout: TimeoutCancellationException) { StepOutcome.Failure("视觉问答超过工作流时间上限") }
          catch (cancelled: CancellationException) { throw cancelled }
          catch (error: Exception) { StepOutcome.Failure(error.message ?: "视觉模型调用失败") }
    }

    /** Resolve the real summary text of an explicit `summary` tool step. */
    private fun executeSummaryTool(session: Session, step: WorkflowStep): StepOutcome {
        val inputs = step.raw["inputs"] as? JsonObject ?: JsonObject(emptyMap())
        val template = stringOf(inputs["text"])
            ?: stringOf(inputs["summary"])
            ?: stringOf(inputs["value"])
            ?: return StepOutcome.Success(buildJsonObject { put("summary", "") })
        val resolved = WorkflowVariableResolver.resolve(template) { session.lookup(it) }
        // Real output only: reject blanks. Bound to the daily-log entry cap.
        val bounded = resolved.trim().take(com.clawmate.app.datamanager.DailyLogBounds.MAX_ENTRY_CHARACTERS)
        if (bounded.isEmpty()) {
            return StepOutcome.Success(buildJsonObject { put("summary", "") })
        }
        replaceSummary(session, bounded)
        return StepOutcome.Success(buildJsonObject { put("summary", bounded) })
    }

    private fun replaceSummary(session: Session, text: String, day: String = runDateProvider()) {
        session.summaryEntries.clear()
        session.resultSummary = ""
        appendSummary(session, day, text)
    }

    private fun appendSummary(session: Session, day: String, text: String, order: Int = session.stepCount) {
        if (session.resultSummary.length + text.length + 1 > com.clawmate.app.datamanager.DailyLogBounds.MAX_DOCUMENT_CHARACTERS) {
            session.limitExceeded = true
            block(session, RunStatus.FAILED, "工作流摘要超出保存上限，请减少单次采集量")
            return
        }
        session.summaryEntries[order] = WorkflowSummaryEntry(day, text)
        session.resultSummary = session.summaryEntries.values.joinToString("\n") { it.text }
    }

    private suspend fun executeLoop(
        session: Session,
        step: WorkflowStep,
        deadlineNanos: Long,
    ): StepOutcome {
        val rawTimes = step.raw["max_times"] ?: step.raw["times"] ?: step.times?.let(::JsonPrimitive)
        val times = rawTimes?.let { WorkflowVariableResolver.resolve(it.jsonText()) { expr -> session.lookup(expr) }.toDoubleOrNull() } ?: 0.0
        require(times.isFinite() && times <= maxLoopIterations) { "loop times=$times exceeds per-loop bound of $maxLoopIterations" }
        val limit = kotlin.math.ceil(times).toInt().coerceAtLeast(0)
        val iterations = mutableListOf<JsonObject>()
        var broke = false
        for (i in 1..limit) {
            val path = "${session.prefix}${step.id}.iter$i"
            broke = session.scoped("$path.", i, limit) {
                executeSteps(session, step.steps, deadlineNanos)
                session.blocked == null && session.condition(step.raw["break_if"])
            }
            if (session.blocked != null) break
            iterations += buildJsonObject { put("iteration", i); put("prefix", path); put("break_triggered", broke) }
            if (broke) break
        }
        return StepOutcome.Success(buildJsonObject {
            put("times", iterations.size); put("max_times", limit); put("broke_early", broke)
            put("break_iteration", if (broke) iterations.size else 0); put("iterations", JsonArray(iterations))
        })
    }

    private suspend fun executeIf(session: Session, step: WorkflowStep, deadlineNanos: Long): StepOutcome {
        val condition = step.raw["condition"] ?: step.condition?.let {
            buildJsonObject { it.left?.let { v -> put("left", v) }; put("operator", it.operator); it.right?.let { v -> put("right", v) } }
        }
        val matched = session.condition(condition)
        val branch = if (matched) "then" else "else"
        val children = if (matched) step.thenSteps else step.elseSteps
        session.scoped("${session.prefix}${step.id}.$branch.") {
            executeSteps(session, children, deadlineNanos)
        }
        return StepOutcome.Success(buildJsonObject {
            put("matched", matched); put("branch", branch); put("executed_step_count", children.size)
        })
    }

    private suspend fun awaitDeadlineSleep(millis: Long, deadlineNanos: Long): Boolean {
        val remaining = deadlineNanos - nowNanos()
        val effective = if (remaining <= 0) 0L else minOf(millis, remaining / 1_000_000L)
        if (effective > 0) clock.sleep(effective)
        return effective == millis && nowNanos() < deadlineNanos
    }

    private fun applyOutcome(session: Session, step: WorkflowStep, outcome: StepOutcome) {
        when (outcome) {
            is StepOutcome.Success -> {
                session.stepOutputs[step.id] = outcome.output
                session.results += StepResult(session.prefix + step.id, step.type, RunStatus.SUCCESS, step.escapedName, output = when {
                    step.type in setOf("if", "loop", "gui_task") || step.toolName == "vlm_qa" ||
                        step.action in setOf("screenshot", "swipe_with_coords") || outcome.output.containsKey("model_requests") ->
                        JsonObject(outcome.output - "image_b64" - "saved_summary")
                    else -> null
                })
            }
            is StepOutcome.Unsupported -> {
                block(session, RunStatus.BLOCKED, "第 ${step.id} 步不受支持：${outcome.reason}")
                session.results += StepResult(session.prefix + step.id, step.type, RunStatus.BLOCKED, "unsupported", outcome.reason)
            }
            is StepOutcome.NotConfigured -> {
                block(session, RunStatus.NOT_CONFIGURED, "第 ${step.id} 步未配置：${outcome.reason}")
                session.results += StepResult(session.prefix + step.id, step.type, RunStatus.NOT_CONFIGURED, "not configured", outcome.reason)
            }
            is StepOutcome.Cancelled -> {
                block(session, RunStatus.CANCELLED, outcome.reason)
                session.results += StepResult(session.prefix + step.id, step.type, RunStatus.CANCELLED, "cancelled", outcome.reason)
            }
            is StepOutcome.Failure -> {
                block(session, RunStatus.FAILED, "第 ${step.id} 步失败：${outcome.message}")
                session.results += StepResult(session.prefix + step.id, step.type, RunStatus.FAILED, "failed", outcome.message)
            }
        }
    }

    private fun block(session: Session, status: RunStatus, message: String) {
        if (session.blocked == null) {
            session.blocked = status
            session.blockedMessage = message
        }
    }

    /** Returns false (and blocks) when the global budget/deadline is exceeded. */
    private fun ensureBudget(session: Session, step: WorkflowStep, label: String): Boolean {
        session.stepCount++
        if (session.stepCount > maxTotalSteps) {
            session.limitExceeded = true
            block(session, RunStatus.FAILED, "max total-step budget of $maxTotalSteps exceeded ($label)")
            session.results += StepResult(session.prefix + step.id, step.type, RunStatus.FAILED, "max step budget", detail = label)
            return false
        }
        return true
    }

    private fun nowNanos(): Long = System.nanoTime()

    private fun com.clawmate.app.task.model.WorkflowDefaults?.toJson(): JsonObject = buildJsonObject {
        this@toJson?.device?.let { put("device", JsonPrimitive(it)) }
        this@toJson?.useQwen3?.let { put("use_qwen3", JsonPrimitive(it)) }
        this@toJson?.useE2e?.let { put("use_e2e", JsonPrimitive(it)) }
        this@toJson?.useExperience?.let { put("use_experience", JsonPrimitive(it)) }
        this@toJson?.acceptPlannerChanges?.let { put("accept_planner_changes", JsonPrimitive(it)) }
    }

    /**
     * Deterministic, repeat-run-friendly daily-log file name (mirrors the Harmony
     * `buildDailyLogFileName` shape but without a GUI package). A stable name means
     * repeat runs of the same workflow on the same day **append** (via the
     * numbered-entry index) instead of creating unbounded new files.
     */
    private fun dailyLogFileName(title: String): String {
        val safe = sanitizeFileName(title.ifBlank { "workflow" })
        return "$safe.md"
    }

    private fun sanitizeFileName(value: String): String =
        value.replace("[^A-Za-z0-9._-]".toRegex(), "_").take(48).ifBlank { "workflow" }
}


/** Default clock that really sleeps for the given millis. */
object RealStepClock : WorkflowRunnerImpl.StepClock {
    override suspend fun sleep(millis: Long): Unit {
        if (millis > 0) delay(millis)
    }
}

/** No-op daily-log sink used when no baseDir is wired (pure runner). */
internal object NoOpDailyLogSink : WorkflowDailyLogSink {
    override fun append(day: String, fileName: String, text: String, metadata: Map<String, String>): String? = null
}

/** Lazy ISO `YYYY-MM-DD` supplier (kept in a tiny object so tests can override the provider). */
internal object LazyIsoDate {
    fun now(): String = DateTimeFormatter.ISO_LOCAL_DATE.format(LocalDate.now())
}

private fun JsonElement.jsonText(): String = (this as? JsonPrimitive)?.content ?: toString()

/** Mirrors WorkflowRunner.ets evaluateCondition and normalizeConditionValue. */
internal object ConditionEvaluator {
    fun evaluate(condition: JsonElement?, lookup: (String) -> JsonElement?): Boolean {
        if (condition == null || condition == JsonNull) return false
        fun resolve(value: JsonElement?): JsonElement? {
            if (value !is JsonPrimitive || !value.isString) return value
            val text = WorkflowVariableResolver.resolve(value.content) { expr ->
                lookup(expr) ?: throw IllegalArgumentException("条件变量未找到: $expr")
            }
            return JsonPrimitive(text)
        }
        if (condition is JsonPrimitive) return resolve(condition)?.jsonText()?.trim()?.lowercase() !in
            setOf(null, "", "0", "false", "no", "null")
        require(condition is JsonObject) { "条件格式无效" }
        val rawLeft = resolve(condition["left"])
        val rawRight = resolve(condition["right"])
        val left = normalize(rawLeft, rawRight)
        val right = normalize(rawRight, left)
        val l = left?.jsonText() ?: "null"
        val r = right?.jsonText() ?: "null"
        val ln = l.toDoubleOrNull() ?: 0.0
        val rn = r.toDoubleOrNull() ?: 0.0
        val op = condition["operator"]?.jsonText().orEmpty().ifEmpty { "==" }
        val equal = if (left is JsonPrimitive && right is JsonPrimitive && !left.isString && !right.isString &&
            l.toDoubleOrNull() != null && r.toDoubleOrNull() != null) ln == rn else left == right
        return when (op) {
            "==" -> equal
            "!=" -> !equal
            "contains" -> r in l
            "not_contains" -> r !in l
            ">" -> ln > rn
            "<" -> ln < rn
            ">=" -> ln >= rn
            "<=" -> ln <= rn
            else -> throw IllegalArgumentException("不支持的条件运算符: $op")
        }
    }

    private fun normalize(value: JsonElement?, peer: JsonElement?): JsonElement? {
        if (value !is JsonPrimitive || peer !is JsonPrimitive || peer.isString) return value
        if (peer.booleanOrNull != null) return when (value.content.trim().lowercase()) {
            "true", "yes", "y", "1" -> JsonPrimitive(true)
            "false", "no", "n", "0" -> JsonPrimitive(false)
            else -> value
        }
        if (peer.content.toDoubleOrNull() != null && value.isString) {
            return value.content.toLongOrNull()?.let(::JsonPrimitive)
                ?: value.content.toDoubleOrNull()?.takeIf { it.isFinite() }?.let(::JsonPrimitive) ?: value
        }
        return value
    }
}
