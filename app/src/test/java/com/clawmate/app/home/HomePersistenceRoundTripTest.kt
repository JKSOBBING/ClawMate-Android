package com.clawmate.app.home

import com.clawmate.app.home.data.FakeHomeRecRepository
import com.clawmate.app.home.data.FakeOnboardingRepository
import com.clawmate.app.home.domain.FIRST_LAUNCH_ONBOARDING_VERSION
import com.clawmate.app.home.domain.HomeRecommendationViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Persistence round-trips (deterministic in-memory stores matching the
 * DataStore-backed repositories' semantics).
 */
class HomePersistenceRoundTripTest {

    // ------------------------------------------------------------- onboarding
    @Test
    fun `onboarding fresh install is not completed`() = runBlocking {
        val repo = FakeOnboardingRepository()
        assertEquals("", repo.storedVersion())
        assertFalse(repo.isCompleted())
    }

    @Test
    fun `onboarding version round-trips through the store`() = runBlocking {
        val repo = FakeOnboardingRepository()
        repo.saveVersion(FIRST_LAUNCH_ONBOARDING_VERSION)
        assertEquals(FIRST_LAUNCH_ONBOARDING_VERSION, repo.storedVersion())
        assertTrue(repo.isCompleted())
        assertEquals(repo.storedVersion(), repo.onboardingVersion.first())
    }

    // -------------------------------------------------------- recommendations
    @Test
    fun `recommendation flags start empty`() = runBlocking {
        val repo = FakeHomeRecRepository()
        assertTrue(repo.snapshot().isEmpty())
        assertFalse(repo.isCompleted("todo-1"))
    }

    @Test
    fun `recommendation completion round-trips`() = runBlocking {
        val repo = FakeHomeRecRepository()
        assertTrue(repo.setCompleted("todo-1", true))
        assertTrue(repo.isCompleted("todo-1"))
        assertEquals(mapOf("todo-1" to true), repo.snapshot())
        repo.setCompleted("todo-1", false)
        assertFalse(repo.isCompleted("todo-1"))
    }

    @Test
    fun `blank recommendation id is rejected`() = runBlocking {
        val repo = FakeHomeRecRepository()
        assertEquals(false, repo.setCompleted("  ", true))
        assertTrue(repo.snapshot().isEmpty())
    }

    @Test
    fun `apply flags colours supplied recommendations without inventing them`() {
        val repo = FakeHomeRecRepository()
        val recs = listOf(
            HomeRecommendationViewModel(id = "todo-1", domain = "eat", tag = "待购买", title = "买牛奶", desc = "", meta = "", color = "#39C2A2"),
            HomeRecommendationViewModel(id = "todo-2", domain = "wear", tag = "", title = "取快递", desc = "", meta = "", color = "#7669FF"),
        )
        val flags = mapOf("todo-1" to true)
        val coloured = repo.applyFlags(recs, flags)
        assertTrue(coloured[0].completed)
        assertFalse(coloured[1].completed)
        assertEquals(2, coloured.size)
    }
}
