package com.clawmate.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Phase 22 — the live Android platform seam for the safe semantic executor.
 *
 * This is the *only* type in the app that touches `AccessibilityNodeInfo` /
 * `performGlobalAction` for the Phase 22 executor boundary. It is constructed by
 * the real [AccessibilityAutoAgentService] with the connected service instance
 * and is **never invoked directly by any UI**: it is reached only through
 * [ControlledSafeAccessibilityDispatcher] (whose gate checks opt-in, service
 * presence and the foreground package before every action).
 *
 * Safety:
 *  - [perform] accepts only an already-allowlisted, bounded
 *    [SafeAccessibilityAction]; it never accepts raw coordinates, key codes,
 *    commands or unbounded text.
 *  - Nodes are resolved by the *semantic* text / content-description the action
 *    carries and are capacity-bounded (a node budget), so traversal cannot run
 *    away.
 *  - `performGlobalAction` is used only for the framework-sanctioned
 *    GLOBAL_ACTION_BACK / GLOBAL_ACTION_HOME (no arbitrary keys).
 *  - No gesture injection, no hidden input, no screenshots, no shell.
 *
 * Because these are Android framework APIs, this class is intentionally *not*
 * exercised by the device-free unit tests; it is a thin, defensive seam whose
 * mapping (which framework action maps to which semantic action) is mirrored by
 * the pure [SafeAccessibilityActionContract] / dispatcher covered by the tests.
 */
class AndroidSafeAccessibilityExecutor(
    private val service: AccessibilityService,
) : SafeAccessibilityActionExecutor {

    /** Bounded node budget so a degenerate/cyclic window cannot hang traversal. */
    private val nodeBudget: Int = 300

    override fun perform(action: SafeAccessibilityAction): SafeActionPerformResult {
        return try {
            when (action.id) {
                SafeAccessibilityActionId.CLICK_TEXT -> {
                    val text = action.targetText ?: return SafeActionPerformResult.Failed(
                        "click_text 缺少 text 目标。未执行任何操作。",
                    )
                    clickMatchingNode(text, byContentDescription = false)
                }

                SafeAccessibilityActionId.CLICK_CONTENT_DESCRIPTION -> {
                    val desc = action.targetContentDescription
                        ?: return SafeActionPerformResult.Failed(
                            "click_content_description 缺少 content_description 目标。未执行任何操作。",
                        )
                    clickMatchingNode(desc, byContentDescription = true)
                }

                SafeAccessibilityActionId.SCROLL_FORWARD -> scroll(direction = "forward")
                SafeAccessibilityActionId.SCROLL_BACKWARD -> scroll(direction = "backward")
                SafeAccessibilityActionId.BACK -> {
                    // Semantic back within the allowlisted foreground app — a safe
                    // framework global back action (no raw key events).
                    if (service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)) {
                        SafeActionPerformResult.Performed("已执行返回（全局返回动作）")
                    } else {
                        SafeActionPerformResult.Failed("辅助功能服务拒绝执行返回动作。")
                    }
                }

                SafeAccessibilityActionId.GLOBAL_BACK -> {
                    if (service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)) {
                        SafeActionPerformResult.Performed("已执行全局返回（GLOBAL_ACTION_BACK）")
                    } else {
                        SafeActionPerformResult.Failed("辅助功能服务拒绝执行全局返回。")
                    }
                }

                SafeAccessibilityActionId.GLOBAL_HOME -> {
                    if (service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)) {
                        SafeActionPerformResult.Performed("已执行回到桌面（GLOBAL_ACTION_HOME）")
                    } else {
                        SafeActionPerformResult.Failed("辅助功能服务拒绝执行回到桌面。")
                    }
                }
            }
        } catch (e: Throwable) {
            SafeActionPerformResult.Failed("执行安全动作时发生异常：${e.javaClass.simpleName}。未执行任何操作。")
        }
    }

    private fun clickMatchingNode(text: String, byContentDescription: Boolean): SafeActionPerformResult {
        val root = service.rootInActiveWindow
            ?: return SafeActionPerformResult.Unsupported("当前没有可读取的活动窗口节点。未执行任何操作。")
        val node = findClickableNode(root, text, byContentDescription)
            ?: return SafeActionPerformResult.Unsupported(
                "在当前活动窗口未找到匹配的${if (byContentDescription) "内容描述" else "文本"}[$text]可点击节点。未执行任何操作。",
            )
        val clicked = try {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } finally {
            root.recycle()
            node.recycle()
        }
        return if (clicked) {
            SafeActionPerformResult.Performed(
                "已点击${if (byContentDescription) "内容描述" else "文本"}节点[$text]",
            )
        } else {
            SafeActionPerformResult.Failed("辅助功能服务拒绝点击节点[$text]。")
        }
    }

    private fun scroll(direction: String): SafeActionPerformResult {
        val root = service.rootInActiveWindow
            ?: return SafeActionPerformResult.Unsupported("当前没有可读取的活动窗口节点。未执行任何操作。")
        val scrollAction =
            if (direction == "forward") AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        val scroller = findScrollableNode(root)
            ?: run {
                root.recycle()
                return SafeActionPerformResult.Unsupported(
                    "在当前活动窗口未找到可滚动节点。未执行任何操作。",
                )
            }
        val scrolled = try {
            scroller.performAction(scrollAction)
        } finally {
            scroller.recycle()
        }
        root.recycle()
        return if (scrolled) {
            SafeActionPerformResult.Performed("已执行${if (direction == "forward") "向前" else "向后"}滚动")
        } else {
            SafeActionPerformResult.Failed("辅助功能服务拒绝执行${if (direction == "forward") "向前" else "向后"}滚动。")
        }
    }

    // ------------------------------------------------------------------
    // Bounded semantic node resolution
    // ------------------------------------------------------------------

    private fun findClickableNode(
        root: AccessibilityNodeInfo,
        text: String,
        byContentDescription: Boolean,
    ): AccessibilityNodeInfo? {
        val budget = NodeBudget(nodeBudget)
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (!budget.consume()) break
            val matches = if (byContentDescription) {
                node.contentDescription?.toString() == text
            } else {
                node.text?.toString() == text
            }
            if (matches && node.isClickable) {
                return node
            }
            val childCount = node.childCount.coerceIn(0, 64)
            for (i in 0 until childCount) {
                val child = node.getChild(i) ?: continue
                queue.add(child)
            }
            node.recycle()
        }
        // Recycle what we did not return.
        queue.forEach { it.recycle() }
        return null
    }

    private fun findScrollableNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val budget = NodeBudget(nodeBudget)
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (!budget.consume()) break
            if (node.isScrollable) return node
            val childCount = node.childCount.coerceIn(0, 64)
            for (i in 0 until childCount) {
                val child = node.getChild(i) ?: continue
                queue.add(child)
            }
            node.recycle()
        }
        queue.forEach { it.recycle() }
        return null
    }

    /** Small bound counter so traversal cannot exceed [nodeBudget] visits. */
    private class NodeBudget(private val limit: Int) {
        private var used = 0
        fun consume(): Boolean = if (used < limit) { used++; true } else false
    }
}
