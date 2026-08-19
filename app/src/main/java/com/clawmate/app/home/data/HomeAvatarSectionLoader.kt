package com.clawmate.app.home.data

import com.clawmate.app.home.domain.AvatarSection
import com.clawmate.app.home.domain.HomeAvatarSectionMapper
import com.clawmate.app.profile.avatar.data.AvatarAssetRepository

/**
 * Reads the **real persisted avatar** into the Home [AvatarSection], on the same
 * read path the avatar generator / ProfileAvatar screen uses — the app-private
 * [AvatarAssetRepository] (current version + state) resolved to an absolute
 * on-device asset path.
 *
 * Honest display policy:
 *  - The avatar is shown **only** when a current version exists **and** its
 *    asset file is present, readable and passes the existing safety gate
 *    ([ReadableImageCheck] — existence, bounded size and real-image decode);
 *  - No avatar / deleted / corrupt / unreadable / over-budget ⇒ the honest
 *    [AvatarSection.notConfigured] empty state. **No demo/placeholder image is
 *    ever generated.**
 *  - No raw absolute path, token or secret is logged or sent anywhere — the
 *    loader only produces a display URI for Coil, never network, never logs.
 *
 * Each invocation re-reads the repository (the loader is stateless), so a Home
 * reload — on process start, after a configuration change, or after the
 * explicit 生成画像/数字分身 refresh — reflects the current persisted avatar.
 */
object HomeAvatarSectionLoader {

    /**
     * Safety + readability gate over a resolved absolute on-device asset path.
     * Production injects a file/bitmap-backed check; tests inject a JVM seam so
     * the "file exists && readable && decodes" rule is proven without a device.
     */
    fun interface ReadableImageCheck {
        /**
         * @return the display URI (`file://…`) when [absolutePath] is a real,
         *   readable, bounded, decodable image; null/blank otherwise.
         */
        fun readableDisplayUri(absolutePath: String?): String?
    }

    suspend fun load(
        assetRepository: AvatarAssetRepository,
        check: ReadableImageCheck,
    ): AvatarSection {
        val state = runCatching { assetRepository.loadState() }.getOrNull() ?: return AvatarSection.notConfigured
        val version = runCatching { assetRepository.currentVersion() }.getOrNull() ?: return AvatarSection.notConfigured
        if (state.currentVersionId.isBlank() || version.versionId != state.currentVersionId) {
            // The persisted current pointer and the resolved version must agree;
            // otherwise fall back to the honest empty state.
            return AvatarSection.notConfigured
        }
        val absolute = runCatching { assetRepository.absoluteAssetPath(version.imageRelativePath) }.getOrNull()
        val displayUri = check.readableDisplayUri(absolute)
        if (displayUri.isNullOrBlank()) return AvatarSection.notConfigured
        return HomeAvatarSectionMapper.map(
            currentVersionId = state.currentVersionId,
            statusToken = state.status,
            createdAt = version.createdAt,
            displayUri = displayUri,
        )
    }
}
