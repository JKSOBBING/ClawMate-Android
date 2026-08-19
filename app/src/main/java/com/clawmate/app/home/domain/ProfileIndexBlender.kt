package com.clawmate.app.home.domain

import com.clawmate.app.datamanager.ProfileTypes.ProfileState
import com.clawmate.app.profile.avatar.domain.AvatarDomainSnapshot
import com.clawmate.app.profile.avatar.domain.LocalAvatarProfile
import com.clawmate.app.profile.avatar.domain.PROFILE_DOMAIN_NO_DATA

/**
 * Phase-32 display-only blend between the **user-curated** local profile
 * ([LocalAvatarProfile], persisted on device) and the **index-derived** four-domain
 * profile ([ProfileState], materialised from real indexed events).
 *
 * Local-first and non-destructive:
 *  - A domain the user has **populated** (non-blank short summary / summary / a
 *    claim) keeps the user's content verbatim — the index foundation never
 *    overwrites a user's manual edits.
 *  - A domain the user has **not** populated is filled from the real, bounded
 *    index-derived summary / claims, with the true evidence count and a bounded
 *    list of real indexed evidence ids surfaced for display.
 *  - Nothing is written back to the DataStore profile — this is purely a
 *    read-time projection consumed by the Home pillar cards.
 */
object ProfileIndexBlender {

    /**
     * Blend a curated profile and an index-derived profile into a Home
     * [HomeProfileViewModel]. When [indexState] carries no indexed events, the
     * result is the deterministic [HomeProfileBuilder] output for the curated
     * profile alone (never a fabricated success).
     */
    fun blend(
        curated: LocalAvatarProfile,
        indexState: ProfileState,
        updatedLabel: String,
        avatar: AvatarSection = AvatarSection.notConfigured,
    ): HomeProfileViewModel {
        val blended = curated.domains.map { snapshot ->
            val index = indexState.domains[snapshot.domain]
            if (hasUserContent(snapshot)) {
                // User-curated domain wins (local-first); evidence ids still
                // surface only as read-only display data.
                snapshot.copy(eventCount = index?.eventCount ?: snapshot.eventCount)
            } else if (index != null && index.eventCount > 0) {
                snapshot.copy(
                    shortSummary = index.shortSummary,
                    summary = index.summary,
                    claims = index.claims.map { it.statement }.take(5),
                    eventCount = index.eventCount,
                )
            } else {
                snapshot
            }
        }
        val base = HomeProfileBuilder.build(
            profile = curated.copy(domains = blended),
            updatedLabel = updatedLabel,
            avatar = avatar,
        )
        // Fold the real evidence ids into the pillars for faithful, read-only display.
        return base.copy(
            domains = base.domains.map { pillar ->
                val index = indexState.domains[pillar.domain]
                pillar.copy(
                    evidenceIds = index?.eventIds?.take(IndexEvidenceDisplayLimit) ?: emptyList(),
                    eventCount = index?.eventCount ?: pillar.eventCount,
                )
            },
        )
    }

    private fun hasUserContent(snapshot: AvatarDomainSnapshot): Boolean =
        snapshot.shortSummary.trim().isNotEmpty() ||
            snapshot.summary.trim().isNotEmpty() ||
            snapshot.claims.any { it.trim().isNotEmpty() }

    /** Bound on how many real evidence ids are surfaced per pillar (display only). */
    const val IndexEvidenceDisplayLimit: Int = 8
}
