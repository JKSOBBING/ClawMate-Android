package com.clawmate.app.home.data

import com.clawmate.app.datamanager.AtomicJsonStore
import com.clawmate.app.datamanager.DataManagerPaths
import com.clawmate.app.datamanager.ProfileCodec
import com.clawmate.app.datamanager.ProfileTypes
import com.clawmate.app.home.domain.AvatarSection
import com.clawmate.app.home.domain.HomeProfileViewModel
import com.clawmate.app.home.domain.ProfileIndexBlender
import com.clawmate.app.home.recommendation.HomeRecommendationRepository
import com.clawmate.app.profile.avatar.data.AvatarAssetRepository
import com.clawmate.app.profile.avatar.data.ProfileAvatarRepository
import java.io.File

/**
 * Phase-32 [HomeProfileSource] that feeds the **real four-domain index-derived
 * profile** into the Home pillar cards, blended with (and never overriding) the
 * user-curated local profile.
 *
 * It reads:
 *  - the app-private persisted `indexes/profile/state.json` ([ProfileTypes.
 *    ProfileState]) tolerantly (missing / corrupt / oversized → empty, never
 *    thrown) via [ProfileIndexBlender];
 *  - the user-curated app-private DataStore profile via [ProfileAvatarRepository].
 *
 * [ProfileIndexBlender] keeps a populated user domain verbatim (local-first, no
 * overwrite of user edits) and fills only un-populated pillars from the bounded
 * index-derived summary / claims and real evidence ids. No URI, image bytes,
 * token or unrelated local data is ever imported into the Home pillar pipeline.
 *
 * Phase-48: an optional [AvatarAssetRepository] (+ [avatarImageCheck]) surfaces
 * a **real persisted avatar** on Home through [HomeAvatarSectionLoader]; the
 * avatar section falls back to the honest not-configured state when there is no
 * readable current asset. Only a validated, real app-private asset is shown.
 */
class ProfileIndexHomeProfileSource(
    private val baseDir: File,
    private val profileRepository: ProfileAvatarRepository,
    override val recommendationRepository: HomeRecommendationRepository? = null,
    private val store: AtomicJsonStore = AtomicJsonStore(),
    private val updatedLabelBuilder: (String) -> String = { it.takeIf { time -> time.isNotBlank() } ?: "暂无画像" },
    private val avatarAssetRepository: AvatarAssetRepository? = null,
    private val avatarImageCheck: HomeAvatarSectionLoader.ReadableImageCheck =
        FileHomeAvatarImageCheck(),
) : HomeProfileSource {

    override suspend fun load(): HomeProfileViewModel {
        val curated = profileRepository.snapshot()
        val indexState = store.readJson(DataManagerPaths.profileState(baseDir)) {
            ProfileCodec.decodeState(it)
        } ?: ProfileTypes.ProfileState()
        val avatar = avatarAssetRepository?.let {
            HomeAvatarSectionLoader.load(it, avatarImageCheck)
        } ?: AvatarSection.notConfigured
        return ProfileIndexBlender.blend(
            curated = curated,
            indexState = indexState,
            updatedLabel = updatedLabelBuilder(
                indexState.generatedAt.ifEmpty { curated.updatedAt },
            ),
            avatar = avatar,
        )
    }
}
