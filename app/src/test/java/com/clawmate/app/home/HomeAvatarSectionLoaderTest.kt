package com.clawmate.app.home

import com.clawmate.app.home.data.HomeAvatarSectionLoader
import com.clawmate.app.home.domain.AvatarSection
import com.clawmate.app.home.domain.AvatarStatus
import com.clawmate.app.home.domain.HomeAvatarSectionMapper
import com.clawmate.app.profile.avatar.data.DiskAvatarAssetRepository
import com.clawmate.app.profile.avatar.data.FakeAvatarAssetRepository
import com.clawmate.app.profile.avatar.data.FakeProfileAvatarRepository
import com.clawmate.app.profile.avatar.data.asset.AvatarVersionRecord
import com.clawmate.app.profile.avatar.domain.AvatarStatusToken
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Device-free tests for the Phase-48 Home avatar read path:
 *  - [HomeAvatarSectionMapper] maps read avatar asset state honestly;
 *  - [HomeAvatarSectionLoader] surfaces a **real persisted avatar** on Home only
 *    when a current version exists **and** its asset file is present, readable
 *    and passes the safety gate; every empty / missing / corrupt / deleted /
 *    bad-reference case falls back to [AvatarSection.notConfigured];
 *  - a persistence restart (a fresh repository read back over the same temp
 *    avatar root) yields the same avatar section — proving process-restart read.
 *
 * No Android class is touched: the production file store is driven via its
 * [DiskAvatarAssetRepository.defaultBaseDir] construction seam, and the safety
 * gate is injected as a JVM [HomeAvatarSectionLoader.ReadableImageCheck].
 */
class HomeAvatarSectionLoaderTest {

    /** Readability seam that treats any absolute path as a readable real image. */
    private fun alwaysReadable() = HomeAvatarSectionLoader.ReadableImageCheck { abs ->
        abs?.takeIf { it.isNotBlank() }?.let { "file://$it" }
    }

    /** Readability seam that only accepts a path that actually exists on disk. */
    private fun onlyIfFileExists() = HomeAvatarSectionLoader.ReadableImageCheck { abs ->
        val path = abs?.trim().orEmpty()
        if (path.isEmpty() || !File(path).isFile) null else "file://$path"
    }

    /** Readability seam that rejects empty / unreadable files (no decode, pure JVM). */
    private fun onlyIfReadableNonEmpty() = HomeAvatarSectionLoader.ReadableImageCheck { abs ->
        val path = abs?.trim().orEmpty()
        if (path.isEmpty()) return@ReadableImageCheck null
        val file = File(path)
        if (!file.isFile || !file.canRead() || file.length() <= 0L) null else "file://$path"
    }

    /** Readability seam that always rejects (missing / corrupt / unreadable). */
    private fun alwaysReject() = HomeAvatarSectionLoader.ReadableImageCheck { null }

    private suspend fun seedRepo(repo: FakeAvatarAssetRepository): AvatarVersionRecord {
        val result = repo.importLocalImage(ByteArray(64) { it.toByte() }, "本机相册照片", "image/png")
        check(result.success)
        return result.version!!
    }

    @Test
    fun `real persisted avatar and readable asset are surfaced on Home`() = runTest {
        val repo = FakeAvatarAssetRepository()
        val version = seedRepo(repo)
        val section = HomeAvatarSectionLoader.load(repo, alwaysReadable())

        assertTrue(section.hasAvatarImage)
        assertTrue(section.imageUri.isNotEmpty())
        assertTrue(section.imageUri.startsWith("file://"))
        // The URI points at the real asset the repository resolved.
        assertEquals("file://${repo.absoluteAssetPath(version.imageRelativePath)}", section.imageUri)
        assertEquals(AvatarStatus.CURRENT, section.status)
        assertFalse(section.updatedLabel.isBlank())
    }

    @Test
    fun `no avatar history maps to the honest not-configured section`() = runTest {
        val repo = FakeAvatarAssetRepository()
        val section = HomeAvatarSectionLoader.load(repo, alwaysReadable())
        assertEquals(AvatarSection.notConfigured, section)
        assertFalse(section.hasAvatarImage)
        assertTrue(section.imageUri.isBlank())
    }

    @Test
    fun `avatar with a bad reference (file absent) stays empty`() = runTest {
        val repo = FakeAvatarAssetRepository()
        seedRepo(repo)
        // The fake repository resolves a synthetic path that never exists on
        // disk; a strict "must be a real file" gate therefore yields empty.
        val section = HomeAvatarSectionLoader.load(repo, onlyIfFileExists())
        assertEquals(AvatarSection.notConfigured, section)
    }

    @Test
    fun `avatar with an unreadable corrupt reference stays empty`() = runTest {
        val repo = FakeAvatarAssetRepository()
        seedRepo(repo)
        val section = HomeAvatarSectionLoader.load(repo, alwaysReject())
        assertEquals(AvatarSection.notConfigured, section)
    }

    @Test
    fun `deleted-until-empty maps back to not-configured`() = runTest {
        val repo = FakeAvatarAssetRepository()
        val version = seedRepo(repo)
        // Delete the only version → honest empty state.
        repo.deleteVersion(version.versionId)
        assertEquals(null, repo.currentVersion())
        val section = HomeAvatarSectionLoader.load(repo, alwaysReadable())
        assertEquals(AvatarSection.notConfigured, section)
        assertFalse(section.hasAvatarImage)
    }

    @Test
    fun `pure mapper never shows an image without a readable uri`() {
        // No current version → empty regardless of labels/uri.
        assertEquals(
            AvatarSection.notConfigured,
            HomeAvatarSectionMapper.map("", AvatarStatusToken.CURRENT, "2025-03-01 00:00", "file:///x.jpg"),
        )
        // Blank display uri → empty (bad reference).
        assertEquals(
            AvatarSection.notConfigured,
            HomeAvatarSectionMapper.map("seedream-1", AvatarStatusToken.CURRENT, "2025-03-01 00:00", "  "),
        )
        // Real readable uri → honest section, no fabricated label for DISABLED.
        val section = HomeAvatarSectionMapper.map(
            "seedream-1", AvatarStatusToken.CURRENT, "2025-03-01 00:00", "file:///avatar/assets/seedream-1.jpg",
        )
        assertTrue(section.hasAvatarImage)
        assertEquals(AvatarStatus.CURRENT, section.status)
        assertEquals("file:///avatar/assets/seedream-1.jpg", section.imageUri)
        assertTrue(section.updatedLabel.contains("2025-03-01"))
    }

    @Test
    fun `process-restart read returns the same real avatar section`() = runTest {
        val root = Files.createTempDirectory("clawmate-phase48-avatar").toFile().apply { deleteOnExit() }
        val body = ByteArray(64) { (it % 256).toByte() }

        // "First process": write a real avatar through the actual file repository.
        val writer = DiskAvatarAssetRepository(
            context = null,
            defaultBaseDir = root,
            decodeValidator = { true },
        )
        val written = writer.importLocalImage(body, "首张本机照片", "image/jpeg")
        check(written.success)
        val firstVersion = written.version!!

        // "Process restart": a brand-new repository instance reads the on-disk state.
        val reader = DiskAvatarAssetRepository(
            context = null,
            defaultBaseDir = root,
            decodeValidator = { true },
        )
        val section = HomeAvatarSectionLoader.load(reader, onlyIfFileExists())

        assertTrue("restart-read avatar must be displayed", section.hasAvatarImage)
        // The asset file the repository resolved actually exists on disk and is
        // what the Home card will load.
        val absolute = reader.absoluteAssetPath(firstVersion.imageRelativePath)
        assertTrue(File(absolute ?: "").isFile)
        assertEquals("file://$absolute", section.imageUri)
        assertEquals(AvatarStatus.CURRENT, section.status)
    }

    @Test
    fun `restart with corrupt asset file stays empty`() = runTest {
        val root = Files.createTempDirectory("clawmate-phase48-avatar-corrupt").toFile().apply { deleteOnExit() }
        val writer = DiskAvatarAssetRepository(
            context = null,
            defaultBaseDir = root,
            decodeValidator = { true },
        )
        val written = writer.importLocalImage(ByteArray(64), "本机照片", "image/jpeg")
        check(written.success)
        val version = written.version!!
        val asset = File(root, version.imageRelativePath)
        // Simulate on-disk corruption: empty the asset file.
        asset.writeText("")
        val reader = DiskAvatarAssetRepository(context = null, defaultBaseDir = root, decodeValidator = { true })
        val section = HomeAvatarSectionLoader.load(reader, onlyIfReadableNonEmpty())
        assertEquals(AvatarSection.notConfigured, section)
    }

    @Test
    fun `home model mapping carries the real avatar through build and blend`() = runTest {
        val real = HomeAvatarSectionMapper.map(
            "seedream-9", AvatarStatusToken.CURRENT, "2026-08-17T10:00:00", "file:///avatar/assets/seedream-9.jpg",
        )
        val curated = com.clawmate.app.profile.avatar.domain.LocalAvatarProfile(
            displayName = "小雅",
            domains = com.clawmate.app.profile.avatar.domain.defaultProfileDomains().mapIndexed { i, d ->
                if (i == 0) d.copy(shortSummary = "常吃清淡") else d
            },
        )
        val built = com.clawmate.app.home.domain.HomeProfileBuilder.build(
            profile = curated,
            updatedLabel = "昨天",
            avatar = real,
        )
        assertEquals(real, built.avatar)
        assertTrue(built.avatar.hasAvatarImage)
        assertEquals("file:///avatar/assets/seedream-9.jpg", built.avatar.imageUri)

        // The four pillars themselves are unaffected by the avatar wiring.
        assertEquals(listOf("eat", "wear", "live", "travel"), built.domains.map { it.domain })
        assertFalse(built.empty)

        // Blend passes the avatar through the index path too.
        val blended = com.clawmate.app.home.domain.ProfileIndexBlender.blend(
            curated = curated,
            indexState = com.clawmate.app.datamanager.ProfileTypes.ProfileState(),
            updatedLabel = "昨天",
            avatar = real,
        )
        assertEquals(real, blended.avatar)
    }

    @Test
    fun `source without an avatar repository keeps the honest disabled state`() = runTest {
        val source = com.clawmate.app.home.data.ProfileBackedHomeProfileSource(
            FakeProfileAvatarRepository(
                com.clawmate.app.profile.avatar.domain.LocalAvatarProfile(
                    displayName = "小林",
                    domains = com.clawmate.app.profile.avatar.domain.defaultProfileDomains().map { it.copy(shortSummary = "有内容") },
                ),
            ),
        )
        val vm = source.load()
        // No avatar repo injected → avatar stays the honest not-configured state.
        assertEquals(AvatarSection.notConfigured, vm.avatar)
        assertFalse(vm.avatar.hasAvatarImage)
    }

    @Test
    fun `source with a real avatar repository surfaces the persisted avatar`() = runTest {
        val avatarRepo = FakeAvatarAssetRepository()
        seedRepo(avatarRepo)
        val source = com.clawmate.app.home.data.ProfileBackedHomeProfileSource(
            FakeProfileAvatarRepository(
                com.clawmate.app.profile.avatar.domain.LocalAvatarProfile(
                    displayName = "小林",
                    domains = com.clawmate.app.profile.avatar.domain.defaultProfileDomains().map { it.copy(shortSummary = "有内容") },
                ),
            ),
            avatarAssetRepository = avatarRepo,
            avatarImageCheck = alwaysReadable(),
        )
        val vm = source.load()
        assertTrue(vm.avatar.hasAvatarImage)
        assertTrue(vm.avatar.imageUri.isNotEmpty())
        val absolute = avatarRepo.absoluteAssetPath(avatarRepo.currentVersion()!!.imageRelativePath)
        assertEquals("file://$absolute", vm.avatar.imageUri)
    }
}
