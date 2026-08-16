package com.clawmate.app.gallery.ui

import com.clawmate.app.gallery.analysis.CapabilityState
import com.clawmate.app.gallery.analysis.CloudVisionAnalyzer
import com.clawmate.app.gallery.analysis.OcrEngine
import com.clawmate.app.gallery.analysis.ReverseGeocodeResult
import com.clawmate.app.gallery.analysis.ReverseGeocoder
import com.clawmate.app.gallery.data.GalleryRepository
import com.clawmate.app.gallery.geocode.GalleryLocationInfo
import com.clawmate.app.gallery.model.CollectionGroup
import com.clawmate.app.gallery.model.GalleryPhotoItem
import com.clawmate.app.gallery.model.GallerySearchHit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Deterministic ViewModel tests for edit/delete persistence and location
 * resolution using an in-memory [FakeGalleryRepo] and a controllable
 * [ReverseGeocoder]. No device or live geocoder is required.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GalleryViewModelEditDeleteTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var repo: FakeGalleryRepo

    private val readyOcr = object : OcrEngine {
        override val state = CapabilityState.NotConfigured
        override fun recognize(photo: GalleryPhotoItem) = com.clawmate.app.gallery.analysis.OcrResult.Unavailable("x")
    }
    private val readyVision = object : CloudVisionAnalyzer {
        override val state = CapabilityState.NotConfigured
        override fun analyze(photo: GalleryPhotoItem) = com.clawmate.app.gallery.analysis.VisionResult.NotConfigured
    }
    private val readyGeocoder: ReverseGeocoder = object : ReverseGeocoder {
        override val state = CapabilityState.Ready("系统地理编码")
        override fun reverse(latitude: Double, longitude: Double): ReverseGeocodeResult =
            ReverseGeocodeResult.Resolved(GalleryLocationInfo(formattedAddress = "上海市世纪大道", provider = "geocoder"))
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun editingTitlePersistsThroughViewModel() = runTest {
        repo = FakeGalleryRepo(item("a"))
        val vm = GalleryViewModel(repo, readyOcr, readyVision, readyGeocoder)
        dispatcher.scheduler.advanceUntilIdle()
        vm.selectItem("a")
        dispatcher.scheduler.advanceUntilIdle()
        vm.editTitle("a", "改后标题")
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals("改后标题", repo.getItemSync("a")!!.title)
    }

    @Test
    fun editingSummaryAndTagsPersist() = runTest {
        repo = FakeGalleryRepo(item("a"))
        val vm = GalleryViewModel(repo, readyOcr, readyVision, readyGeocoder)
        dispatcher.scheduler.advanceUntilIdle()
        vm.editSummary("a", "新摘要")
        vm.editTags("a", listOf("标签1", "标签2"))
        dispatcher.scheduler.advanceUntilIdle()
        val stored = repo.getItemSync("a")!!
        assertEquals("新摘要", stored.summary)
        assertEquals(listOf("标签1", "标签2"), stored.tags)
    }

    @Test
    fun deleteClearsDetailAndRemovesItem() = runTest {
        repo = FakeGalleryRepo(item("a"))
        val vm = GalleryViewModel(repo, readyOcr, readyVision, readyGeocoder)
        dispatcher.scheduler.advanceUntilIdle()
        vm.selectItem("a")
        dispatcher.scheduler.advanceUntilIdle()
        assertNotNull(vm.detail.value)
        vm.deleteItem("a")
        dispatcher.scheduler.advanceUntilIdle()
        assertNull(repo.getItemSync("a"))
        assertNull(vm.selectedItemId.value)
        assertNull(vm.detail.value)
    }

    @Test
    fun resolveLocationPersistsAddressOnlyOnRealResolve() = runTest {
        repo = FakeGalleryRepo(item("coords").copy(exif = null, latitude = 31.2, longitude = 121.4))
        val vm = GalleryViewModel(repo, readyOcr, readyVision, readyGeocoder)
        dispatcher.scheduler.advanceUntilIdle()
        vm.selectItem("coords")
        dispatcher.scheduler.advanceUntilIdle()
        vm.resolveLocation("coords")
        dispatcher.scheduler.advanceUntilIdle()
        val stored = repo.getItemSync("coords")!!
        assertEquals("上海市世纪大道", stored.address)
        assertEquals("geocoder", stored.locationProvider)
        assertTrue(vm.detail.value?.location is LocationViewState.Resolved)
    }

    @Test
    fun failedGeocodeNeverClaimsLocation() = runTest {
        repo = FakeGalleryRepo(item("coords").copy(latitude = 31.2, longitude = 121.4))
        val failing = object : ReverseGeocoder {
            override val state = CapabilityState.Unsupported
            override fun reverse(latitude: Double, longitude: Double): ReverseGeocodeResult =
                ReverseGeocodeResult.Failed(com.clawmate.app.gallery.geocode.ReverseGeocodeError(
                    com.clawmate.app.gallery.geocode.ReverseGeocodeErrorKind.UNAVAILABLE,
                    "系统未提供地理编码服务",
                ))
        }
        val vm = GalleryViewModel(repo, readyOcr, readyVision, failing)
        dispatcher.scheduler.advanceUntilIdle()
        vm.selectItem("coords")
        dispatcher.scheduler.advanceUntilIdle()
        vm.resolveLocation("coords")
        dispatcher.scheduler.advanceUntilIdle()
        val loc = vm.detail.value?.location
        assertTrue(loc is LocationViewState.Failed)
        // No address persisted on failure.
        assertTrue(repo.getItemSync("coords")!!.address.isBlank())
    }

    @Test
    fun noCoordinatesSurfacesNoCoordinatesAndPersistsNothing() = runTest {
        repo = FakeGalleryRepo(item("plain")) // no lat/lng
        val vm = GalleryViewModel(repo, readyOcr, readyVision, readyGeocoder)
        dispatcher.scheduler.advanceUntilIdle()
        vm.selectItem("plain")
        dispatcher.scheduler.advanceUntilIdle()
        vm.resolveLocation("plain")
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.detail.value?.location is LocationViewState.NoCoordinates)
        assertTrue(repo.getItemSync("plain")!!.address.isBlank())
    }

    private fun item(id: String) = GalleryPhotoItem(
        id = id,
        uri = "content://media/picker/$id",
        date = "2026-06-01",
        title = "标题$id",
        category = "餐饮",
        summary = "摘要$id",
        createdAt = 0L,
    )
}

/** In-memory [GalleryRepository] for ViewModel tests. */
private class FakeGalleryRepo(initial: GalleryPhotoItem) : GalleryRepository {
    private val items = MutableStateFlow(mutableListOf(initial))
    private val groups = MutableStateFlow<List<CollectionGroup>>(emptyList())

    fun getItemSync(id: String): GalleryPhotoItem? = items.value.firstOrNull { it.id == id }

    override fun observeItems(): Flow<List<GalleryPhotoItem>> = items

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
        val g = com.clawmate.app.gallery.collection.CollectionGroupingService.groupPhotos(
            tabId, items.value.sortedBy { it.id },
        )
        groups.value = g
        return g
    }

    override fun observeGroups(): Flow<List<CollectionGroup>> = groups

    override suspend fun search(query: String): List<GallerySearchHit> =
        com.clawmate.app.gallery.search.GallerySearchService.search(query, items.value)

    override suspend fun updateTitle(id: String, title: String): Boolean {
        val list = items.value.toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return false
        list[idx] = list[idx].copy(title = title.trim())
        items.value = list
        return true
    }

    override suspend fun updateSummary(id: String, summary: String): Boolean {
        val list = items.value.toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return false
        list[idx] = list[idx].copy(summary = summary.trim())
        items.value = list
        return true
    }

    override suspend fun updateTags(id: String, tags: List<String>): Boolean {
        val list = items.value.toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return false
        list[idx] = list[idx].copy(tags = tags)
        items.value = list
        return true
    }

    override suspend fun updateLocation(id: String, address: String, provider: String): Boolean {
        val list = items.value.toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return false
        list[idx] = list[idx].copy(address = address.trim(), locationProvider = provider.trim())
        items.value = list
        return true
    }

    override suspend fun deleteItem(id: String): Boolean {
        val before = items.value.size
        items.value = items.value.filterNot { it.id == id }.toMutableList()
        val deleted = items.value.size < before
        if (deleted) refreshGroups("gallery")
        return deleted
    }

    override suspend fun itemGroupMembership(itemId: String): List<CollectionGroup> =
        groups.value.filter { it.eventIds.contains(itemId) }
}
