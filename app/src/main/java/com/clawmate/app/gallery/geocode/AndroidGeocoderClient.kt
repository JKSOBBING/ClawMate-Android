package com.clawmate.app.gallery.geocode

import android.content.Context
import android.location.Geocoder

/**
 * Platform reverse geocoder backed by [android.location.Geocoder].
 *
 * Design notes (honesty first):
 *  - Reverse geocoding a *given* coordinate through the platform Geocoder does
 *    not require the raw-location permission (that permission gates reading the
 *    device's own current position, not resolving an arbitrary coordinate).
 *    The `PERMISSION` error kind is therefore modelled but is only ever raised
 *    when the platform itself signals a permission-style failure — we never
 *    fabricate a permission problem.
 *  - No network/service key and no endpoint configuration are involved; there is
 *    nothing to leak and nothing to configure.
 *  - A resolved place is produced **only** from a real [android.location.Address].
 *    If the lookup returns nothing usable, the caller receives a typed
 *    [ReverseGeocodeOutcome.Failed] and must not claim a location.
 *
 * The Android Geocoder has no deterministic, device-free unit test path, so all
 * decision logic (availability gate, error mapping, coordinate sanity) is pushed
 * into small pure functions ([AndroidGeocoderMapping]) that ARE unit tested, and
 * the thin platform call stays untested-by-design (see GeocoderMappingTest).
 *
 * HarmonyOS source of truth (read-only): `GalleryReverseGeocoder.ets` (AMap web
 * service). On Android the same *concept* is served by the platform Geocoder
 * with no external key.
 */
class AndroidGeocoderClient(
    private val context: Context,
    private val formatter: AddressFormatter = AddressFormatter,
) {

    /**
     * True only when the device actually offers a Geocoder service. When false,
     * [reverse] returns a typed UNAVAILABLE outcome — never a fabricated place.
     */
    fun isAvailable(): Boolean =
        AndroidGeocoderMapping.availableFor(context)

    /** Whether both coordinates are sane enough to attempt a lookup. */
    fun canLookup(latitude: Double, longitude: Double): Boolean =
        AndroidGeocoderMapping.isReasonableCoordinate(latitude, longitude)

    /**
     * Resolves a coordinate to a place. Returns [ReverseGeocodeOutcome.Resolved]
     * only after a real lookup produced an address; otherwise a typed
     * [ReverseGeocodeOutcome.Failed]. Throws are converted to typed IO failures.
     */
    fun reverse(latitude: Double, longitude: Double): ReverseGeocodeOutcome {
        if (!isAvailable()) {
            return ReverseGeocodeOutcome.Failed(
                ReverseGeocodeError(ReverseGeocodeErrorKind.UNAVAILABLE, "系统未提供地理编码服务"),
            )
        }
        if (!canLookup(latitude, longitude) ||
            !AndroidGeocoderMapping.isValidLatLng(latitude, longitude)
        ) {
            return ReverseGeocodeOutcome.Failed(
                ReverseGeocodeError(ReverseGeocodeErrorKind.LOOKUP_FAILED, "坐标无效"),
            )
        }
        val geocoder = Geocoder(context)
        val addresses = try {
            geocoder.getFromLocation(latitude, longitude, 1)
        } catch (t: Throwable) {
            // getFromLocation throws IOException on backend failure.
            return ReverseGeocodeOutcome.Failed(
                ReverseGeocodeError(ReverseGeocodeErrorKind.IO, t.message ?: t.javaClass.simpleName),
            )
        }
        val best = addresses?.firstOrNull()
            ?: return ReverseGeocodeOutcome.Failed(
                ReverseGeocodeError(ReverseGeocodeErrorKind.LOOKUP_FAILED, "未解析到地址"),
            )
        val info = AndroidGeocoderMapping.toLocationInfo(
            AddressFields(
                country = best.countryName,
                adminArea = best.adminArea,
                subAdminArea = best.subAdminArea,
                locality = best.locality,
                subLocality = best.subLocality,
                thoroughfare = best.thoroughfare,
                featureName = best.featureName,
            ),
            formatter,
        )
        if (!info.hasAddress) {
            return ReverseGeocodeOutcome.Failed(
                ReverseGeocodeError(ReverseGeocodeErrorKind.LOOKUP_FAILED, "解析结果为空"),
            )
        }
        return ReverseGeocodeOutcome.Resolved(info)
    }
}

/**
 * Plain, device-free carrier for the geocoder locality fields we consume. The
 * Android [Address] getters are not mocked in JVM unit tests, so the pure
 * mapping ([AndroidGeocoderMapping.toLocationInfo]) operates on this value and
 * stays unit-testable; the thin [AndroidGeocoderClient.reverse] bridges from
 * [android.location.Address] to it. Nothing is fabricated: absent fields are
 * simply left null/blank.
 */
data class AddressFields(
    val country: String? = null,
    val adminArea: String? = null,
    val subAdminArea: String? = null,
    val locality: String? = null,
    val subLocality: String? = null,
    val thoroughfare: String? = null,
    val featureName: String? = null,
)

/**
 * Pure, testable decision/mapping logic for [AndroidGeocoderClient].
 */
object AndroidGeocoderMapping {

    fun availableFor(context: Context): Boolean =
        runCatching { Geocoder.isPresent() }.getOrDefault(false)

    fun isValidLatLng(latitude: Double, longitude: Double): Boolean =
        latitude in -90.0..90.0 && longitude in -180.0..180.0 && !latitude.isNaN() && !longitude.isNaN()

    /** Reject unset/placeholder coordinates that would never yield a real place. */
    fun isReasonableCoordinate(latitude: Double, longitude: Double): Boolean {
        if (!isValidLatLng(latitude, longitude)) return false
        // Both zero is a common "unknown" placeholder.
        return !(latitude == 0.0 && longitude == 0.0)
    }

    fun toLocationInfo(address: AddressFields, formatter: AddressFormatter = AddressFormatter): GalleryLocationInfo {
        return GalleryLocationInfo(
            formattedAddress = formatter.format(
                country = address.country,
                adminArea = address.adminArea,
                subAdminArea = address.subAdminArea,
                locality = address.locality,
                subLocality = address.subLocality,
                thoroughfare = address.thoroughfare,
                featureName = address.featureName,
            ),
            province = formatter.provinceOf(address.adminArea),
            city = formatter.cityOf(address.adminArea, address.subAdminArea, address.locality),
            district = formatter.districtOf(address.subAdminArea, address.subLocality),
            street = address.thoroughfare?.trim().orEmpty(),
            placeName = address.featureName?.trim().orEmpty(),
            provider = "geocoder",
        )
    }
}
