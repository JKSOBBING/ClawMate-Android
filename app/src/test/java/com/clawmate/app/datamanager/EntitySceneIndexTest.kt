package com.clawmate.app.datamanager

import com.clawmate.app.datamanager.EntitySceneIndexTypes.EntityDocument
import com.clawmate.app.datamanager.EntitySceneIndexTypes.WorkflowSceneDocument
import com.clawmate.app.datamanager.LocalConsolidationTypes.EntityMention
import com.clawmate.app.datamanager.LocalConsolidationTypes.EventPartition
import com.clawmate.app.datamanager.LocalConsolidationTypes.IndexedEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Focused JVM tests for the Phase-33 **workflow-scene + entity index**
 * foundation. Cases run over isolated temp dirs (never `filesDir`) and exercise
 * the real builders, resolver, codec, runner and model gate.
 *
 * Contract covered:
 *  - empty input -> zero network, no artifacts;
 *  - multi-scene postings from real events (dedup + sorted stable event ids);
 *  - stable event ids across a rerun;
 *  - duplicate alias merge into one canonical entity;
 *  - a different entity type never merges;
 *  - unknown type / empty / over-long mention dropped;
 *  - corrupt / oversized artifact recovery to an honest empty document;
 *  - old (Phase-30) event artifact backward compatibility;
 *  - bounded markdown / JSON artifact sizes;
 *  - model configuration gating (zero network unless explicit + complete).
 */
class EntitySceneIndexTest {

    private fun tempBase(): File =
        Files.createTempDirectory("clawmate-phase33-test").toFile().apply { deleteOnExit() }

    private fun seedPartition(base: File, month: String, events: List<IndexedEvent>) {
        val file = DataManagerPaths.eventPartition(base, month)
        file.parentFile.mkdirs()
        file.writeText(LocalConsolidationCodec.encodePartition(EventPartition(month = month, events = events)))
    }

    private fun event(
        id: String,
        date: String,
        fact: String = "记录内容 $id",
        sceneIds: List<Int> = emptyList(),
        mentions: List<EntityMention> = emptyList(),
    ): IndexedEvent = IndexedEvent(
        eventId = id,
        sourceStateFile = "outputs/${date.subSequence(0, 7)}/a.md.state.json",
        recordId = id.substringAfterLast('#').toIntOrNull() ?: 0,
        eventDate = date,
        lastSeenDate = date,
        summary = fact.take(60),
        normalizedFact = fact,
        workflowSceneIds = sceneIds,
        entityMentions = mentions,
    )

    private fun loadEvents(base: File): List<IndexedEvent> = ProfileIndexBuilder(base).loadEvents()

    /** Seed a daily-log file with a nested workflow_metadata header carrying task_scene_id. */
    private fun seedDailyLogWithScene(base: File, day: String, fileName: String, sceneId: Int, entries: List<String>) {
        val dayDir = File(File(base, DailyLogWorkspace.DAILY_LOG_ROOT_RELATIVE), day)
        dayDir.mkdirs()
        val header = "<!-- DAILY_LOG_METADATA\n{\"workflow_metadata\":{\"task_scene_id\":\"$sceneId\"}}\n-->"
        val body = entries.mapIndexed { i, t -> "${i + 1}. $t" }.joinToString("\n\n")
        val file = File(dayDir, fileName)
        file.writeText("$header\n\n$body\n")
    }

    // ---- 1. empty input -> zero network, no artifacts ----

    @Test
    fun `empty input yields no scene or entity artifact`() {
        val base = tempBase()
        val result = EntitySceneIndexRunner(base).run()
        assertEquals(0, result.sceneCount)
        assertEquals(0, result.entityCount)
        assertFalse(DataManagerPaths.workflowSceneIndex(base).exists())
        assertFalse(DataManagerPaths.entityIndex(base).exists())
    }

    @Test
    fun `events without scene ids or mentions never auto-create an artifact`() {
        val base = tempBase()
        seedPartition(base, "2026-08", listOf(event("m/a#0", "2026-08-01")))
        val result = EntitySceneIndexRunner(base).run()
        assertEquals(0, result.sceneCount)
        assertEquals(0, result.entityCount)
        assertFalse(DataManagerPaths.workflowSceneIndex(base).exists())
        assertFalse(DataManagerPaths.entityIndex(base).exists())
    }

    // ---- 2. multi-scene postings ----

    @Test
    fun `scenes from real events post to the right buckets sorted and deduped`() {
        val base = tempBase()
        seedPartition(
            base,
            "2026-08",
            listOf(
                event("m/a#0", "2026-08-01", sceneIds = listOf(1, 2)),
                event("m/a#1", "2026-08-02", sceneIds = listOf(2, 2, 1)),
                event("m/a#2", "2026-08-03", sceneIds = listOf(4)),
            ),
        )
        val builder = WorkflowSceneIndexBuilder(base)
        val events = loadEvents(base)
        val written = builder.buildAndWrite(events)
        assertEquals(3, written)
        val doc = builder.load()
        assertEquals(LocalConsolidationTypes.Schemas.WORKFLOW_SCENE_INDEX_V1, doc.schema)
        assertEquals(listOf("m/a#0", "m/a#1"), doc.postings.getValue("1"))
        assertEquals(listOf("m/a#0", "m/a#1"), doc.postings.getValue("2"))
        assertEquals(listOf("m/a#2"), doc.postings.getValue("4"))
    }

    // ---- 3. stable event ids across a rerun ----

    @Test
    fun `rerun keeps stable scene postings over unchanged real evidence`() {
        val base = tempBase()
        seedPartition(base, "2026-08", listOf(event("m/a#0", "2026-08-01", sceneIds = listOf(1))))
        val builder = WorkflowSceneIndexBuilder(base)
        val events = loadEvents(base)
        builder.buildAndWrite(events)
        val first = builder.load()
        builder.buildAndWrite(events)
        val second = builder.load()
        assertEquals(first, second)
        assertEquals(listOf("m/a#0"), second.postings.getValue("1"))
    }

    // ---- 4. duplicate alias merge ----

    @Test
    fun `same-type aliases merge into one canonical entity`() {
        val base = tempBase()
        seedPartition(
            base,
            "2026-08",
            listOf(
                event("m/a#0", "2026-08-01", mentions = listOf(EntityMention("person", "小明", listOf("小Ming")))),
                event("m/a#1", "2026-08-02", mentions = listOf(EntityMention("person", "小明", listOf("MingMing")))),
            ),
        )
        val builder = EntitySceneIndexBuilder(base)
        val result = builder.buildAndWrite(loadEvents(base))
        assertEquals(1, result.entityCount)
        val doc = builder.load()
        assertEquals(1, doc.entities.size)
        val person = doc.entities.values.first()
        assertEquals("person", person.type)
        assertEquals("小明", person.name)
        assertTrue(person.aliases.contains("小Ming"))
        assertTrue(person.aliases.contains("MingMing"))
        assertFalse(person.aliases.contains("小明"))
        assertEquals(listOf("m/a#0", "m/a#1"), person.eventIds.sorted())
    }

    // ---- 5. different entity type never merges ----

    @Test
    fun `different entity types with the same name never merge`() {
        val base = tempBase()
        seedPartition(
            base,
            "2026-08",
            listOf(
                event("m/a#0", "2026-08-01", mentions = listOf(EntityMention("person", "老王", emptyList()))),
                event("m/a#1", "2026-08-02", mentions = listOf(EntityMention("merchant", "老王", emptyList()))),
            ),
        )
        val builder = EntitySceneIndexBuilder(base)
        builder.buildAndWrite(loadEvents(base))
        val doc = builder.load()
        val types = doc.entities.values.map { it.type }.toSortedSet()
        assertEquals(setOf("person", "merchant"), types)
        assertEquals(2, doc.entities.size)
    }

    // ---- 6. unknown type / empty / over-long dropped ----

    @Test
    fun `unknown types empty and over-long mentions are dropped`() {
        val base = tempBase()
        val longName = "X".repeat(300)
        seedPartition(
            base,
            "2026-08",
            listOf(
                event("m/a#0", "2026-08-01", mentions = listOf(
                    EntityMention("robot", "机器人", emptyList()),
                    EntityMention("person", "", emptyList()),
                    EntityMention("person", longName, emptyList()),
                    EntityMention("person", "有效人物", emptyList()),
                )),
            ),
        )
        val builder = EntitySceneIndexBuilder(base)
        val result = builder.buildAndWrite(loadEvents(base))
        assertEquals(1, result.entityCount)
        val doc = builder.load()
        assertEquals(1, doc.entities.size)
        assertEquals("有效人物", doc.entities.values.first().name)
    }

    // ---- corrupt / oversized artifact recovery ----

    @Test
    fun `corrupt and oversized artifacts recover to empty`() {
        val base = tempBase()
        DataManagerPaths.workflowSceneIndex(base).apply { parentFile.mkdirs(); writeText("{ not json ]") }
        DataManagerPaths.entityIndex(base).apply { parentFile.mkdirs(); writeText("not-json") }

        assertEquals(0, WorkflowSceneIndexBuilder(base).load().postings.size)
        assertEquals(0, EntitySceneIndexBuilder(base).load().entities.size)

        // Oversized workflow-scene artifact also recovers (read caps at 4 MiB).
        DataManagerPaths.workflowSceneIndex(base).writeText("x".repeat(4 * 1024 * 1024 + 1))
        assertEquals(0, WorkflowSceneIndexBuilder(base).load().postings.size)
    }

    // ---- 7. old event artifact backward compatibility ----

    @Test
    fun `old Phase-30 event partition without scene fields stays compatible`() {
        val base = tempBase()
        val legacyEvent = IndexedEvent(
            eventId = "m/a#0",
            sourceStateFile = "outputs/a.md.state.json",
            recordId = 0,
            eventDate = "2026-08-01",
            lastSeenDate = "2026-08-01",
            summary = "旧记录",
            normalizedFact = "旧记录",
        )
        seedPartition(base, "2026-08", listOf(legacyEvent))
        val events = loadEvents(base)
        // Tolerant decode defaults the new fields to empty; builds stay honest.
        assertEquals(0, WorkflowSceneIndexBuilder(base).buildAndWrite(events))
        assertEquals(0, EntitySceneIndexBuilder(base).buildAndWrite(events).entityCount)
    }

    // ---- bounded markdown / JSON ----

    @Test
    fun `entity and scene index JSON stay bounded and parseable`() {
        val base = tempBase()
        val mentions = (0 until 50).map { EntityMention("person", "人物$it", listOf("a$it", "b$it")) }
        seedPartition(base, "2026-08", listOf(event("m/a#0", "2026-08-01", mentions = mentions)))
        val eb = EntitySceneIndexBuilder(base)
        eb.buildAndWrite(loadEvents(base))
        val entityText = DataManagerPaths.entityIndex(base).readText()
        assertTrue("entity JSON must be under 4 MiB", entityText.toByteArray().size <= 4 * 1024 * 1024)
        val decoded: EntityDocument = EntitySceneCodec.decodeEntity(entityText)
        assertFalse(decoded.entities.isEmpty())
    }

    @Test
    fun `workflow scene index JSON stays bounded and parseable`() {
        val base = tempBase()
        seedPartition(base, "2026-08", listOf(event("m/a#0", "2026-08-01", sceneIds = listOf(1, 2))))
        val wb = WorkflowSceneIndexBuilder(base)
        wb.buildAndWrite(loadEvents(base))
        val text = DataManagerPaths.workflowSceneIndex(base).readText()
        assertTrue(text.toByteArray().size <= 4 * 1024 * 1024)
        val decoded: WorkflowSceneDocument = EntitySceneCodec.decodeWorkflowScene(text)
        assertEquals(LocalConsolidationTypes.Schemas.WORKFLOW_SCENE_INDEX_V1, decoded.schema)
    }

    // ---- deterministic local extraction (no pseudo entities) ----

    @Test
    fun `rule extraction admits only real labelled mentions not whole log segments`() {
        val text = "今天点了外卖，商家：老王面馆，消费 25 元；与“张三”的聊天。"
        val mentions = SceneEntityNormalizer.deriveRuleEntities(text)
        val merchants = mentions.filter { it.type == "merchant" }
        val persons = mentions.filter { it.type == "person" }
        assertEquals(listOf("老王面馆"), merchants.map { it.name })
        assertTrue(persons.any { it.name == "张三" })
        assertTrue(mentions.none { it.name.contains("消费") })
    }

    // ---- 8. model config gating ----

    @Test
    fun `model gate refuses network unless explicit action and complete config`() {
        assertEquals(EntityModelBoundary.Gate.BLOCKED, EntityModelBoundary.gate(false, null))
        assertEquals(EntityModelBoundary.Gate.NOT_CONFIGURED, EntityModelBoundary.gate(true, null))
        assertEquals(
            EntityModelBoundary.Gate.NOT_CONFIGURED,
            EntityModelBoundary.gate(true, ProfileModelConfig("https://x/v1", "", "m")),
        )
        assertEquals(
            EntityModelBoundary.Gate.CONFIGURED,
            EntityModelBoundary.gate(true, ProfileModelConfig("https://x/v1", "k", "m")),
        )
    }

    @Test
    fun `runner with configured model still resolves locally with zero network`() {
        val base = tempBase()
        seedPartition(
            base,
            "2026-08",
            listOf(event("m/a#0", "2026-08-01", mentions = listOf(EntityMention("merchant", "老王面馆", emptyList())))),
        )
        val runner = EntitySceneIndexRunner(base)
        val result = runner.run(
            EntitySceneIndexRunner.BuildMode.ModelRequested(true, ProfileModelConfig("https://x/v1", "k", "m")),
        )
        assertEquals(1, result.entityCount)
        assertEquals(1, EntitySceneIndexBuilder(base).load().entities.size)
    }

    // ---- runner preserves profile state and manual Home data ----

    @Test
    fun `runner never creates or overwrites profile or home data`() {
        val base = tempBase()
        seedPartition(base, "2026-08", listOf(event("m/a#0", "2026-08-01", sceneIds = listOf(1))))
        EntitySceneIndexRunner(base).run()
        assertFalse(DataManagerPaths.profileState(base).exists())
        assertFalse(DataManagerPaths.profileMarkdown(base).exists())
    }

    // ---- scene-id propagation through the real daily-log pipeline ----

    @Test
    fun `task_scene_id metadata flows into event scene ids and postings`() {
        val base = tempBase()
        seedDailyLogWithScene(base, "2026-08-01", "eat.logs.md", 2, listOf("中午吃了火锅", "下午买了奶茶"))
        // Run the real Phase-30 consolidation + index, then the scene rebuild.
        LocalConsolidationIndexRunner(base).run()
        val events = loadEvents(base)
        assertTrue("events must carry the real scene id 2", events.all { it.workflowSceneIds.contains(2) })
        val written = WorkflowSceneIndexBuilder(base).buildAndWrite(events)
        assertTrue(written >= 1)
        val doc = WorkflowSceneIndexBuilder(base).load()
        assertTrue(doc.postings.containsKey("2"))
    }

    @Test
    fun `legacy scene id 7 normalizes to takeout scene 1`() {
        assertEquals(1, SceneEntityNormalizer.normalizeSceneId(7))
        assertEquals(listOf(1, 2, 4), SceneEntityNormalizer.normalizeSceneIds(listOf(4, 2, 7, 2)))
    }
}
