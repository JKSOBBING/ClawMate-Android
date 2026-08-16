package com.clawmate.app.home

import com.clawmate.app.home.data.FakeOnboardingRepository
import com.clawmate.app.home.domain.FIRST_LAUNCH_ONBOARDING_VERSION
import com.clawmate.app.home.ui.onboarding.OnboardingUiState
import com.clawmate.app.home.ui.onboarding.OnboardingViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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
 * First-launch onboarding section navigation + completion version persistence.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var repo: FakeOnboardingRepository
    private lateinit var vm: OnboardingViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repo = FakeOnboardingRepository()
        vm = OnboardingViewModel(repo)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `starts on first slide`() {
        val s = vm.state.value as OnboardingUiState.Active
        assertEquals(0, s.index)
        assertEquals(4, s.slideCount)
        assertEquals("1/4", s.counterLabel)
        assertEquals("下一页", s.buttonText)
    }

    @Test
    fun `next advances then finishes on the last section`() {
        vm.next() // 1
        assertEquals(1, (vm.state.value as OnboardingUiState.Active).index)
        vm.next() // 2
        vm.next() // 3 -> last
        val last = vm.state.value as OnboardingUiState.Active
        assertTrue(last.isLast)
        assertEquals("进入首页", last.buttonText)
        assertFalse(vm.completed.value)
    }

    @Test
    fun `next on the last page completes and persists the version`() = runTest {
        vm.skip() // jump to last page
        val last = vm.state.value as OnboardingUiState.Active
        assertTrue(last.isLast)
        vm.next()
        // The ViewModel persists on Dispatchers.Main (the shared test dispatcher);
        // advance it so the save completes before we assert on the stored version.
        advanceUntilIdle()
        assertEquals(FIRST_LAUNCH_ONBOARDING_VERSION, repo.storedVersion())
        assertTrue(vm.completed.value)
    }

    @Test
    fun `skip jumps straight to the usage-guide (last) page`() {
        vm.next() // 1
        vm.skip()
        val s = vm.state.value as OnboardingUiState.Active
        assertTrue(s.isLast)
        assertEquals(3, s.index)
    }

    @Test
    fun `stored onboarding empty means not completed`() = runTest {
        assertFalse(repo.isCompleted())
        assertTrue(vm.completed.value == false)
    }
}
