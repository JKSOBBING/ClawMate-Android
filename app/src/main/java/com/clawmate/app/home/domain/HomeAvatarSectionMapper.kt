package com.clawmate.app.home.domain

import com.clawmate.app.profile.avatar.domain.AvatarStatusToken

/**
 * Pure, deterministic mapper from the **read** avatar-asset state into the
 * [AvatarSection] the Home card renders. Ports the avatar half of the HarmonyOS
 * read chain
 *   `AvatarStorage.currentHomeView()/homeViewFromState()` +
 *   `HomePage.avatarStatusLabel()/avatarActionLabel()/actionLabel()`
 * onto the Android reality described in [AvatarSection]:
 *
 *  - A real, readable persisted avatar (a current version whose asset file
 *    exists, is readable and passed the [HomeAvatarSectionLoader] safety gate)
 *    maps to a [AvatarSection] with `hasAvatarImage = true` and the display
 *    `imageUri` (`file://<app-private-avatar-asset>`), the status derived from
 *    the honest persisted token and honest human labels.
 *  - Any other input (no current version, a blank/deleted current id, an
 *    unresolved/bad reference, an unreadable or corrupt asset) maps to
 *    [AvatarSection.notConfigured] — the honest empty state. **Nothing here
 *    ever fabricates an avatar image or claims a success it cannot show.**
 *
 * This object is pure so the whole decision can be proven on the JVM; the read
 * + readability gate lives in [com.clawmate.app.home.data.HomeAvatarSectionLoader].
 */
object HomeAvatarSectionMapper {

    /**
     * Map read avatar-asset inputs to the Home [AvatarSection].
     *
     * @param currentVersionId the persisted current version id (`state.json`
     *   `current_version_id`); blank ⇒ no avatar.
     * @param statusToken the persisted honest status token (`AvatarStatusToken`);
     *   only used to drive labels, never to fake an image.
     * @param createdAt the current version's persisted `createdAt`.
     * @param displayUri a validated, readable display URI (`file://…`) of the
     *   real asset, or null/blank when the asset is missing / corrupt /
     *   unreadable / over-budget / fails the decode gate.
     */
    fun map(
        currentVersionId: String,
        statusToken: AvatarStatusToken,
        createdAt: String,
        displayUri: String?,
    ): AvatarSection {
        val uri = displayUri?.trim().orEmpty()
        val id = currentVersionId.trim()
        if (id.isEmpty() || uri.isEmpty()) return AvatarSection.notConfigured

        val status = statusToken.toHomeStatus()
        return AvatarSection(
            status = status,
            statusLabel = statusLabel(status),
            actionLabel = actionLabel(status),
            updatedLabel = createdAt.trim().ifEmpty { "暂无" },
            hasAvatarImage = true,
            imageUri = uri,
        )
    }

    /**
     * Human copy for the status, mirroring Harmony `HomePage.avatarStatusLabel()`.
     * [AvatarStatus.DISABLED] is only reachable from [AvatarSection.notConfigured],
     * not from a real persisted token.
     */
    internal fun statusLabel(status: AvatarStatus): String = when (status) {
        AvatarStatus.CURRENT -> "你的数字分身已更新"
        AvatarStatus.STALE -> "可更新数字分身"
        AvatarStatus.GENERATING -> "正在生成数字分身"
        AvatarStatus.FAILED -> "上次生成失败，可重试"
        AvatarStatus.EMPTY -> "可生成数字分身"
        AvatarStatus.DISABLED -> "未配置数字分身"
    }

    /**
     * Action copy, mirroring Harmony `AvatarStorage.actionLabel()`. A real
     * avatar is actionable (update/regenerate); an empty one offers generation.
     */
    internal fun actionLabel(status: AvatarStatus): String = when (status) {
        AvatarStatus.CURRENT -> "数字分身已更新"
        AvatarStatus.STALE -> "画像已更新，可生成新分身"
        AvatarStatus.GENERATING -> "数字分身生成中"
        AvatarStatus.FAILED -> "生成失败，可重试"
        AvatarStatus.EMPTY -> "生成数字分身"
        AvatarStatus.DISABLED -> "未启用"
    }

    /**
     * Map the persisted honest [AvatarStatusToken] onto the Home [AvatarStatus].
     * A real, displayed avatar never maps to [AvatarStatus.DISABLED].
     */
    internal fun AvatarStatusToken.toHomeStatus(): AvatarStatus = when (this) {
        AvatarStatusToken.CURRENT -> AvatarStatus.CURRENT
        AvatarStatusToken.STALE -> AvatarStatus.STALE
        AvatarStatusToken.GENERATING -> AvatarStatus.GENERATING
        AvatarStatusToken.FAILED -> AvatarStatus.FAILED
        AvatarStatusToken.EMPTY -> AvatarStatus.EMPTY
    }
}
