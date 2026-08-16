package com.clawmate.app.profile.avatar.data

import com.clawmate.app.profile.avatar.data.asset.AvatarClearResult
import com.clawmate.app.profile.avatar.data.asset.AvatarImportResult
import com.clawmate.app.profile.avatar.data.asset.AvatarVersionRecord

/**
 * Persistence boundary for the local digital-avatar **asset repository**
 * (portable part of migration row A2).
 *
 * Implementations must be deterministic and app-private: avatar version history
 * (metadata), the current asset state, and byte-capped local image imports all
 * live under the app's own private storage. No secrets, endpoints, cloud
 * credentials or generated images are ever stored here — an imported asset is
 * a real user-chosen image, never a fabricated generation.
 */
interface AvatarAssetRepository {

    /** Newest-first ordered version history (metadata only; empty when none). */
    suspend fun listVersions(): List<AvatarVersionRecord>

    /** The current state file (always returned sanitised; never an exception). */
    suspend fun loadState(): com.clawmate.app.profile.avatar.data.asset.AvatarAssetStateModel

    /** The currently selected version, or null when none is set. */
    suspend fun currentVersion(): AvatarVersionRecord?

    /**
     * Import a real user-selected local image into the repository.
     *
     * @param displayName user-facing label for the imported asset (metadata).
     * @param sourceMimeType the content MIME the picker reported (used only to
     *   pick the stored extension; does not bypass validation).
     * @return typed [AvatarImportResult]. Empty/oversized/undecodable input is
     *   rejected with an honest error — never persisted, never fabricated.
     */
    suspend fun importLocalImage(
        bytes: ByteArray,
        displayName: String,
        sourceMimeType: String,
    ): AvatarImportResult

    /**
     * Remove a single version from history. Returns true when the version was
     * found and removed; false when it did not exist.
     */
    suspend fun deleteVersion(versionId: String): Boolean

    /**
     * Clear every avatar asset + version + state (deletion/clear behaviour).
     * Returns the number of versions removed and the reset, empty state.
     */
    suspend fun clearAvatar(): AvatarClearResult

    /**
     * Resolve a persisted relative asset path (e.g. `assets/local-1.jpg`) to an
     * **absolute** on-device path the image loader (Coil) can display, or null
     * for a blank relative path. Only reads the path mapping; never decodes or
     * fabricates an asset.
     */
    fun absoluteAssetPath(relativePath: String): String?
}
