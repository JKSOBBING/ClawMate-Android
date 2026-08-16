package com.clawmate.app.home.data

import com.clawmate.app.home.domain.FIRST_LAUNCH_ONBOARDING_VERSION
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * Deterministic in-memory [OnboardingRepository] shared by unit tests. Uses the
 * same token semantics as the DataStore-backed repository (unset -> empty
 * string, not completed).
 */
class FakeOnboardingRepository(
    initialStoredVersion: String = "",
) : OnboardingRepository {

    private val stored = MutableStateFlow(initialStoredVersion)

    override val onboardingVersion: Flow<String> = stored.map { it }

    override suspend fun storedVersion(): String = stored.value

    override suspend fun saveVersion(version: String) {
        stored.value = version
    }

    /** Convenience for tests: mark onboarding complete. */
    suspend fun complete() = saveVersion(FIRST_LAUNCH_ONBOARDING_VERSION)
}
