package com.clawmate.app.gallery.ui

import com.clawmate.app.gallery.analysis.CapabilityState
import com.clawmate.app.gallery.analysis.CloudVisionAnalyzer
import com.clawmate.app.gallery.analysis.OcrEngine
import com.clawmate.app.gallery.analysis.OcrErrorKind
import com.clawmate.app.gallery.analysis.OcrResult
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
 * Deterministic ViewModel tests for the user-triggered Analyze OCR action:
 * only real recognized text (or a genuine empty) is persisted with the "analyzed"
 * status; every failure/model-download/unavailable path persists nothing and never
 * claims text. No device or ML Kit runtime is required (a controllable OcrEngine
 * is injected).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OcrAnalysisViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var repo: FakeOcrRepo

    private fun engine(result: OcrResult): OcrEngine = object : OcrEngine {
        override val state: CapabilityState = CapabilityState.Ready("ML Kit 中文文本识别")
        override suspend fun recognize(photo: GalleryPhotoItem): OcrResult = result
    }

    private val noOpVision = object : CloudVisionAnalyzer {
        override val state = CapabilityState.NotConfigured
        override fun analyze(photo: GalleryPhotoItem) = com.clawmate.app.gallery.analysis.VisionResult.NotConfigured
    }
    private val noOpGeocoder: ReverseGeocoder = object : ReverseGeocoder {
        override val state = CapabilityState.Unsupported
        override fun reverse(latitude: Double, longitude: Double): ReverseGeocodeResult =
            ReverseGeocodeResult.NotConfigured
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
    fun realTextIsPersistedWithAnalyzedStatus() = runTest {
        repo = FakeOcrRepo(item("a"))
        val vm = GalleryViewModel(repo, engine(OcrResult.Text("这是一段中文文字")), noOpVision, noOpGeocoder)
        dispatcher.scheduler.advanceUntilIdle()
        vm.selectItem("a")
        dispatcher.scheduler.advanceUntilIdle()
        vm.analyzeOcr("a")
        dispatcher.scheduler.advanceUntilIdle()
        val stored = repo.getItemSync("a")!!
        assertEquals("这是一段中文文字", stored.ocrText)
        assertEquals(GalleryPhotoItem.ANALYSIS_STATE_ANALYZED, stored.analysisState)
        val ocr = vm.detail.value?.ocr
        assertTrue(ocr is OcrViewState.Success)
        assertEquals("这是一段中文文字", (ocr as OcrViewState.Success).text)
    }

    @Test
    fun emptyResultPersistsEmptyTextWithAnalyzedStatus() = runTest {
        repo = FakeOcrRepo(item("a"))
        val vm = GalleryViewModel(repo, engine(OcrResult.Empty), noOpVision, noOpGeocoder)
        dispatcher.scheduler.advanceUntilIdle()
        vm.selectItem("a")
        dispatcher.scheduler.advanceUntilIdle()
        vm.analyzeOcr("a")
        dispatcher.scheduler.advanceUntilIdle()
        val stored = repo.getItemSync("a")!!
        assertEquals("", stored.ocrText)
        assertEquals(GalleryPhotoItem.ANALYSIS_STATE_ANALYZED, stored.analysisState)
        assertTrue(vm.detail.value?.ocr is OcrViewState.Empty)
    }

    @Test
    fun errorResultPersistsNothingAndNeverRealText() = runTest {
        repo = FakeOcrRepo(item("a"))
        val vm = GalleryViewModel(
            repo, engine(OcrResult.Failed("无法打开内容流", OcrErrorKind.URI_ACCESS)), noOpVision, noOpGeocoder,
        )
        dispatcher.scheduler.advanceUntilIdle()
        vm.selectItem("a")
        dispatcher.scheduler.advanceUntilIdle()
        vm.analyzeOcr("a")
        dispatcher.scheduler.advanceUntilIdle()
        val stored = repo.getItemSync("a")!!
        assertEquals("", stored.ocrText)
        // No claimed analysis on failure.
        assertTrue(stored.analysisState != GalleryPhotoItem.ANALYSIS_STATE_ANALYZED)
        val ocr = vm.detail.value?.ocr
        assertTrue(ocr is OcrViewState.Error)
        assertEquals(OcrErrorKind.URI_ACCESS, (ocr as OcrViewState.Error).kind)
    }

    @Test
    fun unavailableResultPersistsNothing() = runTest {
        repo = FakeOcrRepo(item("a"))
        val vm = GalleryViewModel(repo, engine(OcrResult.Unavailable("on-device OCR 尚未配置")), noOpVision, noOpGeocoder)
        dispatcher.scheduler.advanceUntilIdle()
        vm.selectItem("a")
        dispatcher.scheduler.advanceUntilIdle()
        vm.analyzeOcr("a")
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.detail.value?.ocr is OcrViewState.Unavailable)
        assertEquals("", repo.getItemSync("a")!!.ocrText)
    }

    @Test
    fun modelDownloadResultSurfacesDownloadStateAndPersistsNothing() = runTest {
        repo = FakeOcrRepo(item("a"))
        val vm = GalleryViewModel(
            repo, engine(OcrResult.ModelDownload("中文识别模型不可用，可能需要下载")), noOpVision, noOpGeocoder,
        )
        dispatcher.scheduler.advanceUntilIdle()
        vm.selectItem("a")
        dispatcher.scheduler.advanceUntilIdle()
        vm.analyzeOcr("a")
        dispatcher.scheduler.advanceUntilIdle()
        val ocr = vm.detail.value?.ocr
        assertTrue(ocr is OcrViewState.ModelDownload)
        assertEquals("", repo.getItemSync("a")!!.ocrText)
    }

    @Test
    fun openingDetailReflectsPriorAnalyzedResult() = runTest {
        repo = FakeOcrRepo(
            item("a").copy(ocrText = "旧结果", analysisState = GalleryPhotoItem.ANALYSIS_STATE_ANALYZED),
        )
        val vm = GalleryViewModel(repo, engine(OcrResult.Failed("x", OcrErrorKind.UNKNOWN)), noOpVision, noOpGeocoder)
        dispatcher.scheduler.advanceUntilIdle()
        vm.selectItem("a")
        dispatcher.scheduler.advanceUntilIdle()
        val ocr = vm.detail.value?.ocr
        assertTrue(ocr is OcrViewState.Success)
        assertEquals("旧结果", (ocr as OcrViewState.Success).text)
    }

    @Test
    fun cloudAnalysisWithoutOcrDoesNotClaimAnEmptyOcrResult() = runTest {
        repo = FakeOcrRepo(item("a").copy(analysisState = GalleryPhotoItem.ANALYSIS_STATE_CLOUD_ANALYZED))
        val vm = GalleryViewModel(repo, engine(OcrResult.Empty), noOpVision, noOpGeocoder)
        dispatcher.scheduler.advanceUntilIdle()
        vm.selectItem("a")
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.detail.value?.ocr is OcrViewState.Idle)
    }

    private fun item(id: String) = GalleryPhotoItem(
        id = id,
        uri = "content://media/picker/$id",
        date = "2026-06-01",
        title = "标题$id",
        category = "",
        summary = "",
        createdAt = 0L,
    )
}

/** In-memory [GalleryRepository] for OCR ViewModel tests. */
private class FakeOcrRepo(initial: GalleryPhotoItem) : GalleryRepository {
    private val items = MutableStateFlow(mutableListOf(initial))
    private val groups = MutableStateFlow<List<CollectionGroup>>(emptyList())

    fun getItemSync(id: String): GalleryPhotoItem? = items.value.firstOrNull { it.id == id }

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
        val deleted = items.value.size < before
        if (deleted) refreshGroups("gallery")
        return deleted
    }

    override suspend fun itemGroupMembership(itemId: String): List<CollectionGroup> =
        groups.value.filter { it.eventIds.contains(itemId) }
}
