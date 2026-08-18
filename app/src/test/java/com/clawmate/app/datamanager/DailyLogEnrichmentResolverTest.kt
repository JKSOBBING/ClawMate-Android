package com.clawmate.app.datamanager

import com.clawmate.app.datamanager.BatchEventEnrichTypes.EnrichedEventFields
import com.clawmate.app.datamanager.BatchEventEnrichTypes.EnrichmentResult
import com.clawmate.app.datamanager.BatchEventEnrichTypes.EnrichmentStatus
import com.clawmate.app.datamanager.LocalConsolidationTypes.IndexedEvent
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase-39 focused JVM tests for [DailyLogEnrichmentResolver] — the read-side
 * bridge that lets the Home planner consume the Phase-38 persisted batch
 * event-enrichment fields for real daily-log entries.
 *
 * Coverage (all offline over a temp dir):
 *  - a daily-log entry whose `sourceRef` matches a real indexed event
 *    (sourceRefs) carrying persisted enrich fields resolves to the enriched
 *    keywords / domains / todo_type / todo_deadline / todo_text;
 *  - with no artifact or with no matching event the map is empty (old
 *    behaviour, and a model-output unknown id can never reach Home);
 *  - an event that exists but has only blank enrich fields yields nothing.
 */
class DailyLogEnrichmentResolverTest {

    private fun tempBase(): File =
        Files.createTempDirectory("clawmate-phase39-enrichresolver").toFile().apply { deleteOnExit() }

    private fun dailyEntry(index: Int, ref: String? = null): DailyLogEntry = DailyLogEntry(
        sourceDate = "2026-08-01",
        fileName = "a.logs.md",
        entryIndex = index,
        text = "买了咖啡花了 25 元",
        workflowMetadata = emptyMap(),
        latestEntryIndex = index,
        sourceRef = ref ?: DailyLogParser.sourceRef("2026-08-01", "a.logs.md", index),
    )

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

    private fun indexedEvent(eventId: String, sourceRefs: List<String>): IndexedEvent = IndexedEvent(
        eventId = eventId,
        sourceStateFile = "outputs/2026-08/a.logs.md.state.json",
        recordId = 1,
        eventDate = "2026-08-01",
        summary = "买咖啡",
        normalizedFact = "买咖啡花了 25 元",
        sourceRefs = sourceRefs,
    )

    // ------------------------------------------------------------------

    @Test
    fun `matching entry resolves to the enriched keywords domains and todo fields`() {
        val entries = listOf(dailyEntry(1))
        val sourceRef = entries.first().sourceRef
        // A real consolidated indexed event lists this sourceRef and, after the
        // existing overlay reads the artifact, carries the enriched fields.
        val overlaidEvent = indexedEvent(
            eventId = "outputs/2026-08/a.logs.md.state.json#1",
            sourceRefs = listOf(sourceRef),
        ).copy(
            keywords = listOf("咖啡", "支出"),
            domains = listOf("eat"),
            todoType = "buy",
            todoDeadline = "2026-08-05",
            todoText = "去咖啡店取咖啡",
        )

        val enrichment = DailyLogEnrichmentResolver(File("/nonexistent"))
            .resolveBySourceRef(entries, listOf(overlaidEvent))

        val got = enrichment[sourceRef]!!
        assertEquals(listOf("咖啡", "支出"), got.keywords)
        assertEquals(listOf("eat"), got.domains)
        assertEquals("buy", got.todoType)
        assertEquals("2026-08-05", got.todoDeadline)
        assertEquals("去咖啡店取咖啡", got.todoText)
        assertTrue(!got.isEmpty)
    }

    @Test
    fun `no matching event or unknown source ref yields an empty map`() {
        val entries = listOf(dailyEntry(1))
        val unknown = dailyEntry(9)
        // Event references a different source ref -> no match.
        val eventForDifferentRef = indexedEvent(
            eventId = "outputs/2026-08/a.logs.md.state.json#1",
            sourceRefs = listOf("2026-07-31/other.md#3"),
        ).copy(
            keywords = listOf("咖啡"),
            domains = listOf("eat"),
            todoType = "buy",
        )
        val resolver = DailyLogEnrichmentResolver(File("/nonexistent"))

        assertTrue(resolver.resolveBySourceRef(entries, emptyList()).isEmpty())
        // Events exist but none list the entry's sourceRef.
        assertTrue(resolver.resolveBySourceRef(entries, listOf(eventForDifferentRef)).isEmpty())
        assertTrue(resolver.resolveBySourceRef(listOf(unknown), emptyList()).isEmpty())
        // Empty entries -> empty map.
        assertTrue(resolver.resolveBySourceRef(emptyList(), listOf(eventForDifferentRef)).isEmpty())
    }

    @Test
    fun `matching event with only blank enrich fields yields nothing (old behaviour)`() {
        val entries = listOf(dailyEntry(1))
        val sourceRef = entries.first().sourceRef
        // The event references the sourceRef but carries no non-blank enrich fields.
        val blankEvent = indexedEvent(eventId = "s#1", sourceRefs = listOf(sourceRef))
        val enrichment = DailyLogEnrichmentResolver(File("/nonexistent"))
            .resolveBySourceRef(entries, listOf(blankEvent))
        assertTrue(enrichment.isEmpty())
    }

    @Test
    fun `resolver folds the persisted artifact through the existing read path`() {
        val base = tempBase()
        // Build a real deterministic index (same path as Phase-38 store test).
        DailyLogWorkspace.writeDocument(base, "2026-08-01", "a.logs.md", listOf("买了咖啡花了 25 元"))
        ConsolidationRepository(base).consolidateNow()
        val allStates = listOf(
            LocalConsolidationCodec.decodeState(
                DataManagerPaths.outputState(base, "2026-08", "a.logs.md").readText(),
            ),
        )
        IndexService(base).rebuild(allStates)

        // The indexed event id is the partition key; resolve the sourceRef for the
        // entry that maps to it.
        val idx = DailyLogLocalIndex(base).query(LocalConsolidationTypes.QueryOptions(month = "2026-08"))
        assertTrue("index must contain the consolidated event", idx.totalEvents >= 1)
        val eventId = idx.events.first().eventId

        // No artifact -> resolver returns nothing (old behaviour even though the
        // index exists).
        val entry = dailyEntry(1)
        val resolver = DailyLogEnrichmentResolver(base)
        assertTrue(resolver.resolveBySourceRef(listOf(entry)).isEmpty())

        // Persist an explicit MODEL enrichment for that real event id.
        val store = BatchEventEnrichmentStore(base)
        assertTrue(store.write(modelResult(EnrichmentStatus.MODEL, mapOf(eventId to enrichFields(eventId)))))

        // After the explicit write the resolver reads the enriched fields through
        // the existing ProfileIndexBuilder.loadEvents overlay, keyed by the entry's
        // real sourceRef.
        val enriched = resolver.resolveBySourceRef(listOf(entry))[entry.sourceRef]!!
        assertEquals(listOf("咖啡", "支出"), enriched.keywords)
        assertEquals(listOf("eat"), enriched.domains)
        assertEquals("buy", enriched.todoType)
    }
}
