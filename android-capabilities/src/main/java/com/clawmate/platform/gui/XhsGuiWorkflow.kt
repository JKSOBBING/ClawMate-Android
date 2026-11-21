package com.clawmate.platform.gui

import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import com.clawmate.app.task.model.WorkflowStep
import com.clawmate.app.task.runner.RunStatus
import com.clawmate.app.task.runner.StepResult
import com.clawmate.app.task.runner.WorkflowModelRequest
import com.clawmate.app.task.runner.WorkflowModelTool
import com.clawmate.app.task.runner.WorkflowRunResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

internal object XhsGuiWorkflow {
    const val PACKAGE = "com.xingin.xhs"
    const val LABEL = "小红书公开笔记"
    const val ACTION = "xhs_public_notes"
    const val DEFAULT_TAB = "全部"
    const val DEFAULT_PROMPT = "采集当前页的公开笔记摘录，然后向下翻一页，再采集这一页，最后打开预览。"
    const val MODE = "xhs_public_choice"
    const val MAX_PAGES = 3
    const val MAX_ENTRIES = 15
    val actions = mapOf(
        "read_visible" to "采集当前页公开笔记的可读摘录",
        "scroll_forward" to "向下翻一页以查看更多公开笔记",
        "finish" to "结束采集并打开本机预览",
    )

    fun validate(steps: List<WorkflowStep>): Pair<String, String> {
        val step = requireNotNull(steps.singleOrNull()) { "小红书实例需单独使用一个 AI 采集节点" }
        require(step.type == WeiboGuiWorkflow.TYPE && step.action == ACTION &&
            step.raw.keys.all { it in setOf("id", "type", "action", "name", "on_error", "task_description", "text", "target_text") } &&
            (step.onError == null || step.onError == "stop")) { "小红书 AI 采集节点含不支持的配置" }
        fun field(key: String, max: Int): String {
            val raw = requireNotNull((step.raw[key] as? JsonPrimitive)?.takeIf { it.isString }) {
                "请填写小红书搜索关键词和采集提示词"
            }
            return raw.content.trim().also {
                require(it.length in 1..max && it.none(Char::isISOControl)) { "搜索关键词或采集提示词为空、过长或格式不支持" }
            }
        }
        if ("target_text" in step.raw) field("target_text", 40)
        return field("text", 40) to field("task_description", 300)
    }

    fun launch(keyword: String): Intent = Intent(Intent.ACTION_VIEW, Uri.Builder().scheme("xhsdiscover")
        .authority("search").appendPath("result").appendQueryParameter("keyword", keyword).build()).setPackage(PACKAGE)
        .addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)

    fun request(prompt: String, allowed: Set<String>, pages: Int, collected: Int): WorkflowModelRequest {
        val body = buildJsonObject {
            put("task", prompt)
            put("page", "小红书公开搜索结果")
            put("pages_read", pages)
            put("entries_collected", collected)
            putJsonArray("choices") {
                allowed.forEach { id -> add(buildJsonObject { put("id", id); put("label", actions.getValue(id)) }) }
            }
        }
        return WorkflowModelRequest("", MODE, body.toString(), "", null)
    }

    fun choice(raw: String, allowed: Set<String>): String {
        val value = Json.parseToJsonElement(raw) as? JsonObject
        val action = value?.get("action") as? JsonPrimitive
        require(value?.keys == setOf("action") && action?.isString == true && action.content in allowed) {
            "模型未选择当前页面允许的动作，采集已停止"
        }
        return requireNotNull(action).content
    }

    @RequiresApi(34)
    private fun readable(node: AccessibilityNodeInfo, area: Rect): Boolean = node.packageName?.toString() == PACKAGE &&
        node.isVisibleToUser && !node.isEditable && !node.isPassword && !node.isAccessibilityDataSensitive &&
        Rect.intersects(area, node.weiboBounds())

    @RequiresApi(34)
    private fun searchBottom(root: AccessibilityNodeInfo, area: Rect, keyword: String): Int? {
        val toolbarEnd = area.top + area.height() / 8
        fun inToolbar(node: AccessibilityNodeInfo): Boolean = readable(node, area) && node.weiboBounds().bottom <= toolbarEnd
        val query = root.findAccessibilityNodeInfosByText(keyword)
            .singleOrNull { it.text?.toString() == keyword && inToolbar(it) } ?: return null
        val search = root.findAccessibilityNodeInfosByText("搜索")
            .singleOrNull { it.text?.toString() == "搜索" && it.isClickable && inToolbar(it) } ?: return null
        if (search.weiboBounds().top != query.weiboBounds().top) return null
        return search.weiboBounds().bottom
    }

    @RequiresApi(34)
    private fun descendants(node: AccessibilityNodeInfo, area: Rect, depth: Int): Sequence<AccessibilityNodeInfo> = sequence {
        if (depth == 0 || !readable(node, area)) return@sequence
        yield(node)
        for (index in 0 until node.childCount.coerceAtMost(30)) {
            node.getChild(index)?.let { yieldAll(descendants(it, area, depth - 1)) }
        }
    }

    @RequiresApi(34)
    fun tabs(root: AccessibilityNodeInfo, area: Rect, keyword: String): Map<String, AccessibilityNodeInfo> {
        val bottom = searchBottom(root, area, keyword) ?: return emptyMap()
        val band = Rect(area.left, bottom, area.right, minOf(area.bottom, bottom + area.height() / 8))
        val tabs = descendants(root, area, 18).take(170).filter { node ->
            node.isClickable && node.isEnabled && node.className?.toString()?.endsWith("ActionBar\$Tab") == true &&
                band.contains(node.weiboBounds())
        }.mapNotNull { tab ->
            val label = descendants(tab, band, 6).take(16).mapNotNull { it.text?.toString()?.trim() }
                .filter { it.length in 1..16 && it.none(Char::isISOControl) }.distinct().singleOrNull()
            label?.let { it to tab }
        }.toList()
        return if (tabs.map { it.first }.distinct().size == tabs.size) tabs.toMap() else emptyMap()
    }

    fun isPublicTab(label: String) = label in setOf(DEFAULT_TAB, "综合", "全部笔记", "综合笔记", "综合结果", "全部结果", "综合搜索")

    @RequiresApi(34)
    fun page(root: AccessibilityNodeInfo, area: Rect, keyword: String): XhsPublicPage? {
        val toolbarBottom = searchBottom(root, area, keyword) ?: return null
        // The first TextView in each two-column result card contains the public excerpt.
        val list = descendants(root, area, 18).take(260)
            .firstOrNull { it.className?.toString() == "androidx.recyclerview.widget.RecyclerView" &&
                it.weiboBounds().width() >= area.width() * 9 / 10 } ?: return null
        val cards = (0 until list.childCount.coerceAtMost(24)).mapNotNull(list::getChild).filter { card ->
            val bounds = card.weiboBounds()
            readable(card, area) && bounds.top >= toolbarBottom &&
                bounds.width() in (area.width() / 3)..(area.width() * 3 / 5)
        }.sortedWith(compareBy({ it.weiboBounds().top }, { it.weiboBounds().left }))
        val excerpts = cards.mapNotNull { card ->
            val nodes = descendants(card, area, 10).take(70).toList()
            if (nodes.none { it.isClickable }) return@mapNotNull null
            nodes.firstOrNull { it.className?.toString() == "android.widget.TextView" && !it.text.isNullOrBlank() }
                ?.text?.toString()?.trim()?.takeIf { it.length in 2..140 }
        }.distinct()
        return XhsPublicPage(list, excerpts)
    }
}

internal data class XhsPublicPage(val list: AccessibilityNodeInfo, val excerpts: List<String>)

internal class XhsGuiRun(
    val step: WorkflowStep, val keyword: String, val prompt: String, val model: WorkflowModelTool,
    val collection: VirtualWorkflowCollection,
) {
    val result = CompletableDeferred<WorkflowRunResult>()
    val targetTab = (step.raw["target_text"] as? JsonPrimitive)?.content?.trim() ?: XhsGuiWorkflow.DEFAULT_TAB
    val localPolicy = prompt == XhsGuiWorkflow.DEFAULT_PROMPT
    var displayId: Int? = null
    var selectedTab: String? = null
    var linkRetried = false
    private var modelRequests = 0

    suspend fun decide(request: WorkflowModelRequest): String {
        modelRequests++
        return model.evaluate(request)
    }

    fun finish(status: RunStatus, message: String, summary: String = "") {
        val requests = modelRequests + collection.requests.get()
        val output = if (localPolicy) buildJsonObject {
            put("action", step.action)
            put("executor", "workflow")
            put("model_requests", requests)
            put("entry_model_requests", collection.requests.get())
            put("read_model_requests", modelRequests)
            displayId?.let { put("display_id", it) }
            selectedTab?.let { put("selected_tab", it) }
            put("public_link_retried", linkRetried)
        } else null
        result.complete(WorkflowRunResult(status, message, listOf(StepResult(step.id, step.type, status, message, output = output)),
            resultSummary = summary, collectionModelRequests = if (localPolicy) requests else null))
    }
}
