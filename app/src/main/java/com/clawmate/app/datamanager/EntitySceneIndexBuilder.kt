package com.clawmate.app.datamanager

import com.clawmate.app.datamanager.EntitySceneIndexTypes.EntityDocument
import com.clawmate.app.datamanager.EntitySceneIndexTypes.EntityResolutionStatus
import com.clawmate.app.datamanager.LocalConsolidationTypes.EntityMention
import com.clawmate.app.datamanager.LocalConsolidationTypes.IndexedEvent

/**
 * Phase-33 **entity index / resolver foundation** builder.
 *
 * Reads the **real** indexed events from the Phase-30/32 month partitions and
 * builds the `entity/index.json` contract. Canonical entities are established by
 * the deterministic local [EntityResolver] from two sources only:
 *
 *  1. each event's real `entity_mentions` (already-present mention tokens on an
 *     enriched / externally produced event); and
 *  2. a **conservative, bounded** rule extraction from the event's
 *     `normalized_fact` / `summary` text ([SceneEntityNormalizer.deriveRuleEntities]),
 *     limited to the `person`/`place`/`merchant` labels and quote-wrapped or
 *     labelled names — never whole log segments.
 *
 * Unknown types, empty names and over-long fields are dropped. A URI, image
 * path or token is never written into the index. Same-type normalized names and
 * aliases merge stably into one canonical entity; different entity types can
 * never merge.
 */
class EntitySceneIndexBuilder(
    private val baseDir: java.io.File,
    private val store: AtomicJsonStore = AtomicJsonStore(),
    private val resolver: EntityResolver = EntityResolver(),
) {

    /**
     * Collect real, normalized mentions from one event plus conservative
     * rule-extracted mentions from its real text file. Never reads a URI/image.
     */
    fun eventMentions(event: IndexedEvent): List<EntityMention> {
        val real = SceneEntityNormalizer.normalizeEntityMentions(event.entityMentions)
        val text = "${event.normalizedFact} ${event.summary}"
        val extracted = SceneEntityNormalizer.normalizeEntityMentions(
            SceneEntityNormalizer.deriveRuleEntities(text),
        )
        return mergeMentions(real + extracted)
    }

    /**
     * Build + persist the entity document from real events. When no real
     * mention / extraction yields a canonical entity, no artifact is written and
     * an honest EMPTY result is returned (zero network).
     */
    fun buildAndWrite(events: List<IndexedEvent>): BuildResult {
        if (events.isEmpty()) return BuildResult(EntityResolutionStatus.EMPTY, entityCount = 0, mentionCount = 0)
        val all = LinkedHashMap<String, EntityMention>()
        val eventIdsByMention = LinkedHashMap<String, MutableList<String>>()
        for (event in events) {
            for (mention in eventMentions(event)) {
                val key = SceneEntityNormalizer.entityLookupKey(mention.type, mention.name)
                val prev = all[key]
                if (prev == null) {
                    all[key] = mention
                } else {
                    // Same normalized mention across events: merge bounded aliases.
                    val aliases = SceneEntityNormalizer.uniqueEntityNames(prev.aliases + mention.aliases)
                        .filter { alias -> alias.isNotEmpty() && SceneEntityNormalizer.entityLookupKey(prev.type, alias) != key }
                        .take(SceneEntityNormalizer.MAX_ENTITY_ALIASES)
                    all[key] = prev.copy(aliases = aliases)
                }
                eventIdsByMention.getOrPut(key) { ArrayList() }.add(event.eventId)
            }
        }
        if (all.isEmpty()) return BuildResult(EntityResolutionStatus.EMPTY, entityCount = 0, mentionCount = 0)

        val resolution = resolver.resolve(all.values.toList(), eventIdsByMention)
        val status = if (resolution.entityCount > 0) EntityResolutionStatus.LOCAL_ONLY else EntityResolutionStatus.EMPTY
        if (resolution.entities.isEmpty()) {
            return BuildResult(status, entityCount = 0, mentionCount = resolution.mentionCount)
        }
        val document = EntityDocument(
            generatedAt = nowIsoLocal(),
            entities = resolution.entities,
            aliasPostings = resolution.aliasPostings,
            mentionAssignments = resolution.mentionAssignments,
        )
        store.writeJson(DataManagerPaths.entityIndex(baseDir), document) { EntitySceneCodec.encodeEntity(it) }
        return BuildResult(status, entityCount = resolution.entityCount, mentionCount = resolution.mentionCount)
    }

    /** Tolerant re-read of the persisted entity index (empty when missing/corrupt/oversized). */
    fun load(): EntityDocument =
        store.readJson(DataManagerPaths.entityIndex(baseDir)) { EntitySceneCodec.decodeEntity(it) }
            ?: EntityDocument()

    private fun mergeMentions(mentions: List<EntityMention>): List<EntityMention> {
        val out = LinkedHashMap<String, EntityMention>()
        for (m in mentions) {
            val type = ConsolidationText.normalizeFreeText(m.type).lowercase()
            if (type !in SceneEntityNormalizer.ENTITY_TYPE_KEYS) continue
            val name = SceneEntityNormalizer.normalizeEntityName(m.name)
            if (name.isEmpty() || name.length > SceneEntityNormalizer.MAX_ENTITY_NAME_CHARACTERS) continue
            val key = SceneEntityNormalizer.entityLookupKey(type, name)
            val prev = out[key]
            if (prev == null) {
                out[key] = m
            } else {
                val aliases = SceneEntityNormalizer.uniqueEntityNames(prev.aliases + m.aliases)
                    .filter { it.isNotEmpty() && SceneEntityNormalizer.entityLookupKey(type, it) != key }
                    .take(SceneEntityNormalizer.MAX_ENTITY_ALIASES)
                out[key] = prev.copy(aliases = aliases)
            }
        }
        return out.values.toList()
    }

    private fun nowIsoLocal(): String =
        java.time.LocalDateTime.now(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))

    /** Honest, countable result of one entity-index build attempt. */
    data class BuildResult(
        val status: EntityResolutionStatus,
        val entityCount: Int,
        val mentionCount: Int,
    )
}
