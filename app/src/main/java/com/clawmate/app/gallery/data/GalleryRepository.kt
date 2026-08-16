package com.clawmate.app.gallery.data

import com.clawmate.app.gallery.model.CollectionGroup
import com.clawmate.app.gallery.model.GalleryPhotoItem
import com.clawmate.app.gallery.model.GallerySearchHit
import kotlinx.coroutines.flow.Flow

/**
 * Repository boundary for gallery photo items, collection groups and search.
 *
 * Backed by Room (see [GalleryDatabase]). Persistence is deterministic: a
 * picked photo with the same content URI and date maps to the same stored item
 * (never duplicated on re-pick); unsaved analysis fields are stored as-is and
 * never fabricated.
 */
interface GalleryRepository {

    /** Observe all imported photo items (newest first). */
    fun observeItems(): Flow<List<GalleryPhotoItem>>

    /** Get one item by id. */
    suspend fun getItem(id: String): GalleryPhotoItem?

    /**
     * Upsert a picked photo. Returns the item as stored (with its real id).
     * If the metadata carries no analysis yet, that is preserved honestly.
     */
    suspend fun upsert(item: GalleryPhotoItem): GalleryPhotoItem

    /** Import a batch of picked photos as described (deduplicated by id). */
    suspend fun importItems(items: List<GalleryPhotoItem>): List<GalleryPhotoItem>

    /** How many items are stored (used for UI state). */
    suspend fun countItems(): Int

    /** Recompute collection groups over all stored items. */
    suspend fun refreshGroups(tabId: String): List<CollectionGroup>

    /** Observe the most recent collection group set. */
    fun observeGroups(): Flow<List<CollectionGroup>>

    /** Search stored items. */
    suspend fun search(query: String): List<GallerySearchHit>

    // ---- Explicit safe edit operations (persisted to Room) ----

    /** Set an item's display title. No-op if the item or the id is absent. */
    suspend fun updateTitle(id: String, title: String): Boolean

    /** Set an item's summary. */
    suspend fun updateSummary(id: String, summary: String): Boolean

    /** Replace an item's tags list. */
    suspend fun updateTags(id: String, tags: List<String>): Boolean

    /** Persist a resolved reverse-geocoded place (only after a real success). */
    suspend fun updateLocation(id: String, address: String, provider: String): Boolean

    /**
     * Persist on-device OCR output **only after the user's Analyze action really
     * succeeded**. [ocrText] carries real recognized text (or "" for a genuine
     * empty image); [analysisState] records the "analyzed" status. Callers must
     * pass text guarded by the OCR no-fake-output check; never fake text here.
     */
    suspend fun updateOcr(id: String, ocrText: String, analysisState: String): Boolean

    /**
     * Delete a single item **by id** (keeps its content-URI record semantics:
     * [deleteItem] removes only the stored item row, never a broad file / URI
     * grant). Returns true when a row was removed.
     */
    suspend fun deleteItem(id: String): Boolean

    /** All collection groups the item belongs to (by stored event ids). */
    suspend fun itemGroupMembership(itemId: String): List<CollectionGroup>
}
