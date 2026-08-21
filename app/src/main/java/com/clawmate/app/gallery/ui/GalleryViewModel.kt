package com.clawmate.app.gallery.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.clawmate.app.gallery.analysis.CapabilityState
import com.clawmate.app.gallery.analysis.CloudVisionAnalyzer
import com.clawmate.app.gallery.analysis.OcrEngine
import com.clawmate.app.gallery.analysis.OcrErrorKind
import com.clawmate.app.gallery.analysis.OcrResult
import com.clawmate.app.gallery.analysis.ReverseGeocodeResult
import com.clawmate.app.gallery.analysis.ReverseGeocoder
import com.clawmate.app.gallery.data.DefaultGalleryRepository
import com.clawmate.app.gallery.data.GalleryRepository
import com.clawmate.app.gallery.geocode.GalleryLocationInfo
import com.clawmate.app.gallery.geocode.ReverseGeocodeError
import com.clawmate.app.gallery.geocode.ReverseGeocodeErrorKind
import com.clawmate.app.gallery.model.CollectionGroup
import com.clawmate.app.gallery.model.GalleryPhotoItem
import com.clawmate.app.gallery.photo.MediaMetadataReader
import com.clawmate.app.gallery.photo.PickResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** UI state for the gallery/collection screen. */
sealed class GalleryUiState {
    data object Loading : GalleryUiState()
    data class Loaded(
        val items: List<GalleryPhotoItem>,
        val groups: List<CollectionGroup>,
        val searchQuery: String,
        val capability: CapabilitySummary,
    ) : GalleryUiState()

    data class Error(val message: String) : GalleryUiState()
}

/** A human-readable, honest summary of what analysis is/isn't available. */
data class CapabilitySummary(
    val ocr: CapabilityState,
    val vision: CapabilityState,
    val reverseGeocoding: CapabilityState,
) {
    val hasUnconfigured: Boolean get() =
        ocr == CapabilityState.NotConfigured ||
            vision == CapabilityState.NotConfigured ||
            reverseGeocoding == CapabilityState.NotConfigured

    val hasUnsupported: Boolean get() =
        ocr == CapabilityState.Unsupported ||
            vision == CapabilityState.Unsupported ||
            reverseGeocoding == CapabilityState.Unsupported
}

/**
 * Reverse-geocoding view state for the detail panel. Only [Resolved] ever
 * carries a real place; [Failed] carries a typed error the UI surfaces, and a
 * failure never claims a location.
 */
sealed class LocationViewState {
    data object NotConfigured : LocationViewState()
    data object NoCoordinates : LocationViewState()
    data object Resolving : LocationViewState()
    data class Resolved(val location: GalleryLocationInfo) : LocationViewState()
    data class Failed(val error: ReverseGeocodeError) : LocationViewState()
}

/**
 * On-device OCR view state for an item's Analyze action. Only [Success] ever
 * carries real recognized text. [Empty] means a genuine "no text found" result;
 * [Error]/[ModelDownload]/[Unavailable] are honest non-text outcomes. Nothing is
 * fabricated.
 */
sealed class OcrViewState {
    /** No analyze shown/run yet (or an earlier run didn't produce a result). */
    data object Idle : OcrViewState()

    /** A user-triggered Analyze is in flight. */
    data object Loading : OcrViewState()

    /** Real recognized text (persisted on successful Analyze). */
    data class Success(val text: String) : OcrViewState()

    /** The image decoded but contained no recognized text (a real empty). */
    data object Empty : OcrViewState()

    /** A typed engine/access/decode failure; [kind] classifies the cause. */
    data class Error(val message: String, val kind: OcrErrorKind) : OcrViewState()

    /** The on-device model is not present yet and must be downloaded. */
    data class ModelDownload(val message: String) : OcrViewState()

    /** On-device OCR is not available on this device. */
    data object Unavailable : OcrViewState()
}

/** Detail-panel state for the selected gallery item. */
data class GalleryDetailState(
    val itemId: String,
    /** The live item (refreshed after edits/imports); null if it was deleted. */
    val item: GalleryPhotoItem?,
    val groupMembership: List<CollectionGroup>,
    val location: LocationViewState = LocationViewState.NotConfigured,
    val ocr: OcrViewState = OcrViewState.Idle,
) {
    val currentItem: GalleryPhotoItem?
        get() = item?.takeIf { it.id == itemId }
}

class GalleryViewModel(
    private val repository: GalleryRepository,
    private val ocrEngine: OcrEngine,
    private val visionAnalyzer: CloudVisionAnalyzer,
    private val reverseGeocoder: ReverseGeocoder,
    private val mediaReader: MediaMetadataReader = MediaMetadataReader,
) : ViewModel() {

    private val _state = MutableStateFlow<GalleryUiState>(GalleryUiState.Loading)
    val state: StateFlow<GalleryUiState> = _state

    private val itemsFlow = repository.observeItems()
    private val groupsFlow = repository.observeGroups()
    private val _searchQuery = MutableStateFlow("")

    private val _selectedItemId = MutableStateFlow<String?>(null)
    val selectedItemId: StateFlow<String?> = _selectedItemId

    private val _detail = MutableStateFlow<GalleryDetailState?>(null)
    val detail: StateFlow<GalleryDetailState?> = _detail

    init {
        viewModelScope.launch {
            combine(itemsFlow, groupsFlow, _searchQuery) { items, groups, query ->
                GalleryUiState.Loaded(
                    items = items,
                    groups = groups,
                    searchQuery = query,
                    capability = CapabilitySummary(
                        ocr = ocrEngine.state,
                        vision = visionAnalyzer.state,
                        reverseGeocoding = reverseGeocoder.state,
                    ),
                )
            }.collect { loaded ->
                _state.value = loaded
                // Keep the detail panel consistent: when the selected item is no
                // longer present (deleted elsewhere), reflect that honestly.
                val selected = _selectedItemId.value
                if (selected != null) refreshSelectedIfVisible()
            }
        }
        // Observe selected id to rebuild detail when it changes.
        viewModelScope.launch {
            _selectedItemId.collect { id ->
                if (id == null) {
                    _detail.value = null
                } else {
                    rebuildDetail(id, preserveLocation = false)
                }
            }
        }
        viewModelScope.launch { refreshGroups() }
    }

    /** Refresh collection groups from stored items (deterministic). */
    suspend fun refreshGroups() {
        executeCatching { repository.refreshGroups(DefaultGalleryRepository.GALLERY_TAB_ID) }
    }

    /**
     * Handle the result of a system photo-pick. Maps directly to typed states;
     * a denied/cancelled pick simply does nothing (no error state is invented).
     */
    fun onPickResult(context: Context, result: PickResult) {
        when (result) {
            is PickResult.Picked -> viewModelScope.launch { importPicked(context, result.uris) }
            PickResult.Cancelled -> Unit // user cancelled — no fabricated error
            is PickResult.Unavailable -> {
                _state.value = GalleryUiState.Error(result.reason)
            }
        }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    // ---- Detail panel ----

    fun selectItem(id: String) {
        _selectedItemId.value = id
    }

    fun deselectItem() {
        _selectedItemId.value = null
    }

    /** Persist an explicit title edit (blank input is stored trimmed). */
    fun editTitle(id: String, title: String) {
        viewModelScope.launch {
            executeCatching { repository.updateTitle(id, title) }
            refreshSelectedIfVisible()
        }
    }

    /** Persist an explicit summary edit. */
    fun editSummary(id: String, summary: String) {
        viewModelScope.launch {
            executeCatching { repository.updateSummary(id, summary) }
            refreshSelectedIfVisible()
        }
    }

    /** Persist an explicit tags edit (list is tokenised/stored as-is). */
    fun editTags(id: String, tags: List<String>) {
        viewModelScope.launch {
            executeCatching { repository.updateTags(id, tags) }
            refreshSelectedIfVisible()
        }
    }

    /**
     * Delete a single item **by id** after the UI's confirmation. Only the
     * stored item row is removed; the content-URI record/grant is never deleted
     * broadly. On success the detail is cleared.
     */
    fun deleteItem(id: String) {
        viewModelScope.launch {
            val deleted = executeCatchingReturn { repository.deleteItem(id) }
            if (deleted == true) {
                if (_selectedItemId.value == id) _selectedItemId.value = null
            } else {
                // Nothing to delete is not an error; the detail stays (if present).
                refreshSelectedIfVisible()
            }
        }
    }

    /**
     * Resolve a reverse-geocoded place for the selected item's coordinates.
     * Only ever sets the stored address after a real [ReverseGeocodeResult
     * .Resolved]; failures are surfaced as typed [LocationViewState.Failed] and
     * never claim a location.
     */
    fun resolveLocation(id: String) {
        viewModelScope.launch {
            val coords = runCatching { repository.getItem(id) }.getOrNull()
                ?.let { coordinatesOf(it) }
            if (coords == null) {
                updateDetailLocation(id, LocationViewState.NoCoordinates)
                return@launch
            }
            updateDetailLocation(id, LocationViewState.Resolving)
            try {
                when (val result = reverseGeocoder.reverse(coords.first, coords.second)) {
                    is ReverseGeocodeResult.Resolved -> {
                        repository.updateLocation(id, result.location.formattedAddress, "geocoder")
                        updateDetailLocation(id, LocationViewState.Resolved(result.location))
                    }
                    is ReverseGeocodeResult.Failed -> {
                        updateDetailLocation(id, LocationViewState.Failed(result.error))
                    }
                    ReverseGeocodeResult.NotConfigured -> {
                        updateDetailLocation(id, LocationViewState.NotConfigured)
                    }
                }
            } catch (t: Throwable) {
                updateDetailLocation(
                    id,
                    LocationViewState.Failed(
                        ReverseGeocodeError(
                            com.clawmate.app.gallery.geocode.ReverseGeocodeErrorKind.IO,
                            t.message ?: t.javaClass.simpleName,
                        ),
                    ),
                )
            }
            refreshSelectedIfVisible()
        }
    }

    /**
     * User-triggered on-device OCR Analyze for the selected item. Runs the
     * injected [OcrEngine] against the item's content URI, reflects a typed
     * loading/result state, and persists **only real recognized text** (plus the
     * "analyzed" status) through Room when the action really succeeds. An honest
     * empty image stores empty text + "analyzed"; every failure/unavailable/
     * model-download path surfaces a typed state and never persists fabricated
     * text nor overwrites a prior real result.
     */
    fun analyzeOcr(id: String) {
        viewModelScope.launch {
            val item = repository.getItem(id)
            if (item == null) return@launch
            updateDetailOcr(id, OcrViewState.Loading)
            val result = try {
                ocrEngine.recognize(item)
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce // never convert coroutine cancellation into an OCR error
            } catch (t: Throwable) {
                OcrResult.Failed(
                    t.message ?: "OCR 自动取消或失败",
                    OcrErrorKind.UNKNOWN,
                )
            }
            when (result) {
                is OcrResult.Text -> {
                    if (result.value.isNotBlank()) {
                        com.clawmate.app.log.AppLogger.gallery(
                            com.clawmate.app.log.domain.LogLevel.INFO,
                            "Gallery",
                            "图库 OCR 完成 id=$id，识别 ${result.value.length} 字符",
                        )
                        repository.updateOcr(id, result.value, GalleryPhotoItem.ANALYSIS_STATE_ANALYZED)
                        updateDetailOcr(id, OcrViewState.Success(result.value))
                    } else {
                        // A blank success is treated as an honest empty (never fake text).
                        repository.updateOcr(id, "", GalleryPhotoItem.ANALYSIS_STATE_ANALYZED)
                        updateDetailOcr(id, OcrViewState.Empty)
                    }
                }
                is OcrResult.Empty -> {
                    com.clawmate.app.log.AppLogger.gallery(
                        com.clawmate.app.log.domain.LogLevel.INFO,
                        "Gallery",
                        "图库 OCR 无文本 id=$id",
                    )
                    repository.updateOcr(id, "", GalleryPhotoItem.ANALYSIS_STATE_ANALYZED)
                    updateDetailOcr(id, OcrViewState.Empty)
                }
                is OcrResult.Cancelled -> updateDetailOcr(id, OcrViewState.Idle)
                is OcrResult.ModelDownload -> {
                    com.clawmate.app.log.AppLogger.gallery(
                        com.clawmate.app.log.domain.LogLevel.INFO,
                        "Gallery",
                        "图库 OCR 模型下载中",
                    )
                    updateDetailOcr(id, OcrViewState.ModelDownload(result.message))
                }
                is OcrResult.Failed -> {
                    com.clawmate.app.log.AppLogger.gallery(
                        com.clawmate.app.log.domain.LogLevel.ERROR,
                        "Gallery",
                        "图库 OCR 失败 id=$id: ${result.reason}",
                    )
                    updateDetailOcr(id, OcrViewState.Error(result.reason, result.kind))
                }
                is OcrResult.Unavailable -> updateDetailOcr(id, OcrViewState.Unavailable)
            }
            refreshSelectedIfVisible()
        }
    }

    private suspend fun updateDetailOcr(id: String, viewState: OcrViewState) {
        val d = _detail.value
        if (d?.itemId == id) {
            _detail.value = d.copy(ocr = viewState)
        }
    }

    // ---- private ----

    private suspend fun refreshSelectedIfVisible() {
        val id = _selectedItemId.value ?: return
        val preserve = _detail.value?.location
        val preserveOcr = _detail.value?.ocr
        rebuildDetail(
            id,
            preserveLocation = true,
            initialLocation = preserve,
            preserveOcr = true,
            initialOcr = preserveOcr,
        )
    }

    /** Reads the first non-null coordinate pair the item really carries. */
    private fun coordinatesOf(item: GalleryPhotoItem): Pair<Double, Double>? {
        val lat = item.exif?.latitude ?: item.latitude
        val lng = item.exif?.longitude ?: item.longitude
        if (lat == null || lng == null) return null
        if (lat.isNaN() || lng.isNaN()) return null
        if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return null
        return lat to lng
    }

    private suspend fun rebuildDetail(
        id: String,
        preserveLocation: Boolean,
        initialLocation: LocationViewState? = null,
        preserveOcr: Boolean = false,
        initialOcr: OcrViewState? = null,
    ) {
        // On a fresh selection start at the platform default; on a refresh keep
        // the current transient location (Resolving/Failed/Resolved) so an
        // in-flight resolve isn't clobbered.
        val baseLocation: LocationViewState = when {
            initialLocation != null -> initialLocation
            preserveLocation -> _detail.value?.location ?: LocationViewState.NotConfigured
            else -> LocationViewState.NotConfigured
        }
        val storedItem = repository.getItem(id)
        val membership = if (storedItem != null) {
            repository.itemGroupMembership(id)
        } else {
            emptyList()
        }
        // If a stored address exists, reflect it as honestly Resolved (without a
        // fresh lookup) rather than re-running geocoding.
        val effectiveLocation = storedItem?.takeIf {
            (baseLocation is LocationViewState.NotConfigured || baseLocation is LocationViewState.NoCoordinates) &&
                it.address.isNotBlank()
        }?.let { item ->
            LocationViewState.Resolved(
                GalleryLocationInfo(
                    formattedAddress = item.address,
                    provider = item.locationProvider.ifBlank { "geocoder" },
                ),
            )
        } ?: baseLocation
        // OCR view state: preserve a transient in-flight/recent state on refresh,
        // otherwise derive honestly from the stored item (real text after a
        // successful Analyze, or Idle when nothing was persisted).
        val effectiveOcr: OcrViewState = when {
            initialOcr != null -> initialOcr
            preserveOcr -> _detail.value?.ocr ?: OcrViewState.Idle
            storedItem == null -> OcrViewState.Idle
            storedItem.analysisState == GalleryPhotoItem.ANALYSIS_STATE_ANALYZED ->
                if (storedItem.ocrText.isBlank()) OcrViewState.Empty
                else OcrViewState.Success(storedItem.ocrText)
            else -> OcrViewState.Idle
        }
        _detail.value = GalleryDetailState(
            itemId = id,
            item = storedItem,
            groupMembership = membership,
            location = effectiveLocation,
            ocr = effectiveOcr,
        )
    }

    private suspend fun updateDetailLocation(id: String, viewState: LocationViewState) {
        val d = _detail.value
        if (d?.itemId == id) {
            _detail.value = d.copy(location = viewState)
        }
    }

    private fun buildItem(context: Context, uri: Uri): GalleryPhotoItem {
        val createdAt = System.currentTimeMillis()
        val exif = mediaReader.resolveExif(context, uri)
        return GalleryPhotoItem(
            id = com.clawmate.app.gallery.model.GalleryIds.stableIdForUri(uri.toString()),
            uri = uri.toString(),
            date = mediaReader.resolveDate(context, uri),
            title = "图库图片",
            category = "",
            summary = "",
            exif = exif,
            latitude = exif?.latitude,
            longitude = exif?.longitude,
            createdAt = createdAt,
            analysisState = com.clawmate.app.gallery.model.GalleryPhotoItem.ANALYSIS_STATE_NONE,
        )
    }

    private suspend fun importPicked(context: Context, uris: List<Uri>) {
        executeCatching {
            _state.value = GalleryUiState.Loading
            val items = uris.mapNotNull { uri ->
                // Inaccessible URIs are skipped and reported via metadata that
                // is simply absent — never a fabricated photo.
                if (!mediaReader.isAccessible(context, uri)) null
                else buildItem(context, uri)
            }.sortedBy { it.id }
            repository.importItems(items)
            repository.refreshGroups(DefaultGalleryRepository.GALLERY_TAB_ID)
            _searchQuery.value = ""
        }
    }

    private suspend fun executeCatching(block: suspend () -> Unit) {
        executeCatchingReturn(block)
    }

    private suspend fun <T> executeCatchingReturn(block: suspend () -> T): T? {
        return try {
            block()
        } catch (t: Throwable) {
            _state.value = GalleryUiState.Error(
                t.message ?: "图库操作失败（${t::class.java.simpleName}）",
            )
            null
        }
    }

    class Factory(
        private val repository: GalleryRepository,
        private val ocrEngine: OcrEngine,
        private val visionAnalyzer: CloudVisionAnalyzer,
        private val reverseGeocoder: ReverseGeocoder,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return GalleryViewModel(
                repository, ocrEngine, visionAnalyzer, reverseGeocoder,
            ) as T
        }
    }
}
