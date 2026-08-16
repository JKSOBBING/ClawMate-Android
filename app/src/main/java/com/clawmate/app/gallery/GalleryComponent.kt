package com.clawmate.app.gallery

import android.content.Context
import com.clawmate.app.gallery.analysis.CloudVisionAnalyzer
import com.clawmate.app.gallery.analysis.OcrEngine
import com.clawmate.app.gallery.analysis.ReverseGeocoder
import com.clawmate.app.gallery.analysis.UnconfiguredCapabilities
import com.clawmate.app.gallery.data.DefaultGalleryRepository
import com.clawmate.app.gallery.data.GalleryDatabase
import com.clawmate.app.gallery.data.GalleryRepository
import com.clawmate.app.gallery.geocode.androidReverseGeocoder

/**
 * Lightweight manual dependency container for the gallery slice.
 *
 * Held by [ClawMateApplication] so the database and repository live across
 * configuration changes.
 *
 * Capability wiring (Phase 9):
 *  - OCR / cloud vision stay on the explicit NOT_CONFIGURED implementations (see
 *    CapabilityEngines / the ML Kit note below) — no fake analysis is produced.
 *  - Reverse geocoding is a real, *explicitly injected* Android capability backed
 *    by the platform [android.location.Geocoder] (no key). [reverseGeocoder] builds
 *    the Android adapter; a caller/tests may inject [UnconfiguredCapabilities] to
 *    take it out. The adapter never claims a location when a lookup fails.
 */
object GalleryComponent {

    @Volatile private var database: GalleryDatabase? = null

    @Volatile private var repository: GalleryRepository? = null

    fun getRepository(context: Context): GalleryRepository {
        repository?.let { return it }
        synchronized(this) {
            repository?.let { return it }
            val repo = DefaultGalleryRepository(getDatabase(context).galleryDao())
            repository = repo
            return repo
        }
    }

    fun getDatabase(context: Context): GalleryDatabase {
        database?.let { return it }
        synchronized(this) {
            database?.let { return it }
            val db = GalleryDatabase.build(context)
            database = db
            return db
        }
    }

    val ocr: OcrEngine get() = UnconfiguredCapabilities.ocr
    val vision: CloudVisionAnalyzer get() = UnconfiguredCapabilities.vision

    /** Real Android reverse geocoder (platform Geocoder), implicitly injected. */
    fun reverseGeocoder(context: Context): ReverseGeocoder = androidReverseGeocoder(context)

    /** Release caches (mostly for tests). */
    fun reset() {
        synchronized(this) {
            database = null
            repository = null
        }
    }
}
