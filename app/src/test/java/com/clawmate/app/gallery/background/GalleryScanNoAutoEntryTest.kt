package com.clawmate.app.gallery.background

import com.clawmate.app.gallery.data.GalleryRepository
import com.clawmate.app.gallery.model.CollectionGroup
import com.clawmate.app.gallery.model.GalleryPhotoItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Phase 42 architecture regression: the scan/sync boundary has **no automatic
 * entry**. This is the safety contract the whole phase exists to preserve —
 * nothing in app start, Gallery screen load, or coordinator/ViewModel
 * construction ever starts a media scan; only the explicit user click does.
 *
 * The behavioural checks run device-free against real production classes with
 * fakes. The source-level checks read the actual production source files (the
 * test working directory is the `app` module) and assert the most critical
 * auto-entry hazards are absent.
 */
class GalleryScanNoAutoEntryTest {

    private class FakeRepo : GalleryRepository {
        val store = LinkedHashMap<String, GalleryPhotoItem>()
        override fun observeItems(): Flow<List<GalleryPhotoItem>> = flowOf(emptyList())
        override fun observeMonthlyInsights(): Flow<List<com.clawmate.app.gallery.insights.GalleryMonthInsight>> =
            MutableStateFlow(emptyList())
        override suspend fun getItem(id: String): GalleryPhotoItem? = null
        override suspend fun upsert(item: GalleryPhotoItem): GalleryPhotoItem { store[item.id] = item; return item }
        override suspend fun importItems(items: List<GalleryPhotoItem>): List<GalleryPhotoItem> = items.map { upsert(it) }
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

    /** A media source that records whether it was ever consulted. */
    private class RecordingSource : GalleryMediaSource {
        var scanCalls = 0
        override suspend fun scanNewest(limit: Int): List<GalleryPhotoInput> {
            scanCalls += 1
            return emptyList()
        }
    }

    // ------------------------------------------------------ behavioural (JVM)

    @Test
    fun `constructing the coordinator and viewmodel does not scan`() {
        val source = RecordingSource()
        val gate = { GallerySyncGate(optedIn = true, photoAccessGranted = true) }
        val coordinator = GalleryBackgroundCoordinator(gate = gate, mediaSource = source, repository = FakeRepo())
        val vm = GalleryScanViewModel(coordinator, gate)

        // Construction alone (the reachable seam the phase adds) must not scan.
        assertFalse(coordinator.isRunning)
        assertTrue(vm.state.value is GalleryScanUiState.Idle)
        assertEquals(0, source.scanCalls)
    }

    @Test
    fun `the runtime opt-in registry defaults to disabled`() {
        com.clawmate.app.gallery.background.GallerySyncOptinRegistry.reset()
        assertFalse(com.clawmate.app.gallery.background.GallerySyncOptinRegistry.optedIn)
    }

    // ------------------------------------------------------ source-level (JVM)

    private fun sourceFile(relative: String): File =
        File(System.getProperty("user.dir"), relative)

    @Test
    fun `application onCreate does not auto-run a gallery scan`() {
        val app = sourceFile("src/main/java/com/clawmate/app/ClawMateApplication.kt")
        assertTrue("expected source to exist", app.exists())
        val text = app.readText()
        // The application entry point must never start a scan or construct the
        // scan coordinator by itself.
        assertFalse("Application must not call runOnce()", text.contains("runOnce("))
        assertFalse("Application must not call scanNow()", text.contains("scanNow("))
        assertFalse("Application must not instantiate the coordinator", text.contains("GalleryBackgroundCoordinator("))
    }

    @Test
    fun `gallery component construction is inert and never scans`() {
        // GalleryComponent.scanCoordinator constructs dependencies but must not
        // invoke the media source or a run itself.
        val component = sourceFile("src/main/java/com/clawmate/app/gallery/GalleryComponent.kt")
        assertTrue(component.exists())
        val text = component.readText()
        // The wiring methods construct the pipeline; they never call scanNewest
        // or runOnce at construction time.
        assertFalse("scanCoordinator must not call runOnce", text.contains("scanNewest("))
        assertFalse("component must not auto-run", text.contains(".runOnce()"))
    }
}
