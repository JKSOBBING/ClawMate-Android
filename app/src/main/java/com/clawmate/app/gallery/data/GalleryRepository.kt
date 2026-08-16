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
}
