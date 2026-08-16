package com.clawmate.app.gallery.background

import com.clawmate.app.gallery.cloud.GalleryCloudAdapter
import com.clawmate.app.gallery.data.GalleryRepository
import com.clawmate.app.gallery.insights.GalleryMonthInsight
import com.clawmate.app.gallery.model.CollectionGroup
import com.clawmate.app.gallery.model.GalleryPhotoItem
import com.clawmate.app.gallery.model.GallerySearchHit
import com.clawmate.app.gallery.model.GalleryIds
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Device-free tests for the Phase 23 Gallery background scan/sync coordinator.
 *
 * The coordinator is fully host-testable: the media source, the Room repository
 * and the cloud adapter are all injected as fakes, so scheduling gate,
 * permission gate, dedup/idempotency, cancellation, bounded batches, cloud
 * no-call behavior and honest state mapping are all verified with no device and
 * no network.
 */
class GalleryBackgroundCoordinatorTest {

    private fun gate(optedIn: Boolean, granted: Boolean) =
        GallerySyncGate(optedIn = optedIn, photoAccessGranted = granted)

    private fun item(id: String, uri: String, summary: String = ""): GalleryPhotoItem =
        GalleryPhotoItem(
            id = GalleryIds.stableIdForUri(uri),
            uri = uri,
            date = "2024-06-01",
            title = "t",
            summary = summary,
            category = "",
            createdAt = 0L,
            analysisState = GalleryPhotoItem.ANALYSIS_STATE_NONE,
        )

    private class FakeMediaSource(private val inputs: List<GalleryPhotoInput>) : GalleryMediaSource {
        var requestedLimit = -1
        override suspend fun scanNewest(limit: Int): List<GalleryPhotoInput> {
            requestedLimit = limit
            return inputs.take(limit)
        }
    }

    private class FakeRepo : GalleryRepository {
        val store = LinkedHashMap<String, GalleryPhotoItem>()
        val upsertCount = java.util.concurrent.atomic.AtomicInteger(0)

        override fun observeItems(): Flow<List<GalleryPhotoItem>> =
            kotlinx.coroutines.flow.flow { emit(store.values.sortedBy { it.id }.toList()) }
        override fun observeMonthlyInsights(): Flow<List<GalleryMonthInsight>> =
            MutableStateFlow(emptyList())
        override suspend fun getItem(id: String): GalleryPhotoItem? = store[id]
        override suspend fun upsert(item: GalleryPhotoItem): GalleryPhotoItem {
            // Mirror DefaultGalleryRepository.upsert: preserve previously stored
            // non-blank analysis fields (never overwrite a real value with an
            // empty one) and keep the id/uri stable.
            val existing = store[item.id]
            val toStore = if (existing != null) {
                item.copy(
                    id = existing.id,
                    title = if (item.title.isBlank()) existing.title else item.title,
                    category = if (item.category.isBlank()) existing.category else item.category,
                    summary = if (item.summary.isBlank()) existing.summary else item.summary,
                    details = if (item.details.isEmpty()) existing.details else item.details,
                    tags = if (item.tags.isEmpty()) existing.tags else item.tags,
                    analysisState = if (item.analysisState == GalleryPhotoItem.ANALYSIS_STATE_NONE) existing.analysisState else item.analysisState,
                )
            } else item
            store[toStore.id] = toStore
            upsertCount.incrementAndGet()
            return toStore
        }
        override suspend fun importItems(items: List<GalleryPhotoItem>): List<GalleryPhotoItem> {
            val out = ArrayList<GalleryPhotoItem>()
            for (it in items.sortedBy { it.id }) out.add(upsert(it))
            return out
        }
        override suspend fun countItems(): Int = store.size
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

    /**
     * A fake ChatClient that counts completion calls (the real cloud path uses a
     * GalleryMonthlySummaryClient backed by any ChatClient, so we drive it here
     * with no network).
     */
    private class RecordingChatClient : com.clawmate.app.chat.cloud.ChatClient {
        val completions = java.util.concurrent.atomic.AtomicInteger(0)
        var replyContent: String = """{"title":"t","summary":"s","suggestion":""}"""

        override suspend fun completion(request: com.clawmate.app.chat.cloud.ChatCompletionsRequest):
            com.clawmate.app.chat.cloud.ChatCompletionResult {
            completions.incrementAndGet()
            return com.clawmate.app.chat.cloud.ChatCompletionResult(
                content = replyContent,
                finishReason = "stop",
                raw = replyContent,
            )
        }

        override fun completionStream(request: com.clawmate.app.chat.cloud.ChatCompletionsRequest):
            Flow<com.clawmate.app.chat.cloud.CloudStreamChunk> =
            kotlinx.coroutines.flow.flowOf()
    }

    /** Build a REAL GalleryCloudAdapter with an explicitly-configured summary path
     * (or not), so the coordinator's "call only the existing configured adapter"
     * behaviour is tested against the production class. */
    private fun adapter(summaryConfig: com.clawmate.app.gallery.cloud.GalleryMonthlySummaryConfig?,
                        chat: com.clawmate.app.chat.cloud.ChatClient): GalleryCloudAdapter =
        GalleryCloudAdapter(
            summaryConfig = summaryConfig,
            summaryClientFactory = { config -> com.clawmate.app.gallery.cloud.GalleryMonthlySummaryClient(chat, config) },
            embeddingConfig = null,
            embeddingClientFactory = { error("embedding unused") },
            embeddingModeUnsupported = false,
        )

    private fun readyConfig(): com.clawmate.app.gallery.cloud.GalleryMonthlySummaryConfig =
        com.clawmate.app.gallery.cloud.GalleryMonthlySummaryConfig(
            enabled = true,
            baseUrl = "https://planner.test",
            apiKey = "key",
            modelName = "model",
        )

    // ------------------------------------------------------------------ gate

    @Test
    fun gateMapping_isHonest() {
        assertEquals(GallerySyncGateStatus.NOT_CONFIGURED, gate(false, true).status)
        assertEquals(GallerySyncGateStatus.NOT_CONFIGURED, gate(false, false).status)
        assertEquals(GallerySyncGateStatus.BLOCKED, gate(true, false).status)
        assertEquals(GallerySyncGateStatus.READY, gate(true, true).status)
    }

    @Test
    fun notOptedIn_returnsNotConfigured_andTouchesNoMedia() = runTest {
        val media = FakeMediaSource(listOf(GalleryPhotoInput("content://1", "a", "2024-06-01", null, null)))
        val repo = FakeRepo()
        val coordinator = GalleryBackgroundCoordinator(
            gate = { gate(false, true) },
            mediaSource = media,
            repository = repo,
        )
        val result = coordinator.runOnce()
        assertEquals(GallerySyncState.NotConfigured, result)
        assertEquals(0, repo.store.size)
        assertEquals(-1, media.requestedLimit) // media never asked
        assertEquals(0, repo.upsertCount.get())
    }

    @Test
    fun optedInButNoPermission_returnsBlocked_andTouchesNoMedia() = runTest {
        val media = FakeMediaSource(listOf(GalleryPhotoInput("content://1", "a", "2024-06-01", null, null)))
        val repo = FakeRepo()
        val coordinator = GalleryBackgroundCoordinator(
            gate = { gate(true, false) },
            mediaSource = media,
            repository = repo,
        )
        val result = coordinator.runOnce()
        assertTrue(result is GallerySyncState.Blocked)
        assertEquals(0, repo.store.size)
        assertEquals(-1, media.requestedLimit)
    }

    // ------------------------------------------------------- success + bounds

    @Test
    fun readyRun_scansMarkedNewerThanRequestedLimit() = runTest {
        val limit = 5
        val inputs = (1..10).map { i ->
            GalleryPhotoInput("content://img/$i", "id$i", "2024-06-01", null, null)
        }
        val media = FakeMediaSource(inputs)
        val repo = FakeRepo()
        val coordinator = GalleryBackgroundCoordinator(
            gate = { gate(true, true) },
            mediaSource = media,
            repository = repo,
            scanLimit = limit,
        )
        val result = coordinator.runOnce()
        assertTrue(result is GallerySyncState.Success)
        assertEquals(limit, media.requestedLimit)
        assertEquals(limit, repo.store.size)
    }

    @Test
    fun boundedBatches_maxSizeRespected() {
        // The policy enforces the batch cap regardless of candidate count.
        assertEquals(listOf(20, 20, 20, 20, 20, 20, 20), GallerySyncPolicy.batchSizes(140))
        assertTrue(GallerySyncPolicy.batchSizes(140).all { it <= GallerySyncPolicy.MAX_ITEMS_PER_BATCH })
        assertEquals(emptyList<Int>(), GallerySyncPolicy.batchSizes(0))
        assertEquals(listOf(3), GallerySyncPolicy.batchSizes(3))
    }

    // ----------------------------------------------------- dedup / idempotent

    @Test
    fun repeatedRun_isIdempotent_noDuplicates() = runTest {
        val inputs = listOf(
            GalleryPhotoInput("content://img/1", "id1", "2024-06-01", null, null),
            GalleryPhotoInput("content://img/2", "id2", "2024-06-01", null, null),
        )
        val media = FakeMediaSource(inputs)
        val repo = FakeRepo()
        val coordinator = GalleryBackgroundCoordinator(
            gate = { gate(true, true) },
            mediaSource = media,
            repository = repo,
        )
        coordinator.runOnce()
        coordinator.runOnce()
        assertEquals(2, repo.store.size) // same URIs -> no duplicates
        // total stored rows equals the distinct uri set
        assertEquals(setOf("content://img/1", "content://img/2"), repo.store.values.map { it.uri }.toSet())
    }

    // ------------------------------------------------------------ cancellation

    @Test
    fun cancellation_returnsHonestPartial() = runTest {
        val inputs = (1..10).map { i ->
            GalleryPhotoInput("content://img/$i", "id$i", "2024-06-01", null, null)
        }
        val media = FakeMediaSource(inputs)
        val repo = FakeRepo()
        val coordinator = GalleryBackgroundCoordinator(
            gate = { gate(true, true) },
            mediaSource = media,
            repository = repo,
        )
        // Cancel before/at first batch boundary -> run returns PARTIAL, not SUCCESS.
        coordinator.cancelActive()
        val result = coordinator.runOnce()
        assertTrue("expected Partial but was ${result::class.simpleName}", result is GallerySyncState.Partial)
        val summary = (result as GallerySyncState.Partial).summary
        assertTrue(summary.skippedCount > 0)
    }

    // -------------------------------------------------- cloud no-call behavior

    @Test
    fun noCloudAdapter_neverCallsNetwork_andReportsSkipped() = runTest {
        val inputs = listOf(
            GalleryPhotoInput("content://img/1", "id1", "2024-06-01", null, null),
        )
        val media = FakeMediaSource(inputs)
        val repo = FakeRepo()
        val coordinator = GalleryBackgroundCoordinator(
            gate = { gate(true, true) },
            mediaSource = media,
            repository = repo,
            cloudAdapterProvider = { null }, // no configured adapter
        )
        val result = coordinator.runOnce()
        assertTrue(result is GallerySyncState.Success)
        assertEquals(
            GalleryCloudSyncStatus.SKIPPED_NOT_CONFIGURED,
            (result as GallerySyncState.Success).summary.cloudSync,
        )
    }

    @Test
    fun unconfiguredCloudAdapter_neverCallsNetwork() = runTest {
        val inputs = listOf(
            GalleryPhotoInput("content://img/1", "id1", "2024-06-01", null, null),
        )
        val media = FakeMediaSource(inputs)
        val repo = FakeRepo()
        val chat = RecordingChatClient()
        val cloud = adapter(summaryConfig = null, chat = chat) // adapter exists but not configured
        val coordinator = GalleryBackgroundCoordinator(
            gate = { gate(true, true) },
            mediaSource = media,
            repository = repo,
            cloudAdapterProvider = { cloud },
        )
        val result = coordinator.runOnce()
        assertTrue(result is GallerySyncState.Success)
        assertEquals(0, chat.completions.get())
        assertEquals(GalleryCloudSyncStatus.SKIPPED_NOT_CONFIGURED, (result as GallerySyncState.Success).summary.cloudSync)
    }

    @Test
    fun configuredAdapter_butNothingMeaningfulStored_neverCallsNetwork() = runTest {
        // The coordinator stores bare scan cursors (no analysis fields), so a
        // freshly-scanned library has nothing meaningful to sync. Even with a
        // READY adapter present, the coordinator must NOT send a blank/invented
        // payload -> SKIPPED, and the adapter is never invoked.
        val inputs = listOf(
            GalleryPhotoInput("content://img/1", "id1", "2024-06-01", null, null),
        )
        val media = FakeMediaSource(inputs)
        val repo = FakeRepo()
        val chat = RecordingChatClient()
        val cloud = adapter(summaryConfig = readyConfig(), chat = chat)
        val coordinator = GalleryBackgroundCoordinator(
            gate = { gate(true, true) },
            mediaSource = media,
            repository = repo,
            cloudAdapterProvider = { cloud },
        )
        val result = coordinator.runOnce()
        assertTrue(result is GallerySyncState.Success)
        assertEquals(0, chat.completions.get())
        assertEquals(GalleryCloudSyncStatus.SKIPPED_NOT_CONFIGURED, (result as GallerySyncState.Success).summary.cloudSync)
    }

    @Test
    fun configuredAdapter_withRealContent_runsOnce() = runTest {
        // Build a repo pre-populated with summarized items, then scan via a
        // source that returns inputs whose ids match those items so the
        // coordinator re-upserts them (preserving summary) and the aggregate is
        // non-blank => the explicitly configured adapter's summary path runs.
        val uri = "content://img/1"
        val id = GalleryIds.stableIdForUri(uri)
        val seedRepo = FakeRepo()
        seedRepo.upsert(item(id, uri, summary = "一张早餐的照片"))
        val media = FakeMediaSource(listOf(GalleryPhotoInput(uri, id, "2024-06-01", null, null)))
        val repo = seedRepo
        val chat = RecordingChatClient()
        val cloud = adapter(summaryConfig = readyConfig(), chat = chat)
        val coordinator = GalleryBackgroundCoordinator(
            gate = { gate(true, true) },
            mediaSource = media,
            repository = repo,
            cloudAdapterProvider = { cloud },
        )
        val result = coordinator.runOnce()
        assertTrue(result is GallerySyncState.Success)
        assertEquals(1, chat.completions.get())
        assertEquals(GalleryCloudSyncStatus.RUN, (result as GallerySyncState.Success).summary.cloudSync)
    }

    @Test
    fun configuredAdapter_failingCloudMakesPartial() = runTest {
        // A configured adapter whose summary call throws must surface PARTIAL
        // (the local scan succeeded but the cloud memory-sync step failed), and
        // never a fabricated SUCCESS.
        val uri = "content://img/1"
        val id = GalleryIds.stableIdForUri(uri)
        val seedRepo = FakeRepo()
        seedRepo.upsert(item(id, uri, summary = "有内容的图片"))
        val media = FakeMediaSource(listOf(GalleryPhotoInput(uri, id, "2024-06-01", null, null)))
        val repo = seedRepo
        val chat = object : com.clawmate.app.chat.cloud.ChatClient {
            override suspend fun completion(request: com.clawmate.app.chat.cloud.ChatCompletionsRequest):
                com.clawmate.app.chat.cloud.ChatCompletionResult {
                throw com.clawmate.app.chat.cloud.ChatException(
                    com.clawmate.app.chat.cloud.ChatErrorKind.IO,
                    "network down",
                )
            }

            override fun completionStream(request: com.clawmate.app.chat.cloud.ChatCompletionsRequest):
                Flow<com.clawmate.app.chat.cloud.CloudStreamChunk> =
                kotlinx.coroutines.flow.flowOf()
        }
        val cloud = adapter(summaryConfig = readyConfig(), chat = chat)
        val coordinator = GalleryBackgroundCoordinator(
            gate = { gate(true, true) },
            mediaSource = media,
            repository = repo,
            cloudAdapterProvider = { cloud },
        )
        val result = coordinator.runOnce()
        assertTrue("expected Partial but was ${result::class.simpleName}", result is GallerySyncState.Partial)
        assertEquals(GalleryCloudSyncStatus.FAILED, (result as GallerySyncState.Partial).summary.cloudSync)
    }

    @Test
    fun concurrentRun_refusesSecondWhileInFlight() {
        // A real media source that blocks until released, on real threads, so a
        // concurrent second `runOnce` is guaranteed to observe the first still in
        // flight and refuse to scan (single-flight mutex). Uses Dispatchers.Default
        // so the blocking source and the second call run on different threads.
        val releaseLatch = java.util.concurrent.CountDownLatch(1)
        val inFlight = java.util.concurrent.atomic.AtomicBoolean(false)
        val media = object : GalleryMediaSource {
            override suspend fun scanNewest(limit: Int): List<GalleryPhotoInput> {
                inFlight.set(true)
                releaseLatch.await(5, java.util.concurrent.TimeUnit.SECONDS)
                inFlight.set(false)
                return (1..3).map { i -> GalleryPhotoInput("content://img/$i", "id$i", "2024-06-01", null, null) }
            }
        }
        val repo = FakeRepo()
        val coordinator = GalleryBackgroundCoordinator(
            gate = { gate(true, true) },
            mediaSource = media,
            repository = repo,
        )
        var secondResult: GallerySyncState? = null
        val firstRun = kotlinx.coroutines.GlobalScope.async(kotlinx.coroutines.Dispatchers.Default) {
            coordinator.runOnce()
        }        // Wait until the first run has entered the blocking media scan.
        while (!inFlight.get()) Thread.yield()
        secondResult = kotlinx.coroutines.runBlocking { coordinator.runOnce() } // tryLock fails -> refused
        assertTrue(
            "second concurrent run should be refused (not Scanning) but was ${secondResult::class.simpleName}",
            secondResult is GallerySyncState.Partial,
        )
        releaseLatch.countDown()
        val firstResult = kotlinx.coroutines.runBlocking { firstRun.await() }
        assertTrue(firstResult is GallerySyncState.Success)
        assertEquals(3, repo.store.size) // only one scan folded items
    }
}
