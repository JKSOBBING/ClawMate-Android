package com.clawmate.platform.gui

import android.os.SystemClock
import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import kotlinx.coroutines.delay

/** A read-only shortcut for an ordinary keyword search in the installed Weibo client. */
internal object WeiboChatSearch {
    private val search = Regex("""^(?:请\s*)?(?:帮我\s*)?(?:去|在|到|用|打开|进入)微博[里上中内]?\s*(?:帮我)?(?:找一找|搜一搜|搜索|查找|找|搜|查)(?:一下|下)?\s*(?:有关|关于)?(.+?)\s*[。！？]?$""")
    private val suffix = Regex("""(?:的(?:热点内容|热门内容|相关内容|相关微博|热门微博|内容|帖子|微博))$""")
    private val complex = Regex("""[，,；;。\n]|然后|并且|并(?:发|转|关|回|收|点|记|保|把)|再(?:帮|发|转|关|回|点|去|搜)|帮我|总结|分析|转发给|发私信|点赞|评论一下|第[一二三四五六七八九十\d]+条|[一二三四五六七八九十\d]+(?:个|条|篇)|(?:只看|切换|切到|最新|实时|视频标签)""")
    private val bodies = setOf("contentTextView", "blogMaxLineTextView")
    private val whitespace = Regex("""\s+""")

    fun query(goal: String): String? {
        val phrase = search.matchEntire(goal.trim())?.groupValues?.get(1)?.trim() ?: return null
        if (complex.containsMatchIn(phrase)) return null
        val term = suffix.replace(phrase, "").trim().trim('“', '”', '"', '「', '」').trim()
        return term.takeIf { it.length in 1..90 && it.any(Char::isLetterOrDigit) }
    }

    private fun folded(text: String) = text.replace(whitespace, "").lowercase()

    fun verified(screen: ScreenSnapshot, query: String): Boolean {
        if (screen.protected || screen.packageName != WeiboGuiWorkflow.PACKAGE) return false
        val header = screen.elements.filter { it.bounds.size == 4 && it.bounds[1] < 240 }
        val title = header.any {
            it.resourceId?.substringAfterLast('/') == "tv_search_result_title_text" && folded(it.name) == folded(query)
        }
        val tabs = header.filter { it.resourceId?.substringAfterLast('/') == "search_item_text" }
        return title && tabs.filter { "selected" in it.states }.singleOrNull()?.name == "综合"
    }

    // Search cards include in-feed ads and recommendations. Require each part of a
    // mixed-script query in the actual excerpt, including when the parts appear apart.
    private val terms = Regex("""[\p{IsHan}]+|[\p{L}\p{N}&&[^\p{IsHan}]]+""")

    private fun normalized(value: String) =
        java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKC).lowercase()

    internal fun queryTerms(query: String) = terms.findAll(normalized(query)).map { it.value }.distinct().toList()

    private fun isHan(c: Int) = Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN

    // A separate acronym anchors the topic. Allow two non-overlapping Chinese fragments
    // of at least two characters each, in either order, within this one post.
    private fun coversHan(content: String, word: String): Boolean {
        val length = word.codePointCount(0, word.length)
        for (n in 2..length - 2) {
            val split = word.offsetByCodePoints(0, n)
            val left = word.substring(0, split)
            val right = word.substring(split)
            val a = content.indexOf(left)
            val b = content.indexOf(right)
            if (a >= 0 && b >= 0 && (a + left.length <= content.lastIndexOf(right) ||
                        b + right.length <= content.lastIndexOf(left))) return true
        }
        return false
    }

    private fun hasAnchor(required: List<String>) = required.any {
        it.length >= 2 && it.any(Char::isLetter) && !isHan(it.codePointAt(0))
    }

    internal fun matchesExcerpt(post: String, required: List<String>): Boolean {
        val content = normalized(post)
        val anchored = hasAnchor(required)
        return required.isNotEmpty() && required.all { term ->
            term in content || (anchored && isHan(term.codePointAt(0)) && coversHan(content, term))
        }
    }

    internal class Results(val query: String) {
        private val required = queryTerms(query)
        // Only offer partial results for a single trailing Han character. Require an
        // independent anchor and two different bodies that corroborate the literal prefix.
        private val shorter = normalized(query).takeIf { text ->
            val last = terms.findAll(text).lastOrNull()?.value.orEmpty()
            hasAnchor(required) && last.codePointCount(0, last.length) >= 5 &&
                text.endsWith(last) && isHan(last.codePointAt(0))
        }?.let { it.substring(0, it.offsetByCodePoints(it.length, -1)) }
        private val partialTerms = shorter?.let(::queryTerms).orEmpty()
        private val inspected = mutableSetOf<String>()
        val inspectedCount get() = inspected.size
        private val matches = mutableListOf<String>()
        private val partial = mutableListOf<String>()
        private var corroborating = 0
        private var showPartial = false
        val partialCount get() = if (showPartial) partial.size else 0
        val count get() = matches.size + partialCount

        fun addPage(posts: List<String>) {
            for (post in posts) {
                if (!inspected.add(folded(post)) || count >= 24) continue
                if (matchesExcerpt(post, required)) {
                    matches.add(post)
                } else if ((matches.isEmpty() || showPartial) && partial.size < 24 &&
                    matchesExcerpt(post, partialTerms)) {
                    partial.add(post)
                    if (partialTerms.all(normalized(post)::contains)) corroborating++
                }
            }
            if (matches.isEmpty() && corroborating >= 2) showPartial = true
        }

        fun evidence() = buildString {
            append("微博 · ").append(query)
            for ((i, post) in matches.withIndex()) append("\n\n${i + 1}. $post")
            if (showPartial) {
                val ending = query.substring(query.offsetByCodePoints(query.length, -1))
                append("\n\n部分匹配 · 正文可核对「$shorter」，原搜索词的末字「$ending」未核实。")
                for ((i, post) in partial.withIndex()) append("\n\n${matches.size + i + 1}. $post")
            }
        }
    }

    fun posts(screen: ScreenSnapshot): List<String> = screen.elements.filter {
        it.resourceId?.substringAfterLast('/') in bodies && !it.editable && !it.secret
    }.map { it.name.replace(whitespace, " ").trim() }.filter { it.length >= 8 }.distinctBy(::folded)

    suspend fun read(query: String, port: MikaScreenPort, progress: (String) -> Unit, evidence: (String) -> Unit): String {
        val results = Results(query)
        val deadline = SystemClock.elapsedRealtime() + 12_000
        progress("核对微博搜索词「$query」")
        var frame = port.snapshot()
        while (!verified(frame, query) || posts(frame).isEmpty()) {
            if (SystemClock.elapsedRealtime() >= deadline) {
                check(verified(frame, query)) { "未核实微博结果页的搜索词「$query」，已停止读取。" }
                return "微博已显示「$query」的搜索结果，此页没有可核实的公开博文正文。"
            }
            delay(200)
            frame = port.snapshot()
        }
        var repeats = 0
        var pages = 0
        for (page in 1..8) {
            if (!verified(frame, query)) break
            pages++
            val previous = results.count
            val beforePage = results.inspectedCount
            results.addPage(posts(frame))
            repeats = if (results.inspectedCount == beforePage) repeats + 1 else 0
            if (results.count > previous) evidence(results.evidence())
            progress("微博「$query」· 第 $page 屏，已读 ${results.count} 条博文")
            if (page == 8 || repeats >= 2 || results.count >= 24) break
            val x = frame.width / 2
            val result = port.workflowSwipe(x, frame.height * 80 / 100, x, frame.height * 32 / 100)
            if (result !is ActionResult.Ok) break
            frame = port.snapshot()
        }
        return when {
            results.count == 0 -> "已核对微博「$query」综合搜索结果 $pages 屏，检查了 ${results.inspectedCount} 条不同正文，未找到可核实的匹配博文。"
            results.partialCount > 0 -> "已核对微博「$query」综合搜索结果 $pages 屏，保存 ${results.count} 条摘录，其中 ${results.partialCount} 条为部分匹配。"
            else -> "已读取微博「$query」综合搜索结果，共 $pages 屏、${results.count} 条正文含检索词的博文。下方是去重后的正文摘录。"
        }
    }
}

