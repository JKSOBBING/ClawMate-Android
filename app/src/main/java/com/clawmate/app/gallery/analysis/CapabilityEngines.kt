package com.clawmate.app.gallery.analysis

import com.clawmate.app.gallery.model.GalleryPhotoItem

/**
 * OCR / cloud-vision / reverse-geocoding capability boundaries.
 *
 * These HarmonyOS features have no 1:1 Android port shipped in this phase:
 *   - OCR: Harmony CoreVisionKit `textRecognition` -> would need on-device
 *     ML Kit (a separate dependency + model download) — NOT brought in here.
 *   - Cloud vision: Harmony `CloudModelClient` calls a multimodal model
 *     (MiniCPM-V) — the endpoint/keys are not configured and no such client is
 *     wired for the gallery pipeline yet — NOT implemented here.
 *   - Reverse geocoding: Harmony AMap Web Service — requires an AMap key,
 *     which is not configured — NOT implemented here.
 *
 * Instead of silently claiming these work, each capability is behind an
 * explicit interface. Callers receive a typed [CapabilityState] describing
 * NOT_CONFIGURED / UNSUPPORTED and must surface it to the user. No fake OCR /
 * vision text is ever produced.
 */
sealed class CapabilityState {
    /** The capability exists but is not configured (no key / no endpoint). */
    data object NotConfigured : CapabilityState()

    /** The platform cannot support this capability in this build. */
    data object Unsupported : CapabilityState()

    /** The capability is implemented and produced a real result. */
    data class Ready(val description: String) : CapabilityState()
}

/**
 * Port of Harmony `GalleryOcrService` (CoreVisionKit text recognition).
 * On Android this maps to on-device ML Kit Text Recognition, which is not yet
 * vendored. Implementations must return a [CapabilityState] and only ever
 * return real recognized text through their concrete result type.
 */
interface OcrEngine {
    val state: CapabilityState
    fun recognize(photo: GalleryPhotoItem): OcrResult
}

sealed class OcrResult {
    data class Text(val value: String) : OcrResult()
    data object Failed : OcrResult()
    data class Unavailable(val reason: String) : OcrResult()
}

/**
 * Port of the gallery cloud vision analysis step (multimodal model that
 * extracts title/category/summary/tags/details from a photo). The HarmonyOS
 * source calls a cloud model with an API key; that is not configured on
 * Android yet, so the default implementation reports NOT_CONFIGURED.
 */
interface CloudVisionAnalyzer {
    val state: CapabilityState
    fun analyze(photo: GalleryPhotoItem): VisionResult
}

sealed class VisionResult {
    data object NotConfigured : VisionResult()
    data object Unsupported : VisionResult()
    data class Failed(val reason: String) : VisionResult()
}

/**
 * Port of Harmony `GalleryReverseGeocoder` (AMap Web Service). Requires an
 * AMap web key; not configured on Android, so the default reports NOT_CONFIGURED.
 */
interface ReverseGeocoder {
    val state: CapabilityState
    fun reverse(latitude: Double, longitude: Double): ReverseGeocodeResult
}

sealed class ReverseGeocodeResult {
    data object NotConfigured : ReverseGeocodeResult()
    data class Failed(val reason: String) : ReverseGeocodeResult()
}

/** Default unconfigured engine instances (controllable, no fake data). */
object UnconfiguredCapabilities {
    val ocr: OcrEngine = UnconfiguredOcrEngine
    val vision: CloudVisionAnalyzer = UnconfiguredVisionAnalyzer
    val reverseGeocoder: ReverseGeocoder = UnconfiguredReverseGeocoder
}

private object UnconfiguredOcrEngine : OcrEngine {
    override val state: CapabilityState = CapabilityState.NotConfigured
    override fun recognize(photo: GalleryPhotoItem): OcrResult =
        OcrResult.Unavailable("on-device OCR 尚未配置（需接入本地 OCR 引擎）")
}

private object UnconfiguredVisionAnalyzer : CloudVisionAnalyzer {
    override val state: CapabilityState = CapabilityState.NotConfigured
    override fun analyze(photo: GalleryPhotoItem): VisionResult = VisionResult.NotConfigured
}

private object UnconfiguredReverseGeocoder : ReverseGeocoder {
    override val state: CapabilityState = CapabilityState.NotConfigured
    override fun reverse(latitude: Double, longitude: Double): ReverseGeocodeResult =
        ReverseGeocodeResult.NotConfigured
}
