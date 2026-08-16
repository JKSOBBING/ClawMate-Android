package com.clawmate.app.gallery.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for a single gallery photo item.
 *
 * Mirrors the item shape in docs/gallery/storage-format.md
 * (`gallery-log/items/<date>/<id>.json`). Every column is nullable-or-defaulted
 * so that a picked photo with no model analysis yet still persists honestly.
 */
@Entity(
    tableName = "gallery_items",
    indices = [Index(value = ["date"]), Index(value = ["category"]), Index(value = ["uri"], unique = true)],
)
data class GalleryItemEntity(
    @PrimaryKey val id: String,
    val uri: String,
    val date: String,
    val title: String,
    val category: String,
    val summary: String,
    val details: String, // JSON array literal "" when empty
    val tags: String,    // JSON array literal "" when empty
    val ocrText: String,
    val rawModelText: String,
    val keywords: String, // JSON array literal "" when empty
    val latitude: Double?,
    val longitude: Double?,
    val exifJson: String,
    val createdAt: Long,
)

@Entity(tableName = "collection_groups")
data class CollectionGroupEntity(
    @PrimaryKey val groupId: String,
    val tabId: String,
    val title: String,
    val source: String,
    val time: String,
    val meta: String,
    val status: String,
    val domain: String,
    val eventIds: String, // JSON array literal
)

/**
 * Room FTS4 table over searchable item metadata. Materialised alongside
 * [GalleryItemEntity]; kept as a separate table so item updates can re-sync
 * deterministically without triggering a destructive migration.
 */
@Fts4(contentEntity = GalleryItemEntity::class)
@Entity(tableName = "gallery_items_fts")
data class GalleryItemFtsEntity(
    @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "category") val category: String,
    @ColumnInfo(name = "summary") val summary: String,
    @ColumnInfo(name = "details") val details: String,
    @ColumnInfo(name = "tags") val tags: String,
    @ColumnInfo(name = "ocrText") val ocrText: String,
    @ColumnInfo(name = "date") val date: String,
)
