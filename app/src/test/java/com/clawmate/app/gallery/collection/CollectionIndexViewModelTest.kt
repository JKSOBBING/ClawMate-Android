package com.clawmate.app.gallery.collection

import com.clawmate.app.datamanager.LocalConsolidationTypes.IndexedEvent
import com.clawmate.app.gallery.ui.CollectionIndexViewModel
import com.clawmate.app.gallery.ui.IndexedCollectionUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * JVM tests for CollectionIndexViewModel over a fake (non-Room, non-network)
 * reader. Confirms:
 *  - IndexedEventUiState.Loaded grouping from real events;
 *  - empty state on no events;
 *  - detail resolves only real group members;
 *  - open / close (back navigation) transitions;
 *  - no model / network call is ever made (the fake reader is the only seam).
 */
class CollectionIndexViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `idle until refresh then loads deterministic groups`() = runWith(readerFor(sampleEvents())) {
        val vm = it.vm
        assertTrue(vm.uiState.value is IndexedCollectionUiState.Idle)
        vm.refreshIndex()
        dispatcher.scheduler.advanceUntilIdle()
        val loaded = vm.uiState.value as IndexedCollectionUiState.Loaded
        assertEquals("local", loaded.generation)
        assertTrue(loaded.groups.isNotEmpty())
        assertTrue(loaded.groups.all { g -> g.local })
    }

    @Test
    fun `no events yields an empty load with zero groups`() = runWith(readerFor(emptyList())) {
        it.vm.refreshIndex()
        dispatcher.scheduler.advanceUntilIdle()
        val loaded = it.vm.uiState.value as IndexedCollectionUiState.Loaded
        assertEquals(0, loaded.indexedEventCount)
        assertEquals(0, loaded.groups.size)
    }

    @Test
    fun `selecting a scene re-projects the same loaded events`() = runWith(readerFor(sampleEvents())) {
        it.vm.refreshIndex()
        dispatcher.scheduler.advanceUntilIdle()
        it.vm.selectTab(3)
        dispatcher.scheduler.advanceUntilIdle()
        val loaded = it.vm.uiState.value as IndexedCollectionUiState.Loaded
        assertEquals(3, loaded.tab.sceneId)
    }

    @Test
    fun `open group resolves only real member events and close restores list`() = runWith(readerFor(sampleEvents())) {
        it.vm.refreshIndex()
        dispatcher.scheduler.advanceUntilIdle()
        val loaded = it.vm.uiState.value as IndexedCollectionUiState.Loaded
        val group = loaded.groups.first()

        it.vm.openGroup(group.groupId)
        val detail = it.vm.detail.value
        assertNotNull(detail)
        assertEquals(group.eventIds.sorted(), detail!!.events.map { e -> e.eventId }.sorted())
        // Only real events appear; every id belongs to the group.
        assertTrue(detail.events.all { e -> e.eventId in group.eventIds })

        it.vm.closeDetail()
        assertNull(it.vm.detail.value)
    }

    @Test
    fun `unknown group id is ignored (no fabricated detail)`() = runWith(readerFor(sampleEvents())) {
        it.vm.refreshIndex()
        dispatcher.scheduler.advanceUntilIdle()
        it.vm.openGroup("idx_nonexistent_00000000")
        assertNull(it.vm.detail.value)
    }

    @Test
    fun `reader failure surfaces an honest error state`() = runWith(failingReader()) {
        it.vm.refreshIndex()
        dispatcher.scheduler.advanceUntilIdle()
        val err = it.vm.uiState.value
        assertTrue(err is IndexedCollectionUiState.Error)
        assertTrue((err as IndexedCollectionUiState.Error).message.contains("索引读取失败"))
    }

    private fun sampleEvents(): List<IndexedEvent> = listOf(
        IndexedEvent(
            eventId = "e0", eventDate = "2026-06-01", summary = "在购物网站买了一件外套",
            workflowSceneIds = listOf(0), sources = listOf("购物"),
        ),
        IndexedEvent(
            eventId = "a", eventDate = "2026-06-01", summary = "与老张沟通周五会议",
            workflowSceneIds = listOf(3), sources = listOf("DailyLog"),
        ),
        IndexedEvent(
            eventId = "b", eventDate = "2026-06-02", summary = "与老张沟通项目排期",
            workflowSceneIds = listOf(3), sources = listOf("DailyLog"),
        ),
        IndexedEvent(
            eventId = "c", eventDate = "2026-06-03", summary = "看了电影",
            workflowSceneIds = listOf(2), sources = listOf("娱乐"),
        ),
    )

    private fun readerFor(events: List<IndexedEvent>) =
        IndexedEventReader { _ -> events }

    private fun failingReader() = IndexedEventReader { _ ->
        throw RuntimeException("索引读取失败 mocked")
    }

    /** Run a block with a fresh VM whose reader is [reader]; see [Fixture]. */
    private fun runWith(reader: IndexedEventReader, block: (Fixture) -> Unit) {
        val vm = CollectionIndexViewModel(reader)
        block(Fixture(vm))
    }

    private class Fixture(val vm: CollectionIndexViewModel)
}
