package com.clawmate.app.gallery.insights

import com.clawmate.app.gallery.model.GalleryPhotoItem
import java.util.Calendar
import java.util.TimeZone

/**
 * Portable Gallery monthly-insights builder.
 *
 * Ported line-for-line in behaviour from the HarmonyOS source of truth
 * (read-only reference):
 *   entry/src/main/ets/utils/gallery/GalleryMonthlyInsights.ets
 *
 * It is pure over [GalleryPhotoItem] (the same data Room persists in
 * `gallery_items`, Phases 3/9/11) and fully deterministic so it can be unit
 * tested without a device.
 *
 * The cloud LLM / embedding are deliberately NOT part of this builder: it only
 * derives local deterministic insights from stored analysis data and never
 * fabricates an insight. The optional AI monthly summary (cloud LLM) is exposed
 * separately as an explicit NOT_CONFIGURED boundary (see
 * [GalleryMonthlyInsightCloudState]).
 */
object GalleryMonthlyInsights {

    /** Maximum representative items kept per monthly insight (matches Harmony 6). */
    const val REPRESENTATIVES_LIMIT = 6

    /** Maximum monthly items fed into [buildLlmInput] (matches Harmony 80). */
    const val LLM_INPUT_LIMIT = 80

    /**
     * Same fixed timezone for fallback year/month derived from [createdAt], so
     * the pure logic is deterministic across devices and test runs. Items that
     * carry a real `date` (primary source) are unaffected.
     */
    private const val FALLBACK_TZ = "UTC"

    /** Build monthly insights over stored photo items (sorted newest month first). */
    fun build(results: List<GalleryPhotoItem>): List<GalleryMonthInsight> {
        if (results.isEmpty()) return emptyList()

        val buckets = LinkedHashMap<String, MutableList<GalleryPhotoItem>>()
        val labels = HashMap<String, String>()
        for (item in results) {
            val bucket = monthBucket(item)
            labels[bucket.first] = bucket.second
            buckets.getOrPut(bucket.first) { mutableListOf() }.add(item)
        }

        val insights = buckets.map { (key, rawItems) ->
            val items = rawItems.sortedWith(
                compareByDescending<GalleryPhotoItem> { itemTime(it) }
                    .thenByDescending { it.createdAt },
            )
            val categoryCounts = tagCounts(items)
            GalleryMonthInsight(
                key = key,
                label = labels.getValue(key),
                totalCount = items.size,
                categoryCounts = categoryCounts,
                representativeItems = items.take(REPRESENTATIVES_LIMIT),
                entriesForLlm = items,
                summary = summary(labels.getValue(key), items.size, categoryCounts),
            )
        }
        // Newest (greatest key) month first, matching Harmony's desc localeCompare.
        return insights.sortedByDescending { it.key }
    }

    /**
     * Build the deterministic text prompt that would be sent to a cloud LLM for
     * a monthly AI summary. Kept pure and portable so the exact input contract
     * round-trips with Harmony; the *call* itself stays NOT_CONFIGURED on
     * Android until a cloud config exists.
     */
    fun buildLlmInput(insight: GalleryMonthInsight): String {
        val limit = minOf(LLM_INPUT_LIMIT, insight.entriesForLlm.size)
        val lines = mutableListOf<String>()
        lines.add("月份：" + insight.label)
        lines.add("总数：" + insight.totalCount.toString())
        lines.add("标签分布：" + categoryCountText(insight.categoryCounts))
        lines.add("本地初步摘要：" + insight.summary)
        lines.add(
            "图片摘要清单：共 " + insight.totalCount.toString() + " 张，本次提供 " + limit.toString() +
                if (insight.totalCount > limit) " 张代表样本。" else " 张全部样本。",
        )
        for (i in 0 until limit) {
            val item = insight.entriesForLlm[i]
            val timeText = if (item.date.isNotEmpty()) item.date else formatDate(item.createdAt)
            val tagText = displayTags(item).joinToString("、")
            val effectiveTag = if (tagText.isNotEmpty()) tagText
            else if (item.category.isNotEmpty()) item.category else "其他"
            val text = compactText((item.title + " " + item.summary).trim(), 120)
            lines.add("- $timeText｜$effectiveTag｜$text")
        }
        if (limit == 0) lines.add("无")
        return lines.joinToString("\n")
    }

    /** Bucket by `yyyy-MM` from the item's [GalleryPhotoItem.date], else createdAt. */
    fun monthBucket(item: GalleryPhotoItem): Pair<String, String> {
        val dateText = item.date.trim()
        if (dateText.length >= 7) {
            val year = dateText.substring(0, 4)
            val monthText = dateText.substring(5, 7)
            val monthNumber = monthText.toIntOrNull()
            if (year.length == 4 && monthNumber != null && monthNumber in 1..12) {
                return monthToKeyLabel(year, monthText, monthNumber)
            }
        }
        if (item.createdAt > 0) {
            val cal = Calendar.getInstance(TimeZone.getTimeZone(FALLBACK_TZ)).apply {
                timeInMillis = item.createdAt
            }
            val year = cal.get(Calendar.YEAR).toString()
            val monthNumber = cal.get(Calendar.MONTH) + 1
            val monthText = monthNumber.toString().padStart(2, '0')
            return monthToKeyLabel(year, monthText, monthNumber)
        }
        return "0000-00" to "时间未知"
    }

    /** Timestamp used to order items within a month (date first, else createdAt). */
    fun itemTime(item: GalleryPhotoItem): Long {
        val dateText = item.date.trim()
        if (dateText.length >= 10) {
            val parsed = parseDateMs(dateText)
            if (parsed != null) return parsed
        }
        return item.createdAt
    }

    private fun monthToKeyLabel(year: String, monthText: String, monthNumber: Int): Pair<String, String> =
        (year + "-" + monthText) to (year + "年" + monthNumber.toString() + "月")

    private fun parseDateMs(dateText: String): Long? {
        // date is yyyy-MM-dd; parse in the fixed UTC calendar for determinism.
        val y = dateText.substring(0, 4).toIntOrNull() ?: return null
        val m = dateText.substring(5, 7).toIntOrNull() ?: return null
        val d = dateText.substring(8, 10).toIntOrNull() ?: return null
        if (m in 1..12 && d in 1..31) {
            val cal = Calendar.getInstance(TimeZone.getTimeZone(FALLBACK_TZ))
            cal.clear()
            cal.set(y, m - 1, d)
            return cal.timeInMillis
        }
        return null
    }

    private fun tagCounts(items: List<GalleryPhotoItem>): List<GalleryMonthCategoryCount> {
        val counts = LinkedHashMap<String, Int>()
        for (item in items) {
            for (tag in displayTags(item)) {
                counts[tag] = (counts[tag] ?: 0) + 1
            }
        }
        return counts.entries
            .map { GalleryMonthCategoryCount(it.key, it.value) }
            .sortedByDescending { it.count }
    }

    private fun displayTags(item: GalleryPhotoItem): List<String> {
        val tags = mutableListOf<String>()
        if (item.tags.isNotEmpty()) {
            for (tag in item.tags) {
                if (tags.size >= 4) break
                val cleaned = cleanTag(tag)
                if (cleaned.isNotEmpty() && !tags.contains(cleaned)) tags.add(cleaned)
            }
        }
        if (tags.isEmpty()) {
            val fallback = categoryLabel(item.category)
            if (fallback.isNotEmpty()) tags.add(fallback)
        }
        return tags
    }

    private fun cleanTag(value: String): String {
        val tag = value.replace(Regex("[#，。！？、；：,.!?;:\r\n\t]+"), " ")
            .replace(Regex("\\s+"), " ").trim()
        return if (tag.length > 12) tag.substring(0, 12) else tag
    }

    private fun categoryLabel(category: String): String {
        val value = category.trim()
        return if (value == "餐饮" || value == "出行" || value == "购物" || value == "健康" ||
            value == "文档" || value == "聊天" || value == "工作"
        ) value else "其他"
    }

    private fun summary(
        monthLabel: String,
        totalCount: Int,
        categoryCounts: List<GalleryMonthCategoryCount>,
    ): String {
        val topLimit = minOf(3, categoryCounts.size)
        val top = categoryCounts.take(topLimit).joinToString("、") { it.label }
        val leading = if (top.isNotEmpty()) {
            "$monthLabel 共分析 $totalCount 张图片，主要集中在 $top。"
        } else {
            "$monthLabel 共分析 $totalCount 张图片。"
        }
        if (categoryCounts.isNotEmpty() && categoryCounts[0].count >= maxOf(3, Math.floorDiv(totalCount, 2))) {
            return leading + "高频标签比较集中，适合围绕“" + categoryCounts[0].label + "”先整理代表图片。"
        }
        if (categoryCounts.size >= 3) {
            return leading + "标签分布较分散，可以先查看代表图片，再按主题拆分归档。"
        }
        return leading + "可以先查看代表图片，再决定是否按月份归档。"
    }

    private fun categoryCountText(categoryCounts: List<GalleryMonthCategoryCount>): String {
        val parts = categoryCounts.joinToString("、") { it.label + "=" + it.count.toString() }
        return if (parts.isNotEmpty()) parts else "无"
    }

    private fun compactText(text: String, maxLength: Int): String {
        val value = text.replace(Regex("[\r\n\t]+"), " ").replace(Regex("\\s+"), " ").trim()
        return if (value.length <= maxLength) value else value.substring(0, maxLength)
    }

    private fun formatDate(ts: Long): String {
        if (ts <= 0) return ""
        val cal = Calendar.getInstance(TimeZone.getTimeZone(FALLBACK_TZ)).apply { timeInMillis = ts }
        val y = cal.get(Calendar.YEAR)
        val m = (cal.get(Calendar.MONTH) + 1).toString().padStart(2, '0')
        val d = cal.get(Calendar.DAY_OF_MONTH).toString().padStart(2, '0')
        return "$y-$m-$d"
    }
}
