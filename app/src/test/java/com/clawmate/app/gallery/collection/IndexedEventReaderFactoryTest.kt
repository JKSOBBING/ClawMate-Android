package com.clawmate.app.gallery.collection

import com.clawmate.app.datamanager.DataManagerPaths
import com.clawmate.app.datamanager.LocalConsolidationCodec
import com.clawmate.app.datamanager.LocalConsolidationIndexRunner
import com.clawmate.app.datamanager.LocalConsolidationTypes.IndexedEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Confirms the real production reader ([IndexedEventReaderFactory.appPrivate])
 * reads genuinely indexed daily-log events from a temp base dir — the actual
 * call chain is: daily-log → consolidate → index ([IndexService]) →
 * [com.clawmate.app.datamanager.DailyLogLocalIndex.query] → grouping. Pure JVM,
 * no Room / network / model.
 */
class IndexedEventReaderFactoryTest {

    private fun tempBase(): File =
        Files.createTempDirectory("clawmate-phase44").toFile().apply { deleteOnExit() }

    /** Write a chat-scene daily-log document with a proper workflow metadata header. */
    private fun writeChatLog(base: File, day: String, entries: List<String>) {
        val dayDir = File(DataManagerPaths.dailyLogRoot(base), day)
        dayDir.mkdirs()
        val header =
            "<!-- DAILY_LOG_METADATA\n{\"workflow_metadata\":{\"task_scene_id\":3,\"app\":\"wechat\"},\"latest_entry_index\":${entries.size}}\n-->\n"
        val body = entries.joinToString("\n\n") { it } + "\n\n"
        File(dayDir, "chat.logs.md").writeText(header + body)
    }

    @Test
    fun `production reader returns real indexed events and groups them`() {
        val base = tempBase()
        // Seed real daily-log documents with a proper chat workflow metadata
        // header (task_scene_id=3), then run the consolidate + index pipeline so
        // events genuinely carry scene 3.
        writeChatLog(base, "2026-08-01", listOf("1. 与老张沟通周五会议", "2. 与老张沟通项目排期"))
        LocalConsolidationIndexRunner(base).run()

        val reader = IndexedEventReaderFactory.appPrivate(base)
        val events = reader.read(3)
        assertTrue("real indexed chat events must be present", events.isNotEmpty())
        assertTrue(events.all { it.workflowSceneIds.contains(3) })

        val groups = IndexedEventCollectionService.group(events, 3)
        assertTrue("chat events should group deterministically", groups.isNotEmpty())
        assertTrue(groups.all { it.local })
    }

    @Test
    fun `no index means the reader honestly returns zero events`() {
        val base = tempBase()
        val reader = IndexedEventReaderFactory.appPrivate(base)
        assertEquals(0, reader.read(0).size)
        assertEquals(0, IndexedEventCollectionService.group(reader.read(0), 0).size)
    }

    @Test
    fun `reader reads bounded partitions and tolerates corrupt ones`() {
        val base = tempBase()
        // Seed a valid partition via the real codec.
        val eventsDir = DataManagerPaths.eventsDir(base)
        eventsDir.mkdirs()
        File(eventsDir, "2026-06.json").writeText(
            LocalConsolidationCodec.encodePartition(
                com.clawmate.app.datamanager.LocalConsolidationTypes.EventPartition(
                    month = "2026-06",
                    events = listOf(
                        IndexedEvent(
                            eventId = "m#1", eventDate = "2026-06-01", summary = "点了咖啡外卖",
                            workflowSceneIds = listOf(1), sources = listOf("外卖"),
                        ),
                    ),
                ),
            ),
        )
        // A corrupt partition must be tolerated (recovered, not thrown).
        File(eventsDir, "2026-07.json").writeText("{ corrupt")

        val reader = IndexedEventReaderFactory.appPrivate(base)
        val takeout = reader.read(1)
        assertTrue("takeout event should be read", takeout.any { it.eventId == "m#1" })
        val groups = IndexedEventCollectionService.group(takeout, 1)
        assertTrue(groups.isNotEmpty())
    }
}
