package com.clawmate.app.gallery

import android.content.Context
import com.clawmate.app.gallery.analysis.CloudVisionAnalyzer
import com.clawmate.app.gallery.analysis.OcrEngine
import com.clawmate.app.gallery.analysis.ReverseGeocoder
import com.clawmate.app.gallery.analysis.UnconfiguredCapabilities
import com.clawmate.app.gallery.background.AndroidGalleryMediaPermission
import com.clawmate.app.gallery.background.GalleryBackgroundCoordinator
import com.clawmate.app.gallery.background.GalleryMediaPermission
import com.clawmate.app.gallery.background.GalleryMediaSource
import com.clawmate.app.gallery.background.GallerySyncGate
import com.clawmate.app.gallery.background.GallerySyncPolicy
import com.clawmate.app.gallery.background.MediaStoreGalleryMediaSource
import com.clawmate.app.gallery.collection.IndexedEventReader
import com.clawmate.app.gallery.collection.IndexedEventReaderFactory
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
 *  - Cloud vision is wired by galleryVisionFactory to the saved gallery model,
 *    with explicit upload consent and preview-before-save. The legacy synchronous
 *    capability below remains an unavailable fallback.
 *  - Reverse geocoding is a real, *explicitly injected* Android capability backed
 *    by the platform [android.location.Geocoder] (no key). [reverseGeocoder] builds
 *    the Android adapter; a caller/tests may inject [UnconfiguredCapabilities] to
 *    take it out. The adapter never claims a location when a lookup fails.
 */
object GalleryComponent {

    @Volatile private var database: GalleryDatabase? = null

    @Volatile private var repository: GalleryRepository? = null

    /** Serial scan/sync coordinator singleton (Phase 42 explicit entry). */
    @Volatile private var scanCoordinator: GalleryBackgroundCoordinator? = null

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

    /** Legacy synchronous fallback; the real suspend client is wired by galleryVisionFactory. */
    val vision: CloudVisionAnalyzer get() = UnconfiguredCapabilities.vision

    /** Real Android reverse geocoder (platform Geocoder), implicitly injected. */
    fun reverseGeocoder(context: Context): ReverseGeocoder = androidReverseGeocoder(context)

    /**
     * Phase-44 — production [IndexedEventReader] over the app-private indexed
     * daily-log events. Reads only the persisted local index partitions (never
     * creates artifacts, never touches the network / model). It is constructed
     * here (the single production seam) and consumed exclusively by the 汇总
     * index-backed collection panel on an explicit user action.
     */
    fun indexedEventReader(context: Context): IndexedEventReader =
        IndexedEventReaderFactory.appPrivate(context.filesDir)

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

    // ---------------------------------------------------------------
    // Phase 42 (work package B) — production-reachable, explicit gallery
    // scan/sync boundary.
    //
    // The [GalleryBackgroundCoordinator] + [MediaStoreGalleryMediaSource]
    // pipeline already existed but was never wired into a real Android caller:
    // nothing constructed/interjected them in production. These methods are the
    // single production construction point — they build the *real* MediaStore
    // source, the *live* Android runtime permission detector, and the *serial*
    // coordinator over the existing Room [GalleryRepository], and hand back the
    // reachable seam. They are deliberately *not* invoked on app start /
    // onCreate / recomposition — only the explicit "扫描图库" user action calls
    // [GalleryBackgroundCoordinator.runOnce].
    // ---------------------------------------------------------------

    /** Real Android runtime photo/library permission detector (never requests). */
    fun mediaPermission(context: Context): GalleryMediaPermission =
        AndroidGalleryMediaPermission(context)

    /**
     * Production [GalleryMediaSource] over `MediaStore`. Constructed here so the
     * coordinator in [scanCoordinator] receives the real source; it only queries
     * the media library after the gate is READY and the local source also
     * re-verifies the runtime grant before scanning (defense in depth).
     */
    fun mediaSource(context: Context): GalleryMediaSource =
        MediaStoreGalleryMediaSource(context, mediaPermission(context))

    /**
     * A live [GallerySyncGate] builder for a context. Reads the *persisted*
     * explicit opt-in ([AppSettings.galleryBackgroundSyncEnabled], default false)
     * plus the *live* observed media-read grant. Never scans by itself — it only
     * derives one honest status for the gate.
     */
    fun scanGateProvider(context: Context): () -> GallerySyncGate = {
        // The explicit opt-in is mirrored onto [GallerySyncOptinRegistry] from the
        // persisted setting at Settings-save time (see SettingsViewModel.save).
        // Reading the flag here is synchronous and honest: until the user
        // explicitly opts in (and saves), the gate stays NOT_CONFIGURED.
        GallerySyncGate(
            optedIn = com.clawmate.app.gallery.background.GallerySyncOptinRegistry.optedIn,
            photoAccessGranted = mediaPermission(context).isPhotoAccessGranted(),
        )
    }

    /**
     * The singleton serial [GalleryBackgroundCoordinator] for the app process,
     * with the real [MediaStoreGalleryMediaSource] and the existing Room
     * [GalleryRepository] injected. Constructing it triggers **no** scan —
     * [GalleryBackgroundCoordinator.runOnce] only runs from an explicit user
     * action after [GallerySyncPolicy.decideRun] reports [GallerySyncPolicy.Run].
     */
    fun scanCoordinator(context: Context): GalleryBackgroundCoordinator {
        scanCoordinator?.let { return it }
        synchronized(this) {
            scanCoordinator?.let { return it }
            val coordinator = GalleryBackgroundCoordinator(
                gate = scanGateProvider(context),
                mediaSource = mediaSource(context),
                repository = getRepository(context),
                scanLimit = GallerySyncPolicy.DEFAULT_SCAN_LIMIT,
                // cloudAdapterProvider stays null (default): the optional cloud
                // memory-sync step is deliberately NOT auto-invoked this phase.
                // The coordinator then honestly reports
                // SKIPPED_NOT_CONFIGURED and sends no bytes. A configured cloud
                // path remains covered by the existing coordinator cloud tests.
            )
            scanCoordinator = coordinator
            return coordinator
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
            scanCoordinator = null
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
