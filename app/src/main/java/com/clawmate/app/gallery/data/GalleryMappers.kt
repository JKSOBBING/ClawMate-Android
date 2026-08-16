package com.clawmate.app.gallery.data

import com.clawmate.app.gallery.model.CollectionGroup
import com.clawmate.app.gallery.model.GalleryExifMetadata
import com.clawmate.app.gallery.model.GalleryPhotoItem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * Deterministic mappers between the gallery domain models and Room entities.
 *
 * String-list fields are serialised as JSON array literals ("" when empty),
 * matching the eager-JSON style the HarmonyOS item files use. Mapping is
 * bijective for unit testing.
 */
object GalleryMappers {

    internal val strictJson = Json { isLenient = false; ignoreUnknownKeys = true }

    fun toEntity(item: GalleryPhotoItem): GalleryItemEntity = GalleryItemEntity(
        id = item.id,
        uri = item.uri,
        date = item.date,
        title = item.title,
        category = item.category,
        summary = item.summary,
        details = listToJson(item.details),
        tags = listToJson(item.tags),
        ocrText = item.ocrText,
        rawModelText = item.rawModelText,
        keywords = listToJson(item.keywords),
        latitude = item.latitude,
        longitude = item.longitude,
        exifJson = exifToJson(item.exif),
        createdAt = item.createdAt,
    )

    fun fromEntity(entity: GalleryItemEntity): GalleryPhotoItem = GalleryPhotoItem(
        id = entity.id,
        uri = entity.uri,
        date = entity.date,
        title = entity.title,
        category = entity.category,
        summary = entity.summary,
        details = jsonToList(entity.details),
        tags = jsonToList(entity.tags),
        ocrText = entity.ocrText,
        rawModelText = entity.rawModelText,
        keywords = jsonToList(entity.keywords),
        latitude = entity.latitude,
        longitude = entity.longitude,
        exif = exifFromJson(entity.exifJson),
        createdAt = entity.createdAt,
    )

    fun toGroupEntity(tabId: String, group: CollectionGroup): CollectionGroupEntity =
        CollectionGroupEntity(
            groupId = group.groupId,
            tabId = tabId,
            title = group.title,
            source = group.source,
            time = group.time,
            meta = group.meta,
            status = group.status,
            domain = group.domain,
            eventIds = listToJson(group.eventIds),
        )

    fun fromGroupEntity(entity: CollectionGroupEntity): CollectionGroup = CollectionGroup(
        groupId = entity.groupId,
        title = entity.title,
        source = entity.source,
        time = entity.time,
        meta = entity.meta,
        status = entity.status,
        domain = entity.domain,
        eventIds = jsonToList(entity.eventIds),
    )

    internal fun listToJson(values: List<String>): String {
        if (values.isEmpty()) return ""
        val arr = buildJsonArray { values.forEach { add(it) } }
        return arr.toString()
    }

    internal fun jsonToList(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        return runCatching {
            strictJson.parseToJsonElement(raw).jsonArray.map { el ->
                el.jsonPrimitive.content
            }
        }.getOrDefault(emptyList())
    }

    internal fun exifToJson(exif: GalleryExifMetadata?): String {
        exif ?: return ""
        val arr = buildJsonArray {
            add(JsonPrimitive(exif.dateTimeOriginal))
            add(JsonPrimitive(exif.deviceMake))
            add(JsonPrimitive(exif.deviceModel))
            add(exif.imageWidth?.let { JsonPrimitive(it) } ?: JsonNull)
            add(exif.imageHeight?.let { JsonPrimitive(it) } ?: JsonNull)
            add(exif.latitude?.let { JsonPrimitive(it) } ?: JsonNull)
            add(exif.longitude?.let { JsonPrimitive(it) } ?: JsonNull)
        }
        return arr.toString()
    }

    internal fun exifFromJson(raw: String): GalleryExifMetadata? {
        if (raw.isBlank()) return null
        return runCatching {
            val arr = strictJson.parseToJsonElement(raw).let { it as JsonArray }
            fun s(i: Int): String? = arr.getOrNull(i)?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
            fun d(i: Int): Double? = arr.getOrNull(i)?.takeIf { it !is JsonNull }?.jsonPrimitive?.content?.toDoubleOrNull()
            fun n(i: Int): Int? = arr.getOrNull(i)?.takeIf { it !is JsonNull }?.jsonPrimitive?.content?.toIntOrNull()
            GalleryExifMetadata(
                dateTimeOriginal = s(0),
                deviceMake = s(1),
                deviceModel = s(2),
                imageWidth = n(3),
                imageHeight = n(4),
                latitude = d(5),
                longitude = d(6),
            )
        }.getOrNull()
    }
}
