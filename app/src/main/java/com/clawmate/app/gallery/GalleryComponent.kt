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
import com.clawmate.app.gallery.ocr.androidOcrEngine

/**
 * Lightweight manual dependency container for the gallery slice.
 *
 * Held by [ClawMateApplication] so the database and repository live across
 * configuration changes.
 *
 * Capability wiring:
 *  - OCR (Phase 11): the real on-device ML Kit Chinese text recognition adapter
 *    ([androidOcrEngine]) is the default — it opens the picked content URI and
 *    runs real recognition only on an explicit user action. It never fabricates
 *    text and reports typed success/empty/cancel/model-download/error/unavailable
 *    states. ML Kit's runtime + model download require a real device.
 *  - Cloud vision stays on the explicit NOT_CONFIGURED implementation (no key /
 *    endpoint configured) — no fake analysis is produced.
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

    /** Real on-device ML Kit Chinese OCR adapter (Phase 11). */
    fun ocr(context: Context): OcrEngine = androidOcrEngine(context)

    /** Cloud vision stays explicitly NOT_CONFIGURED (no endpoint/key). */
    val vision: CloudVisionAnalyzer get() = UnconfiguredCapabilities.vision

    /** Real Android reverse geocoder (platform Geocoder), implicitly injected. */
    fun reverseGeocoder(context: Context): ReverseGeocoder = androidReverseGeocoder(context)

    /**
     * Phase 21 — build a config-driven [com.clawmate.app.gallery.cloud.GalleryCloudAdapter]
     * from the current persisted settings + runtime planner endpoint. This is a
     * `suspend` provider so the DataStore settings snapshot can be read; the
     * returned adapter only reports gate states (no network) and short-circuits
     * before any I/O when a feature is not configured. Returns `null` when the
     * Settings store cannot be read (the caller then falls back to the honest
     * NOT_CONFIGURED default).
     */
    fun cloudAdapterProvider(context: Context): suspend () -> com.clawmate.app.gallery.cloud.GalleryCloudAdapter? {
        return {
            val snapshot = runCatching {
                com.clawmate.app.settings.SettingsComponent.repository(context).snapshot()
            }.getOrNull()
            // A failed / empty settings read means nothing is explicitly configured —
            // return null so the caller falls back to the honest NOT_CONFIGURED gate.
            if (snapshot == null) {
                null
            } else {
                buildCloudAdapter(snapshot)
            }
        }
    }

    /** Build the adapter from an [AppSettings] snapshot, reusing the Planner chat
     * backend and a shared OkHttp client. Never adds credentials. */
    fun buildCloudAdapter(
        settings: com.clawmate.app.settings.domain.AppSettings,
    ): com.clawmate.app.gallery.cloud.GalleryCloudAdapter {
        val planner = com.clawmate.app.config.EndpointConfig
        val chat = com.clawmate.app.chat.cloud.ChatServerConfig(
            baseUrl = planner.cloudPlannerBaseUrl,
            apiKey = planner.cloudPlannerApiKey,
            modelName = planner.cloudPlannerModel,
        )
        val chatClient: com.clawmate.app.chat.cloud.ChatClient =
            com.clawmate.app.chat.cloud.ChatCompletionsClient(cloudHttpClient(), chat)
        return com.clawmate.app.gallery.cloud.GalleryCloudAdapter.fromSettings(
            settings = settings,
            plannerBaseUrl = planner.cloudPlannerBaseUrl,
            plannerApiKey = planner.cloudPlannerApiKey,
            plannerModel = planner.cloudPlannerModel,
            httpClient = cloudHttpClient(),
            chatClient = chatClient,
        )
    }

    /** Shared OkHttp client for the gallery cloud slice (embedding + summary chat). */
    fun cloudHttpClient(): okhttp3.OkHttpClient = galleryCloudHttpClient

    /** Release caches (mostly for tests). */
    fun reset() {
        synchronized(this) {
            database = null
            repository = null
        }
    }
}

/** Lazily-created shared OkHttp client for the gallery cloud slice. */
private val galleryCloudHttpClient: okhttp3.OkHttpClient by lazy {
    okhttp3.OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(90, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
        .build()
}
