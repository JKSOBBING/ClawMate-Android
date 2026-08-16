package com.clawmate.app.task.runner

import com.clawmate.app.task.model.WorkflowDefinition
import com.clawmate.app.task.model.WorkflowStep
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A bounded, honest workflow runner for the Android Task slice.
 *
 * Only deterministic, non-GUI step types execute for real:
 *   - `sleep` -> real bounded delay (via an injectable [WorkflowRunnerImpl.StepClock]
 *                so unit tests do not wait);
 *   - `loop`  -> bounded iteration over nested steps with `loop.index`/`loop.last`;
 *   - `if`    -> deterministic condition evaluation driving then/else branches;
 *   - `tool`  -> only a deterministic `echo` no-op; any VLM/model tool is
 *                NOT_CONFIGURED.
 *
 * Every GUI / HDC / open-app / device-control step (`gui_task`, and `gui_action`
 * screenshot / swipe / click) is *never* executed and *never* fabricated: it is
 * reported as [StepOutcome.Unsupported] with a clear reason, and the overall run
 * becomes [RunStatus.BLOCKED]. A VLM `tool` step becomes [RunStatus.NOT_CONFIGURED].
 * The run therefore stays honest when blocked.
 *
 * Bounds: total step budget, per-loop iteration cap, and a wall-clock timeout are
 * enforced; coroutine cancellation surfaces as [RunStatus.CANCELLED].
 */
interface WorkflowRunner {
    suspend fun run(definition: WorkflowDefinition): WorkflowRunResult
}

class WorkflowRunnerImpl(
    private val clock: StepClock = RealStepClock,
    private val maxTotalSteps: Int = 200,
    private val maxLoopIterations: Int = 100,
    private val maxMillis: Long = 60_000L,
) : WorkflowRunner {

    /** Injectable time source so tests avoid real delays. */
    fun interface StepClock {
        suspend fun sleep(millis: Long): Unit
    }

    private class Session(
        val context: JsonObject,
        val defaults: JsonObject,
        val results: MutableList<StepResult> = mutableListOf(),
        val stepOutputs: MutableMap<String, JsonObject> = mutableMapOf(),
        var stepCount: Int = 0,
        var blocked: RunStatus? = null,
        var blockedMessage: String = "",
    )

    override suspend fun run(definition: WorkflowDefinition): WorkflowRunResult = coroutineScope {
        val deadline = if (maxMillis > 0) nowNanos() + maxMillis * 1_000_000L else Long.MAX_VALUE
        try {
            val session = Session(
                context = definition.context,
                defaults = definition.defaults.toJson(),
            )
            for (step in definition.steps) {
                if (session.blocked == null) executeStep(session, step, deadline)
            }
            val status = session.blocked ?: RunStatus.SUCCESS
            val message = when (status) {
                RunStatus.SUCCESS -> "Workflow completed successfully"
                RunStatus.BLOCKED -> session.blockedMessage
                RunStatus.NOT_CONFIGURED -> session.blockedMessage
                RunStatus.CANCELLED -> "Workflow cancelled"
                RunStatus.FAILED -> session.blockedMessage
            }
            WorkflowRunResult(status = status, message = message, steps = session.results.toList())
        } catch (c: CancellationException) {
            // Cooperative cancellation: surface an honest CANCELLED result.
            WorkflowRunResult(status = RunStatus.CANCELLED, message = "Workflow cancelled", steps = emptyList())
        }
    }

    private suspend fun executeStep(
        session: Session,
        step: WorkflowStep,
        deadlineNanos: Long,
    ) {
        if (nowNanos() >= deadlineNanos) {
            block(session, RunStatus.FAILED, "max-seconds timeout exceeded")
            session.results += StepResult(step.id, step.type, RunStatus.FAILED, "timeout")
            return
        }
        if (!ensureBudget(session, step, "top-level step")) return
        val outcome = execute(session, step, deadlineNanos)
        applyOutcome(session, step, outcome)
    }

    private suspend fun execute(session: Session, step: WorkflowStep, deadlineNanos: Long): StepOutcome {
        return when (step.type) {
            "gui_task" -> StepOutcome.Unsupported(
                "GUI task automation (${step.taskDescription ?: step.name ?: ""}) requires a screenshot-driven decider and HDC/accessibility device control, which is unavailable on Android."
            )
            "gui_action" -> executeGuiAction(step, deadlineNanos)
            "tool" -> executeTool(step)
            "loop" -> executeLoop(session, step, deadlineNanos)
            "if" -> executeIf(session, step, deadlineNanos)
            else -> StepOutcome.Unsupported("unknown step type '${step.type}' cannot be executed by this bounded runner.")
        }
    }

    private suspend fun executeGuiAction(step: WorkflowStep, deadlineNanos: Long): StepOutcome {
        val action = step.action ?: return StepOutcome.Unsupported("gui_action without an action field.")
        return when (action) {
            // `sleep` is a deterministic non-GUI op: bounded real delay.
            "sleep" -> {
                val millis = (step.seconds ?: 1L).coerceAtLeast(0L) * 1000L
                awaitDeadlineSleep(millis, deadlineNanos)
                StepOutcome.Success(JsonObject(emptyMap()))
            }
            else -> StepOutcome.Unsupported(
                "gui_action '$action' is a device-control step (screenshot/swipe/click/open-app) that requires HDC/accessibility control unavailable on Android."
            )
        }
    }

    private suspend fun executeTool(step: WorkflowStep): StepOutcome {
        val toolName = step.toolName ?: return StepOutcome.Unsupported("tool step without tool_name.")
        return when (toolName) {
            // Deterministic no-op used to exercise the runner in tests.
            "echo" -> StepOutcome.Success(buildJsonObject { put("echo", step.raw["inputs"]?.toString() ?: "") })
            else -> StepOutcome.NotConfigured(
                "tool '$toolName' requires a cloud VLM/model that is not configured on this device; GUI instrumentation is unavailable."
            )
        }
    }

    private suspend fun executeLoop(
        session: Session,
        step: WorkflowStep,
        deadlineNanos: Long,
    ): StepOutcome {
        val times = step.times ?: 1
        if (times <= 0) return StepOutcome.Container("loop skipped (times<=0)")
        if (times > maxLoopIterations.toLong()) {
            return StepOutcome.Failure("loop times=$times exceeds per-loop bound of $maxLoopIterations")
        }
        for (i in 0 until times.toInt()) {
            if (session.blocked != null) return StepOutcome.Container("stopped")
            val loopLast = (i == times.toInt() - 1)
            for (sub in step.steps) {
                if (session.blocked != null) return StepOutcome.Container("stopped")
                if (!ensureBudget(session, sub, "loop step")) return StepOutcome.Container("budget")
                val out = execute(session, sub, deadlineNanos)
                when (out) {
                    is StepOutcome.Success -> {
                        val resolved = out.output.resolve(session, loopIndex = i + 1, loopLast = loopLast)
                        session.stepOutputs["${step.id}.${i + 1}"] = resolved
                        session.results += StepResult(sub.id, sub.type, RunStatus.SUCCESS, sub.escapedName)
                    }
                    is StepOutcome.Container -> { /* walked itself */ }
                    else -> {
                        applyOutcome(session, sub, out)
                        return StepOutcome.Container("stopped on sub-step")
                    }
                }
            }
        }
        return StepOutcome.Container("loop completed $times iterations")
    }

    private suspend fun executeIf(
        session: Session,
        step: WorkflowStep,
        deadlineNanos: Long,
    ): StepOutcome {
        val cond = step.condition ?: return StepOutcome.Container("if without condition")
        val ok = try {
            ConditionEvaluator.evaluate(cond.left, cond.operator, cond.right, session.context, session.defaults)
        } catch (t: Throwable) {
            return StepOutcome.Failure("if condition error: ${t.message}")
        }
        val branch = if (ok) step.thenSteps else step.elseSteps
        for (sub in branch) {
            if (session.blocked != null) return StepOutcome.Container("stopped")
            if (!ensureBudget(session, sub, "if branch step")) return StepOutcome.Container("budget")
            val out = execute(session, sub, deadlineNanos)
            when (out) {
                is StepOutcome.Success -> {
                    session.stepOutputs[sub.id] = out.output.resolve(session, 0, false)
                    session.results += StepResult(sub.id, sub.type, RunStatus.SUCCESS, sub.escapedName)
                }
                is StepOutcome.Container -> { }
                else -> {
                    applyOutcome(session, sub, out)
                    return StepOutcome.Container("stopped on sub-step")
                }
            }
        }
        return StepOutcome.Container("if branch: ${if (ok) "then" else "else"}")
    }

    private suspend fun awaitDeadlineSleep(millis: Long, deadlineNanos: Long) {
        val remaining = deadlineNanos - nowNanos()
        val effective = if (remaining <= 0) 0L else minOf(millis, remaining / 1_000_000L)
        if (effective > 0) clock.sleep(effective)
        // Leaving actual wall-clock timeout enforcement to the top-level deadline check.
    }

    private fun applyOutcome(session: Session, step: WorkflowStep, outcome: StepOutcome) {
        when (outcome) {
            is StepOutcome.Success -> {
                session.stepOutputs[step.id] = outcome.output
                session.results += StepResult(step.id, step.type, RunStatus.SUCCESS, step.escapedName)
            }
            is StepOutcome.Container -> { /* containers report via their internal branch results */ }
            is StepOutcome.Unsupported -> {
                block(session, RunStatus.BLOCKED, "step ${step.id} unsupported: ${outcome.reason}")
                session.results += StepResult(step.id, step.type, RunStatus.BLOCKED, "unsupported", outcome.reason)
            }
            is StepOutcome.NotConfigured -> {
                block(session, RunStatus.NOT_CONFIGURED, "step ${step.id} not configured: ${outcome.reason}")
                session.results += StepResult(step.id, step.type, RunStatus.NOT_CONFIGURED, "not configured", outcome.reason)
            }
            is StepOutcome.Failure -> {
                block(session, RunStatus.FAILED, "step ${step.id} failed: ${outcome.message}")
                session.results += StepResult(step.id, step.type, RunStatus.FAILED, "failed", outcome.message)
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
            block(session, RunStatus.FAILED, "max total-step budget of $maxTotalSteps exceeded ($label)")
            session.results += StepResult(step.id, step.type, RunStatus.FAILED, "max step budget", detail = label)
            return false
        }
        return true
    }

    private fun nowNanos(): Long = System.nanoTime()

    private fun JsonObject.resolve(session: Session, loopIndex: Int, loopLast: Boolean): JsonObject =
        buildJsonObject {
            for ((k, v) in this@resolve.entries) {
                val resolved = WorkflowVariableResolver.resolve(v.toString()) { expr ->
                    WorkflowVariableResolver.lookup(
                        session.stepOutputs, session.context, session.defaults, loopIndex, loopLast, expr
                    )
                }
                put(k, resolved)
            }
        }

    private fun com.clawmate.app.task.model.WorkflowDefaults?.toJson(): JsonObject = buildJsonObject {
        this@toJson?.device?.let { put("device", JsonPrimitive(it)) }
        this@toJson?.useQwen3?.let { put("use_qwen3", JsonPrimitive(it)) }
        this@toJson?.useE2e?.let { put("use_e2e", JsonPrimitive(it)) }
        this@toJson?.useExperience?.let { put("use_experience", JsonPrimitive(it)) }
        this@toJson?.acceptPlannerChanges?.let { put("accept_planner_changes", JsonPrimitive(it)) }
    }
}

/** Default clock that really sleeps for the given millis. */
object RealStepClock : WorkflowRunnerImpl.StepClock {
    override suspend fun sleep(millis: Long): Unit {
        if (millis > 0) delay(millis)
    }
}

/** Deterministic condition evaluator for `if` steps. */
internal object ConditionEvaluator {
    fun evaluate(
        left: JsonElement?,
        operator: String?,
        right: JsonElement?,
        context: JsonObject,
        defaults: JsonObject,
    ): Boolean {
        val op = operator?.trim() ?: "=="
        val l = resolveOperand(left, context, defaults)
        val r = resolveOperand(right, context, defaults)
        if (l is JsonObject || r is JsonObject) {
            return if (op == "==") l == r else l != r
        }
        val ls = l?.toString() ?: ""
        val rs = r?.toString() ?: ""
        return when (op) {
            "==" -> ls == rs
            "!=" -> ls != rs
            ">" -> num(ls) > num(rs)
            "<" -> num(ls) < num(rs)
            ">=" -> num(ls) >= num(rs)
            "<=" -> num(ls) <= num(rs)
            else -> ls == rs
        }
    }

    private fun num(s: String): Double = s.toDoubleOrNull() ?: when (s) {
        "true" -> 1.0
        "false" -> 0.0
        else -> Double.NaN
    }

    private fun resolveOperand(e: JsonElement?, context: JsonObject, defaults: JsonObject): JsonElement? {
        if (e == null) return null
        val p = e as? JsonPrimitive ?: return e
        val text = p.content
        if (text.startsWith("\${") && text.endsWith("}")) {
            return WorkflowVariableResolver.lookup(emptyMap(), context, defaults, 0, false, text.substring(2, text.length - 1))
        }
        return e
    }
}
