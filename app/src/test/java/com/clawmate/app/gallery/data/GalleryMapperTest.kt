package com.clawmate.app.gallery.data

import com.clawmate.app.gallery.model.CollectionGroup
import com.clawmate.app.gallery.model.GalleryExifMetadata
import com.clawmate.app.gallery.model.GalleryPhotoItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Deterministic mapper tests (domain <-> Room entity, bijective for the
 * fields used by the gallery slice).
 */
class GalleryMapperTest {

    private fun sampleItem() = GalleryPhotoItem(
        id = "gx_123",
        uri = "content://media/picker/123",
        date = "2026-06-01",
        title = "早餐聊天截图",
        category = "餐饮",
        summary = "去楼下买豆浆包子",
        details = listOf("两个包子", "一杯豆浆"),
        tags = listOf("早餐", "微信"),
        ocrText = "买豆浆和包子",
        rawModelText = "{\"title\":\"...\"}",
        keywords = listOf("早餐", "豆浆"),
        latitude = 31.20,
        longitude = 121.47,
        exif = GalleryExifMetadata(
            dateTimeOriginal = "2026:06:01 08:12:20",
            deviceModel = "TestPhone",
            imageWidth = 4096,
            imageHeight = 3072,
        ),
        createdAt = 1780316461000L,
    )

    @Test
    fun entityToDomainToEntityRoundTrip() {
        val original = sampleItem()
        val entity = GalleryMappers.toEntity(original)
        val back = GalleryMappers.fromEntity(entity)
        assertEquals(original, back)
        assertEquals(original.exif, back.exif)
    }

    @Test
    fun listFieldsSerializeToJsonAndBack() {
        assertEquals("[\"早餐\",\"微信\"]", GalleryMappers.listToJson(listOf("早餐", "微信")))
        assertEquals(listOf("早餐", "微信"), GalleryMappers.jsonToList("[\"早餐\",\"微信\"]"))
        assertEquals("", GalleryMappers.listToJson(emptyList()))
        assertEquals(emptyList<String>(), GalleryMappers.jsonToList(""))
    }

    @Test
    fun exifRoundTripThroughJson() {
        val exif = GalleryExifMetadata(
            imageWidth = 4096,
            latitude = 31.2,
            longitude = 121.47,
        )
        val json = GalleryMappers.exifToJson(exif)
        val back = GalleryMappers.exifFromJson(json)
        assertEquals(exif, back)
    }

    @Test
    fun missingExifMappingGivesNull() {
        assertNull(GalleryMappers.exifFromJson(""))
        assertEquals("", GalleryMappers.exifToJson(null))
    }

    @Test
    fun groupRoundTrip() {
        val group = CollectionGroup(
            groupId = "grp_x",
            title = "早餐图片",
            source = "餐饮",
            time = "2026-06-01",
            meta = "2 条",
            status = "已整理",
            domain = "eat",
            eventIds = listOf("a", "b"),
        )
        val entity = GalleryMappers.toGroupEntity("gallery", group)
        assertEquals(group, GalleryMappers.fromGroupEntity(entity))
    }
}
