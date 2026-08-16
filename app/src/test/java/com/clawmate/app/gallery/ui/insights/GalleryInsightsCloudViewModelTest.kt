package com.clawmate.app.gallery.ui.insights

import com.clawmate.app.gallery.cloud.GalleryCloudAdapter
import com.clawmate.app.gallery.cloud.GalleryCloudException
import com.clawmate.app.gallery.cloud.GalleryCloudErrorReason
import com.clawmate.app.gallery.insights.GalleryMonthlyInsightCloudState
import com.clawmate.app.settings.domain.AppSettings
import com.clawmate.app.settings.domain.EmbeddingMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Device-free check that [GalleryInsightsViewModel] wires the config-driven
 * cloud gate behind explicit settings: default (no provider) is NOT_CONFIGURED;
 * a configured provider yields READY; an explicit action against a failing
 * adapter surfaces an honest, redacted ERROR. No network is involved.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GalleryInsightsCloudViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val httpClient = OkHttpClient()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun insightsRepo() = FakeInsightRepo()

    @Test
    fun `default provider keeps cloud states NOT_CONFIGURED`() = kotlinx.coroutines.test.runTest {
        val vm = GalleryInsightsViewModel(insightsRepo())
        dispatcher.scheduler.advanceUntilIdle()
        val state = vm.state.value as GalleryInsightsUiState.Loaded
        assertTrue(state.aiSummary is GalleryMonthlyInsightCloudState.NotConfigured)
        assertTrue(state.embedding is GalleryMonthlyInsightCloudState.NotConfigured)
    }

    @Test
    fun `configured adapter yields READY gates`() = kotlinx.coroutines.test.runTest {
        val settings = AppSettings.defaults().copy(
            galleryAiSummaryEnabled = true,
            galleryEmbeddingMode = EmbeddingMode.Remote,
            galleryEmbeddingBaseUrl = "https://embed.test",
            galleryEmbeddingApiKey = "sk-embed",
            galleryEmbeddingModel = "text-embedding-3",
        )
        val adapter = GalleryCloudAdapter.fromSettings(
            settings = settings,
            plannerBaseUrl = "https://planner.test",
            plannerApiKey = "sk-planner",
            plannerModel = "gpt-4o",
            httpClient = httpClient,
            chatClient = fakeChat(),
        )
        val vm = GalleryInsightsViewModel(insightsRepo()) { adapter }
        dispatcher.scheduler.advanceUntilIdle()
        val state = vm.state.value as GalleryInsightsUiState.Loaded
        assertTrue(state.aiSummary is GalleryMonthlyInsightCloudState.Ready)
        assertTrue(state.embedding is GalleryMonthlyInsightCloudState.Ready)
    }

    @Test
    fun `explicit action against failing adapter surfaces redacted ERROR`() =
        kotlinx.coroutines.test.runTest {
            val settings = AppSettings.defaults().copy(galleryAiSummaryEnabled = true)
            val adapter = GalleryCloudAdapter.fromSettings(
                settings = settings,
                plannerBaseUrl = "https://planner.test",
                plannerApiKey = "sk-planner-secret",
                plannerModel = "gpt-4o",
                httpClient = httpClient,
                chatClient = object : com.clawmate.app.chat.cloud.ChatClient {
                    override suspend fun completion(
                        r: com.clawmate.app.chat.cloud.ChatCompletionsRequest,
                    ): com.clawmate.app.chat.cloud.ChatCompletionResult {
                        throw GalleryCloudException(
                            reason = GalleryCloudErrorReason.HTTP,
                            message = "provider rejected sk-planner-secret",
                        )
                    }

                    override fun completionStream(
                        r: com.clawmate.app.chat.cloud.ChatCompletionsRequest,
                    ): kotlinx.coroutines.flow.Flow<com.clawmate.app.chat.cloud.CloudStreamChunk> =
                        kotlinx.coroutines.flow.emptyFlow()
                },
            )
            val vm = GalleryInsightsViewModel(insightsRepo()) { adapter }
            dispatcher.scheduler.advanceUntilIdle()

            val loaded = vm.state.value as GalleryInsightsUiState.Loaded
            val insight = loaded.insights.first()
            vm.generateMonthlySummary(insight)
            dispatcher.scheduler.advanceUntilIdle()

            val after = vm.aiSummary.value
            assertTrue(after is GalleryMonthlyInsightCloudState.Error)
            assertTrue(!(after as GalleryMonthlyInsightCloudState.Error).message.contains("sk-planner-secret"))
        }

    private fun fakeChat(): com.clawmate.app.chat.cloud.ChatClient = object : com.clawmate.app.chat.cloud.ChatClient {
        override suspend fun completion(
            r: com.clawmate.app.chat.cloud.ChatCompletionsRequest,
        ): com.clawmate.app.chat.cloud.ChatCompletionResult =
            com.clawmate.app.chat.cloud.ChatCompletionResult(
                content = """{"title":"六月","summary":"观察","suggestion":"整理"}""",
                finishReason = "stop",
                raw = """{"title":"六月","summary":"观察","suggestion":"整理"}""",
            )

        override fun completionStream(
            r: com.clawmate.app.chat.cloud.ChatCompletionsRequest,
        ): kotlinx.coroutines.flow.Flow<com.clawmate.app.chat.cloud.CloudStreamChunk> =
            kotlinx.coroutines.flow.emptyFlow()
    }
}

/** In-memory [com.clawmate.app.gallery.data.GalleryRepository] with one item. */
private class FakeInsightRepo : com.clawmate.app.gallery.data.GalleryRepository {
    private val items = kotlinx.coroutines.flow.MutableStateFlow(
        mutableListOf(
            com.clawmate.app.gallery.model.GalleryPhotoItem(
                id = "p1",
                uri = "content://x/1",
                date = "2026-06-01",
                title = "a",
                category = "",
                summary = "",
                createdAt = 0L,
            ),
        ),
    )
    private val groups =
        kotlinx.coroutines.flow.MutableStateFlow<List<com.clawmate.app.gallery.model.CollectionGroup>>(emptyList())

    override fun observeItems(): kotlinx.coroutines.flow.Flow<List<com.clawmate.app.gallery.model.GalleryPhotoItem>> = items

    override fun observeMonthlyInsights():
        kotlinx.coroutines.flow.Flow<List<com.clawmate.app.gallery.insights.GalleryMonthInsight>> =
        items.map { com.clawmate.app.gallery.insights.GalleryMonthlyInsights.build(it) }


    override suspend fun getItem(id: String): com.clawmate.app.gallery.model.GalleryPhotoItem? =
        items.value.firstOrNull { it.id == id }

    override suspend fun upsert(item: com.clawmate.app.gallery.model.GalleryPhotoItem):
        com.clawmate.app.gallery.model.GalleryPhotoItem {
        val list = items.value.toMutableList()
        list.removeAll { it.id == item.id }
        list.add(item)
        items.value = list
        return item
    }

    override suspend fun importItems(items: List<com.clawmate.app.gallery.model.GalleryPhotoItem>):
        List<com.clawmate.app.gallery.model.GalleryPhotoItem> = items.map { upsert(it) }

    override suspend fun countItems(): Int = items.value.size

    override suspend fun refreshGroups(tabId: String): List<com.clawmate.app.gallery.model.CollectionGroup> {
        groups.value = com.clawmate.app.gallery.collection.CollectionGroupingService.groupPhotos(
            tabId, items.value.sortedBy { it.id },
        )
        return groups.value
    }

    override fun observeGroups(): kotlinx.coroutines.flow.Flow<List<com.clawmate.app.gallery.model.CollectionGroup>> = groups

    override suspend fun search(query: String): List<com.clawmate.app.gallery.model.GallerySearchHit> =
        com.clawmate.app.gallery.search.GallerySearchService.search(query, items.value)

    override suspend fun updateTitle(id: String, title: String): Boolean = true
    override suspend fun updateSummary(id: String, summary: String): Boolean = true
    override suspend fun updateTags(id: String, tags: List<String>): Boolean = true
    override suspend fun updateLocation(id: String, address: String, provider: String): Boolean = true
    override suspend fun updateOcr(id: String, ocrText: String, analysisState: String): Boolean = true
    override suspend fun deleteItem(id: String): Boolean = true
    override suspend fun itemGroupMembership(itemId: String): List<com.clawmate.app.gallery.model.CollectionGroup> = emptyList()
}
