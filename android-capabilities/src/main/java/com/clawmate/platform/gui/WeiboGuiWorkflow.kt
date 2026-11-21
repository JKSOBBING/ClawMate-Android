package com.clawmate.platform.gui

import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import com.clawmate.app.task.model.WorkflowStep
import com.clawmate.app.task.runner.RunStatus
import com.clawmate.app.task.runner.StepResult
import com.clawmate.app.task.runner.WorkflowRunResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

internal object WeiboGuiWorkflow {
    const val PACKAGE = "com.sina.weibo"
    const val TYPE = GuiCollectionWorkflow.TYPE
    const val OPEN = "weibo_open_board"
    const val SELECT = "weibo_select_hot"
    const val READ = "weibo_read_hot"
    const val PREVIEW = "weibo_preview"
    private const val TAB = "$PACKAGE:id/search_item_text"
    private const val TAB_BUTTON = "$PACKAGE:id/root"
    private const val TITLE = "$PACKAGE:id/tv_coupon_des"
    private const val HEAT = "$PACKAGE:id/tv_coupon_des_extr"
    private const val RANK = "$PACKAGE:id/iv_coupon_icon"
    private const val LIST = "$PACKAGE:id/view_recycler"
    private val sectionHeaders = listOf("实时上升热点", "文娱热搜", "同城热搜", "好友热搜", "热搜榜规则说明")

    fun launchIntent(): Intent = Intent(Intent.ACTION_VIEW,
        Uri.parse("sinaweibo://pageinfo?containerid=106003_-_type%3A25_-_filter_type%3Aband")).setPackage(PACKAGE)

    fun validate(steps: List<WorkflowStep>): Int {
        require(steps.size in 4..7) { "微博采集需要四个采集节点，可另外插入最多三个等待节点" }
        val sequence = steps.filterNot { it.type == "gui_action" && it.action == "sleep" }
        require(sequence.all { it.type == TYPE } && sequence.map { it.action } == listOf(OPEN, SELECT, READ, PREVIEW) &&
            steps.first() == sequence.first() && steps.last() == sequence.last()) {
            "请依次连接：打开微博榜单 → 切换热搜标签 → 读取热搜 → 预览并保存。等待节点可插在中间。"
        }
        for (step in steps) {
            val extra = when (step.action) {
                SELECT -> setOf("target_text")
                READ -> setOf("item_limit")
                "sleep" -> setOf("seconds")
                else -> emptySet()
            }
            require(step.raw.keys.all { it in extra || it in setOf("id", "type", "action", "name", "on_error") } &&
                (step.onError == null || step.onError == "stop")) { "第 ${step.id} 步含有不支持的参数或错误策略" }
            step.raw["target_text"]?.let {
                require(it is JsonPrimitive && it.isString && it.content.isNotBlank() && it.content.length <= 40) { "热搜标签的入口名称无效" }
            }
            if (step.action == "sleep") require((step.seconds ?: 0) in 1L..5L) { "等待时间须为 1 至 5 秒" }
        }
        val field = sequence[2].raw["item_limit"] ?: return 0
        require(field is JsonPrimitive && !field.isString && (field.intOrNull ?: -1) in 0..5) { "读取范围请选择整榜或 1 至 5 条" }
        return checkNotNull(field.intOrNull)
    }

    @RequiresApi(34)
    private fun readable(node: AccessibilityNodeInfo, area: Rect): Boolean = node.isVisibleToUser &&
        node.packageName?.toString() == PACKAGE && !node.isPassword && !node.isEditable && !node.isAccessibilityDataSensitive &&
        Rect.intersects(area, Rect().also(node::getBoundsInScreen))

    @RequiresApi(34)
    fun boardTabs(root: AccessibilityNodeInfo, area: Rect): List<AccessibilityNodeInfo> =
        root.findAccessibilityNodeInfosByViewId(TAB).filter {
            readable(it, area) && it.isEnabled && (it.text?.length ?: 0) in 1..30
        }.take(20)

    @RequiresApi(34)
    fun hotTab(root: AccessibilityNodeInfo, area: Rect, label: String): AccessibilityNodeInfo? =
        boardTabs(root, area).singleOrNull { it.text?.toString() == label }

    @RequiresApi(34)
    fun hotTabButton(tab: AccessibilityNodeInfo, area: Rect): AccessibilityNodeInfo? =
        generateSequence(tab.parent) { it.parent }.take(4).firstOrNull {
            it.viewIdResourceName == TAB_BUTTON && it.isEnabled && it.isClickable && readable(it, area)
        }

    @RequiresApi(34)
    fun hotTabBounds(tab: AccessibilityNodeInfo, area: Rect): Rect? =
        tab.weiboBounds().takeIf {
            tab.viewIdResourceName == TAB && tab.isEnabled &&
                readable(tab, area) && !it.isEmpty && area.contains(it)
        }

    @RequiresApi(34)
    fun titleBounds(root: AccessibilityNodeInfo, area: Rect, title: String): Rect? =
        root.findAccessibilityNodeInfosByViewId(TITLE).singleOrNull {
            readable(it, area) && it.text?.toString() == title
        }?.weiboBounds()?.takeIf(area::contains)

    @RequiresApi(34)
    fun topStory(root: AccessibilityNodeInfo, area: Rect, title: String): WeiboLead? {
        val body = sequenceOf("contentTextView", "tv_standpoint").flatMap { id ->
            root.findAccessibilityNodeInfosByViewId("$PACKAGE:id/$id").asSequence()
        }.filter { readable(it, area) }
            .flatMap { sequenceOf(it.text, it.contentDescription) }
            .filterNotNull().map { it.toString().trim() }
            .firstOrNull { it.contains(title) } ?: return null
        val excerpt = body.replace(Regex("\\s+"), " ").take(260)
        return if (excerpt.replace(title, "").trim('#', ' ').length >= 12) WeiboLead(title, excerpt) else null
    }

    @RequiresApi(34)
    fun read(root: AccessibilityNodeInfo, area: Rect, label: String): WeiboVisiblePage? {
        if (hotTab(root, area, label)?.isSelected != true) return null
        // The selected state can update just before the previous tab's rows leave the screen.
        if (root.findAccessibilityNodeInfosByText("热搜雷达").any {
                it.text?.toString() == "热搜雷达，发现你关心的热点" && readable(it, area)
            }) return null
        val list = root.findAccessibilityNodeInfosByViewId(LIST).singleOrNull { readable(it, area) } ?: return null
        val listBounds = list.weiboBounds().apply { if (!intersect(area)) setEmpty() }
        if (listBounds.isEmpty) return null
        val boundary = sectionHeaders.asSequence().flatMap { label ->
            root.findAccessibilityNodeInfosByText(label).asSequence().filter { it.text?.toString() == label }
        }.filter { readable(it, listBounds) }.minOfOrNull { it.weiboBounds().top }
        // Some client versions place the title and heat under different wrappers.
        // Pair the two known resource IDs only when they share the same row geometry.
        val heats = root.findAccessibilityNodeInfosByViewId(HEAT).mapNotNull { node ->
            val number = node.text?.toString()?.trim()?.takeIf { it.length in 1..40 }
            if (number == null || !readable(node, listBounds)) null else node.weiboBounds() to number
        }
        val icons = root.findAccessibilityNodeInfosByViewId(RANK)
            .filter { readable(it, listBounds) }.map { it.weiboBounds() }.filter(listBounds::contains)
        val rows = root.findAccessibilityNodeInfosByViewId(TITLE)
            .filter { readable(it, listBounds) }.map { it.weiboBounds() to it }
            .sortedBy { it.first.top }
            .filter { (bounds, _) -> boundary == null || bounds.top < boundary }
            .take(24)
            .mapNotNull { (bounds, node) ->
                val title = node.text?.toString()?.trim()?.takeIf { it.length in 1..150 } ?: return@mapNotNull null
                val heat = heats.singleOrNull { (other, _) ->
                    other.top == bounds.top && other.bottom == bounds.bottom && other.left >= bounds.right
                }?.second.orEmpty()
                val icon = icons.singleOrNull { it.left < bounds.left && it.centerY() in bounds.top until bounds.bottom }
                WeiboVisibleRow(title, heat, icon)
            }
        return WeiboVisiblePage(rows, listBounds, boundary != null)
    }
}

internal class WeiboGuiRun(val steps: List<WorkflowStep>, val limit: Int, val collection: VirtualWorkflowCollection) {
    val result = CompletableDeferred<WorkflowRunResult>()
    private val finished = mutableListOf<StepResult>()
    private var current: WorkflowStep? = null
    private var stepDisplayId = 0
    private var requestsAtStart = 0
    private var candidateWindow: Pair<Int, Rect>? = null
    private var candidateSince = 0L

    fun windowReady(candidate: Pair<Int, Rect>?, now: Long): Boolean {
        if (candidate == null || candidate != candidateWindow) {
            candidateWindow = candidate
            candidateSince = now
            return false
        }
        return now - candidateSince >= 1_000
    }

    fun begin(step: WorkflowStep, displayId: Int) {
        current = step
        stepDisplayId = displayId
        requestsAtStart = collection.requests.get()
    }

    fun record(status: RunStatus, note: String) {
        val step = current ?: return
        val calls = collection.requests.get() - requestsAtStart
        finished += StepResult(step.id, step.type, status, note, output = buildJsonObject {
            put("action", step.action)
            put("display_id", stepDisplayId)
            put("model_requests", calls)
            put("executor", if (calls == 0) "workflow" else "mika")
        })
        current = null
    }

    fun finish(status: RunStatus, message: String, summary: String = "") {
        if (result.isCompleted) return
        record(status, message)
        result.complete(WorkflowRunResult(status, message, finished.toList(), resultSummary = summary, collectionModelRequests = collection.requests.get()))
    }
}
