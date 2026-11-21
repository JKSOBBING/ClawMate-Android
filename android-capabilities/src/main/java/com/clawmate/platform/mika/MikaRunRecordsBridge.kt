package com.clawmate.platform.mika

import com.wangzan101.mika.core.record.RunEvent
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Views of the stored run records for the ArkTS bridge.
 *
 * The shared Android host does not depend on `:mika-core`, so it must not name [MikaRunStore]
 * (whose supertype is a kernel type). Everything here has a signature of plain
 * `File`/`Int`/`String` only, and keeps kernel types inside `:android-capabilities`.
 *
 * The list/row projections are plain data on purpose: `org.json` is only a stub in JVM unit
 * tests, so the tests cover [briefs]/[rows] and the JSON assembly below stays a thin wrapper.
 */
object MikaRunRecordsBridge {

    fun json(root: File, limit: Int): String {
        val records = runCatching {
            briefs(root, limit).map { brief ->
                JSONObject().put("id", brief.id).put("goal", brief.goal).put("model", brief.model)
                    .put("startedAt", brief.startedAtMs).put("endedAt", brief.endedAtMs ?: JSONObject.NULL)
                    .put("outcome", brief.outcome.orEmpty()).put("steps", brief.steps)
                    .put("answer", brief.answerOrReason.orEmpty().take(600))
            }
        }.getOrDefault(emptyList())
        return JSONObject().put("v", 1).put("records", JSONArray(records)).toString()
    }

    fun eventsJson(root: File, runId: String): String {
        val rows = runCatching { rows(root, runId) }.getOrDefault(emptyList()).map { row ->
            JSONObject().put("atMs", row.atMs).put("type", row.type).put("step", row.step)
                .put("text", row.text)
        }
        return JSONObject().put("v", 1).put("id", runId).put("events", JSONArray(rows)).toString()
    }

    /** Newest-first run summaries; unreadable entries are skipped. */
    fun briefs(root: File, limit: Int): List<MikaRunBrief> =
        runCatching { MikaRunStore(root).listBriefs(limit.coerceIn(1, 50)) }.getOrDefault(emptyList())

    /**
     * Per-step view of one run: only what a person can act on (goal, model replies, tool results,
     * screen effects, confirmations, ending). Kernel bookkeeping events are dropped.
     */
    fun rows(root: File, runId: String): List<MikaStepRow> {
        val events = runCatching {
            kotlinx.coroutines.runBlocking { MikaRunStore(root).events(runId) }
        }.getOrDefault(emptyList())
        return events.mapNotNull { event ->
            val text = describe(event) ?: return@mapNotNull null
            MikaStepRow(event.atMs, event::class.simpleName.orEmpty(), stepOf(event), text.take(400))
        }
    }

    private fun stepOf(event: RunEvent): Int = when (event) {
        is RunEvent.ModelReply -> event.step
        is RunEvent.ToolResult -> event.step
        is RunEvent.Activity -> event.step
        is RunEvent.Observed -> event.step
        else -> 0
    }

    private fun describe(event: RunEvent): String? = when (event) {
        is RunEvent.TaskStarted -> "开始 · 目标：${event.goal.take(120)}"
        is RunEvent.ModelReply -> {
            val tool = event.tool
            if (tool == null) "第 ${event.step} 步 · 模型回复：${event.text.trim().take(160)}"
            else "第 ${event.step} 步 · 调用 $tool"
        }
        is RunEvent.ToolResult -> {
            val changed = when (event.changed) {
                true -> " · 界面有变化"
                false -> " · 界面无变化"
                null -> ""
            }
            "第 ${event.step} 步 · ${event.tool} 返回：${event.resultJson.take(200)}$changed"
        }
        is RunEvent.Activity -> "第 ${event.step} 步 · ${event.kind} ${event.label.take(80)}" +
            (event.effect?.let { " · 效果 $it" } ?: "")
        is RunEvent.Observed -> "第 ${event.step} 步 · 读到：${event.text.trim().take(160)}"
        is RunEvent.Confirmation -> "确认 ${event.action.take(60)} · ${event.decision ?: "未决定"}"
        is RunEvent.TaskEnded -> "结束 · ${event.outcome} ${event.answerOrReason.take(200)}".trim()
        else -> null
    }
}

/** One human-readable step of a stored run; carries no `:mika-core` type. */
data class MikaStepRow(
    val atMs: Long,
    val type: String,
    val step: Int,
    val text: String,
)
