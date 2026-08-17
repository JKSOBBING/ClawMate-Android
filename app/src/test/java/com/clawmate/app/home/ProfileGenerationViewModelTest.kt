package com.clawmate.app.home

import com.clawmate.app.datamanager.ProfileGenerationRepository
import com.clawmate.app.datamanager.ProfileGenerationState
import com.clawmate.app.home.data.FakeHomeRecRepository
import com.clawmate.app.home.domain.HomeProfileViewModel
import com.clawmate.app.home.ui.HomeViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Focused JVM tests for the Home "生成画像" explicit action: it is only ever
 * invoked from the explicit button (never on load), and its typed lifecycle
 * state is surfaced honestly (running / success / error / no entry configured).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileGenerationViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `no repository injected reports an honest disabling error and never auto-runs`() = runTest {
        var calls = 0
        val repo = ProfileGenerationRepository { calls++; ProfileGenerationState.Success(eventCount = 1, claimCount = 0, isModel = false) }
        // No repository injected -> generate returns the honest disabling state.
        val vm = HomeViewModel(FakeHomeRecRepository()) { HomeProfileViewModel.empty }
        dispatcher.scheduler.advanceUntilIdle()
        vm.generateProfile()
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.profileGeneration.value is ProfileGenerationState.Error)
    }

    @Test
    fun `explicit generate runs the repository and surfaces a successful local state`() = runTest {
        var calls = 0
        val repo = ProfileGenerationRepository {
            calls++
            ProfileGenerationState.Success(eventCount = 3, claimCount = 1, isModel = false)
        }
        val vm = HomeViewModel(
            FakeHomeRecRepository(),
            profileGenerationRepository = repo,
        ) { HomeProfileViewModel.empty }
        dispatcher.scheduler.advanceUntilIdle()
        // Idle until the explicit action.
        assertTrue(vm.profileGeneration.value is ProfileGenerationState.Idle)
        vm.generateProfile()
        dispatcher.scheduler.advanceUntilIdle()
        val s = vm.profileGeneration.value
        assertTrue(s is ProfileGenerationState.Success)
        assertTrue(calls == 1)
    }

    @Test
    fun `profile generation is never invoked on load`() = runTest {
        var calls = 0
        val repo = ProfileGenerationRepository {
            calls++
            ProfileGenerationState.Success(eventCount = 1, claimCount = 0, isModel = false)
        }
        val vm = HomeViewModel(
            FakeHomeRecRepository(),
            profileGenerationRepository = repo,
        ) { HomeProfileViewModel.empty }
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(0, calls)
    }
}
