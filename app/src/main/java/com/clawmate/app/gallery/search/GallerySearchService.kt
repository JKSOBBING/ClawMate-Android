package com.clawmate.app.gallery.search

import com.clawmate.app.gallery.model.GalleryPhotoItem
import com.clawmate.app.gallery.model.GallerySearchHit
import kotlin.math.max

/**
 * Deterministic weighted full-text scoring over photo metadata.
 *
 * Ported from the HarmonyOS source of truth (read-only reference):
 *   entry/src/main/ets/utils/gallery/GallerySearchService.ets
 *
 * Pure Kotlin, no Android dependencies — unit-testable. Weighting mirrors the
 * source: title(10) > tags(9) > category(8) > summary(6) > details(5) >
 * date(4) > OCR(3), with a recency boost by item age.
 */
object GallerySearchService {

    fun search(query: String, items: List<GalleryPhotoItem>, limit: Int = 20): List<GallerySearchHit> {
        val tokens = queryTokens(query)
        if (tokens.isEmpty()) return emptyList()
        val hits = items.mapNotNull { item ->
            val (score, reasons) = scoreItem(tokens, item)
            if (score > 0) GallerySearchHit(item, score, reasonText(reasons)) else null
        }
        val sorted = hits.sortedWith(
            compareByDescending<GallerySearchHit> { it.score }
                .thenByDescending { it.item.createdAt },
        )
        return sorted.take(max(0, limit))
    }

    private fun scoreItem(tokens: List<String>, item: GalleryPhotoItem): Pair<Int, MutableList<String>> {
        val reasons = mutableListOf<String>()
        var score = 0
        score += scoreField(tokens, item.title, 10, "标题", reasons)
        score += scoreField(tokens, item.category, 8, "类别", reasons)
        score += scoreField(tokens, item.tags.joinToString(" "), 9, "标签", reasons)
        score += scoreField(tokens, item.summary, 6, "摘要", reasons)
        score += scoreField(tokens, item.details.joinToString(" "), 5, "细节", reasons)
        score += scoreField(tokens, item.ocrText, 3, "OCR", reasons)
        score += scoreField(tokens, item.date, 4, "日期", reasons)
        if (score > 0) score += recencyBoost(item.createdAt)
        return score to reasons
    }

    private fun scoreField(
        tokens: List<String>,
        value: String,
        weight: Int,
        label: String,
        reasons: MutableList<String>,
    ): Int {
        val text = normalize(value)
        if (text.isEmpty()) return 0
        var matched = 0
        tokens.forEach { token ->
            if (text.contains(token)) matched += 1
        }
        if (matched > 0) {
            reasons.appendUnique("${label}命中")
            return matched * weight
        }
        return 0
    }

    private fun queryTokens(query: String): List<String> {
        val normalized = normalize(query)
        if (normalized.isEmpty()) return emptyList()
        val compact = normalized.replace(Regex("\\s+"), "")
        val candidates = candidateTerms()
        val tokens = mutableListOf<String>()
        candidates.forEach { it ->
            if (compact.contains(it)) tokens.appendUnique(it)
        }
        val raw = normalized.split(" ")
        raw.forEach {
            val token = it.trim()
            if (token.isNotEmpty() && !isStopWord(token)) tokens.appendUnique(token)
        }
        pushChineseBigrams(compact, tokens)
        if (tokens.isEmpty() && normalized.isNotEmpty()) tokens += normalized
        return tokens
    }

    private fun normalize(value: String): String = value
        .lowercase()
        .replace(Regex("[，。！？、；：,.!?;:\\r\\n\\t/\\\\|()\\[\\]{}'\"`~@#\$%^&*_+=<>-]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun isStopWord(value: String): Boolean = value in listOf(
        "我", "的", "了", "吗", "呢", "啊", "找", "一下", "帮我", "有没有", "哪些", "什么", "图片", "截图",
    )

    private fun candidateTerms(): List<String> = listOf(
        "聊天", "待办", "会议", "工作", "项目", "客户", "通知", "截图",
        "账单", "消费", "外卖", "餐饮", "购物", "快递", "售后", "健康",
        "体检", "用药", "运动", "睡眠", "饮食", "医疗", "文档", "合同",
        "笔记", "课程", "作业", "证件", "资料", "发票", "家庭", "朋友",
        "聚会", "亲子", "宠物", "节日", "纪念", "账号", "安全", "配置",
        "报错", "登录", "密码", "订单", "金额", "时间", "地点", "商户",
        "标题", "行程", "交通", "停车",
    )

    private fun pushChineseBigrams(text: String, tokens: MutableList<String>) {
        if (text.length < 2) return
        for (i in 0 until text.length - 1) {
            val token = text.substring(i, i + 2)
            if (!isStopWord(token)) tokens.appendUnique(token)
        }
    }

    private fun reasonText(reasons: List<String>): String =
        if (reasons.isEmpty()) "内容命中" else reasons.take(3).joinToString("、")

    private fun recencyBoost(createdAt: Long): Int {
        if (createdAt <= 0L) return 0
        val age = System.currentTimeMillis() - createdAt
        if (age < 0) return 0
        val day = 24L * 60 * 60 * 1000
        return when {
            age <= 7 * day -> 3
            age <= 30 * day -> 2
            age <= 90 * day -> 1
            else -> 0
        }
    }

    private fun MutableList<String>.appendUnique(value: String) {
        if (value.isNotEmpty() && !contains(value)) add(value)
    }
}
