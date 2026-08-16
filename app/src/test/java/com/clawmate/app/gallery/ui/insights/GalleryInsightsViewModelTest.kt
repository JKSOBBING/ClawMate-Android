package com.clawmate.app.gallery.ui.insights

import com.clawmate.app.gallery.data.GalleryRepository
import com.clawmate.app.gallery.insights.GalleryMonthlyInsightCloudState
import com.clawmate.app.gallery.model.CollectionGroup
import com.clawmate.app.gallery.model.GalleryPhotoItem
import com.clawmate.app.gallery.model.GallerySearchHit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
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
 * Deterministic ViewModel tests for the Phase-15 monthly-insights panel state
 * machine: loading -> loaded / empty / error, and the cloud LLM + embedding
 * always reporting NOT_CONFIGURED (never a fabricated summary).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GalleryInsightsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun item(id: String, date: String = "2026-06-01") = GalleryPhotoItem(
        id = id,
        uri = "content://media/picker/$id",
        date = date,
        title = "",
        category = "",
        summary = "",
        createdAt = 0L,
    )

    @Test
    fun emptyStoredItemsProduceEmptyState() = runTest {
        val repo = FakeInsightsRepo()
        val vm = GalleryInsightsViewModel(repo)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(GalleryInsightsUiState.Empty, vm.state.value)
    }

    @Test
    fun storedItemsProduceLoadedInsights() = runTest {
        val repo = FakeInsightsRepo(item("a"), item("b", date = "2026-07-03"))
        val vm = GalleryInsightsViewModel(repo)
        dispatcher.scheduler.advanceUntilIdle()
        val state = vm.state.value
        assertTrue(state is GalleryInsightsUiState.Loaded)
        val loaded = state as GalleryInsightsUiState.Loaded
        assertEquals(listOf("2026-07", "2026-06"), loaded.insights.map { it.key })
        assertEquals(1, loaded.insights[0].totalCount)
        // Cloud LLM + embedding always truthful NOT_CONFIGURED.
        assertTrue(loaded.aiSummary is GalleryMonthlyInsightCloudState.NotConfigured)
        assertTrue(loaded.embedding is GalleryMonthlyInsightCloudState.NotConfigured)
    }

    @Test
    fun repositoryErrorSurfacesErrorState() = runTest {
        val repo = FailingInsightsRepo()
        val vm = GalleryInsightsViewModel(repo)
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.state.value is GalleryInsightsUiState.Error)
    }
}

/** In-memory [GalleryRepository] that yields the given items (or fails). */
private class FakeInsightsRepo(vararg initial: GalleryPhotoItem) : GalleryRepository {
    private val items = MutableStateFlow(initial.toMutableList())
    private val groups = MutableStateFlow<List<CollectionGroup>>(emptyList())

    override fun observeItems(): Flow<List<GalleryPhotoItem>> = items

    override fun observeMonthlyInsights(): Flow<List<com.clawmate.app.gallery.insights.GalleryMonthInsight>> =
        items.map { com.clawmate.app.gallery.insights.GalleryMonthlyInsights.build(it) }

    override suspend fun getItem(id: String): GalleryPhotoItem? = items.value.firstOrNull { it.id == id }

    override suspend fun upsert(item: GalleryPhotoItem): GalleryPhotoItem {
        val list = items.value.toMutableList()
        list.removeAll { it.id == item.id }
        list.add(item)
        items.value = list
        return item
    }

    override suspend fun importItems(items: List<GalleryPhotoItem>): List<GalleryPhotoItem> =
        items.map { upsert(it) }

    override suspend fun countItems(): Int = items.value.size

    override suspend fun refreshGroups(tabId: String): List<CollectionGroup> {
        groups.value = com.clawmate.app.gallery.collection.CollectionGroupingService.groupPhotos(
            tabId, items.value.sortedBy { it.id },
        )
        return groups.value
    }

    override fun observeGroups(): Flow<List<CollectionGroup>> = groups

    override suspend fun search(query: String): List<GallerySearchHit> =
        com.clawmate.app.gallery.search.GallerySearchService.search(query, items.value)

    override suspend fun updateTitle(id: String, title: String): Boolean = true
    override suspend fun updateSummary(id: String, summary: String): Boolean = true
    override suspend fun updateTags(id: String, tags: List<String>): Boolean = true
    override suspend fun updateLocation(id: String, address: String, provider: String): Boolean = true
    override suspend fun updateOcr(id: String, ocrText: String, analysisState: String): Boolean {
        val list = items.value.toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return false
        list[idx] = list[idx].copy(ocrText = ocrText, analysisState = analysisState)
        items.value = list
        return true
    }

    override suspend fun deleteItem(id: String): Boolean {
        val before = items.value.size
        items.value = items.value.filterNot { it.id == id }.toMutableList()
        return items.value.size < before
    }

    override suspend fun itemGroupMembership(itemId: String): List<CollectionGroup> = emptyList()
}

/** [GalleryRepository] whose items/insights observation always fails. */
private class FailingInsightsRepo : GalleryRepository {
    override fun observeItems(): Flow<List<GalleryPhotoItem>> = kotlinx.coroutines.flow.flow {
        throw IllegalStateException("storage unavailable")
    }

    override fun observeMonthlyInsights(): Flow<List<com.clawmate.app.gallery.insights.GalleryMonthInsight>> =
        kotlinx.coroutines.flow.flow {
            throw IllegalStateException("storage unavailable")
        }

    override suspend fun getItem(id: String): GalleryPhotoItem? = null
    override suspend fun upsert(item: GalleryPhotoItem): GalleryPhotoItem = item
    override suspend fun importItems(items: List<GalleryPhotoItem>): List<GalleryPhotoItem> = emptyList()
    override suspend fun countItems(): Int = 0
    override suspend fun refreshGroups(tabId: String): List<CollectionGroup> = emptyList()
    override fun observeGroups(): Flow<List<CollectionGroup>> = MutableStateFlow(emptyList())

    override suspend fun search(query: String): List<GallerySearchHit> = emptyList()

    override suspend fun updateTitle(id: String, title: String): Boolean = false
    override suspend fun updateSummary(id: String, summary: String): Boolean = false
    override suspend fun updateTags(id: String, tags: List<String>): Boolean = false
    override suspend fun updateLocation(id: String, address: String, provider: String): Boolean = false
    override suspend fun updateOcr(id: String, ocrText: String, analysisState: String): Boolean = false
    override suspend fun deleteItem(id: String): Boolean = false
    override suspend fun itemGroupMembership(itemId: String): List<CollectionGroup> = emptyList()
}
