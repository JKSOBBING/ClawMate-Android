package com.clawmate.app.gallery.model

/**
 * Gallery / collection domain models.
 *
 * Ported from the HarmonyOS source of truth (read-only reference):
 *   entry/src/main/ets/utils/gallery/GalleryTypes.ets
 *   entry/src/main/ets/datamanager/CollectionGroupingService.ets
 *
 * The storage format mirrors docs/gallery/storage-format.md so that items,
 * grouping and searchable metadata have a stable, deterministic shape that can
 * later round-trip to the JSON layout used on HarmonyOS.
 */

/** EXIF metadata captured from a picked photo (subset of the HarmonyOS type). */
data class GalleryExifMetadata(
    val dateTimeOriginal: String? = null,
    val deviceMake: String? = null,
    val deviceModel: String? = null,
    val imageWidth: Int? = null,
    val imageHeight: Int? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
)

/**
 * A single picked / imported photo along with its (possibly incomplete)
 * analysis metadata. Mirrors `GalleryAnalysisResult` and the `items/<date>/<id>.json`
 * item file. No field is ever fabricated.
 */
data class GalleryPhotoItem(
    val id: String,
    val uri: String,
    val date: String,
    val title: String,
    val category: String,
    val summary: String,
    val details: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val ocrText: String = "",
    val rawModelText: String = "",
    val keywords: List<String> = emptyList(),
    val latitude: Double? = null,
    val longitude: Double? = null,
    val exif: GalleryExifMetadata? = null,
    val createdAt: Long,
    /** Resolved reverse-geocoded place (only set after a real lookup succeeds). */
    val address: String = "",
    /** Which provider resolved [address] ("geocoder" vs "" for none). */
    val locationProvider: String = "",
    /** Deterministic analysis lifecycle state (never fabricated). */
    val analysisState: String = ANALYSIS_STATE_NONE,
) {
    companion object {
        const val ANALYSIS_STATE_NONE = "none"
        const val ANALYSIS_STATE_UNSUPPORTED = "unsupported"

        /** A user-triggered on-device OCR action completed (real text or empty). */
        const val ANALYSIS_STATE_ANALYZED = "analyzed"

        /** A user-confirmed cloud vision result was explicitly saved. */
        const val ANALYSIS_STATE_CLOUD_ANALYZED = "cloud_analyzed"
    }
}

/** A collection group produced by [GroupingService] over a set of photo items. */
data class CollectionGroup(
    val groupId: String,
    val title: String,
    val source: String,
    val time: String,
    val meta: String,
    val status: String,
    val domain: String,
    val eventIds: List<String>,
)

/** A search hit — the item plus its deterministic score and human reason. */
data class GallerySearchHit(
    val item: GalleryPhotoItem,
    val score: Int,
    val reason: String,
)

/**
 * Photo stability helpers. `id` is derived deterministically from the content
 * URI so re-picking the same photo maps to the same stored item. Mirrors the
 * HarmonyOS "stable image id" priority (asset id first, then a reproducible
 * uri-based id).
 */
object GalleryIds {
    /**
     * Builds a stable id from a content URI. Kept pure (no Android deps) so it
     * is unit-testable. If the URI already has a final path segment we hash a
     * normalised form; otherwise we use a structural hash.
     */
    fun stableIdForUri(uri: String): String {
        val trimmed = uri.trim()
        if (trimmed.isEmpty()) return ""
        // Use the last non-empty path segment if it looks like a stable id.
        val segs = trimmed.split('/').filter { it.isNotEmpty() }
        return if (segs.isNotEmpty() && !suffixLikelyTemporary(segs.last())) {
            "gx_" + sanitizeId(segs.last())
        } else {
            "gx_" + Integer.toHexString(trimmed.hashCode())
        }
    }

    private fun suffixLikelyTemporary(last: String): Boolean =
        last.length < 3 || last.contains(".") && last.length < 6

    private fun sanitizeId(value: String): String = value.take(96)
        .map { if (it.isLetterOrDigit() || it == '_' || it == '-') it else '_' }
        .joinToString("")
}
