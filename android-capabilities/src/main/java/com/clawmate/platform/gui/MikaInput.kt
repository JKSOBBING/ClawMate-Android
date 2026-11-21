package com.clawmate.platform.gui

/**
 * 手机任务里允许打进输入框的文字。
 *
 * 与 Mika TextTools 对齐：支持模型按任务生成的搜索词、计算结果和多行正文。
 * 授权窗口与提交风险由会话及原版动作执行器检查，不用原话子串判定任务授权。
 */
internal object MikaInput {
    const val MAX_CHARS = 10_000

    fun allowed(@Suppress("UNUSED_PARAMETER") goal: String, text: String): Boolean =
        text.length <= MAX_CHARS && text.none { it.isISOControl() && it !in "\n\r\t" }
}
