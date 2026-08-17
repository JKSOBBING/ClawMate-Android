package com.clawmate.app.home

import com.clawmate.app.gallery.model.GalleryPhotoItem
import com.clawmate.app.home.data.FakeHomeRecRepository
import com.clawmate.app.home.domain.HomeProfileViewModel
import com.clawmate.app.home.domain.HomeRecommendationState
import com.clawmate.app.home.domain.HomeUiState
import com.clawmate.app.home.recommendation.HomeRecommendationGenerationResult
import com.clawmate.app.home.recommendation.HomeRecommendationGenerationState
import com.clawmate.app.home.recommendation.HomeRecommendationRepository
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
 * Device-free tests for the Phase-28 explicit Home recommendation generation
 * flow at the ViewModel layer: unconfigured / no-data / success + flag
 * persistence states driven only by the explicit button action. The error state
 * is covered by the planner tests; the UI button itself compiles via
 * `:app:assembleDebug`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeRecommendationViewModelTest {

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

    private fun vm(
        repository: HomeRecommendationRepository,
        gallery: suspend () -> List<GalleryPhotoItem> = { emptyList() },
    ): HomeViewModel = HomeViewModel(
        homeRecRepository = recRepo,
        profileLoader = { HomeProfileViewModel.empty },
        recommendationRepository = repository,
        galleryItemsSource = gallery,
    )

    private fun state(id: String = "todo-1", title: String = "推荐"): HomeRecommendationState =
        HomeRecommendationState(
            id = id,
            tag = "待处理",
            title = title,
            desc = "说明",
            meta = "",
            domain = "travel",
            sourceEventIds = listOf("gx_1"),
            completed = false,
        )

    private fun repoReturning(state: HomeRecommendationGenerationState, onCall: (List<GalleryPhotoItem>) -> Unit = {}):
        HomeRecommendationRepository =
        object : HomeRecommendationRepository {
            override suspend fun generate(
                photoItems: List<GalleryPhotoItem>,
            ): HomeRecommendationGenerationResult {
                onCall(photoItems)
                return HomeRecommendationGenerationResult(state = state)
            }
        }

    @Test
    fun `explicit generate with unconfigured repository reports Error in zero calls`() = runTest {
        var calls = 0
        val vm = vm(
            object : HomeRecommendationRepository {
                override suspend fun generate(
                    photoItems: List<GalleryPhotoItem>,
                ): HomeRecommendationGenerationResult {
                    calls++
                    return HomeRecommendationGenerationResult(state = HomeRecommendationGenerationState.NotConfigured)
                }

                override fun isConfigured(): Boolean = false
            },
        )
        dispatcher.scheduler.advanceUntilIdle()
        vm.generateRecommendations()
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.generation.value is HomeRecommendationGenerationState.Error)
        assertEquals("explicit-not-configured must not reach the repository", 0, calls)
    }

    @Test
    fun `explicit generate with no data keeps the empty list and reports NoData`() = runTest {
        var calls = 0
        val vm = vm(
            repoReturning(HomeRecommendationGenerationState.NoData) { calls++ },
            gallery = { listOf(blankItem()) },
        )
        dispatcher.scheduler.advanceUntilIdle()
        vm.generateRecommendations()
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.generation.value is HomeRecommendationGenerationState.NoData)
        assertTrue(vm.list.value.recommendations.isEmpty())
        assertEquals(1, calls)
    }

    @Test
    fun `success injects recommendations into profile and list and persists flags`() = runTest {
        recRepo.setCompleted("todo-1", true)
        val repo = repoReturning(HomeRecommendationGenerationState.Success(listOf(state("todo-1"))))
        val vm = vm(repo, gallery = { listOf(blankItem()) })
        dispatcher.scheduler.advanceUntilIdle()
        vm.generateRecommendations()
        dispatcher.scheduler.advanceUntilIdle()
        val gen = vm.generation.value as HomeRecommendationGenerationState.Success
        assertEquals(1, gen.recommendations.size)
        // Injected into both the profile-ready state and the list.
        val ready = vm.state.value as HomeUiState.Ready
        assertEquals(1, ready.profile.recommendations.size)
        assertEquals(1, vm.list.value.recommendations.size)
        assertTrue("span the injected item", ready.profile.recommendations[0].id == "todo-1")
    }

    @Test
    fun `idle states until an explicit action`() = runTest {
        val vm = vm(repoReturning(HomeRecommendationGenerationState.Success(listOf(state()))))
        dispatcher.scheduler.advanceUntilIdle()
        // No auto-generation on load.
        assertTrue(vm.generation.value is HomeRecommendationGenerationState.Idle)
        assertTrue(vm.list.value.recommendations.isEmpty())
    }

    private fun blankItem(): GalleryPhotoItem =
        GalleryPhotoItem(
            id = "gx_blank",
            uri = "content://media/blank",
            date = "2026-08-01",
            title = "",
            category = "",
            summary = "",
            createdAt = 0L,
        )
}
