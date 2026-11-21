// Adapted from wangzan101/Mika ScreenReader.kt at 9fd0be4 (Apache-2.0). See third_party/mika/LICENSE.
package com.clawmate.platform.gui

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.wangzan101.mika.core.replay.RouteAnchors
import com.wangzan101.mika.core.uistate.ElementLocator
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.UiElement
import com.wangzan101.mika.core.uistate.UiStates
import com.wangzan101.mika.core.uistate.UiTreeNode
import com.wangzan101.mika.core.uistate.UiWindowTree
import com.wangzan101.mika.core.uistate.normalize

internal class MikaScreenFrame(val screen: ScreenSnapshot, val snapshot: NodeSnapshot) {
    val window: Int get() = snapshot.window
    val nodes: List<NodeEntry> get() = snapshot.entries
}

/** Read the approved content window in the same coordinate space as its screenshots and gestures. */
internal object MikaScreenReader {
    private const val MIN_SIZE = 5
    private const val MAX_DEPTH = 80
    private const val AGGREGATE_MAX = 60

    fun read(window: AccessibilityWindowInfo, root: AccessibilityNodeInfo, bounds: Rect,
        target: String, snapshotId: String, includeViewIds: Boolean = false,
    ): MikaScreenFrame {
        val width = bounds.width()
        val height = bounds.height()
        var remaining = 1_500
        fun canRead(node: AccessibilityNodeInfo) = node.packageName?.toString() == target &&
            !node.isPassword && !node.isAccessibilityDataSensitive
        val entries = mutableListOf<NodeEntry>()
        val seen = HashSet<String>()
        var protected = false
        var lastLabel: String? = null

        fun visit(n: AccessibilityNodeInfo, depth: Int, inList: Boolean): List<UiTreeNode> {
            if (depth > MAX_DEPTH || remaining-- <= 0 || n.packageName?.toString() != target) return emptyList()
            val rect = Rect().also(n::getBoundsInScreen)
            val visible = n.isVisibleToUser && Rect.intersects(bounds, rect)
            if (!canRead(n)) {
                if (visible) protected = true
                return emptyList()
            }
            val editable = visible && canSetText(n)
            var ref: String? = null
            var clippedBounds = listOf(0, 0, 0, 0)
            if (visible) {
                rect.offset(-bounds.left, -bounds.top)
                clip(rect, width, height)
                val own = ownLabel(n)
                clippedBounds = listOf(normalize(rect.left, width), normalize(rect.top, height),
                    normalize(rect.right, width), normalize(rect.bottom, height))
                val usable = rect.width() >= MIN_SIZE && rect.height() >= MIN_SIZE
                val interactive = n.isClickable || editable || n.isScrollable
                if (usable && (own != null || interactive)) {
                    val label = (own ?: if (n.isClickable) descendantLabel(n) { child ->
                        canRead(child) && Rect.intersects(bounds, Rect().also(child::getBoundsInScreen))
                    } else "").take(280)
                    val role = roleOf(n)
                    if (seen.add("$role|$label|${rect.flattenToString()}")) {
                        val id = "@e${entries.size + 1}"
                        ref = id
                        val rid = n.viewIdResourceName
                        val element = UiElement(
                            ref = id, role = role, name = label,
                            centerX = rect.centerX(), centerY = rect.centerY(),
                            bounds = listOf(
                                normalize(rect.left, width), normalize(rect.top, height),
                                normalize(rect.right, width), normalize(rect.bottom, height),
                            ),
                            // Replay omits volatile names (e.g. digits). Keep the view ID when a
                            // clickable control would otherwise lose its identity on the next run.
                            resourceId = if (includeViewIds) rid else if (editable || label.isEmpty() || (n.isClickable && !RouteAnchors.stable(label)))
                                readableResourceId(rid) else null,
                            clickable = n.isEnabled && n.isClickable, editable = n.isEnabled && editable,
                            scrollable = n.isEnabled && n.isScrollable,
                            hint = own != null && showingHint(n),
                            focused = n.isFocused,
                            placeholder = if (editable) n.hintText?.toString()?.trim()?.ifEmpty { null } else null,
                            // 安卓的搜索框控件(SearchView 里的输入框)按类名认
                            searchField = editable && (n.className?.toString()?.contains("Search", true) == true ||
                                rid?.substringAfterLast('/')?.contains("search", true) == true ||
                                label.contains("搜索") || label.contains("search", true)),
                            states = uiStates(n),
                        )
                        val locator = ElementLocator(role, label, rid, rect.centerX(), rect.centerY(),
                            lastLabel, element.hint, inList)
                        entries += NodeEntry(element, locator, n)
                        if (label.isNotEmpty()) lastLabel = label
                    }
                }
            }
            val childrenInList = inList || isList(n)
            val children = buildList {
                for (i in 0 until n.childCount) {
                    if (remaining <= 0) break
                    n.getChild(i)?.let { addAll(visit(it, depth + 1, childrenInList)) }
                }
            }
            if (!visible) return children
            return listOf(UiTreeNode(
                role = roleOf(n), bounds = clippedBounds, ref = ref,
                text = n.text?.toString()?.take(280)?.takeUnless { it.isBlank() },
                description = n.contentDescription?.toString()?.take(280)?.takeUnless { it.isBlank() },
                hintText = n.hintText?.toString()?.take(100)?.takeUnless { it.isBlank() },
                resourceId = n.viewIdResourceName,
                clickable = n.isClickable, editable = editable, scrollable = n.isScrollable,
                checkable = n.isCheckable, checked = n.isChecked, selected = n.isSelected,
                enabled = n.isEnabled, focused = n.isFocused,
                children = children,
            ))
        }

        val tree = UiWindowTree(
            id = window.id, type = "application", layer = window.layer,
            title = null,
            packageName = target, active = window.isActive, focused = window.isFocused,
            nodes = visit(root, 0, false),
        )
        return MikaScreenFrame(
            ScreenSnapshot(snapshotId, target, entries.map { it.element }, width, height,
                windows = listOf(tree), protected = protected),
            NodeSnapshot(snapshotId, window.id, entries),
        )
    }

    /** 列表类容器:子项会复用,同一位置可能换成别的条目。 */
    private fun isList(n: AccessibilityNodeInfo): Boolean {
        if (n.collectionInfo != null) return true
        val cls = n.className?.toString() ?: return false
        return cls.endsWith("RecyclerView") || cls.endsWith("ListView") || cls.endsWith("GridView")
    }

    private fun clip(rect: Rect, width: Int, height: Int) {
        if (!rect.intersect(0, 0, width, height)) rect.setEmpty()
    }

    private fun roleOf(n: AccessibilityNodeInfo) = n.className?.toString()?.substringAfterLast('.')?.ifEmpty { null } ?: "View"

    /** 判动作效果用的控件状态(和 [hash] 看的同一批)。 */
    @Suppress("DEPRECATION")
    private fun uiStates(n: AccessibilityNodeInfo): Set<String> = buildSet {
        if (n.isChecked) add(UiStates.CHECKED)
        if (n.isSelected) add(UiStates.SELECTED)
        if (!n.isEnabled) add(UiStates.DISABLED)
    }

    /** 节点自己的名字:文字(提示语也算,输入框靠它说明用途),没有就用内容描述。 */
    private fun ownLabel(n: AccessibilityNodeInfo): String? {
        val text = n.text?.toString()?.trim()
        if (!text.isNullOrEmpty()) return text
        val desc = n.contentDescription?.toString()?.trim()
        if (!desc.isNullOrEmpty()) return desc
        return n.hintText?.toString()?.trim()?.ifEmpty { null }
    }

    /**
     * 节点上显示的是提示语:系统标了 showingHintText,或没有实际文字、名字来自提示语,
     * 或报出的文字就是提示语本身(有的输入框不设那个标记)。
     */
    private fun showingHint(n: AccessibilityNodeInfo): Boolean {
        if (n.isShowingHintText) return true
        val hint = n.hintText?.toString()?.trim()
        if (hint.isNullOrEmpty()) return false
        val text = n.text?.toString()?.trim()
        return if (text.isNullOrEmpty()) n.contentDescription.isNullOrBlank() else text == hint
    }

    /** Some accessibility providers expose the action without setting the editable flag. */
    private fun canSetText(n: AccessibilityNodeInfo): Boolean =
        n.isEditable || n.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }

    /** 没名字的可点容器用子节点的文字当名字。 */
    private fun descendantLabel(n: AccessibilityNodeInfo, canRead: (AccessibilityNodeInfo) -> Boolean): String {
        val parts = mutableListOf<String>()
        var remaining = 80
        fun walk(c: AccessibilityNodeInfo, depth: Int) {
            if (depth > 6 || parts.joinToString(" ").length > AGGREGATE_MAX) return
            for (i in 0 until c.childCount) {
                if (remaining-- <= 0) return
                val child = c.getChild(i) ?: continue
                if (!child.isVisibleToUser || !canRead(child)) continue
                ownLabel(child)?.let { if (it !in parts) parts += it }
                walk(child, depth + 1)
            }
        }
        walk(n, 0)
        val joined = parts.joinToString(" ")
        return if (joined.length > AGGREGATE_MAX) joined.take(AGGREGATE_MAX) + "…" else joined
    }

    /** `com.x:id/cart_icon` → `cart_icon`;混淆过的短名认不出用途,不给。 */
    private fun readableResourceId(id: String?): String? {
        val short = id?.substringAfterLast('/') ?: return null
        return short.takeIf { Regex("^[A-Za-z0-9_]{4,}$").matches(it) && Regex("[A-Za-z]{3}").containsMatchIn(it) }
    }

}
