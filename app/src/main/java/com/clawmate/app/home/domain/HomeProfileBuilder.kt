package com.clawmate.app.home.domain

import com.clawmate.app.profile.avatar.domain.AvatarDomainSnapshot
import com.clawmate.app.profile.avatar.domain.LocalAvatarProfile
import com.clawmate.app.profile.avatar.domain.PROFILE_DOMAIN_NO_DATA

/**
 * Pure, deterministic derivation of the home profile (pillars + completeness +
 * honest empty state) from the locally persisted avatar profile.
 *
 * This closes the documented A1 gap: the Phase-12 local profile now flows into
 * the Home recommendation pipeline as its real `HomeProfileSource`. The mapping
 * follows the HarmonyOS source of truth `datamanager/DataManagerFacade.ets`
 * (`homeProfileDomains` / `homeProfileDomainDescription` /
 * `homeProfileDomainFullDescription` / `domainClaimStatements`), adapted for the
 * Android reality that the profile is **locally curated** (the user types their
 * 吃/穿/住/行 short summary, summary and claims) rather than derived from the
 * deferred daily-log indexing pipeline:
 *
 *  - Harmony gates every pillar on `doc.event_count === 0`. On Android the
 *    profile is manually authored and has no index-derived event count, so a
 *    pillar is treated as populated by the **user-curated content it actually
 *    contains** (short summary / summary / claims), never by an event count.
 *  - Only content that is genuinely in the stored profile is ever surfaced —
 *    `暂无数据` (a truthful empty sentinel) is shown for any un-populated pillar,
 *    and an entirely empty profile still yields the honest [HomeProfileViewModel
 *    .empty] state (backward compatible with the pre-Phase-16 behaviour).
 *
 * The recommendation list itself is **never fabricated**: on Android there is no
 * indexed-todo content source (the data-manager pipeline that Harmony feeds into
 * `homeRecommendations()` is documented as deferred), so this builder always
 * yields no recommendations. Nothing here invents remote recommendations, avatar
 * images, cloud calls, or secrets.
 */
object HomeProfileBuilder {

    /** Order of the four persona pillars on the home grid (source-stable). */
    const val PILLAR_COUNT: Int = 4

    /**
     * Compose the home profile view-model from a stored local profile.
     *
     * @param profile the persisted [LocalAvatarProfile] (read from the app-private
     *   DataStore profile repository).
     * @param updatedLabel human "last updated" label to surface on the home card.
     * @return a deterministic [HomeProfileViewModel]. Parameters return the
     *   honest empty profile when [profile] carries no populated pillar signal.
     */
    fun build(
        profile: LocalAvatarProfile,
        updatedLabel: String,
        recommendations: List<HomeRecommendationViewModel> = emptyList(),
    ): HomeProfileViewModel {
        val domains = homeProfileDomains(profile)
        val populated = domains.any { it.desc.trim() != PROFILE_DOMAIN_NO_DATA }
        if (!populated) {
            return HomeProfileViewModel(
                updatedLabel = updatedLabel,
                completeness = 0,
                totalEvents = 0,
                domains = defaultHomeDomains(),
                recommendations = recommendations,
                empty = true,
            )
        }
        val totalEvents = profile.domains.sumOf { it.eventCount }
        return HomeProfileViewModel(
            updatedLabel = updatedLabel,
            completeness = homeProfileCompleteness(domains),
            totalEvents = totalEvents,
            domains = domains,
            recommendations = recommendations,
            empty = false,
        )
    }

    /**
     * Map the four avatar persona domains onto the home pillar cards, faithfully
     * following `DataManagerFacade.homeProfileDomains()`. Pillar order, titles,
     * icons, colors and the empty sentinel come from [defaultHomeDomains].
     */
    fun homeProfileDomains(profile: LocalAvatarProfile): List<HomeDomainViewModel> =
        defaultHomeDomains().map { pillar ->
            val doc = profile.domains.firstOrNull { it.domain == pillar.domain }
            val snapshot = doc ?: AvatarDomainSnapshot(
                domain = pillar.domain,
                displayName = pillar.title,
                shortSummary = "",
                summary = "",
            )
            pillar.copy(
                desc = homeProfileDomainDescription(snapshot),
                fullDesc = homeProfileDomainFullDescription(snapshot),
                claims = domainClaimStatements(snapshot),
                eventCount = snapshot.eventCount,
            )
        }

    /**
     * Faithful port of `DataManagerFacade.homeProfileDomainDescription()`:
     * a filled short summary is the pillar line; when only a summary or claims
     * exist it reads `查看画像`; otherwise the honest `暂无数据`.
     *
     * The Harmony `event_count === 0` gate is intentionally replaced by the
     * Android local-curation equivalent (no short summary / summary / claims ⇒
     * 暂无数据) because the profile is typed by the user on-device and carries no
     * index-derived event evidence.
     */
    fun homeProfileDomainDescription(snapshot: AvatarDomainSnapshot): String {
        val shortSummary = snapshot.shortSummary.trim()
        if (shortSummary.isNotEmpty()) {
            return shortSummary
        }
        val summary = snapshot.summary.trim()
        val hasClaims = snapshot.claims.any { it.trim().isNotEmpty() }
        if (summary.isNotEmpty() || hasClaims) {
            return "查看画像"
        }
        return PROFILE_DOMAIN_NO_DATA
    }

    /**
     * Faithful port of `DataManagerFacade.homeProfileDomainFullDescription()`:
     * the full summary, or `暂无数据` when absent.
     */
    fun homeProfileDomainFullDescription(snapshot: AvatarDomainSnapshot): String {
        val summary = snapshot.summary.trim()
        return summary.ifEmpty { PROFILE_DOMAIN_NO_DATA }
    }

    /**
     * Faithful port of `DataManagerFacade.domainClaimStatements()`: at most five
     * non-blank claim statements (the Android profile stores statements directly).
     */
    fun domainClaimStatements(snapshot: AvatarDomainSnapshot): List<String> =
        snapshot.claims
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(5)

    /** True when any persona pillar carries a populated short summary. */
    fun hasPopulatedPillar(profile: LocalAvatarProfile): Boolean =
        homeProfileDomains(profile).any { it.desc.trim() != PROFILE_DOMAIN_NO_DATA }
}
