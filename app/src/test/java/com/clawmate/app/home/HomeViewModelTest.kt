package com.clawmate.app.home

import com.clawmate.app.home.data.FakeHomeRecRepository
import com.clawmate.app.home.domain.HomeDomainViewModel
import com.clawmate.app.home.domain.HomeProfileViewModel
import com.clawmate.app.home.domain.HomeRecommendationViewModel
import com.clawmate.app.home.domain.HomeUiState
import com.clawmate.app.home.ui.HomeViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Home ViewModel behaviour: loading a profile, applying stored completion flags,
 * toggling completion, and surfacing empty/error states honestly.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var recRepo: FakeHomeRecRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        recRepo = FakeHomeRecRepository()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun rec(id: String, completed: Boolean = false): HomeRecommendationViewModel =
        HomeRecommendationViewModel(id = id, domain = "eat", tag = "tag", title = id, desc = "", meta = "", color = "#39C2A2", completed = completed)

    private fun profileWith(recs: List<HomeRecommendationViewModel>) = HomeProfileViewModel(
        updatedLabel = "暂无画像",
        completeness = 0,
        totalEvents = 0,
        domains = defaultDomains(),
        recommendations = recs,
        empty = recs.isEmpty(),
    )

    @Test
    fun `loads empty profile as ready and empty`() = runTest {
        val vm = HomeViewModel(recRepo) { HomeProfileViewModel.empty }
        dispatcher.scheduler.advanceUntilIdle()
        val s = vm.state.value
        assertTrue(s is HomeUiState.Ready)
        assertTrue((s as HomeUiState.Ready).profile.empty)
        assertTrue(vm.list.value.recommendations.isEmpty())
    }

    @Test
    fun `loads with persisted completion flags applied`() = runTest {
        recRepo.setCompleted("todo-1", true)
        val vm = HomeViewModel(recRepo) { profileWith(listOf(rec("todo-1"), rec("todo-2"))) }
        dispatcher.scheduler.advanceUntilIdle()
        val ready = vm.state.value as HomeUiState.Ready
        assertEquals(true, ready.profile.recommendations[0].completed)
        assertEquals(false, ready.profile.recommendations[1].completed)
    }

    @Test
    fun `toggling a recommendation completion persists it`() = runTest {
        val vm = HomeViewModel(recRepo) { profileWith(listOf(rec("todo-1"))) }
        dispatcher.scheduler.advanceUntilIdle()
        vm.setRecommendationCompleted("todo-1", true)
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(recRepo.isCompleted("todo-1"))
        val updated = vm.list.value.recommendations[0]
        assertTrue(updated.completed)
    }

    @Test
    fun `surfaces an error state on loader failure`() = runTest {
        val vm = HomeViewModel(recRepo) { throw IllegalStateException("boom") }
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.state.value is HomeUiState.Error)
        assertEquals("boom", (vm.state.value as HomeUiState.Error).message)
    }

    private fun defaultDomains(): List<HomeDomainViewModel> = emptyList()
}
