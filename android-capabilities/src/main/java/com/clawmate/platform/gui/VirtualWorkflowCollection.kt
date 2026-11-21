package com.clawmate.platform.gui

import android.app.Activity
import android.app.AlertDialog
import android.view.WindowManager
import com.clawmate.app.task.model.WorkflowStep
import com.clawmate.app.task.runner.WorkflowModelRequest
import com.clawmate.app.task.runner.WorkflowModelTool
import com.clawmate.platform.mika.MikaVisionConfig
import com.clawmate.platform.mika.mikaVisionService
import com.wangzan101.mika.core.gate.IrreversibleDetector
import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.uistate.ScreenPort
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.UiElement
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import org.json.JSONObject

internal fun collectionPrerequisite(labels: List<String>): String? {
    val agreement = labels.any { it in setOf("不同意", "拒绝", "暂不同意") } &&
        labels.any { it in setOf("同意", "同意并继续", "同意并进入", "同意并接受") } &&
        labels.any { it.contains("隐私") || it.contains("个人信息保护") || it.contains("协议") || it == "温馨提示" }
    if (agreement) return "请先在目标应用中自行核对首次隐私协议，返回后重试"
    val login = labels.any { it in setOf("手机号登录/注册", "手机号码登录/注册", "淘宝用户快速登录", "手机号快捷登录") } ||
        labels.any { it == "其他登录方式" } && labels.any { it.contains("登录") && it != "其他登录方式" }
    return if (login) "目标应用要求登录，请在目标应用中自行登录后重试" else null
}

internal class CollectionDeclined : IllegalStateException("已取消本次采集，未保存")

/** All screen reads and actions use the same target-bound virtual display. Only ask() has a model transport. */
internal class VirtualWorkflowCollection(private val activity: Activity, private val authorization: WorkflowModelTool) {
    val requests = AtomicInteger()
    val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .readTimeout(45, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(45, java.util.concurrent.TimeUnit.SECONDS)
        .addNetworkInterceptor { chain ->
            if (chain.request().method == "POST") requests.incrementAndGet()
            chain.proceed(chain.request())
        }.build()
    private var pending: GuiCollection? = null
    private var accountPage: AccountCollectionPage? = null
    private var accountWindow: Int? = null
    private var readyText = ""
    private var readyId = ""
    private var orderDetails: EleOrderDetails? = null

    companion object {
        val actions = setOf("collection_open_app", "collection_navigate", "collection_read_pages", "collection_model_read", "collection_preview")
    }

    private fun WorkflowStep.field(key: String) = (raw[key] as? JsonPrimitive)?.content.orEmpty().trim()
    internal suspend fun safeScreen(port: ScreenPort, target: String): ScreenSnapshot {
        val screen = port.snapshot()
        check(screen.packageName == target && screen.hasWindow && !screen.protected && !screen.changedDuringRead &&
            screen.elements.none { it.secret }) { "目标页面受保护、窗口已变化或尚未就绪" }
        val labels = screen.elements.map { it.name.trim() }
        val prerequisite = collectionPrerequisite(labels)
        check(prerequisite == null) { prerequisite.orEmpty() }
        return screen
    }

    suspend fun config(reason: String): MikaVisionConfig {
        val response = authorization.evaluate(WorkflowModelRequest("", "collection_authorize", reason, "", null))
        if (response == "cancel") throw CollectionDeclined()
        val credentials = JSONObject(response)
        return requireNotNull(MikaVisionConfig.parse(credentials.getString("url"), credentials.getString("model"), credentials.optString("key"),
            MikaVisionConfig.debugOrigin(activity))) {
            "请为本次接管配置 HTTPS 模型"
        }
    }

    private suspend fun ask(reason: String, system: String, question: String, images: List<ByteArray> = emptyList()): String {
        return mikaVisionService(config(reason), http).ask(system, question, images).trim()
    }

    suspend fun chooseEntry(label: String, name: String, proof: String, options: List<DeciderEntry>,
        screenshot: suspend () -> ByteArray): Int {
        require(options.isNotEmpty() && options.size <= 60) { "当前页面没有可核验的入口" }
        val destination = config("$label · 定位“$name”入口，将发送当前目标窗口截图和候选控件名称")
        return WorkflowDecider.choose(destination, http, "定位“$name”入口。到达标志：$proof。", options, screenshot())
    }

    private suspend fun chooseOnPort(port: ScreenPort, target: String, label: String, name: String, proof: String,
        options: List<UiElement>, observed: ScreenSnapshot): Int {
        suspend fun checkFrame() {
            val now = safeScreen(port, target)
            check(now.width == observed.width && now.height == observed.height && now.elements == observed.elements) {
                "等待 Decider 期间页面已变化，请重新运行"
            }
            check(com.wangzan101.mika.core.uistate.ScreenDiagnosis.paymentText(now.elements.map { it.name }) == null) {
                "请自行处理付款页面"
            }
        }
        return chooseEntry(label, name, proof, options.map { DeciderEntry(it.name, it.bounds) }) {
            checkFrame()
            requireNotNull(port.screenshotPng()) { "无法捕获目标窗口" }.also { checkFrame() }
        }
    }

    private fun matches(element: UiElement, name: String, id: String): Boolean =
        if (id.isNotEmpty()) element.resourceId == id || element.resourceId?.substringAfterLast('/') == id
        else name.isNotEmpty() && element.name.trim() == name

    private suspend fun navigate(port: ScreenPort, target: String, label: String, name: String, id: String, ready: String, proofId: String) {
        require((name.isNotBlank() || id.isNotBlank()) && (ready.isNotBlank() || proofId.isNotBlank())) { "导航步骤需要入口和到达标志" }
        port.waitStable(1500)
        val screen = safeScreen(port, target)
        fun arrived(s: ScreenSnapshot): Boolean = s.elements.any {
            matches(it, ready, proofId) && (ready != name || proofId != id || !it.clickable || "selected" in it.states)
        }
        val exact = screen.elements.filter { matches(it, name, id) }
        if (arrived(screen) && (exact.isEmpty() || ready != name || proofId.isNotEmpty())) return
        val entry = exact.filter { it.clickable }.singleOrNull() ?: exact.singleOrNull()
        val recognized = if (entry == null && exact.isEmpty() && name.isNotBlank() && id.isEmpty())
            port.readVisibleText().orEmpty().singleOrNull { it.text.trim() == name && it.bounds.size == 4 } else null
        val action = if (entry != null) port.tapRef(entry.ref, screen.snapshotId) else if (recognized != null) {
            val b = recognized.bounds
            val current = safeScreen(port, target)
            check(current.elements == screen.elements) { "文字定位期间页面已变化" }
            port.tapXY((b[0] + b[2]) * (current.width - 1) / 1998, (b[1] + b[3]) * (current.height - 1) / 1998)
        } else {
            val options = screen.elements.filter { it.clickable && !it.editable && !it.secret && it.name.isNotBlank() &&
                IrreversibleDetector.matchName(it.name, screen, it) == null }.take(60)
            check(options.isNotEmpty()) { "当前页面没有可核验的入口" }
            val choice = options[chooseOnPort(port, target, label, name.ifBlank { id }, ready.ifBlank { proofId }, options, screen)]
            val current = safeScreen(port, target)
            val same = current.elements.singleOrNull { it.clickable && it.name == choice.name && it.resourceId == choice.resourceId &&
                it.bounds == choice.bounds }
            checkNotNull(same) { "等待 Mika 期间入口已变化，已停止点按" }
            port.tapRef(same.ref, current.snapshotId)
        }
        check(action is ActionResult.Ok) { "入口点按未成功：$action" }
        repeat(12) {
            delay(220)
            val after = safeScreen(port, target)
            if (arrived(after) && (ready != name || proofId.isNotEmpty() ||
                    after.elements.map { it.name to it.states } != screen.elements.map { it.name to it.states })) return
        }
        error("已点按入口，但未核实到达“${ready.ifBlank { proofId }}”")
    }

    private suspend fun openPath(step: WorkflowStep, port: ScreenPort, target: String, label: String) {
        pending = null
        val instructions = step.field("task_description")
        val contact = step.field("contact_name")
        val explicit = step.raw["route"] as? JsonArray
        val route = when {
            explicit != null -> explicit.map { it.jsonPrimitive.content.trim() }
            target == "com.tencent.mm" -> listOf(contact.ifBlank { error("请先填写本次采集的微信联系人") })
            instructions.contains("「") -> Regex("「([^」]+)」").findAll(instructions)
                .flatMap { it.groupValues[1].split("→").asSequence() }.map { it.trim() }.filter { it.isNotBlank() }.toList()
            instructions.isBlank() -> emptyList()
            instructions.length <= 180 && !instructions.any { it in "。！？" } -> instructions.split("→").map { it.trim() }
            else -> error("请给打开应用步骤填写可核验的入口路径，例如「我的 → 订单」")
        }
        require(route.size <= 12 && route.all { it.length in 1..120 }) { "入口路径无效" }
        readyText = step.field("ready_text").ifBlank { route.lastOrNull().orEmpty() }
        readyId = step.field("ready_id")
        accountWindow = null
        orderDetails = null
        accountPage = if (explicit == null && readyId.isBlank() && step.field("ready_text").isBlank())
            AccountCollectionPage.forRoute(target, route) else null
        accountPage?.let { page ->
            page.open(port, { safeScreen(port, target) }) { entry, choices, observed ->
                chooseOnPort(port, target, label, entry, page.heading, choices, observed)
            }
            accountWindow = (port as? MikaScreenPort)?.lastObservedWindow
            return
        }
        for ((index, entry) in route.withIndex()) {
            val last = index == route.lastIndex
            navigate(port, target, label, entry, "", if (last) readyText else route[index + 1], if (last) readyId else "")
        }
    }

    private suspend fun collect(step: WorkflowStep, port: ScreenPort, target: String, label: String, visual: Boolean): GuiCollection {
        val limit = step.field("page_limit").ifEmpty { "1" }.toIntOrNull()
        val maxPages = if (!visual && target == AccountCollectionPage.ELE.pkg) 7 else GUI_COLLECTION_PAGE_LIMIT
        require(limit != null && limit in 1..maxPages) { "采集页数须为 1 至 $maxPages" }
        val direction = step.field("direction")
        require(direction in setOf("", "up", "down")) { "翻页方向无效" }
        val prompt = step.field("task_description")
        if (visual) require(prompt.length in 1..1000) { "请填写读屏问题" }
        val orderReader = if (!visual && accountPage == AccountCollectionPage.ELE) {
            EleOrderDetails(requireNotNull(port as? MikaScreenPort)) { safeScreen(port, target) }
        } else null
        orderDetails = orderReader
        val accumulator = GuiCollectionAccumulator()
        var limited = false
        val first = GuiCollection(target, label, System.currentTimeMillis(), emptyList(), false)
        for (page in 1..limit) {
            port.waitStable(1400)
            var screen = safeScreen(port, target)
            val account = accountPage.takeUnless { visual }
            var accountLines: List<String> = emptyList()
            var visibleOrders = emptyList<AccountCollectionPage.OrderCard>()
            if (account != null) {
                var onList = false
                for (attempt in 0..3) {
                    if (attempt > 0) { delay(300); screen = safeScreen(port, target) }
                    val text = if (account == AccountCollectionPage.ELE) port.readVisibleText().orEmpty() else emptyList()
                    visibleOrders = orderReader?.cards(text).orEmpty()
                    val lines = account.records(screen, text)
                    val window = (port as? MikaScreenPort)?.lastObservedWindow
                    // The custom-drawn order list can collapse its tab strip while scrolling.
                    // Require a continuously verified window and a card with a price and item count.
                    val continuedOrders = account == AccountCollectionPage.ELE && window != null && window == accountWindow &&
                        visibleOrders.isNotEmpty()
                    onList = account.onList(screen, text) || continuedOrders
                    if (onList) {
                        accountWindow = window
                        accountLines = lines
                        if (accountLines.isNotEmpty() || visibleOrders.isNotEmpty()) break
                    }
                }
                if (!onList && accumulator.entryCount > 0) { limited = true; break }
                check(onList) { "采集目标列表已变化，已停止读取" }
            }
            if (account == null && page == 1 && (readyText.isNotEmpty() || readyId.isNotEmpty())) {
                check(screen.elements.any { matches(it, readyText, readyId) }) { "目标页面标志已消失，已停止采集" }
            }
            val lines = if (account != null) {
                (if (orderReader != null && visibleOrders.isNotEmpty())
                    orderReader.pageRecords(visibleOrders) else accountLines).also {
                    check(it.isNotEmpty() || page > 1) { "已进入目标页，尚未读到可核实的记录或空列表提示" }
                }
            } else if (visual) {
                val image = requireNotNull(port.screenshotPng()) { "当前页面无法截图" }
                ask("$label · 按任务要求读第 ${page} 页，将发送当前页截图和问题：$prompt",
                    "根据给定截图回答问题，仅提取可见内容。", prompt, listOf(image)).lines()
            } else {
                screen.elements.filterNot { it.editable }.map { it.name.trim() }.filter { it.isNotEmpty() }.ifEmpty {
                    check(screen.elements.none { it.editable }) { "页面仅含输入框，无法采集可见正文" }
                    port.readVisibleText().orEmpty().map { it.text.trim() }
                }
            }
            val unique = lines.filter { it.isNotBlank() }.map { it.take(500) }.distinct()
            val overlap = orderReader != null && visibleOrders.isNotEmpty() && page > 1
            if (unique.isEmpty() && page > 1 && account != null && !overlap) { limited = true; break }
            check(unique.isNotEmpty() || overlap) { "在目标页面未读取到可见内容" }
            val added = accumulator.append(first.copy(lines = unique))
            limited = account != null && page == limit
            if (page == limit || !accumulator.hasCapacity || !added && account != AccountCollectionPage.ELE) break
            val scroller = if (account == null) screen.elements.firstOrNull { it.scrollable }
                else screen.elements.filter { it.scrollable && it.bounds[3] - it.bounds[1] > 280 && it.bounds[2] - it.bounds[0] > 550 }
                    .sortedByDescending { it.role == "RecyclerView" }.firstOrNull()
            if (orderReader != null && direction != "up") {
                if (orderReader.advance()) continue
                limited = true
                break
            }
            val outcome = when {
                scroller != null -> port.scroll(scroller.ref, screen.snapshotId, direction != "up")
                else -> break
            }
            if (outcome is ActionResult.Ok && outcome.changed == false && orderReader == null) break
            check(outcome is ActionResult.Ok) { "采集翻页失败：$outcome" }
        }
        return accumulator.record(first, incomplete = limited || !accumulator.hasCapacity || orderReader?.partial == true)
    }

    private suspend fun save(record: GuiCollection): String {
        val approved = suspendCancellableCoroutine<Boolean> { continuation ->
            val dialog = AlertDialog.Builder(activity, com.clawmate.platform.R.style.ClawMateDialog).setTitle("采集预览 · " + record.sourceLabel)
                .setMessage("已读取 ${record.pages} 页" + if (record.partial) " · 仅保存本次核实的可见内容" else "").setView(record.preview(activity))
                .setPositiveButton("保存到本机") { _, _ -> if (continuation.isActive) continuation.resume(true) }
                .setNegativeButton("取消", null).create()
            dialog.setOnDismissListener { if (continuation.isActive) continuation.resume(false) }
            dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            dialog.show()
            continuation.invokeOnCancellation { activity.runOnUiThread { dialog.dismiss() } }
        }
        if (!approved) throw CollectionDeclined()
        GuiCollectionHistory.save(activity, record)
        pending = null
        return record.lines.joinToString("\n")
    }

    suspend fun perform(step: WorkflowStep, port: ScreenPort, target: String, label: String): JsonObject {
        var saved: String? = null
        when (step.action) {
            "collection_open_app" -> openPath(step, port, target, label)
            "collection_navigate" -> {
                accountPage = null
                accountWindow = null
                val name = step.field("target_text")
                val ready = step.field("ready_text")
                val proof = step.field("ready_id")
                pending = null
                navigate(port, target, label, name, step.field("target_id"), ready, proof)
                readyText = ready
                readyId = proof
            }
            "collection_read_pages", "collection_model_read" -> {
                pending = null
                val visual = step.action == "collection_model_read"
                val verbatim = step.field("task_description").trimEnd('。', '.') in
                    setOf("读取当前页文字", "读取页面", "读取可见文字", "采集当前页的可见文字")
                val record = collect(step, port, target, label, visual && !verbatim)
                pending = record
                if (visual) saved = save(record)
            }
            "collection_preview" -> saved = save(checkNotNull(pending) { "没有待保存的本次采集结果" })
            else -> error("未知采集动作")
        }
        return buildJsonObject {
            put("action", step.action)
            pending?.let { put("pages", it.pages); put("lines", it.lines.size) }
            if (step.action == "collection_read_pages") orderDetails?.let {
                put("order_page_cards", JsonArray(it.pageSizes.map(::JsonPrimitive)))
                put("order_details_opened", it.opens)
                put("order_list_returns", it.returns)
                put("order_verified_overlaps", it.verifiedOverlaps)
                put("list_price_reads", JsonArray(it.reasons.map(::JsonPrimitive)))
            }
            saved?.let { put("saved_summary", it); put("response", it) }
        }
    }
}
