package com.clawmate.app.profile.avatar.data

import com.clawmate.app.profile.avatar.data.asset.AvatarAssetLimits
import com.clawmate.app.profile.avatar.data.asset.AvatarAssetStateModel
import com.clawmate.app.profile.avatar.data.asset.AvatarAssetStorageLogic
import com.clawmate.app.profile.avatar.data.asset.AvatarClearResult
import com.clawmate.app.profile.avatar.data.asset.AvatarImportResult
import com.clawmate.app.profile.avatar.data.asset.AvatarVersionRecord
import com.clawmate.app.profile.avatar.domain.AvatarGenerationMode
import java.util.concurrent.atomic.AtomicLong

/**
 * In-memory, deterministic [AvatarAssetRepository] for device-free unit tests.
 *
 * Mirrors the same bounded/correct pipeline as [DiskAvatarAssetRepository]:
 * byte cap → decode-like validity gate → atomic-in-memory version write →
 * reference-photo-kept state update → newest-first ordering. Because it shares
 * the pure logic in [AvatarAssetStorageLogic], tests exercise exactly the
 * ordering, index, deletion/clear and validation the production store uses.
 */
class FakeAvatarAssetRepository(
    private val initialState: AvatarAssetStateModel = AvatarAssetStateModel(),
    private val maxImportBytes: Int = AvatarAssetLimits.DEFAULT_MAX_IMPORT_BYTES,
    /** Optional validator that supports .NET-free real-image emulation: set false to simulate undecodable input. */
    private val decodes: Boolean = true,
) : AvatarAssetRepository {

    private data class Item(
        val record: AvatarVersionRecord,
        val bytes: ByteArray,
        val referencePhoto: Boolean,
    )

    private val items = LinkedHashMap<String, Item>()
    private var stateCopy = AvatarAssetStorageLogic.normalizeState(initialState)
    private val sequence = AtomicLong(System.currentTimeMillis() % 100_000)
    private var createdAtCounter = 0L

    override suspend fun listVersions(): List<AvatarVersionRecord> =
        AvatarAssetStorageLogic.versionsNewestFirst(items.values.map { it.record.copy() })

    override suspend fun loadState(): AvatarAssetStateModel = stateCopy.copy()

    override suspend fun currentVersion(): AvatarVersionRecord? {
        val id = stateCopy.currentVersionId
        return if (id.isBlank()) null else items[id]?.record?.copy()
    }

    override suspend fun importLocalImage(
        bytes: ByteArray,
        displayName: String,
        sourceMimeType: String,
    ): AvatarImportResult {
        if (bytes.isEmpty()) return AvatarImportResult(stateCopy.copy(), errorMessage = "所选图片为空")
        if (bytes.size > maxImportBytes) {
            return AvatarImportResult(
                stateCopy.copy(),
                errorMessage = "所选图片过大（超过 ${maxImportBytes / 1024} KB 限制）",
            )
        }
        if (!decodes) return AvatarImportResult(stateCopy.copy(), errorMessage = "所选文件不是可识别的图片")

        val extension = AvatarAssetStorageLogic.referencePhotoExtension(sourceMimeType)
        val versionId = "local-${sequence.incrementAndGet()}"
        createdAtCounter += 1
        // Deterministic, lexicographically-increasing "createdAt" so ordering
        // tests are stable and identical in any locale/clock.
        val createdAt = "2025-03-%02d".format(createdAtCounter)
        val record = AvatarVersionRecord(
            versionId = versionId,
            createdAt = createdAt,
            imageRelativePath = AvatarAssetStorageLogic.relativeAssetPath(versionId, extension),
            mode = AvatarGenerationMode.IMAGE_TO_IMAGE,
            provider = "local-import",
            model = "n/a",
            promptSnapshot = displayName.trim(),
            profileSnapshotHash = stateCopy.profileSnapshotHash,
        )
        items[versionId] = Item(record, bytes.copyOf(), referencePhoto = false)
        stateCopy = AvatarAssetStorageLogic.stateAfterImport(stateCopy, record)
        return AvatarImportResult(stateCopy.copy(), record.copy())
    }

    override suspend fun deleteVersion(versionId: String): Boolean {
        if (versionId.isBlank()) return false
        val removed = items.remove(versionId) != null
        if (stateCopy.currentVersionId == versionId) {
            val nextId = listVersions().firstOrNull()?.versionId
            if (nextId == null) {
                stateCopy = AvatarAssetStorageLogic.emptyState()
            } else {
                val next = items[nextId]!!.record
                stateCopy = AvatarAssetStorageLogic.stateAfterImport(stateCopy, next)
            }
        }
        return removed
    }

    override suspend fun clearAvatar(): AvatarClearResult {
        val cleared = items.size
        items.clear()
        stateCopy = AvatarAssetStorageLogic.emptyState()
        return AvatarClearResult(stateCopy.copy(), clearedVersionCount = cleared)
    }

    override fun absoluteAssetPath(relativePath: String): String? {
        val rel = relativePath.trim()
        return if (rel.isBlank()) null else "fake/avatar/$rel"
    }
}
