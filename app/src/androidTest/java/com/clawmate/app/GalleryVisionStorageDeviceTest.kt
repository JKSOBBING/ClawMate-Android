package com.clawmate.app

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.clawmate.app.gallery.data.*
import com.clawmate.app.gallery.model.*
import com.clawmate.app.gallery.vision.GalleryVisionAnalysis
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Independent named test database; never opens the user's actual gallery database. */
@RunWith(AndroidJUnit4::class)
class GalleryVisionStorageDeviceTest {
    @Test fun metadataReplacementSurvivesDatabaseRecreationWithoutChangingImageIdentity() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "gallery-vision-test-${UUID.randomUUID()}.db"
        var db = Room.databaseBuilder(context, GalleryDatabase::class.java, name).build()
        val original = GalleryPhotoItem("fixture", "content://fixture/image", "2026-10-23", "旧标题", "旧分类", "旧摘要",
            tags = listOf("旧标签"), ocrText = "真实测试OCR", latitude = 12.34, longitude = 56.78, createdAt = 123,
            address = "测试地点", locationProvider = "fixture")
        val result = GalleryVisionAnalysis("设备测试杯子", "生活", "设备测试摘要", listOf("可见细节"), emptyList(), emptyList(), "{\"fixture\":true}")
        try {
            var repo = DefaultGalleryRepository(db.galleryDao())
            repo.upsert(original)
            assertFalse(repo.saveVisionAnalysis(original.id, "content://wrong/image", result))
            assertEquals(original.title, repo.getItem(original.id)!!.title)
            assertTrue(repo.saveVisionAnalysis(original.id, original.uri, result))
            db.close()
            db = Room.databaseBuilder(context, GalleryDatabase::class.java, name).build()
            repo = DefaultGalleryRepository(db.galleryDao())
            val stored = repo.getItem(original.id)!!
            assertEquals(result.title, stored.title)
            assertTrue(stored.tags.isEmpty())
            assertEquals(original.uri, stored.uri)
            assertEquals(original.date, stored.date)
            assertEquals(original.ocrText, stored.ocrText)
            assertEquals(original.latitude, stored.latitude)
            assertEquals(original.longitude, stored.longitude)
            assertEquals(original.address, stored.address)
            assertEquals(original.createdAt, stored.createdAt)
            assertEquals(GalleryPhotoItem.ANALYSIS_STATE_CLOUD_ANALYZED, stored.analysisState)
            repo.deleteItem(original.id)
            assertFalse(repo.saveVisionAnalysis(original.id, original.uri, result))
            assertEquals(0, repo.countItems())
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
