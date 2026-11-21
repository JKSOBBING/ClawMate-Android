package com.clawmate.platform.gui

import android.app.Activity
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.clawmate.platform.display.WirelessDisplayPairing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/** Android window port for the shared WeChat collector. All operations belong to one approved run. */
class WechatCollectionDevice(private val activity: Activity) {
    private var owner = ""
    private var request: GuiControlRequest? = null
    private var observedWindow = -1
    private var observedTree = ""

    fun reserve(token: String) {
        GuiControlEntry.foreground(activity)
        require(token.matches(Regex("wechat-[0-9]+-[0-9]+")))
        check(owner.isEmpty()) { "微信采集正在进行" }
        owner = token
    }

    suspend fun step(token: String, json: String): String = withContext(Dispatchers.Main.immediate) {
        check(token == owner && token.isNotEmpty()) { "微信采集已停止" }
        require(json.length <= 4096)
        GuiControlEntry.foreground(activity)
        check(Build.VERSION.SDK_INT >= 34) { "手机微信采集需要 Android 14 或更高版本" }
        val service = checkNotNull(GuiControlService.connected) { "请先在系统设置开启 ClawMate 界面控制" }
        val args = JSONObject(json)
        val op = args.getString("action")
        if (op == "open") {
            check(request == null) { "微信窗口已打开" }
            MikaAppAccess.requireAllowed(activity, PACKAGE)
            val launch = checkNotNull(activity.packageManager.getLaunchIntentForPackage(PACKAGE)) { "未找到可打开的微信" }
            WirelessDisplayPairing.prepareForTask(activity)?.let { error(it) }
            check(token == owner) { "微信采集已停止" }
            GuiControlEntry.foreground(activity)
            MikaAppAccess.requireAllowed(activity, PACKAGE)
            val selected = GuiControlRequest("微信", launch, GuiControlTask.Wechat)
            request = selected
            try {
                service.startSession(selected, true) { GuiControlEntry.foreground(activity) }
                repeat(60) {
                    check(owner == token) { "微信采集已停止" }
                    if (runCatching { service.wechatWindow(selected) }.isSuccess) return@withContext ""
                    delay(150)
                }
                error("未找到本次授权的微信窗口，请打开微信聊天首页后重试")
            } catch (failure: Throwable) {
                stop(token)
                throw failure
            }
        }
        val selected = checkNotNull(request)
        val window = service.wechatWindow(selected)
        val root = checkNotNull(window.root) { "微信页面暂时不可读" }
        val bounds = Rect().also(window::getBoundsInScreen)
        val controls = mutableListOf<Control>()
        var count = 0
        var chars = 0
        fun read(node: AccessibilityNodeInfo, row: Boolean, depth: Int): JSONObject? {
            check(depth <= 80 && ++count <= 12000) { "微信页面过大，请缩小范围" }
            if (!node.isVisibleToUser || node.isPassword || node.isAccessibilityDataSensitive) return null
            check(node.packageName?.toString() == PACKAGE) { "目标已离开微信窗口" }
            val box = Rect().also(node::getBoundsInScreen)
            if (box.isEmpty) return null
            val cls = node.className?.toString().orEmpty()
            val label = (node.text ?: node.contentDescription)?.toString()?.take(16000).orEmpty()
            chars += label.length
            check(chars <= 250000) { "微信页面文本超出读取上限" }
            val type = when {
                node.isEditable -> "TextInput"
                cls.endsWith("ListView") || cls.endsWith("RecyclerView") || node.collectionInfo != null && !row -> "List"
                row -> "ListItem"
                cls.endsWith("ImageView") || cls.endsWith("ImageButton") -> "Image"
                label.isNotEmpty() -> "Text"
                else -> "Group"
            }
            val rect = "[${box.left},${box.top}][${box.right},${box.bottom}]"
            fun attributes(kind: String, text: String) = JSONObject().put("type", kind).put("text", text)
                .put("bounds", rect).put("bundleName", PACKAGE)
            val children = JSONArray()
            if (type == "ListItem" || type == "TextInput" || label == "搜索") controls.add(Control(type, box, node, label))
            if (type == "List") controls.add(Control(type, box, node, ""))
            for (i in 0 until node.childCount) node.getChild(i)?.let { read(it, type == "List", depth + 1)?.let(children::put) }
            if (label.isNotBlank() && (type == "Image" && label == "搜索" || type == "ListItem" && children.length() == 0)) {
                children.put(JSONObject().put("attributes", attributes("Text", label)))
            }
            val attrs = attributes(type, if (type == "Text") label else "")
            if (type == "List") attrs.put("scrollable", node.isScrollable)
            return JSONObject().put("attributes", attrs).put("children", children)
        }
        val tree = checkNotNull(read(root, false, 0)) { "微信当前页面不可读取" }.toString()
        if (op == "dump") {
            observedWindow = window.id
            observedTree = tree
            return@withContext tree
        }
        check(window.id == observedWindow && tree == observedTree) { "微信页面在操作前已变化，请重新开始采集" }
        observedTree = ""
        when (op) {
            "click", "click_input" -> {
                val x = args.getInt("x"); val y = args.getInt("y")
                val input = op == "click_input"
                val control = controls.filter { it.bounds.centerX() == x && it.bounds.centerY() == y &&
                    (if (input) it.type == "TextInput" else it.type == "ListItem" || it.label == "搜索") }
                    .minByOrNull { it.bounds.width().toLong() * it.bounds.height() } ?: error("未确认微信会话行或搜索控件")
                service.consumeWechatAction(selected)
                if (input) {
                    val edit = control.node
                    val isSearch = (edit.hintText ?: edit.contentDescription)?.toString().orEmpty().contains("搜索") ||
                        controls.any { it.label == "搜索" && it.bounds.bottom < bounds.centerY() }
                    check(isSearch && control.bounds.bottom < bounds.centerY()) { "只允许在微信搜索框中输入联系人名称" }
                    val name = args.getString("text")
                    require(name.isNotBlank() && name.length <= 256 && !name.any(Char::isISOControl))
                    check(edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, name)
                    })) { "微信搜索框未接受联系人名称" }
                } else {
                    var actionNode: AccessibilityNodeInfo? = control.node
                    while (actionNode != null && !actionNode.isClickable && actionNode.className?.toString()?.endsWith("ListView") != true) {
                        actionNode = actionNode.parent
                    }
                    check(actionNode?.packageName == PACKAGE && actionNode.isClickable &&
                        actionNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "微信未接受点击" }
                }
            }
            "swipe_with_coords" -> {
                val x = args.getInt("start_x"); val y = args.getInt("start_y"); val end = args.getInt("end_y")
                check(x == args.getInt("end_x") && controls.any { it.type == "List" && it.bounds.contains(x,y) && it.bounds.contains(x,end) }) {
                    "微信列表位置已变化"
                }
                val ms = (1000L * abs(end-y) / args.getInt("swipe_speed").coerceIn(200, 20000)).coerceIn(100, 1500)
                check(service.wechatSwipe(selected, window.id, bounds, Path().apply { moveTo(x.toFloat(), y.toFloat()); lineTo(x.toFloat(), end.toFloat()) }, ms)) { "微信列表滑动未完成" }
            }
            "keyevent" -> {
                check(args.getString("key") == "BACK")
                service.consumeWechatAction(selected)
                check(service.wechatBack(selected)) { "微信返回失败" }
            }
            else -> error("不支持的微信采集操作")
        }
        ""
    }

    /** Called on the main thread, including host shutdown. A late stop cannot close a newer run. */
    fun stop(token: String) {
        if (token != owner) return
        request?.let { GuiControlService.connected?.stopSession(it, "微信采集已结束") }
        owner = ""; request = null; observedTree = ""; observedWindow = -1
    }

    fun close() = stop(owner)
    private data class Control(val type: String, val bounds: Rect, val node: AccessibilityNodeInfo, val label: String)
    private companion object { const val PACKAGE = "com.tencent.mm" }
}
