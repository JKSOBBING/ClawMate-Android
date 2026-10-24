package com.clawmate.app.gallery.ui

import com.clawmate.app.gallery.model.GalleryPhotoItem
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

internal val harmonyGalleryCategories = listOf("全部", "聊天", "文档", "餐饮", "购物", "出行", "健康", "工作", "其他")
internal data class GalleryFrontendCategory(val index: Int, val label: String, val count: Int)
internal fun harmonyGalleryCategory(value: String): String = value.trim().takeIf { it in harmonyGalleryCategories.drop(1).dropLast(1) } ?: "其他"
internal fun galleryFrontendTime(item: GalleryPhotoItem, zone: ZoneId = ZoneId.systemDefault()): Long =
    runCatching { LocalDate.parse(item.date).atStartOfDay(zone).toInstant().toEpochMilli() }.getOrDefault(item.createdAt)

/** GalleryPage's rolling-day and calendar-year cutoffs, with date→createdAt fallback. */
internal fun galleryFrontendTimeResults(items: List<GalleryPhotoItem>, timeFilter: Int,
    now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault(),
): List<GalleryPhotoItem> {
    val start = when(timeFilter) {
        1 -> now - 7L * 24 * 60 * 60 * 1000
        2 -> now - 30L * 24 * 60 * 60 * 1000
        3 -> Instant.ofEpochMilli(now).atZone(zone).toLocalDate().withDayOfYear(1).atStartOfDay(zone).toInstant().toEpochMilli()
        else -> Long.MIN_VALUE
    }
    return items.filter { galleryFrontendTime(it, zone) >= start }
        .sortedWith(compareByDescending<GalleryPhotoItem> { galleryFrontendTime(it, zone) }.thenByDescending { it.createdAt })
}

internal fun galleryFrontendCategories(items: List<GalleryPhotoItem>): List<GalleryFrontendCategory> {
    val counts = items.groupingBy { harmonyGalleryCategory(it.category) }.eachCount()
    return listOf(GalleryFrontendCategory(0, "全部", items.size)) + harmonyGalleryCategories.drop(1).mapIndexedNotNull { index, label ->
        counts[label]?.takeIf { it > 0 }?.let { GalleryFrontendCategory(index + 1, label, it) }
    }
}

internal fun GalleryPhotoItem.hasCompletedAnalysis() = analysisState == GalleryPhotoItem.ANALYSIS_STATE_ANALYZED ||
    analysisState == GalleryPhotoItem.ANALYSIS_STATE_CLOUD_ANALYZED

internal data class GalleryFrontendTone(val foreground: String, val background: String)
internal fun galleryCategoryTone(category: String): GalleryFrontendTone = when (harmonyGalleryCategory(category)) {
    "餐饮" -> GalleryFrontendTone("#39BFA3", "#EAF8F3")
    "出行" -> GalleryFrontendTone("#4B8BFF", "#EAF1FF")
    "购物" -> GalleryFrontendTone("#FF8A3D", "#FFF1E7")
    "健康" -> GalleryFrontendTone("#765BFF", "#F0ECFF")
    else -> GalleryFrontendTone("#5B68FF", "#EEF3FF")
}
internal fun galleryTagTone(label: String): GalleryFrontendTone {
    val index = label.ifEmpty { "tag" }.fold(0) { sum, char -> (sum + char.code) % 6 }
    return GalleryFrontendTone(listOf("#4B8BFF", "#39BFA3", "#FF8A3D", "#765BFF", "#D14B7A", "#667085")[index],
        listOf("#EAF1FF", "#EAF8F3", "#FFF1E7", "#F0ECFF", "#FCECF3", "#F2F4F7")[index])
}
internal fun galleryMonthBarFraction(count: Int, total: Int): Float =
    if (total <= 0 || count <= 0) 0f else maxOf(6, count * 100 / total).coerceAtMost(100) / 100f
