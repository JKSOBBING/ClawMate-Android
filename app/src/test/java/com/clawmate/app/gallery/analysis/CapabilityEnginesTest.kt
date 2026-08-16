package com.clawmate.app.gallery.analysis

import com.clawmate.app.gallery.model.GalleryPhotoItem
import com.clawmate.app.gallery.ui.CapabilitySummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests that OCR / cloud vision / reverse geocoding are behind explicit
 * interfaces and report NOT_CONFIGURED — no fake analysis is produced.
 */
class CapabilityEnginesTest {

    private val photo = GalleryPhotoItem(
        id = "x",
        uri = "content://media/picker/1",
        date = "2026-06-01",
        title = "",
        category = "",
        summary = "",
        createdAt = 0L,
    )

    @Test
    fun ocrEngineIsExplicitlyNotConfigured() {
        val engine = UnconfiguredCapabilities.ocr
        assertEquals(CapabilityState.NotConfigured, engine.state)
        val result = engine.recognize(photo)
        assertTrue(result is OcrResult.Unavailable)
        assertTrue(result !is OcrResult.Text)
    }

    @Test
    fun visionAnalyzerIsExplicitlyNotConfigured() {
        val analyzer = UnconfiguredCapabilities.vision
        assertEquals(CapabilityState.NotConfigured, analyzer.state)
        assertEquals(VisionResult.NotConfigured, analyzer.analyze(photo))
    }

    @Test
    fun reverseGeocoderIsExplicitlyNotConfigured() {
        val geocoder = UnconfiguredCapabilities.reverseGeocoder
        assertEquals(CapabilityState.NotConfigured, geocoder.state)
        assertEquals(ReverseGeocodeResult.NotConfigured, geocoder.reverse(31.2, 121.4))
    }

    @Test
    fun anInterfaceImplementationCanReportReadyWithoutFaking() {
        val ready = object : OcrEngine {
            override val state = CapabilityState.Ready("MOCK_ONLY_FOR_TEST")
            override fun recognize(photo: GalleryPhotoItem): OcrResult = OcrResult.Text("real")
        }
        assertEquals(CapabilityState.Ready("MOCK_ONLY_FOR_TEST"), ready.state)
        assertEquals("real", (ready.recognize(photo) as OcrResult.Text).value)
    }

    @Test
    fun capabilitySummaryFlagIsCorrect() {
        val unconfigured = CapabilitySummary(
            ocr = CapabilityState.NotConfigured,
            vision = CapabilityState.NotConfigured,
            reverseGeocoding = CapabilityState.NotConfigured,
        )
        assertTrue(unconfigured.hasUnconfigured)

        val ready = CapabilitySummary(
            ocr = CapabilityState.Ready("x"),
            vision = CapabilityState.Ready("y"),
            reverseGeocoding = CapabilityState.Ready("z"),
        )
        assertFalse(ready.hasUnconfigured)
    }

    @Test
    fun unsupportedIsDistinctFromNotConfigured() {
        val unsupported: CapabilityState = CapabilityState.Unsupported
        val notConfigured: CapabilityState = CapabilityState.NotConfigured
        assertTrue(unsupported != notConfigured)
    }
}
