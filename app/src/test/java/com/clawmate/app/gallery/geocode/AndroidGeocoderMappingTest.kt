package com.clawmate.app.gallery.geocode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic, device-free tests for the pure geocoder decision/mapping logic.
 * Only pure functions are exercised — the platform Geocoder call itself is not
 * runnable in a local test and is intentionally not faked.
 */
class AndroidGeocoderMappingTest {

    @Test
    fun validLatLngAccepted() {
        assertTrue(AndroidGeocoderMapping.isValidLatLng(31.2, 121.4))
        assertTrue(AndroidGeocoderMapping.isValidLatLng(-90.0, -180.0))
        assertTrue(AndroidGeocoderMapping.isValidLatLng(90.0, 180.0))
    }

    @Test
    fun outOfRangeRejected() {
        assertFalse(AndroidGeocoderMapping.isValidLatLng(91.0, 0.0))
        assertFalse(AndroidGeocoderMapping.isValidLatLng(0.0, 181.0))
        assertFalse(AndroidGeocoderMapping.isValidLatLng(Double.NaN, 0.0))
    }

    @Test
    fun zeroZeroPlaceholderRejected() {
        assertFalse(AndroidGeocoderMapping.isReasonableCoordinate(0.0, 0.0))
        assertTrue(AndroidGeocoderMapping.isReasonableCoordinate(31.2, 121.4))
    }

    @Test
    fun addressMappedWithFullFields() {
        val info = AndroidGeocoderMapping.toLocationInfo(
            AddressFields(
                country = "中国",
                adminArea = "上海市",
                subAdminArea = "浦东新区",
                locality = "上海市",
                subLocality = "陆家嘴街道",
                thoroughfare = "世纪大道",
                featureName = "示例大厦",
            ),
        )
        assertTrue(info.formattedAddress.contains("世纪大道"))
        assertEquals("上海市", info.province)
        assertEquals("上海市", info.city)
        assertEquals("浦东新区", info.district)
        assertEquals("世纪大道", info.street)
        assertEquals("示例大厦", info.placeName)
        assertEquals("geocoder", info.provider)
    }

    @Test
    fun blanksAreOmittedNotFabricated() {
        val info = AndroidGeocoderMapping.toLocationInfo(
            AddressFields(locality = "上海市"),
        )
        assertEquals("上海市", info.formattedAddress)
        assertTrue(info.district.isEmpty())
        assertTrue(info.street.isEmpty())
        assertTrue(info.placeName.isEmpty())
    }

    @Test
    fun featureNameUsedAsFallbackAnchor() {
        val info = AndroidGeocoderMapping.toLocationInfo(
            AddressFields(featureName = "转角咖啡"),
        )
        assertTrue(info.formattedAddress.contains("转角咖啡"))
    }
}
