package com.clawmate.app.chat.ui

/** ChatPageTypes.ets: count UTF-16 characters, including empty newline segments. */
internal fun harmonyComposerLines(text: String): Int =
    text.split('\n').sumOf { maxOf(1, (it.length + 9) / 10) }.coerceIn(1, 4)

internal fun harmonyComposerHeight(text: String): Int = 58 + (harmonyComposerLines(text) - 1) * 22

internal fun harmonyChatGreeting(hour: Int): String = when (hour) {
    in 6..10 -> "早上好"
    in 11..13 -> "中午好"
    in 14..17 -> "下午好"
    in 18..23 -> "晚上好"
    else -> "夜深了"
}

internal data class HarmonyChatPrompt(val title: String, val description: String, val prompt: String)
internal val harmonyChatPrompts = listOf(
    HarmonyChatPrompt("总结上周做过的事", "回顾上周的记录与进展", "帮我总结一下上周我做了哪些事情。"),
    HarmonyChatPrompt("淘宝搜索华为 Mate 80 Pro", "在淘宝查找这款手机", "淘宝上搜索华为Mate 80 Pro手机"),
    HarmonyChatPrompt("手机碰一碰，找到同频爱好", "发现共同兴趣与话题", "手机碰一碰，找到同频爱好"),
)
