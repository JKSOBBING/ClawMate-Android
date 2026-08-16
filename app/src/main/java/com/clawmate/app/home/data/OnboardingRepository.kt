package com.clawmate.app.home.data

import com.clawmate.app.home.domain.FIRST_LAUNCH_ONBOARDING_VERSION
import kotlinx.coroutines.flow.Flow

/**
 * Persistent store for the first-launch onboarding completion version.
 *
 * Onboarding completion is a single versioned key: the shell shows the guide
 * only while the stored version differs from [FIRST_LAUNCH_ONBOARDING_VERSION].
 * The store must work on a configuration-free fresh install (defaults to not
 * completed) and never requires network access.
 */
interface OnboardingRepository {

    /** Flow of the stored onboarding version (empty string when unset). */
    val onboardingVersion: Flow<String>

    /** Read the stored version once (empty string when unset). */
    suspend fun storedVersion(): String

    /** True when the stored version matches the current onboarding version. */
    suspend fun isCompleted(): Boolean = storedVersion() == FIRST_LAUNCH_ONBOARDING_VERSION

    /** Persist the given version (normally [FIRST_LAUNCH_ONBOARDING_VERSION]). */
    suspend fun saveVersion(version: String)
}
