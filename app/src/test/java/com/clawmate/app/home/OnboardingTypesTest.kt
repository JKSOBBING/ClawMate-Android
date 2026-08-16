package com.clawmate.app.home

import com.clawmate.app.home.domain.FIRST_LAUNCH_ONBOARDING_SLIDES
import com.clawmate.app.home.domain.FIRST_LAUNCH_ONBOARDING_VERSION
import com.clawmate.app.home.domain.FIRST_LAUNCH_USAGE_GUIDE_ITEMS
import com.clawmate.app.home.domain.clampFirstLaunchOnboardingIndex
import com.clawmate.app.home.domain.firstLaunchOnboardingButtonText
import com.clawmate.app.home.domain.isFirstLaunchOnboardingLastPage
import com.clawmate.app.home.domain.shouldShowFirstLaunchOnboarding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic checks over the onboarding data contract (ported from
 * `pages/onboarding/OnboardingTypes.ets`).
 */
class OnboardingTypesTest {

    @Test
    fun `four slides with three capabilities then a usage guide`() {
        assertEquals(4, FIRST_LAUNCH_ONBOARDING_SLIDES.size)
        assertEquals("data_home", FIRST_LAUNCH_ONBOARDING_SLIDES[0].id)
        assertEquals("avatar", FIRST_LAUNCH_ONBOARDING_SLIDES[1].id)
        assertEquals("agent", FIRST_LAUNCH_ONBOARDING_SLIDES[2].id)
        assertEquals("usage_guide", FIRST_LAUNCH_ONBOARDING_SLIDES[3].id)
    }

    @Test
    fun `usage guide lists three feature entries`() {
        assertEquals(3, FIRST_LAUNCH_USAGE_GUIDE_ITEMS.size)
        assertEquals("usage_data_home", FIRST_LAUNCH_USAGE_GUIDE_ITEMS[0].id)
        assertEquals("usage_avatar", FIRST_LAUNCH_USAGE_GUIDE_ITEMS[1].id)
        assertEquals("usage_agent", FIRST_LAUNCH_USAGE_GUIDE_ITEMS[2].id)
    }

    @Test
    fun `should show onboarding when stored version differs`() {
        assertTrue(shouldShowFirstLaunchOnboarding(""))
        assertTrue(shouldShowFirstLaunchOnboarding("old-version"))
        assertFalse(shouldShowFirstLaunchOnboarding(FIRST_LAUNCH_ONBOARDING_VERSION))
    }

    @Test
    fun `versioned completion key matches the source`() {
        assertEquals("2026-07-core-preview-usage-guide", FIRST_LAUNCH_ONBOARDING_VERSION)
    }

    @Test
    fun `last page detection`() {
        assertFalse(isFirstLaunchOnboardingLastPage(0))
        assertFalse(isFirstLaunchOnboardingLastPage(2))
        assertTrue(isFirstLaunchOnboardingLastPage(3))
        // Out-of-range indexes clamp and are still reported as last.
        assertTrue(isFirstLaunchOnboardingLastPage(99))
    }

    @Test
    fun `button text is next-page then enter-home on last page`() {
        assertEquals("下一页", firstLaunchOnboardingButtonText(0))
        assertEquals("下一页", firstLaunchOnboardingButtonText(2))
        assertEquals("进入首页", firstLaunchOnboardingButtonText(3))
        assertEquals("进入首页", firstLaunchOnboardingButtonText(999))
    }

    @Test
    fun `clamp index bounds`() {
        assertEquals(0, clampFirstLaunchOnboardingIndex(-1))
        assertEquals(0, clampFirstLaunchOnboardingIndex(0))
        assertEquals(2, clampFirstLaunchOnboardingIndex(2))
        assertEquals(3, clampFirstLaunchOnboardingIndex(10))
    }
}
