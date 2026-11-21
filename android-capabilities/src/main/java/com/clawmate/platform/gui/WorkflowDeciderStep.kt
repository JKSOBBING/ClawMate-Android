package com.clawmate.platform.gui

import com.clawmate.app.task.model.WorkflowStep
import com.clawmate.platform.mika.MikaVisionConfig
import com.wangzan101.mika.core.action.*
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.cap.phone.PHONE
import com.wangzan101.mika.core.gate.IrreversibleDetector
import com.wangzan101.mika.core.guard.OperationGuards
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.record.RunSink
import com.wangzan101.mika.core.uistate.*
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*

/** A gui_task owns one Decider conversation. Adjacent workflow nodes do not inherit its model or actions. */
internal object WorkflowDeciderStep {
    suspend fun run(collection: VirtualWorkflowCollection, port: MikaScreenPort, target: String, label: String,
        step: WorkflowStep, sink: RunSink): JsonObject {
        val goal = requireNotNull(step.taskDescription).trim()
        require(goal.length in 1..2000) { "请填写本步 GUI 目标" }
        val ready = (step.raw["ready_text"] as? JsonPrimitive)?.content.orEmpty().trim()
        var config: MikaVisionConfig? = null
        val history = mutableListOf<String>()
        val tools = toolDispatcher(port, allowedToolNames = setOf("phone_list_elements", "phone_tap", "phone_type_text", "phone_swipe", "phone_back"))
            .apply { expand(PHONE) }
        suspend fun screen(): ScreenSnapshot {
            val s = collection.safeScreen(port, target)
            val facts = ScreenFacts(target, s.hasWindow, s.elements.size, s.changedDuringRead, emptyList(),
                s.elements.map { it.name }, s.elements.any { it.secret })
            check(!ScreenDiagnosis.classify(facts).needsHuman) { "此页需要用户亲自处理" }
            return s
        }
        val executor = ActionExecutor(tools, OperationGuards(), null, gate = { port.checkAppAccess() },
            recorder = ActionRecorder(sink))
        suspend fun finish(s: ScreenSnapshot): JsonObject {
            val lines = s.elements.filterNot { it.editable }.map { it.name.trim() }.filter(String::isNotBlank).distinct()
            check(ready.isEmpty() || ready in lines) { "未核实 GUI 步骤的到达标志" }
            if ((step.raw["extract_result"] as? JsonPrimitive)?.booleanOrNull == true) port.captureFinalWorkflowImage()
            return buildJsonObject { put("response", ("已核对当前页面\n" + lines.joinToString("\n")).take(4000)) }
        }
        val limit = step.maxSteps?.toInt() ?: 20
        for (index in 0..limit) {
            port.waitStable(1500)
            val before = screen()
            if (hasArrivalMarker(ready, before.elements)) return finish(before)
            if (index == limit) break
            val window = port.lastObservedWindow
            suspend fun fresh(): ScreenSnapshot = screen().also {
                check(port.lastObservedWindow == window && it.width == before.width && it.height == before.height &&
                    it.elements == before.elements) { "等待 Decider 期间目标页面已变化" }
            }
            val approved = config ?: collection.config("$label · 执行 GUI 步骤，将发送本步的目标窗口截图和操作记录：$goal").also { config = it }
            fresh()
            val image = requireNotNull(port.screenshotPng()) { "无法读取工作流目标窗口" }
            fresh()
            val reply = WorkflowDecider.propose(approved, collection.http, goal, history, image)
            val s = fresh()
            val kind = reply.json.getValue("action").jsonPrimitive.content
            val params = WorkflowDecider.parameters(reply.json)
            if (kind == "done") {
                check(params["status"]?.jsonPrimitive?.content == "success") { "Decider 未能完成当前 GUI 步骤" }
                return finish(s)
            }
            if (kind == "wait") delay(1000) else {
                val (tool, args) = action(kind, params, reply, s)
                // Mika's guard resolves a reference against an observation registered in its own session.
                val checked = if ("ref" in args) {
                    val observe = Part.ToolCall("observe-$index", "phone_list_elements", "{}")
                    val registered = executor.run(observe, Origin.McpTool(index + 1)).execution().content
                    check(registered["ok"] == JsonPrimitive(true) && registered["currentApp"] == JsonPrimitive(target)) {
                        "Mika 未能登记目标窗口"
                    }
                    fresh()
                    JsonObject(args + ("snapshotId" to registered.getValue("snapshotId")))
                } else args
                val call = Part.ToolCall("decider-${index + 1}", tool, checked.toString())
                val result = executor.run(call, Origin.McpTool(index + 1)).execution().content
                check(result["ok"] == JsonPrimitive(true)) { "Mika 未执行 $kind：${result["error"] ?: "目标无法核实"}" }
            }
            history += "${index + 1}. $kind " + (params["target_element"] as? JsonPrimitive)?.content.orEmpty().take(120)
        }
        error("达到本步允许的 Decider 调用次数，尚未核实完成")
    }

    internal fun hasArrivalMarker(ready: String, elements: List<UiElement>): Boolean {
        if (ready.isBlank()) return false
        val marker = elements.singleOrNull { it.name.trim() == ready } ?: return false
        return !marker.clickable && !marker.editable && !marker.hint && !marker.secret
    }

    private fun action(kind: String, params: JsonObject, reply: WorkflowDecider.Proposal, s: ScreenSnapshot): Pair<String, JsonObject> {
        fun point(key: String): List<Int> {
            val coords = params.getValue(key).jsonArray
            require(coords.size == 2)
            return coords.mapIndexed { i, field ->
                val size = if (i == 0) reply.width else reply.height
                normalize(WorkflowDecider.pixel(field, size), size)
            }
        }
        if (kind == "press_back") return "phone_back" to buildJsonObject {}
        if (kind == "swipe") {
            val from = point("start_coords"); val to = point("end_coords")
            require(from != to) { "Decider 滑动距离为零" }
            return "phone_swipe" to buildJsonObject {
                put("startX", from[0]); put("startY", from[1]); put("endX", to[0]); put("endY", to[1])
            }
        }
        require(kind in setOf("click", "click_input", "input")) { "本步骤不允许 Decider 执行动作 $kind" }
        val typing = kind != "click"
        val candidates = s.elements.filter { !it.secret && if (typing) it.editable else it.clickable }
        val selected = if (kind == "input") candidates.singleOrNull { it.focused } ?: error("未找到唯一的已聚焦输入框")
        else {
            val click = JsonObject(reply.json + ("action" to JsonPrimitive("click")))
            candidates[WorkflowDecider.resolve(click.toString(), reply.width, reply.height, candidates.map { DeciderEntry(it.name, it.bounds) })]
        }
        check(IrreversibleDetector.matchName(selected.name, s, selected) == null) { "此操作需要用户亲自处理" }
        return (if (typing) "phone_type_text" else "phone_tap") to buildJsonObject {
            put("ref", selected.ref); put("snapshotId", s.snapshotId)
            if (typing) {
                val value = params.getValue("text").jsonPrimitive
                require(value.isString && value.content.length in 1..2000)
                put("text", value)
            }
        }
    }
}
