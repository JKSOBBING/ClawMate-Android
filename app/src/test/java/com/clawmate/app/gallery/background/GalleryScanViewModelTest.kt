package com.clawmate.app.gallery.background

import com.clawmate.app.gallery.data.GalleryRepository
import com.clawmate.app.gallery.model.CollectionGroup
import com.clawmate.app.gallery.model.GalleryPhotoItem
import com.clawmate.app.gallery.model.GalleryIds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Phase 42 (work package B) — device-free tests for the explicit
 * [GalleryScanViewModel] entry.
 *
 * The production coordinator already has its own coverage for scanning bounds,
 * batch sizes, cloud no-call behaviour and idempotency. These tests focus on the
 * *reachability seam* the Phase 42 work adds: the explicit action that wires a
 * real [GalleryBackgroundCoordinator] + [GalleryMediaSource] into a caller, and
 * that proves permission/opt-in gating, cancellation/single-flight/limit and a
 * real repository write all behave honestly.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GalleryScanViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ------------------------------------------------------------------ fakes

    private class FakeMediaSource(private val inputs: List<GalleryPhotoInput>) : GalleryMediaSource {
        var scanCalls = 0
        override suspend fun scanNewest(limit: Int): List<GalleryPhotoInput> {
            scanCalls += 1
            return inputs.take(limit)
        }
    }

    private class FakeRepo : GalleryRepository {
        val store = LinkedHashMap<String, GalleryPhotoItem>()

        override fun observeItems(): Flow<List<GalleryPhotoItem>> =
            flowOf(store.values.sortedBy { it.id }.toList())
        override fun observeMonthlyInsights(): Flow<List<com.clawmate.app.gallery.insights.GalleryMonthInsight>> =
            MutableStateFlow(emptyList())
        override suspend fun getItem(id: String): GalleryPhotoItem? = store[id]
        override suspend fun upsert(item: GalleryPhotoItem): GalleryPhotoItem {
            val existing = store[item.id]
            val toStore = if (existing != null) {
                item.copy(
                    id = existing.id,
                    title = if (item.title.isBlank()) existing.title else item.title,
                    category = if (item.category.isBlank()) existing.category else item.category,
                    summary = if (item.summary.isBlank()) existing.summary else item.summary,
                )
            } else item
            store[toStore.id] = toStore
            return toStore
        }
        override suspend fun importItems(items: List<GalleryPhotoItem>): List<GalleryPhotoItem> =
            items.sortedBy { it.id }.map { upsert(it) }
        override suspend fun countItems(): Int = store.size
        override suspend fun refreshGroups(tabId: String): List<CollectionGroup> = emptyList()
        override fun observeGroups(): Flow<List<CollectionGroup>> = MutableStateFlow(emptyList())
        override suspend fun search(query: String): List<com.clawmate.app.gallery.model.GallerySearchHit> = emptyList()
        override suspend fun updateTitle(id: String, title: String): Boolean = false
        override suspend fun updateSummary(id: String, summary: String): Boolean = false
        override suspend fun updateTags(id: String, tags: List<String>): Boolean = false
        override suspend fun updateLocation(id: String, address: String, provider: String): Boolean = false
        override suspend fun updateOcr(id: String, ocrText: String, analysisState: String): Boolean = false
        override suspend fun deleteItem(id: String): Boolean = false
        override suspend fun itemGroupMembership(itemId: String): List<CollectionGroup> = emptyList()
    }

    /** A gate whose opt-in / permission can be flipped between actions. */
    private class MutableGate(var optedIn: Boolean, var granted: Boolean) {
        val provider: () -> GallerySyncGate = { GallerySyncGate(optedIn, granted) }
    }

    private fun input(uri: String): GalleryPhotoInput =
        GalleryPhotoInput(uri, GalleryIds.stableIdForUri(uri), "2024-06-01", null, null)

    // ------------------------------------------------------- permission gate

    @Test
    fun `permission not granted does not scan`() = runTest {
        val media = FakeMediaSource(listOf(input("content://img/1")))
        val gate = MutableGate(true, false)
        val coordinator = GalleryBackgroundCoordinator(
            gate = gate.provider,
            mediaSource = media,
            repository = FakeRepo(),
        )
        val vm = GalleryScanViewModel(coordinator, gate.provider)
        vm.scanNow()
        val state = vm.state.value
        assertTrue("expected Failed but was ${state::class.simpleName}", state is GalleryScanUiState.Failed)
        assertEquals(0, media.scanCalls) // media never consulted
    }

    @Test
    fun `not opted in does not scan and surfaces idle`() = runTest {
        val media = FakeMediaSource(listOf(input("content://img/1")))
        val gate = MutableGate(false, true)
        val coordinator = GalleryBackgroundCoordinator(
            gate = gate.provider,
            mediaSource = media,
            repository = FakeRepo(),
        )
        val vm = GalleryScanViewModel(coordinator, gate.provider)
        vm.scanNow()
        assertTrue(vm.state.value is GalleryScanUiState.Idle)
        assertEquals(0, media.scanCalls)
    }

    // ------------------------------------------- explicit action reaches pipeline

    @Test
    fun `authorized explicit action scans and writes to the real repository`() = runTest {
        val repo = FakeRepo()
        val media = FakeMediaSource(listOf(input("content://img/1"), input("content://img/2")))
        val gate = MutableGate(true, true)
        val coordinator = GalleryBackgroundCoordinator(
            gate = gate.provider,
            mediaSource = media,
            repository = repo,
        )
        val vm = GalleryScanViewModel(coordinator, gate.provider)
        vm.scanNow()
        advanceUntilIdle()
        val state = vm.state.value
        assertTrue("expected Succeeded but was ${state::class.simpleName}", state is GalleryScanUiState.Succeeded)
        assertEquals(1, media.scanCalls)
        // The scanned URIs actually reached the existing Room-backed repository.
        assertEquals(2, repo.store.size)
        assertEquals(setOf("content://img/1", "content://img/2"), repo.store.values.map { it.uri }.toSet())
    }

    @Test
    fun `idempotent re-run upserts no duplicates by uri`() = runTest {
        val repo = FakeRepo()
        val media = FakeMediaSource(listOf(input("content://img/1"), input("content://img/2")))
        val gate = MutableGate(true, true)
        val coordinator = GalleryBackgroundCoordinator(
            gate = gate.provider,
            mediaSource = media,
            repository = repo,
        )
        val vm = GalleryScanViewModel(coordinator, gate.provider)
        vm.scanNow()
        advanceUntilIdle()
        vm.scanNow()
        advanceUntilIdle()
        assertEquals(setOf("content://img/1", "content://img/2"), repo.store.values.map { it.uri }.toSet())
        assertEquals(2, repo.store.size)
    }

    // ------------------------------------------------------------ cancellation

    @Test
    fun `cancel yields an honest partial`() = runTest {
        val repo = FakeRepo()
        val inputs = (1..10).map { input("content://img/$it") }
        val media = FakeMediaSource(inputs)
        val gate = MutableGate(true, true)
        val coordinator = GalleryBackgroundCoordinator(
            gate = gate.provider,
            mediaSource = media,
            repository = repo,
        )
        coordinator.cancelActive()
        val vm = GalleryScanViewModel(coordinator, gate.provider)
        vm.scanNow()
        advanceUntilIdle()
        val state = vm.state.value
        assertTrue("expected Partial but was ${state::class.simpleName}", state is GalleryScanUiState.Partial)
        assertTrue((state as GalleryScanUiState.Partial).summary.skippedCount > 0)
    }

    // --------------------------------------------------------- limit enforcement

    @Test
    fun `scan limit is clamped to the policy bound`() {
        assertEquals(GallerySyncPolicy.MAX_ITEMS_PER_RUN, GallerySyncPolicy.clampScanLimit(999))
        assertEquals(1, GallerySyncPolicy.clampScanLimit(0))
        assertEquals(60, GallerySyncPolicy.clampScanLimit(60))
    }
}
