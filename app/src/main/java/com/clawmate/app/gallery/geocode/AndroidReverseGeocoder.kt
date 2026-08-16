package com.clawmate.app.gallery.geocode

import android.content.Context
import com.clawmate.app.gallery.analysis.CapabilityState
import com.clawmate.app.gallery.analysis.ReverseGeocodeResult
import com.clawmate.app.gallery.analysis.ReverseGeocoder
import com.clawmate.app.gallery.analysis.asCapabilityResult

/**
 * The Android implementation of the gallery [ReverseGeocoder] capability boundary,
 * backed by the platform [android.location.Geocoder] through [AndroidGeocoderClient].
 *
 * This is an *explicitly injected* capability: [GalleryComponent] owns wiring it in
 * by default; callers/tests may inject the NOT_CONFIGURED engine instead. It never
 * claims a location on failure — every failure returns a typed
 * [ReverseGeocodeResult.Failed], and availability is reported honestly via [state].
 */
class AndroidReverseGeocoder(
    private val client: AndroidGeocoderClient,
) : ReverseGeocoder {

    override val state: CapabilityState
        get() = if (client.isAvailable()) {
            CapabilityState.Ready("系统地理编码服务")
        } else {
            CapabilityState.Unsupported
        }

    override fun reverse(latitude: Double, longitude: Double): ReverseGeocodeResult =
        client.reverse(latitude, longitude).asCapabilityResult()
}

/**
 * Convenience factory keeping the Android Context dependency out of the pure
 * capability wiring (used by [com.clawmate.app.gallery.GalleryComponent]).
 */
fun androidReverseGeocoder(context: Context): ReverseGeocoder =
    AndroidReverseGeocoder(AndroidGeocoderClient(context))
