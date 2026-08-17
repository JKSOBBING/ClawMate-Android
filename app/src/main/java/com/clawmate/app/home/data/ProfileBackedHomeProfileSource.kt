package com.clawmate.app.home.data

import com.clawmate.app.home.domain.HomeProfileBuilder
import com.clawmate.app.home.domain.HomeProfileViewModel
import com.clawmate.app.home.recommendation.HomeRecommendationRepository
import com.clawmate.app.profile.avatar.data.ProfileAvatarRepository

/**
 * Real [HomeProfileSource] that feeds the persisted local profile into the Home
 * recommendation pipeline — closing the Phase-12 A1 gap.
 *
 * It reads the app-private DataStore profile (via [ProfileAvatarRepository]) and
 * derives the four persona pillars, the 0..100 completeness and the honest empty
 * state using the deterministic [HomeProfileBuilder]. This is genuinely
 * on-device state: only what the user curated in the 数字分身 screen ever appears.
 *
 * Truthful capability boundaries preserved:
 *  - The avatar section stays NOT_CONFIGURED ([HomeProfileViewModel.avatar] is
 *    never populated here) — no generated avatar image is ever invented.
 *  - The recommendation list is produced only by the explicit Phase-28
 *    "生成推荐" action through [recommendationRepository]; [load] itself never
 *    invents recommendation content.
 *
 * Phase-28 DI: a concrete [HomeRecommendationRepository] (or recommendations
 * loader) may be injected so the Home's explicit button can generate real
 * recommendations from gallery data; when none is supplied, generation is
 * simply unavailable (honest empty state).
 */
class ProfileBackedHomeProfileSource(
    private val profileRepository: ProfileAvatarRepository,
    override val recommendationRepository: HomeRecommendationRepository? = null,
    private val updatedLabelBuilder: (String) -> String = { time ->
        time.takeIf { it.isNotBlank() } ?: "暂无画像"
    },
) : HomeProfileSource {

    override suspend fun load(): HomeProfileViewModel {
        val profile = profileRepository.snapshot()
        return HomeProfileBuilder.build(
            profile = profile,
            updatedLabel = updatedLabelBuilder(profile.updatedAt),
        )
    }
}
