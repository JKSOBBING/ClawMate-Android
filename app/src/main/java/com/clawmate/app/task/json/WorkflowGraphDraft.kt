package com.clawmate.app.task.json

import kotlinx.serialization.json.*

/** Lossless JSON tree edits. Unknown fields and nested branches always survive. */
data class WorkflowGraphDraft(val root: JsonObject) {
    fun text(): String = pretty.encodeToString(JsonObject.serializer(), root)
    fun maxDepth(): Int {
        fun depth(nodes: JsonArray, level: Int): Int = nodes.maxOfOrNull { value ->
            val node = value as JsonObject
            listOf("steps", "then_steps", "else_steps").mapNotNull { node[it] as? JsonArray }
                .filter { it.isNotEmpty() }.maxOfOrNull { depth(it, level + 1) } ?: level
        } ?: level
        return depth(root["steps"] as JsonArray, 0)
    }
    fun at(path: String): JsonElement? = path.split('/').filter(String::isNotEmpty).fold(root as JsonElement?) { node, key ->
        when (node) { is JsonObject -> node[key]; is JsonArray -> key.toIntOrNull()?.let(node::getOrNull); else -> null }
    }
    fun replace(path: String, value: JsonElement): WorkflowGraphDraft {
        fun visit(node: JsonElement, keys: List<String>): JsonElement {
            if (keys.isEmpty()) return value
            val key = keys.first()
            return when (node) {
                is JsonObject -> JsonObject(node + (key to visit(node[key] ?: JsonObject(emptyMap()), keys.drop(1))))
                is JsonArray -> {
                    val index = key.toInt(); require(index in node.indices)
                    JsonArray(node.mapIndexed { i, item -> if (i == index) visit(item, keys.drop(1)) else item })
                }
                else -> error("无法编辑此字段")
            }
        }
        return copy(root = visit(root, path.split('/').filter(String::isNotEmpty)) as JsonObject)
    }
    fun field(path: String, key: String, value: JsonElement): WorkflowGraphDraft {
        val node = at(path) as? JsonObject ?: error("节点不存在")
        return replace(path, JsonObject(node + (key to value)))
    }
    fun action(path: String, action: String): WorkflowGraphDraft {
        val node = at(path) as? JsonObject ?: error("节点不存在")
        var result = field(path, "action", JsonPrimitive(action))
        val default = when (action) {
            "screenshot" -> "file_name" to JsonPrimitive("screen_${node.string("id")}.jpg")
            "sleep" -> "seconds" to JsonPrimitive(1)
            "keyevent" -> "key" to JsonPrimitive("BACK")
            "swipe" -> "direction" to JsonPrimitive("up")
            else -> null
        }
        if (default != null && (node[default.first] == null || node[default.first] == JsonPrimitive("")))
            result = result.field(path, default.first, default.second)
        return result
    }
    fun insert(template: String, after: String? = null, branch: String? = null): WorkflowGraphDraft {
        val parent = branch ?: after?.substringBeforeLast('/') ?: "steps"
        val list = (at(parent) as? JsonArray)?.toMutableList() ?: error("分支不存在")
        val selected = after?.substringAfterLast('/')?.toIntOrNull()
        val index = if (selected == null) list.size else groupStart(list, selected).let { it + groupSize(list, it) }
        val used = mutableSetOf<String>()
        fun collect(node: JsonElement) {
            when (node) {
                is JsonObject -> { (node["id"] as? JsonPrimitive)?.content?.let(used::add); node.values.forEach(::collect) }
                is JsonArray -> node.forEach(::collect)
                else -> Unit
            }
        }
        collect(root)
        var counter = (used.mapNotNull(String::toIntOrNull).maxOrNull() ?: 0).toLong() + 1
        fun id(): Long { while (counter.toString() in used) counter++; return counter++.also { used.add(it.toString()) } }
        val first = id()
        val nodes = if (template == "shot_summary") listOf(templateNode("screenshot", first), templateNode("summary", id(), first))
            else listOf(templateNode(template, first))
        list.addAll(index, nodes)
        return replace(parent, JsonArray(list))
    }
    fun remove(path: String): WorkflowGraphDraft = changeGroup(path) { list, index, size -> repeat(size) { list.removeAt(index) } }
    fun move(path: String, offset: Int): WorkflowGraphDraft = changeGroup(path) { list, index, size ->
        if (offset < 0 && index > 0) {
            val previous = groupStart(list, index - 1)
            val moving = list.subList(index, index + size).toList()
            repeat(size) { list.removeAt(index) }; list.addAll(previous, moving)
        } else if (offset > 0 && index + size < list.size) {
            val nextSize = groupSize(list, index + size)
            val moving = list.subList(index, index + size).toList()
            repeat(size) { list.removeAt(index) }; list.addAll(index + nextSize, moving)
        }
    }
    private fun changeGroup(path: String, transform: (MutableList<JsonElement>, Int, Int) -> Unit): WorkflowGraphDraft {
        val parent = path.substringBeforeLast('/')
        val list = (at(parent) as? JsonArray)?.toMutableList() ?: error("分支不存在")
        val selected = path.substringAfterLast('/').toInt(); require(selected in list.indices)
        val start = groupStart(list, selected)
        transform(list, start, groupSize(list, start))
        return replace(parent, JsonArray(list))
    }

    companion object {
        private val pretty = Json { prettyPrint = true }
        fun parse(raw: String): WorkflowGraphDraft? = if (WorkflowEditValidator.error(raw) != null) null
            else runCatching { WorkflowGraphDraft(Json.parseToJsonElement(raw) as JsonObject) }.getOrNull()
        fun empty(): String = """{"metadata":{"name":"custom_workflow","description":"新建任务"},"context":{},"steps":[]}"""
        fun isPair(list: List<JsonElement>, index: Int): Boolean {
            val first = list.getOrNull(index) as? JsonObject ?: return false
            val second = list.getOrNull(index + 1) as? JsonObject ?: return false
            val inputs = second["inputs"] as? JsonObject ?: return false
            return first.string("type") == "gui_action" && first.string("action") == "screenshot" &&
                second.string("type") == "tool" && second.string("tool_name") == "vlm_qa" && inputs.string("mode") == "summary" &&
                inputs.string("image") == "\${steps.${first["id"]?.jsonPrimitive?.content}.output.image_b64}"
        }
        private fun groupStart(list: List<JsonElement>, index: Int) = if (index > 0 && isPair(list, index - 1)) index - 1 else index
        private fun groupSize(list: List<JsonElement>, index: Int) = if (isPair(list, index)) 2 else 1
        private fun templateNode(key: String, id: Long, shotId: Long = 0): JsonObject = buildJsonObject {
            put("id", id)
            when (key) {
                "open_app", "gui_task" -> {
                    put("type", "gui_task"); put("task_description", if (key == "open_app") "打开目标应用" else "描述需要智能体完成的界面操作"); put("on_error", "stop")
                }
                "screenshot" -> { put("type", "gui_action"); put("action", "screenshot"); put("file_name", "screen_$id.jpg") }
                "summary" -> { put("type", "tool"); put("tool_name", "vlm_qa"); putJsonObject("inputs") {
                    put("mode", "summary"); put("image", "\${steps.$shotId.output.image_b64}")
                    put("question", "请用一段话总结当前截图中的关键信息，不要使用 Markdown 格式。")
                } }
                "if" -> { put("type", "if"); putJsonObject("condition") { put("left", "\${steps.2.output.response}"); put("operator", "contains"); put("right", "关键词") }; put("then_steps", JsonArray(emptyList())); put("else_steps", JsonArray(emptyList())) }
                "for_loop" -> { put("type", "loop"); put("times", 3); put("steps", JsonArray(emptyList())) }
                "until_loop" -> { put("type", "loop"); put("max_times", 10); putJsonObject("break_if") { put("left", "\${steps.2.output.structured_output.contains_today_chat}"); put("operator", "=="); put("right", false) }; put("steps", JsonArray(emptyList())) }
                else -> error("未知节点模板")
            }
        }
    }
}

internal fun JsonObject.string(key: String): String = (get(key) as? JsonPrimitive)?.content.orEmpty()
