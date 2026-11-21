package com.clawmate.platform.gui

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.UiElement
import com.wangzan101.mika.core.uistate.UiStates
import com.wangzan101.mika.core.uistate.normalize

internal class MikaElement(val node: AccessibilityNodeInfo, val description: UiElement)

internal class MikaFrame(val screen: ScreenSnapshot, val window: Int, val nodes: List<MikaElement>)

/**
 * 读一屏 Mika 界面：只读目标包名、可见、非密码/敏感节点，元素坐标相对窗口。
 * 手机任务端口与任务页的本机采集共用这一份实现，避免两套读屏口径。
 */
internal fun readMikaFrame(root: AccessibilityNodeInfo, bounds: Rect, target: String, snapshotId: String): MikaFrame {
    val elements = ArrayList<MikaElement>()
    var remaining = 360
    var protected = false
    val source = Rect()

    fun visit(node: AccessibilityNodeInfo, depth: Int) {
        if (depth > 32 || remaining-- <= 0 || node.packageName?.toString() != target) return
        node.getBoundsInScreen(source)
        val visible = node.isVisibleToUser && Rect.intersects(bounds, source)
        if (node.isPassword || node.isAccessibilityDataSensitive) {
            if (visible) protected = true
            return
        }
        if (visible && elements.size < 120) {
            val editable = node.isEditable
            val name = (if (editable) node.hintText ?: node.contentDescription else
                node.text?.takeIf { it.isNotBlank() } ?: node.contentDescription)?.toString().orEmpty().take(280)
            val element = UiElement(
                ref = "@e${elements.size + 1}",
                role = node.className?.toString()?.substringAfterLast('.').orEmpty(),
                name = name,
                centerX = source.centerX() - bounds.left,
                centerY = source.centerY() - bounds.top,
                bounds = listOf(normalize(source.left - bounds.left, bounds.width()),
                    normalize(source.top - bounds.top, bounds.height()),
                    normalize(source.right - bounds.left, bounds.width()),
                    normalize(source.bottom - bounds.top, bounds.height())),
                resourceId = node.viewIdResourceName,
                clickable = node.isEnabled && node.isClickable,
                editable = node.isEnabled && editable,
                scrollable = node.isEnabled && node.isScrollable,
                hint = editable,
                focused = node.isFocused,
                placeholder = node.hintText?.toString()?.take(100),
                searchField = editable && (node.viewIdResourceName?.substringAfterLast('/')?.contains("search", true) == true ||
                    name.contains("搜索") || name.contains("search", true)),
                states = buildSet {
                    if (node.isChecked) add(UiStates.CHECKED)
                    if (node.isSelected) add(UiStates.SELECTED)
                    if (!node.isEnabled) add(UiStates.DISABLED)
                },
            )
            if (element.meaningful) elements += MikaElement(node, element)
        }
        for (index in 0 until node.childCount) {
            if (remaining <= 0) break
            node.getChild(index)?.let { visit(it, depth + 1) }
        }
    }
    visit(root, 0)
    return MikaFrame(
        ScreenSnapshot(snapshotId, target, elements.map { it.description }, bounds.width(), bounds.height(), protected = protected),
        root.windowId,
        elements,
    )
}
