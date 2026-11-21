package com.clawmate.platform.mika

import com.wangzan101.mika.core.agent.LearnGate
import com.wangzan101.mika.core.agent.OutcomeJudge
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.agent.RunOutcome
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.replay.RouteRecorder
import com.wangzan101.mika.core.replay.RouteStore
import com.wangzan101.mika.core.tool.ToolIndex
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Mika TaskRun judge/learn flow, detached from the display lifetime after delivery. */
internal object MikaRouteLearning {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun schedule(goal: String, end: TaskState.Succeeded, events: List<RunEvent>,
        routes: RouteStore, model: ChatModel, tools: ToolIndex,
        onOutcome: suspend (RunOutcome) -> Unit = {},
    ) = scope.launch {
        try { learn(goal, end, events, routes, model, tools, onOutcome) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Background learning never crashes the application. */ }
    }

    suspend fun learn(goal: String, end: TaskState.Succeeded, events: List<RunEvent>,
        routes: RouteStore, model: ChatModel, tools: ToolIndex,
        onOutcome: suspend (RunOutcome) -> Unit = {},
    ) {
        // Check recordability before spending a model call. The upstream recorder rejects
        // protected inputs, partial traces, non-phone actions and takeover/confirmation runs.
        // Replayer emits Replay events and can finish without any new ActionTarget.
        val replayCompleted = events.filterIsInstance<RunEvent.Replay>().lastOrNull()?.let {
            it.matched && it.step == it.total
        } == true
        // An operation error may be raised after dispatch. Omitting that action
        // would teach the preceding taps as the entire route to the final page.
        if (!replayCompleted && events.filterIsInstance<RunEvent.ToolResult>().any { result ->
            tools[result.tool]?.routable == true && runCatching {
                (Json.parseToJsonElement(result.resultJson) as? JsonObject)?.get("error")
            }.getOrNull() == JsonPrimitive("operation_failed")
        }) return
        val route = if (replayCompleted) null else RouteRecorder.record(goal, events, System.currentTimeMillis(), tools)
        if (!replayCompleted && route == null) return
        val judged = OutcomeJudge(model).judge(goal, end.answer)
        onOutcome(judged.outcome)
        if (!LearnGate.shouldSaveRoute(true, end, judged.outcome, "recorded", goal)) return
        if (route == null) return
        try { routes.putReplacing(route) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Learning failure must not change the delivered task result. */ }
    }
}
