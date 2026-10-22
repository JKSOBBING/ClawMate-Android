package com.clawmate.app.datamanager

import com.clawmate.app.datamanager.LocalConsolidationTypes.EntityMention
import com.clawmate.app.datamanager.LocalConsolidationTypes.EventPartition
import com.clawmate.app.datamanager.LocalConsolidationTypes.EventPartitionIndex
import com.clawmate.app.datamanager.LocalConsolidationTypes.IndexedEvent
import com.clawmate.app.datamanager.LocalConsolidationTypes.QueryOptions
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class QueryEventsIndexTest {
    @get:Rule val folder = TemporaryFolder()
    private val base get() = folder.root

    private fun write(events: List<IndexedEvent>) {
        DataManagerPaths.eventsDir(base).mkdirs()
        DataManagerPaths.eventPartition(base, "2026-10").writeText(
            LocalConsolidationCodec.encodePartition(EventPartition(month = "2026-10", events = events)))
        DataManagerPaths.partitionIndex(base).writeText(LocalConsolidationCodec.encodePartitionIndex(
            EventPartitionIndex(partitionFiles = listOf("2026-10.json"))))
    }

    @Test fun `missing corrupt and explicitly empty indexes are distinct without writes`() {
        val index = DailyLogLocalIndex(base)
        assertFalse(index.hasBuiltIndex())
        assertEquals(0, index.query(QueryOptions()).totalEvents)
        assertFalse(DataManagerPaths.eventsDir(base).exists())
        write(listOf(IndexedEvent(eventId = "stale", eventDate = "2026-10-01", summary = "stale")))
        assertTrue(index.hasBuiltIndex())
        DataManagerPaths.partitionIndex(base).writeText(LocalConsolidationCodec.encodePartitionIndex(EventPartitionIndex()))
        assertTrue(index.hasBuiltIndex())
        assertTrue(index.query(QueryOptions()).events.isEmpty()) // Old partition is not resurrected.
        DataManagerPaths.partitionIndex(base).writeText("broken")
        DataManagerPaths.eventPartition(base, "2026-10").writeText("broken")
        assertFalse(index.hasBuiltIndex())
    }

    @Test fun `keywords entities domains and inclusive dates combine correctly`() {
        write(listOf(
            IndexedEvent(eventId = "a", eventDate = "2026-10-01", summary = "咖啡 会议", domains = listOf("work"),
                entityMentions = listOf(EntityMention("person", "Alice", listOf("小艾")))),
            IndexedEvent(eventId = "b", eventDate = "2026-10-02", summary = "咖啡 订单", domains = listOf("eat")),
            IndexedEvent(eventId = "c", eventDate = "2026-10-03", summary = "会议", domains = listOf("work")),
        ))
        val index = DailyLogLocalIndex(base)
        assertEquals(listOf("a"), index.query(QueryOptions(keywords = listOf("咖啡", "会议"))).events.map { it.eventId })
        assertEquals(3, index.query(QueryOptions(keywords = listOf("咖啡", "会议"), keywordOperator = "or")).totalEvents)
        assertEquals(listOf("a"), index.query(QueryOptions(domains = listOf("work"), entities = listOf("person:小艾"))).events.map { it.eventId })
        assertEquals(1, index.query(QueryOptions(entities = listOf("person:ALICE", "merchant:no match"))).totalEvents)
        assertEquals(listOf("b", "a"), index.query(QueryOptions(startDate = "2026-10-01", endDate = "2026-10-02")).events.map { it.eventId })
        assertEquals(0, index.query(QueryOptions(domains = listOf("eat"), keyword = "会议")).totalEvents)
    }

    @Test fun `deadline filtering includes older plans and sorts by deadline`() {
        write(listOf(
            IndexedEvent(eventId = "plan", eventDate = "2026-09-01", summary = "买票", todoDeadline = "2026-10-20"),
            IndexedEvent(eventId = "current", eventDate = "2026-10-10", summary = "会议"),
            IndexedEvent(eventId = "old", eventDate = "2026-09-02", summary = "past", todoDeadline = "2026-09-20"),
        ))
        val index = DailyLogLocalIndex(base)
        assertEquals(1, index.query(QueryOptions(month = "2026-10")).totalEvents)
        val found = index.query(QueryOptions(month = "2026-10", includeTodoDeadlineInTimeRange = true, limit = 1))
        assertEquals(2, found.totalEvents)
        assertTrue(found.eventsTruncated)
        assertEquals("plan", found.events.single().eventId)
        assertEquals("2026-09-01", found.events.single().eventDate)
        assertEquals(1, index.query(QueryOptions(date = "2026-10-20", includeTodoDeadlineInTimeRange = true)).totalEvents)
    }

    @Test fun `persisted enrichment participates in filtering before selection`() {
        write(listOf(IndexedEvent(eventId = "real-id", eventDate = "2026-10-01", summary = "记录")))
        val file = DataManagerPaths.eventEnrichment(base)
        file.parentFile!!.mkdirs()
        file.writeText(BatchEventEnrichmentCodec.encodeArtifact(BatchEventEnrichmentArtifact(
            schema = BatchEventEnrichmentArtifact.SCHEMA,
            fields = mapOf("real-id" to PersistedEnrichedFields(eventId = "real-id", keywords = listOf("咖啡"),
                domains = listOf("eat"), entities = listOf(EntityMention("merchant", "店铺")), enrichmentMethod = "MODEL")),
        )))
        val result = DailyLogLocalIndex(base).query(QueryOptions(keywords = listOf("咖啡"), domains = listOf("eat"), entities = listOf("merchant:店铺")))
        assertEquals("real-id", result.events.single().eventId)
    }

    @Test fun `partition manifest cannot read files outside its event directory`() {
        write(emptyList())
        base.resolve("sentinel.json").writeText(LocalConsolidationCodec.encodePartition(EventPartition(events = listOf(
            IndexedEvent(eventId = "private", summary = "must not be read")))))
        DataManagerPaths.partitionIndex(base).writeText(LocalConsolidationCodec.encodePartitionIndex(EventPartitionIndex(
            partitionFiles = listOf("../../sentinel.json", "C:\\private.json", "/tmp/private.json"))))
        val index = DailyLogLocalIndex(base)
        assertFalse(index.hasBuiltIndex())
        assertTrue(index.query(QueryOptions()).events.isEmpty())
    }
}
