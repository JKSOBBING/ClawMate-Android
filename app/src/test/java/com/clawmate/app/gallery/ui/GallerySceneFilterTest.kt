package com.clawmate.app.gallery.ui

import com.clawmate.app.gallery.model.GalleryPhotoItem
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GallerySceneFilterTest {
    private fun item(category: String, title: String = "", tags: List<String> = emptyList()) =
        GalleryPhotoItem(
            id = "1",
            uri = "content://1",
            date = "2026-08-20",
            title = title,
            category = category,
            summary = "",
            tags = tags,
            createdAt = 1L,
        )

    @Test
    fun allSceneKeepsEveryStoredItem() {
        assertTrue(gallerySceneMatches(item("任意"), "全部"))
    }

    @Test
    fun sceneMatchesStoredMetadataWithoutFabricatingData() {
        assertTrue(gallerySceneMatches(item("", title = "酒店预订"), "差旅"))
        assertTrue(gallerySceneMatches(item("其他", tags = listOf("微信")), "聊天"))
        assertFalse(gallerySceneMatches(item("风景", title = "公园"), "购物"))
    }
}
