package com.clawmate.app.gallery.analysis

import com.clawmate.app.gallery.geocode.GalleryLocationInfo
import com.clawmate.app.gallery.geocode.ReverseGeocodeError
import com.clawmate.app.gallery.geocode.ReverseGeocodeOutcome
import com.clawmate.app.gallery.model.GalleryPhotoItem

/**
 * OCR / cloud-vision / reverse-geocoding capability boundaries.
 *
 * Phase-9 status (honesty first — a capability is only ever claimed when it
 * really works):
 *   - OCR: Harmony CoreVisionKit `textRecognition` maps on Android to on-device
 *     ML Kit `text-recognition-chinese` (version 16.0.x is resolvable on Google
 *     Maven). It is **not vendored** because the Chinese model is a
 *     download-on-device Play-Services dependency (not bundled in the repo), and
 *     there is no emulator/device here to verify real recognized text — shipping
 *     a wired-but-unverifiable OCR path would risk fabricating output. The
 *     engine stays behind the explicit [OcrEngine] NOT_CONFIGURED boundary.
 *   - Cloud vision (MiniCPM-V multimodal model): requires an endpoint/key that is
 *     not configured and no client is wired — NOT implemented, explicit
 *     NOT_CONFIGURED.
 *   - Reverse geocoding: Harmony uses AMap Web Service (keyed). On Android the
 *     same concept is served by the platform [android.location.Geocoder] with no
 *     key; the Android adapter is wired as an explicit injected capability (see
 *     com.clawmate.app.gallery.geocode). It reports [CapabilityState.Ready] only
 *     when the device has a geocoder and never claims a location on failure.
 *
 * Instead of silently claiming work, each capability is behind an explicit
 * interface. Callers receive a typed [CapabilityState] describing
 * NOT_CONFIGURED / UNSUPPORTED / READY and must surface it to the user. No fake
 * OCR / vision text or location is ever produced.
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
 * Port of Harmony `GalleryReverseGeocoder`. On Android this maps to the
 * platform [android.location.Geocoder] (a local heuristics lookup the device
 * exposes; no web-service key). A provider is always injected explicitly; a
 * caller may inject the default NOT_CONFIGURED engine or the Android adapter
 * ([com.clawmate.app.gallery.geocode.AndroidReverseGeocoder]).
 */
interface ReverseGeocoder {
    val state: CapabilityState
    fun reverse(latitude: Double, longitude: Double): ReverseGeocodeResult
}

sealed class ReverseGeocodeResult {
    /** The provider is not configured (default). */
    data object NotConfigured : ReverseGeocodeResult()

    /** A real place was resolved (never fabricated). */
    data class Resolved(val location: GalleryLocationInfo) : ReverseGeocodeResult()

    /** The platform has no geocoder / the lookup failed for the given reason. */
    data class Failed(val error: ReverseGeocodeError) : ReverseGeocodeResult()
}

/**
 * Direct adapter from the typed [com.clawmate.app.gallery.geocode]
 * ReverseGeocodeOutcome to the capability-boundary [ReverseGeocodeResult].
 */
fun ReverseGeocodeOutcome.asCapabilityResult(): ReverseGeocodeResult = when (this) {
    is ReverseGeocodeOutcome.Resolved -> ReverseGeocodeResult.Resolved(location)
    is ReverseGeocodeOutcome.Failed -> ReverseGeocodeResult.Failed(error)
    ReverseGeocodeOutcome.NotConfigured -> ReverseGeocodeResult.NotConfigured
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
