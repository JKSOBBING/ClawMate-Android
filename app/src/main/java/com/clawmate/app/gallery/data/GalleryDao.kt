package com.clawmate.app.gallery.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
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
