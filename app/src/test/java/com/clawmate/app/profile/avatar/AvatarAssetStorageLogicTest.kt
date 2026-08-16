package com.clawmate.app.profile.avatar

import com.clawmate.app.profile.avatar.data.asset.AvatarAssetStateModel
import com.clawmate.app.profile.avatar.data.asset.AvatarAssetStorageLogic
import com.clawmate.app.profile.avatar.data.asset.AvatarVersionRecord
import com.clawmate.app.profile.avatar.domain.AvatarGenerationMode
import com.clawmate.app.profile.avatar.domain.AvatarStatusToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Device-free tests for the pure port of Harmony `AvatarStorage.ets`
 * (the local asset-repository logic of migration row A2): newest-first version
 * ordering/metadata, version-index dedup, extension resolution, reference-photo
 * name + stale-file selection, and state normalisation. No device/network.
 */
class AvatarAssetStorageLogicTest {

    private fun record(
        id: String,
        at: String,
        image: String = "assets/$id.jpg",
        provider: String = "local-import",
    ) = AvatarVersionRecord(
        versionId = id,
        createdAt = at,
        imageRelativePath = image,
        mode = AvatarGenerationMode.IMAGE_TO_IMAGE,
        provider = provider,
        model = "n/a",
    )

    @Test
    fun `versions are ordered newest first by createdAt`() {
        val ordered = AvatarAssetStorageLogic.versionsNewestFirst(
            listOf(record("older", "2025-03-01"), record("newest", "2025-03-09"), record("mid", "2025-03-05")),
        )
        assertEquals(listOf("newest", "mid", "older"), ordered.map { it.versionId })
        // Metadata survives the ordering pass intact.
        assertEquals("assets/newest.jpg", ordered.first().imageRelativePath)
        assertEquals("local-import", ordered.first().provider)
    }

    @Test
    fun `index append dedupes and ignores blank ids deterministically`() {
        assertEquals(
            listOf("a", "b"),
            AvatarAssetStorageLogic.appendVersionId(listOf("a", "b"), "a"),
        )
        assertTrue(AvatarAssetStorageLogic.appendVersionId(emptyList(), "  ").isEmpty())
        assertEquals(
            listOf("x", "y"),
            AvatarAssetStorageLogic.appendVersionId(listOf("x"), "y"),
        )
        // ordering is insertion order preserved, duplicates suppressed.
        assertEquals(listOf("1", "2"), AvatarAssetStorageLogic.appendVersionId(listOf("1", "2"), "2"))
    }

    @Test
    fun `asset extension resolves from output format`() {
        assertEquals(".jpg", AvatarAssetStorageLogic.assetExtension("jpeg"))
        assertEquals(".png", AvatarAssetStorageLogic.assetExtension("png"))
        assertEquals(".webp", AvatarAssetStorageLogic.assetExtension("webp"))
        assertEquals(".jpg", AvatarAssetStorageLogic.assetExtension(null))
        assertEquals("assets/v1.png", AvatarAssetStorageLogic.relativeAssetPath("v1", "png"))
    }

    @Test
    fun `reference photo extension follows known image sources else jpg`() {
        assertEquals(".png", AvatarAssetStorageLogic.referencePhotoExtension("data:image/png;base64,xx"))
        assertEquals(".webp", AvatarAssetStorageLogic.referencePhotoExtension("photo.webp"))
        assertEquals(".heic", AvatarAssetStorageLogic.referencePhotoExtension("photo.heic"))
        assertEquals(".jpg", AvatarAssetStorageLogic.referencePhotoExtension("unknown-arbitrary-input"))
        assertEquals(".jpg", AvatarAssetStorageLogic.referencePhotoExtension(""))
    }

    @Test
    fun `stale reference photo selection keeps only the newest`() {
        val stale = AvatarAssetStorageLogic.staleReferencePhotoNames(
            keepName = "reference-photo-999-2.png",
            assetNames = listOf(
                "reference-photo-111-1.png",
                "reference-photo-999-2.png",
                "assets/local-1.jpg", // unrelated asset is never selected
            ),
        )
        assertEquals(listOf("reference-photo-111-1.png"), stale)
    }

    @Test
    fun `reference photo name is deterministic for timestamp and sequence`() {
        assertEquals(
            "reference-photo-100-3.png",
            AvatarAssetStorageLogic.referencePhotoName(100L, 3L, ".PNG"),
        )
    }

    @Test
    fun `state normalisation trims and keeps a known status token`() {
        val normal = AvatarAssetStorageLogic.normalizeState(
            AvatarAssetStateModel(
                status = AvatarStatusToken.CURRENT,
                lastErrorCode = " keep ",
            ),
        )
        assertEquals("keep", normal.lastErrorCode)
        assertEquals(AvatarStatusToken.CURRENT, normal.status)
        // A failed status from storage is preserved, never invented.
        assertEquals(
            AvatarStatusToken.FAILED,
            AvatarAssetStorageLogic.normalizeState(AvatarAssetStateModel(status = AvatarStatusToken.FAILED)).status,
        )
    }

    @Test
    fun `oversized metadata record is rejected by the size guard`() {
        val small = AvatarVersionRecord(
            versionId = "id",
            imageRelativePath = "assets/id.jpg",
            provider = "local-import",
            promptSnapshot = "x".repeat(16),
        )
        assertFalse(AvatarAssetStorageLogic.isRecordOversized(small, maxBytes = 1000))
        val big = small.copy(promptSnapshot = "x".repeat(5000))
        assertTrue(AvatarAssetStorageLogic.isRecordOversized(big, maxBytes = 100))
    }

    @Test
    fun `empty state matches the honest default`() {
        val s = AvatarAssetStorageLogic.emptyState()
        assertEquals("", s.currentVersionId)
        assertEquals(AvatarStatusToken.EMPTY, s.status)
    }
}
