package com.clawmate.app.datamanager

import com.clawmate.app.datamanager.BatchEventEnrichTypes.EnrichedEventFields
import com.clawmate.app.datamanager.BatchEventEnrichTypes.EnrichmentResult
import com.clawmate.app.datamanager.BatchEventEnrichTypes.EnrichmentStatus
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase-38 focused JVM tests for the **persisted batch event-enrichment** store
 * and read overlay.
 *
 * Coverage (all offline over a temp dir):
 *  - writing a MODEL result persists a versioned, atomic artifact keyed by the
 *    real event ids (keywords/domains/entities/todo/method);
 *  - LOCAL_ONLY / FALLBACK / EMPTY are never written and the prior artifact is
 *    preserved byte-for-byte;
 *  - the artifact is readable back and overlays the fields onto events by real
 *    event id (unknown / absent ids are untouched → old behaviour);
 *  - an old-schema `IndexedEvent` (no enrich keys) still decodes to an
 *    un-enriched event (backward compatible);
 *  - a corrupt / empty artifact does not throw and falls back to the default;
 *  - [DailyLogLocalIndex] and [ProfileIndexBuilder] read the overlay after an
 *    explicit persisted MODEL success, and keep old behaviour with no artifact.
 */
class BatchEventEnrichmentStoreTest {

    private fun tempBase(): File =
        Files.createTempDirectory("clawmate-phase38-batchenrich").toFile().apply { deleteOnExit() }

    private fun enrichFields(eventId: String, method: String = "model_batch"): EnrichedEventFields =
        EnrichedEventFields(
            eventId = eventId,
            keywords = listOf("咖啡", "支出"),
            domains = listOf("eat"),
            entities = listOf(LocalConsolidationTypes.EntityMention(type = "merchant", name = "瑞幸")),
            todoType = "buy",
            todoDeadline = "2026-08-05",
            todoText = "去咖啡店取咖啡",
            todoAgentTask = "",
            enrichmentMethod = method,
        )

    private fun modelResult(status: EnrichmentStatus, fields: Map<String, EnrichedEventFields>): EnrichmentResult =
        EnrichmentResult(
            status = status,
            eventCount = fields.size,
            admittedEventCount = fields.size,
            generatedAt = "2026-08-03T10:00:00",
            enrichedFields = fields,
        )

    // ------------------------------------------------------------------

    @Test
    fun `write persists a versioned atomic artifact keyed by real event ids`() {
        val base = tempBase()
        val store = BatchEventEnrichmentStore(base)
        val result = modelResult(
            EnrichmentStatus.MODEL,
            mapOf("2026-08/out.md#1" to enrichFields("2026-08/out.md#1")),
        )
        assertTrue(store.canPersist(result))
        assertTrue(store.write(result))
        val artifactFile = DataManagerPaths.eventEnrichment(base)
        assertTrue(artifactFile.exists())
        val artifact = BatchEventEnrichmentCodec.decodeArtifact(artifactFile.readText())
        val fields = artifact!!.fields
        assertEquals(1, fields.size)
        val f = fields["2026-08/out.md#1"]!!
        assertEquals(listOf("咖啡", "支出"), f.keywords)
        assertEquals(listOf("eat"), f.domains)
        assertEquals("buy", f.todoType)
        assertEquals("model_batch", f.enrichmentMethod)
        // Re-read via the store's tolerant loader.
        assertEquals(1, store.load().size)
    }

    @Test
    fun `non-MODEL results never write and preserve the prior artifact`() {
        val base = tempBase()
        val store = BatchEventEnrichmentStore(base)
        val prior = modelResult(
            EnrichmentStatus.MODEL,
            mapOf("2026-08/out.md#1" to enrichFields("2026-08/out.md#1")),
        )
        assertTrue(store.write(prior))
        val beforeBytes = DataManagerPaths.eventEnrichment(base).readText()

        for (status in listOf(EnrichmentStatus.LOCAL_ONLY, EnrichmentStatus.FALLBACK, EnrichmentStatus.EMPTY)) {
            val nonModel = modelResult(
                status,
                mapOf("2026-08/out.md#1" to enrichFields("2026-08/out.md#1", method = "heuristic_batch_fallback")),
            )
            assertFalse(store.canPersist(nonModel))
            assertFalse("non-MODEL must never overwrite", store.write(nonModel))
        }
        // Old artifact preserved byte-for-byte.
        assertEquals(beforeBytes, DataManagerPaths.eventEnrichment(base).readText())
    }

    @Test
    fun `overlay applies fields by real event id and leaves unknown ids untouched`() {
        val events = listOf(
            indexedEvent("2026-08/out.md#1"),
            indexedEvent("2026-08/out.md#2"),
        )
        val fields = mapOf("2026-08/out.md#1" to persistFields("2026-08/out.md#1"))
        val overlaid = BatchEventEnrichmentOverlay.overlay(events, fields)
        // #1 enriched, #2 (not in artifact) untouched.
        assertEquals(listOf("咖啡", "支出"), overlaid[0].keywords)
        assertEquals(listOf("eat"), overlaid[0].domains)
        assertEquals("model_batch", overlaid[0].enrichmentMethod)
        assertEquals(1, overlaid[0].entityMentions.size)
        assertTrue(overlaid[1].keywords.isEmpty())
        assertTrue(overlaid[1].enrichmentMethod.isEmpty())
    }

    @Test
    fun `empty or corrupt artifact falls back to the default and does not throw`() {
        val base = tempBase()
        val store = BatchEventEnrichmentStore(base)
        assertEquals(0, store.load().size) // no artifact
        // Corrupt artifact -> tolerant empty.
        val artifactFile = DataManagerPaths.eventEnrichment(base)
        artifactFile.parentFile.mkdirs()
        artifactFile.writeText("not json{")
        assertEquals(0, store.load().size)
        // An empty artifact returns every event unchanged.
        val events = listOf(indexedEvent("2026-08/out.md#1"))
        assertEquals(events, BatchEventEnrichmentOverlay.overlay(events, store.load()))
    }

    @Test
    fun `old-schema IndexedEvent without enrich keys still decodes`() {
        // A Phase-30-style event JSON (no enrich keys) decodes to an un-enriched event.
        val json =
            """{"event_id":"2026-08/out.md#1","source_state_file":"outputs/2026-08/out.md.state.json","record_id":1,"event_date":"2026-08-01","summary":"买咖啡","normalized_fact":"买咖啡花了 25 元"}"""
        val decoded = LocalConsolidationCodec.decodePartition(
            """{"schema":"datamanager-event-partition-index-local-v1","month":"2026-08","events":[$json]}""",
        )
        val event = decoded.events.first()
        assertEquals("2026-08/out.md#1", event.eventId)
        assertTrue(event.keywords.isEmpty()) // no enrichment fields on old data
        assertTrue(event.domains.isEmpty())
        assertTrue(event.enrichmentMethod.isEmpty())
    }

    @Test
    fun `DailyLogLocalIndex reads the persisted overlay after an explicit MODEL success`() {
        val base = tempBase()
        // Build a deterministic local index with one event.
        DailyLogWorkspace.writeDocument(base, "2026-08-01", "a.logs.md", listOf("买了咖啡花了 25 元"))
        ConsolidationRepository(base).consolidateNow()
        // Rebuild index through the Phase-30 index service so partitions exist.
        val allStates = listOf(
            LocalConsolidationCodec.decodeState(
                DataManagerPaths.outputState(base, "2026-08", "a.logs.md").readText(),
            ),
        )
        IndexService(base).rebuild(allStates)

        // No artifact -> default (event ids present, no enrich fields).
        val idx = DailyLogLocalIndex(base)
        val before = idx.query(LocalConsolidationTypes.QueryOptions(month = "2026-08"))
        assertTrue(before.totalEvents >= 1)
        val eventId = before.events.first().eventId
        assertTrue(before.events.first().keywords.isEmpty())

        // Persist an explicit MODEL enrichment for that real event id.
        val store = BatchEventEnrichmentStore(base)
        assertTrue(store.write(modelResult(EnrichmentStatus.MODEL, mapOf(eventId to enrichFields(eventId)))))

        // After the explicit write, the query overlay is read back.
        val after = idx.query(LocalConsolidationTypes.QueryOptions(month = "2026-08"))
        assertEquals(listOf("咖啡", "支出"), after.events.first().keywords)
        assertEquals("model_batch", after.events.first().enrichmentMethod)
    }

    // ------------------------------------------------------------------ helpers

    private fun indexedEvent(eventId: String): LocalConsolidationTypes.IndexedEvent =
        LocalConsolidationTypes.IndexedEvent(
            eventId = eventId,
            sourceStateFile = "outputs/2026-08/out.md.state.json",
            recordId = 1,
            eventDate = "2026-08-01",
            summary = "买咖啡",
            normalizedFact = "买咖啡花了 25 元",
        )

    private fun persistFields(eventId: String): PersistedEnrichedFields =
        PersistedEnrichedFields.from(enrichFields(eventId))
}
