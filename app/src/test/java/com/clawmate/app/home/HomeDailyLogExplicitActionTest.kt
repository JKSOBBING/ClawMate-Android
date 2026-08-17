package com.clawmate.app.home

import com.clawmate.app.datamanager.DailyLogEntry
import com.clawmate.app.datamanager.DailyLogParser
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
 * Device-free tests for the Phase-29 explicit daily-log input at the ViewModel
 * layer: the app-private daily-log source is read **only** on an explicit
 * "生成推荐" tap (never at load/recomposition), is passed to the repository
 * alongside gallery items, and contributes to a successful generation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeDailyLogExplicitActionTest {

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

    private fun dailyLogEntry(): DailyLogEntry = DailyLogEntry(
        sourceDate = "2026-08-02",
        fileName = "wechat.logs.md",
        entryIndex = 1,
        text = "计划购买日用品",
        workflowMetadata = emptyMap(),
        latestEntryIndex = 1,
        sourceRef = DailyLogParser.sourceRef("2026-08-02", "wechat.logs.md", 1),
    )

    @Test
    fun `daily log source is not read at load only on explicit action`() = runTest {
        var dailyReads = 0
        var repoReceivedDaily: List<DailyLogEntry> = emptyList()
        val repo = object : HomeRecommendationRepository {
            override suspend fun generate(
                photoItems: List<GalleryPhotoItem>,
                dailyLogEntries: List<DailyLogEntry>,
            ): HomeRecommendationGenerationResult {
                repoReceivedDaily = dailyLogEntries
                return HomeRecommendationGenerationResult(
                    state = HomeRecommendationGenerationState.Success(emptyList()),
                )
            }

            override suspend fun generate(photoItems: List<GalleryPhotoItem>): HomeRecommendationGenerationResult =
                HomeRecommendationGenerationResult(state = HomeRecommendationGenerationState.NoData)
        }
        val vm = HomeViewModel(
            homeRecRepository = recRepo,
            profileLoader = { HomeProfileViewModel.empty },
            recommendationRepository = repo,
            galleryItemsSource = { listOf(blankGalleryItem()) },
            dailyLogSource = {
                dailyReads++
                listOf(dailyLogEntry())
            },
        )
        dispatcher.scheduler.advanceUntilIdle()
        // Load/recomposition must NOT read the daily-log source.
        assertEquals("daily-log source must not be read on load", 0, dailyReads)

        vm.generateRecommendations()
        dispatcher.scheduler.advanceUntilIdle()
        // Explicit action reads daily-log and hands it to the repository.
        assertEquals(1, dailyReads)
        assertEquals(1, repoReceivedDaily.size)
        assertEquals("2026-08-02/wechat.logs.md#1", repoReceivedDaily[0].sourceRef)
    }

    @Test
    fun `daily log source contributes to success result injected into home`() = runTest {
        val repo = object : HomeRecommendationRepository {
            override suspend fun generate(
                photoItems: List<GalleryPhotoItem>,
                dailyLogEntries: List<DailyLogEntry>,
            ): HomeRecommendationGenerationResult {
                assertTrue(dailyLogEntries.isNotEmpty())
                return HomeRecommendationGenerationResult(
                    state = HomeRecommendationGenerationState.Success(
                        listOf(
                            HomeRecommendationState(
                                id = "todo-2",
                                tag = "待处理",
                                title = "购买日用品",
                                desc = "计划购买日用品",
                                meta = "",
                                domain = "live",
                                sourceEventIds = listOf("2026-08-02/wechat.logs.md#1"),
                                completed = false,
                            ),
                        ),
                    ),
                )
            }

            override suspend fun generate(photoItems: List<GalleryPhotoItem>): HomeRecommendationGenerationResult =
                HomeRecommendationGenerationResult(state = HomeRecommendationGenerationState.NoData)
        }
        val vm = HomeViewModel(
            homeRecRepository = recRepo,
            profileLoader = { HomeProfileViewModel.empty },
            recommendationRepository = repo,
            galleryItemsSource = { listOf(blankGalleryItem()) },
            dailyLogSource = { listOf(dailyLogEntry()) },
        )
        dispatcher.scheduler.advanceUntilIdle()
        vm.generateRecommendations()
        dispatcher.scheduler.advanceUntilIdle()
        val gen = vm.generation.value as HomeRecommendationGenerationState.Success
        assertEquals(1, gen.recommendations.size)
        val ready = vm.state.value as HomeUiState.Ready
        assertEquals(1, ready.profile.recommendations.size)
    }

    private fun blankGalleryItem(): GalleryPhotoItem = GalleryPhotoItem(
        id = "gx_blank",
        uri = "content://media/blank",
        date = "2026-08-01",
        title = "",
        category = "",
        summary = "",
        createdAt = 0L,
    )
}
