package com.clawmate.app.gallery.data

import com.clawmate.app.gallery.collection.CollectionGroupingService
import com.clawmate.app.gallery.insights.GalleryMonthlyInsights
import com.clawmate.app.gallery.model.CollectionGroup
import com.clawmate.app.gallery.model.GalleryPhotoItem
import com.clawmate.app.gallery.model.GallerySearchHit
import com.clawmate.app.gallery.search.GallerySearchService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Room-backed repository for the gallery slice.
 *
 * Deterministic behaviour:
 *  - an item is deduplicated by its content URI (re-picking the same photo
 *    updates, never duplicates);
 *  - collection groups are recomputed from stored items with the deterministic
 *    [CollectionGroupingService];
 *  - search uses the deterministic [GallerySearchService] over stored metadata
 *    (the FTS table is maintained alongside for fast matching, but results are
 *    always ordered and scored by the shared service).
 *
 * No analysis is performed here: OCR / cloud vision / reverse geocoding are the
 * responsibility of the (currently unconfigured) capability engines.
 */
class DefaultGalleryRepository(
    private val dao: GalleryDao,
    private val groupingService: CollectionGroupingService = CollectionGroupingService,
    private val searchService: GallerySearchService = GallerySearchService,
) : GalleryRepository {

    override fun observeItems(): Flow<List<GalleryPhotoItem>> =
        dao.observeItems().map { list -> list.map(GalleryMappers::fromEntity) }

    override fun observeMonthlyInsights(): Flow<List<com.clawmate.app.gallery.insights.GalleryMonthInsight>> =
        observeItems().map { items ->
            // Pure derivation over stored items — never fabricates an insight and
            // never scans any media (only Room rows contribute).
            GalleryMonthlyInsights.build(items)
        }

    override suspend fun getItem(id: String): GalleryPhotoItem? =
        dao.getItem(id)?.let(GalleryMappers::fromEntity)

    override suspend fun saveVisionAnalysis(id: String, expectedUri: String,
        analysis: com.clawmate.app.gallery.vision.GalleryVisionAnalysis): Boolean = dao.applyVisionAnalysis(id, expectedUri, analysis)

    override suspend fun upsert(item: GalleryPhotoItem): GalleryPhotoItem {
        val existing = dao.getByUri(item.uri)
        val toStore = existing?.let { e ->
            item.copy(
                id = e.id,
                // Preserve previously stored analysis fields; never overwrite a
                // real value with an empty one.
                title = if (item.title.isBlank()) e.title else item.title,
                category = if (item.category.isBlank()) e.category else item.category,
                summary = if (item.summary.isBlank()) e.summary else item.summary,
                details = if (item.details.isEmpty()) GalleryMappers.jsonToList(e.details) else item.details,
                tags = if (item.tags.isEmpty()) GalleryMappers.jsonToList(e.tags) else item.tags,
            )
        } ?: item

        dao.upsert(GalleryMappers.toEntity(toStore))
        return toStore
    }

    override suspend fun importItems(items: List<GalleryPhotoItem>): List<GalleryPhotoItem> {
        // Deterministic order (by id) so re-imports behave identically.
        return items.sortedBy { it.id }.map { upsert(it) }
    }

    override suspend fun countItems(): Int = dao.count()

    override suspend fun refreshGroups(tabId: String): List<CollectionGroup> {
        val items = dao.getAllItems().map(GalleryMappers::fromEntity)
        val groups = groupingService.groupPhotos(tabId, items)
        dao.deleteGroupsForTab(tabId)
        groups.forEach { dao.upsertGroup(GalleryMappers.toGroupEntity(tabId, it)) }
        return groups
    }

    override fun observeGroups(): Flow<List<CollectionGroup>> {
        // The gallery slice observes the generic tab for now.
        return dao.observeGroupsForTab(GALLERY_TAB_ID).map { list ->
            list.map(GalleryMappers::fromGroupEntity)
        }
    }

    override suspend fun search(query: String): List<GallerySearchHit> {
        val items = dao.getAllItems().map(GalleryMappers::fromEntity)
        return searchService.search(query, items)
    }

    override suspend fun updateTitle(id: String, title: String): Boolean =
        dao.updateTitle(id, title.trim()) > 0

    override suspend fun updateSummary(id: String, summary: String): Boolean =
        dao.updateSummary(id, summary.trim()) > 0

    override suspend fun updateTags(id: String, tags: List<String>): Boolean =
        dao.updateTags(id, GalleryMappers.listToJson(tags)) > 0

    override suspend fun updateLocation(id: String, address: String, provider: String): Boolean =
        dao.updateLocation(id, address.trim(), provider.trim()) > 0

    override suspend fun updateOcr(id: String, ocrText: String, analysisState: String): Boolean =
        dao.updateOcr(id, ocrText, analysisState.trim().ifBlank { GalleryPhotoItem.ANALYSIS_STATE_NONE }) > 0

    override suspend fun deleteItem(id: String): Boolean {
        val deleted = dao.deleteById(id) > 0
        if (deleted) refreshGroups(GALLERY_TAB_ID)
        return deleted
    }

    override suspend fun itemGroupMembership(itemId: String): List<CollectionGroup> {
        return dao.observeGroupsForTab(GALLERY_TAB_ID).first()
            .map(GalleryMappers::fromGroupEntity)
            .filter { it.eventIds.contains(itemId) }
    }

    companion object {
        /** The generic grouping tab used by the gallery screen. */
        const val GALLERY_TAB_ID = "gallery"
    }
}
