package com.clawmate.app.gallery.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface GalleryDao {

    @Query("SELECT * FROM gallery_items ORDER BY date DESC, createdAt DESC")
    fun observeItems(): Flow<List<GalleryItemEntity>>

    @Query("SELECT * FROM gallery_items ORDER BY date DESC, createdAt DESC")
    suspend fun getAllItems(): List<GalleryItemEntity>

    @Query("SELECT * FROM gallery_items WHERE id = :id")
    suspend fun getItem(id: String): GalleryItemEntity?

    @Query("SELECT * FROM gallery_items WHERE uri = :uri LIMIT 1")
    suspend fun getByUri(uri: String): GalleryItemEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: GalleryItemEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(entity: GalleryItemEntity): Long

    @Query("SELECT COUNT(*) FROM gallery_items")
    suspend fun count(): Int

    @Query("DELETE FROM gallery_items WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("UPDATE gallery_items SET title = :title WHERE id = :id")
    suspend fun updateTitle(id: String, title: String): Int

    @Query("UPDATE gallery_items SET summary = :summary WHERE id = :id")
    suspend fun updateSummary(id: String, summary: String): Int

    @Query("UPDATE gallery_items SET tags = :tagsJson WHERE id = :id")
    suspend fun updateTags(id: String, tagsJson: String): Int

    @Query("UPDATE gallery_items SET address = :address, locationProvider = :provider WHERE id = :id")
    suspend fun updateLocation(id: String, address: String, provider: String): Int

    /**
     * Persist OCR output only when the user action really succeeded: real
     * recognized text plus the analysis status. An honest empty result stores
     * empty text and the "analyzed" status (nothing fabricated).
     */
    @Query("UPDATE gallery_items SET ocrText = :ocrText, analysisState = :analysisState WHERE id = :id")
    suspend fun updateOcr(id: String, ocrText: String, analysisState: String): Int

    @Query("UPDATE gallery_items SET analysisState = :state WHERE id = :id")
    suspend fun updateAnalysisState(id: String, state: String): Int

    @Transaction
    suspend fun applyVisionAnalysis(id: String, expectedUri: String, analysis: com.clawmate.app.gallery.vision.GalleryVisionAnalysis): Boolean {
        val existing = getItem(id) ?: return false
        if (existing.uri != expectedUri) return false
        upsert(existing.copy(title = analysis.title, category = analysis.category, summary = analysis.summary,
            details = GalleryMappers.listToJson(analysis.details), tags = GalleryMappers.listToJson(analysis.tags),
            keywords = GalleryMappers.listToJson(analysis.keywords), rawModelText = analysis.rawText,
            analysisState = com.clawmate.app.gallery.model.GalleryPhotoItem.ANALYSIS_STATE_CLOUD_ANALYZED))
        return true
    }

    @Query("DELETE FROM gallery_items")
    suspend fun clear()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGroup(entity: CollectionGroupEntity)

    @Query("DELETE FROM collection_groups WHERE tabId = :tabId")
    suspend fun deleteGroupsForTab(tabId: String)

    @Query("SELECT * FROM collection_groups WHERE tabId = :tabId ORDER BY time DESC")
    fun observeGroupsForTab(tabId: String): Flow<List<CollectionGroupEntity>>

    @Query("SELECT count(*) FROM gallery_items_fts WHERE gallery_items_fts MATCH :query")
    suspend fun ftsMatchCount(query: String): Int
}
