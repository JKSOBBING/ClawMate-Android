package com.clawmate.app.gallery.background

/**
 * Abstraction over the media library for the background scanner, mirroring what
 * `GalleryAutoScanner.ets` reads from `photoAccessHelper` on HarmonyOS.
 *
 * Production uses a [GalleryMediaSource] built on `MediaStore` when the runtime
 * photo-access grant is held (see
 * [com.clawmate.app.gallery.background.AndroidGalleryMediaSource]). Tests use a
 * fake so the coordinator runs device-free.
 *
 * [GalleryPhotoInput] mirrors the Harmony `GalleryPhotoInput`: a content URI and
 * optional stable id / date / coordinates. Nothing here reads pixels; it only
 * yields light metadata the coordinator stores deterministically via the
 * existing Room repository.
 */
interface GalleryMediaSource {
    /**
     * Request the newest [limit] image inputs, ordered most-recent first.
     * [limit] is already clamped by [GallerySyncPolicy.clampScanLimit]; an
     * implementation MUST NOT scan more than the requested count.
     */
    suspend fun scanNewest(limit: Int): List<GalleryPhotoInput>
}

/** Light translated-from-scan input (port of Harmony `GalleryPhotoInput`). */
data class GalleryPhotoInput(
    val uri: String,
    val id: String,
    val date: String,
    val latitude: Double?,
    val longitude: Double?,
)
