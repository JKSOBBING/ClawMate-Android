package com.clawmate.app.gallery.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.clawmate.app.gallery.analysis.CloudVisionAnalyzer
import com.clawmate.app.gallery.analysis.CapabilityState
import com.clawmate.app.gallery.analysis.OcrEngine
import com.clawmate.app.gallery.analysis.ReverseGeocoder
import com.clawmate.app.gallery.data.DefaultGalleryRepository
import com.clawmate.app.gallery.data.GalleryRepository
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

    private fun buildItem(context: Context, uri: Uri): GalleryPhotoItem {
        val createdAt = System.currentTimeMillis()
        return GalleryPhotoItem(
            id = com.clawmate.app.gallery.model.GalleryIds.stableIdForUri(uri.toString()),
            uri = uri.toString(),
            date = mediaReader.resolveDate(context, uri),
            title = "图库图片",
            category = "",
            summary = "",
            exif = mediaReader.resolveExif(context, uri),
            createdAt = createdAt,
        )
    }

    private suspend fun executeCatching(block: suspend () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            _state.value = GalleryUiState.Error(
                t.message ?: "图库操作失败（${t::class.java.simpleName}）",
            )
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
