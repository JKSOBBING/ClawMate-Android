package com.clawmate.app.gallery.geocode

/**
 * Deterministic, typed result/error models for reverse geocoding.
 *
 * Port of the HarmonyOS reverse-geocoding concept (see
 * entry/src/main/ets/utils/gallery/GalleryReverseGeocoder.ets and the
 * `GalleryLocationCacheRecord` in GalleryTypes.ets). On Android the transport
 * is the platform [android.location.Geocoder] (no web-service key, no network
 * endpoint). Every error is typed and explicit so a caller can surface it truthfully
 * and never claims a location when the lookup did not actually succeed.
 */
sealed class ReverseGeocodeOutcome {
    /** A real address could not be resolved and no location is claimed. */
    // (kept for the default unconfigured paths / future keyed providers)
    data object NotConfigured : ReverseGeocodeOutcome()

    /** A real, resolved place; only produced from an actual lookup. */
    data class Resolved(val location: GalleryLocationInfo) : ReverseGeocodeOutcome()

    /** The lookup ran but failed (typed error explains why). */
    data class Failed(val error: ReverseGeocodeError) : ReverseGeocodeOutcome()
}

/**
 * Discriminated reverse-geocode failure reasons. Reverse geocoding a *given*
 * coordinate through the platform Geocoder needs no location permission, but the
 * error type is modelled up-front so callers choose exactly what to surface and
 * never map an unrelated failure to "permission".
 */
enum class ReverseGeocodeErrorKind {
    /** The platform has no Geocoder (device missing / service disabled). */
    UNAVAILABLE,

    /** A location permission would be required (not the case for a plain coordinate lookup). */
    PERMISSION,

    /** The lookup returned nothing usable for the given coordinate. */
    LOOKUP_FAILED,

    /** Underlying I/O / backend failure. */
    IO,

    /** The capability was never configured (no provider wired). */
    NOT_CONFIGURED,
}

/** A typed reverse-geocode error with an honest, user-facing message. */
data class ReverseGeocodeError(val kind: ReverseGeocodeErrorKind, val detail: String = "") {
    val message: String
        get() = when (kind) {
            ReverseGeocodeErrorKind.UNAVAILABLE -> "设备未提供逆地理编码服务" +
                (if (detail.isBlank()) "" else "（$detail）")
            ReverseGeocodeErrorKind.PERMISSION -> "位置解析需要定位权限" +
                (if (detail.isBlank()) "" else "（$detail）")
            ReverseGeocodeErrorKind.LOOKUP_FAILED -> "未获取到该坐标的有效地址" +
                (if (detail.isBlank()) "" else "（$detail）")
            ReverseGeocodeErrorKind.IO -> "逆地理编码调用失败" +
                (if (detail.isBlank()) "" else "（$detail）")
            ReverseGeocodeErrorKind.NOT_CONFIGURED -> "逆地理编码尚未配置"
        }
}

/**
 * A resolved place (subset of the HarmonyOS `GalleryLocationCacheRecord`
 * display fields). Populated only from a real platform [Address].
 */
data class GalleryLocationInfo(
    /** Best-effort single-line place, deterministic. */
    val formattedAddress: String,
    /** Province / admin area. */
    val province: String = "",
    /** City / locality. */
    val city: String = "",
    /** District / township. */
    val district: String = "",
    /** Street / thoroughfare name. */
    val street: String = "",
    /** Feature/premise name when available. */
    val placeName: String = "",
    /** Which provider produced it (android geocoder vs future keyed service). */
    val provider: String = "geocoder",
) {
    val hasAddress: Boolean get() = formattedAddress.isNotBlank()

    override fun toString(): String = formattedAddress
}
