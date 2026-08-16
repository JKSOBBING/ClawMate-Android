package com.clawmate.app.gallery.geocode

import com.clawmate.app.gallery.analysis.asCapabilityResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Deterministic tests for [AddressFormatter]. */
class AddressFormatterTest {

    @Test
    fun joinsAvailableHierarchy() {
        val s = AddressFormatter.format(
            country = "中国",
            adminArea = "上海市",
            subAdminArea = "浦东新区",
            locality = "上海市",
            subLocality = "陆家嘴街道",
            thoroughfare = "世纪大道",
            featureName = "示例大厦",
        )
        assertTrue(s.contains("世纪大道"))
        assertTrue(s.contains("浦东新区"))
    }

    @Test
    fun omittedBlanks() {
        val s = AddressFormatter.format(
            country = null,
            adminArea = "上海市",
            subAdminArea = null,
            locality = null,
            subLocality = "",
            thoroughfare = null,
            featureName = null,
        )
        assertEquals("上海市", s)
    }

    @Test
    fun featureNameWhenNoThoroughfare() {
        val s = AddressFormatter.format(
            country = null,
            adminArea = null,
            subAdminArea = null,
            locality = "上海市",
            subLocality = null,
            thoroughfare = null,
            featureName = "转角咖啡",
        )
        assertTrue(s.contains("转角咖啡"))
    }

    @Test
    fun emptyInputYieldsEmpty() {
        assertEquals("", AddressFormatter.format(null, null, null, null, null, null, null))
    }

    @Test
    fun cityProvinceDistrictSelection() {
        assertEquals("上海市", AddressFormatter.cityOf("上海市", "浦东新区", "上海市"))
        assertEquals("浦东新区", AddressFormatter.cityOf("上海市", "浦东新区", null))
        assertEquals("zhangjiang", AddressFormatter.districtOf("zhangjiang", null))
        assertEquals("上海市", AddressFormatter.provinceOf("上海市"))
    }
}

class ReverseGeocodeErrorTest {

    @Test
    fun unavailableMessageIsExplicit() {
        val err = ReverseGeocodeError(ReverseGeocodeErrorKind.UNAVAILABLE, "系统未提供地理编码服务")
        assertTrue(err.message.contains("未提供逆地理编码服务"))
        assertEquals(ReverseGeocodeErrorKind.UNAVAILABLE, err.kind)
    }

    @Test
    fun failedCarriesKind() {
        val err = ReverseGeocodeError(ReverseGeocodeErrorKind.LOOKUP_FAILED, "解析结果为空")
        assertTrue(err.message.contains("未获取到该坐标的有效地址"))
    }

    @Test
    fun noLocationIsClaimedOnFailure() {
        val failed: ReverseGeocodeOutcome = ReverseGeocodeOutcome.Failed(
            ReverseGeocodeError(ReverseGeocodeErrorKind.IO, "boom"),
        )
        assertFalse(failed is ReverseGeocodeOutcome.Resolved)
        assertTrue(failed is ReverseGeocodeOutcome.Failed)
    }

    @Test
    fun outcomeAdapterMapsToCapabilityResult() {
        val resolved = ReverseGeocodeOutcome.Resolved(
            GalleryLocationInfo(formattedAddress = "上海市世纪大道"),
        )
        val cap = resolved.asCapabilityResult()
        assertTrue(cap is com.clawmate.app.gallery.analysis.ReverseGeocodeResult.Resolved)
        val capResolved = cap as com.clawmate.app.gallery.analysis.ReverseGeocodeResult.Resolved
        assertEquals("上海市世纪大道", capResolved.location.formattedAddress)
    }
}
