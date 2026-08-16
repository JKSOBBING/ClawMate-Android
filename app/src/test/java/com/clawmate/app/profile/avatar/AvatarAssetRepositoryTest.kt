package com.clawmate.app.profile.avatar

import com.clawmate.app.profile.avatar.data.FakeAvatarAssetRepository
import com.clawmate.app.profile.avatar.data.asset.AvatarAssetLimits
import com.clawmate.app.profile.avatar.data.asset.AvatarAssetStateModel
import com.clawmate.app.profile.avatar.domain.AvatarGenerationGate
import com.clawmate.app.profile.avatar.domain.AvatarGenerationState
import com.clawmate.app.profile.avatar.domain.AvatarProviderSettings
import com.clawmate.app.profile.avatar.domain.AvatarStatusToken
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Deterministic, device-free tests for the local avatar **asset repository**
 * (row A2 portable subset) over the in-memory fake, plus the honest
 * generation-boundary guarantee:
 *   - real local image import is persisted with metadata + newest-first history;
 *   - bounded input/validation rejects empty, oversized and undecodable input;
 *   - delete / clear behaviour resets history + state honestly;
 *   - importing a local image **never** flips the seed/cloud generation gate
 *     away from NOT_CONFIGURED — no fake "generating/ready" is ever produced.
 */
class AvatarAssetRepositoryTest {

    private val pngBytes = ByteArray(64) { (it % 256).toByte() }

    @Test
    fun `import persists a real metadata-version and moves it to current`() = runTest {
        val repo = FakeAvatarAssetRepository()
        val result = repo.importLocalImage(pngBytes, "本机相册照片", "image/png")
        assertTrue(result.success)
        val version = result.version!!
        // metadata is captured honestly
        assertEquals("local-import", version.provider)
        assertEquals("本机相册照片", version.promptSnapshot)
        assertTrue(version.createdAt.isNotBlank())
        assertEquals("assets/${version.versionId}.png", version.imageRelativePath)
        assertNotNull(repo.absoluteAssetPath(version.imageRelativePath))

        val state = repo.loadState()
        assertEquals(version.versionId, state.currentVersionId)
        assertEquals(AvatarStatusToken.CURRENT, state.status)
    }

    @Test
    fun `versions are listed newest-first after multiple imports`() = runTest {
        val repo = FakeAvatarAssetRepository()
        repo.importLocalImage(ByteArray(10), "first", "image/png")
        repo.importLocalImage(ByteArray(10), "second", "image/png")
        repo.importLocalImage(ByteArray(10), "third", "image/png")
        val history = repo.listVersions()
        assertEquals(3, history.size)
        // newest import is the current version and the first in history
        assertEquals(history[0].versionId, repo.currentVersion()!!.versionId)
        assertEquals(history[0].versionId, repo.loadState().currentVersionId)
    }

    @Test
    fun `empty input is rejected and nothing is persisted`() = runTest {
        val repo = FakeAvatarAssetRepository()
        val empty = repo.importLocalImage(ByteArray(0), "label", "image/png")
        assertFalse(empty.success)
        assertNotNull(empty.errorMessage)
        assertNull(empty.version)
        assertTrue(repo.listVersions().isEmpty())
        assertEquals(AvatarStatusToken.EMPTY, repo.loadState().status)
    }

    @Test
    fun `oversized input is rejected within the byte budget`() = runTest {
        val repo = FakeAvatarAssetRepository(maxImportBytes = 16)
        val oversized = repo.importLocalImage(ByteArray(64), "big", "image/png")
        assertFalse(oversized.success)
        assertNotNull(oversized.errorMessage)
        assertNull(oversized.version)
        assertTrue(repo.listVersions().isEmpty())
    }

    @Test
    fun `undecodable input is rejected as not a real image`() = runTest {
        val repo = FakeAvatarAssetRepository(decodes = false)
        val result = repo.importLocalImage(ByteArray(64), "not an image", "image/png")
        assertFalse(result.success)
        assertNotNull(result.errorMessage)
        assertTrue(repo.listVersions().isEmpty())
    }

    @Test
    fun `delete removes a version and repoints current`() = runTest {
        val repo = FakeAvatarAssetRepository()
        repo.importLocalImage(ByteArray(10), "one", "image/png")
        repo.importLocalImage(ByteArray(10), "two", "image/png")
        val two = repo.currentVersion()!!.versionId
        val removed = repo.deleteVersion(two)
        assertTrue(removed)
        assertEquals(1, repo.listVersions().size)
        assertTrue(repo.loadState().currentVersionId != two)
    }

    @Test
    fun `delete of the last version resets the state to honest empty`() = runTest {
        val repo = FakeAvatarAssetRepository()
        repo.importLocalImage(ByteArray(10), "only", "image/png")
        val only = repo.currentVersion()!!.versionId
        repo.deleteVersion(only)
        assertNull(repo.currentVersion())
        assertEquals(AvatarStatusToken.EMPTY, repo.loadState().status)
        assertTrue(repo.listVersions().isEmpty())
    }

    @Test
    fun `clear empties all versions and assets and resets state`() = runTest {
        val repo = FakeAvatarAssetRepository()
        repeat(3) { repo.importLocalImage(ByteArray(10), "v$it", "image/png") }
        val clear = repo.clearAvatar()
        assertEquals(3, clear.clearedVersionCount)
        assertEquals(AvatarStatusToken.EMPTY, clear.state.status)
        assertTrue(repo.listVersions().isEmpty())
        assertNull(repo.currentVersion())
        // clearing again is a no-op that still returns honest empty
        val again = repo.clearAvatar()
        assertEquals(0, again.clearedVersionCount)
        assertFalse(again.state.currentVersionId.isNotEmpty())
    }

    @Test
    fun `importing a local image never flips the generation gate to non-not-configured`() = runTest {
        val repo = FakeAvatarAssetRepository()
        repo.importLocalImage(pngBytes, "本地照片", "image/png")
        // Even with a real local asset present and status CURRENT, the seed/cloud
        // avatar generation gate reads only the (blank) provider settings and
        // must remain NOT_CONFIGURED — never "generating"/"ready".
        val gate = AvatarGenerationGate.state(AvatarProviderSettings(imageBaseUrl = "", apiKey = ""))
        assertEquals(AvatarGenerationState.NOT_CONFIGURED, gate)
        // A record that claims local-import exists does not make that a lie.
        assertEquals("local-import", repo.currentVersion()!!.provider)
        assertFalse(AvatarGenerationState.CONFIGURED_BUT_NO_PROVIDER == gate)
    }

    @Test
    fun `absolute path resolution is honest per relative patch`() = runTest {
        val repo = FakeAvatarAssetRepository()
        val result = repo.importLocalImage(ByteArray(10), "label", "image/png")
        val rel = result.version!!.imageRelativePath
        val resolved = repo.absoluteAssetPath(rel)
        assertNotNull(resolved)
        assertTrue(resolved!!.contains(rel))
        assertNull(repo.absoluteAssetPath(""))
        assertNull(repo.absoluteAssetPath("   "))
    }
}
