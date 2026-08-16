package com.clawmate.app.gallery.ui

import com.clawmate.app.gallery.geocode.ReverseGeocodeError
import com.clawmate.app.gallery.model.GalleryPhotoItem

/**
 * Pure, deterministic label/presentation helpers for the gallery detail panel.
 * Kept standalone (no Android deps) so the logic is unit-testable and never
 * fabricates values: a state maps to a fixed label, and an unknown state maps
 * to an honest fallback.
 */
object GalleryDetailLabels {

    /** Stable, honest label for an analysis lifecycle state. */
    fun analysisStateLabel(state: String): String = when (state) {
        GalleryPhotoItem.ANALYSIS_STATE_NONE ->
            "未分析（尚未对图片执行本地 OCR）"
        GalleryPhotoItem.ANALYSIS_STATE_UNSUPPORTED ->
            "不支持（当前平台未提供此分析能力）"
        GalleryPhotoItem.ANALYSIS_STATE_ANALYZED -> "已分析（本地 OCR）"
        "analyzing" -> "分析中"
        else -> "状态未知"
    }

    /** Whether an analysis state means real analysis was produced. */
    fun isAnalyzed(state: String): Boolean = state == GalleryPhotoItem.ANALYSIS_STATE_ANALYZED

    /** Human text for a typed reverse-geocode failure (no location claimed). */
    fun locationFailureText(error: ReverseGeocodeError): String = error.message

    /** Joined tags shown as plain text (空 when none). */
    fun tagsText(tags: List<String>): String = tags.joinToString("、")

    /**
     * Title fallback for an item: explicit title, else the last URI segment,
     * else the stable id. Never returns an empty headline for the detail panel.
     */
    fun displayTitle(item: GalleryPhotoItem): String =
        if (item.title.isBlank()) item.id else item.title

    /** Content-URI line shown in the detail (raw, untrusted provider string). */
    fun contentUriLine(uri: String): String = if (uri.isBlank()) "（无内容 URI）" else uri

    /** EXIF capture-time line when available, else honest "—". */
    fun captureTimeLine(item: GalleryPhotoItem): String =
        item.exif?.dateTimeOriginal?.ifBlank { null } ?: "（无拍摄时间）"
}
