package com.clawmate.app.datamanager

import com.clawmate.app.datamanager.EntitySceneIndexTypes.EntityDefinition
import com.clawmate.app.datamanager.LocalConsolidationTypes.EntityMention

/**
 * A **deterministic, local-only** entity resolver for the Phase-33 entity index.
 *
 * It is a faithful port of the Harmony entity catalog/definition logic
 * (`IndexService.buildEntityMentionCatalog` / `addEntityDefinition` /
 * `buildEntityAliasPostings` / `finalizeEntityDocument`) but performs only the
 * **exact, typed, normalized-value** merge — it never invokes a model. Two
 * mentions merge into one canonical entity when they share a type and one side's
 * canonical name (or any of its aliases) equals the other side's canonical name
 * (or any alias), compared by the case/punct-insensitive lookup token. The
 * canonical entity id is the stable `entityDisplayKey(type, canonicalName)`.
 *
 * Unknown types, empty names and over-long fields are dropped (never coerced).
 * Only real mention tokens from real events/admitted model mapping are accepted —
 * a URI, image, token or whole log segment is never written into the index.
 */
class EntityResolver {

    /** Resolved output: canonical entities keyed by display-key, plus typed counts. */
    data class Resolution(
        val entities: Map<String, EntityDefinition> = emptyMap(),
        val aliasPostings: Map<String, List<String>> = emptyMap(),
        val mentionAssignments: Map<String, String> = emptyMap(),
        val mentionCount: Int = 0,
        val mergedMentionCount: Int = 0,
        val newEntityCount: Int = 0,
    ) {
        val entityCount: Int get() = entities.size
    }

    /**
     * Resolve a set of real, already-normalized event mentions into canonical
     * entities. [eventIdsByMention] maps each mention's lookup key to the union of
     * real event ids that mention it.
     */
    fun resolve(mentions: List<EntityMention>, eventIdsByMention: Map<String, List<String>>): Resolution {
        val entities = LinkedHashMap<String, EntityDefinition>()
        val aliasTokensByEntity = HashMap<String, Set<String>>()
        val mentionAssignments = LinkedHashMap<String, String>()
        var mentionCount = 0
        var mergedCount = 0
        var newCount = 0

        for (mention in mentions) {
            val type = ConsolidationText.normalizeFreeText(mention.type).lowercase()
            if (type !in SceneEntityNormalizer.ENTITY_TYPE_KEYS) continue
            val name = SceneEntityNormalizer.normalizeEntityName(mention.name)
            if (name.isEmpty() || name.length > SceneEntityNormalizer.MAX_ENTITY_NAME_CHARACTERS) continue
            mentionCount++
            val mentionKey = SceneEntityNormalizer.entityLookupKey(type, name)
            val eventIds = eventIdsByMention[mentionKey].orEmpty()
            val aliases = SceneEntityNormalizer.uniqueEntityNames(mention.aliases)
                .filter { it.isNotEmpty() && SceneEntityNormalizer.entityLookupKey(type, it) != mentionKey }
                .take(SceneEntityNormalizer.MAX_ENTITY_ALIASES)

            // Find the canonical entity this mention should fold into by exact
            // typed value-overlap (name or aliases), deterministically.
            val targetKey = matchEntityKey(type, name, aliases, entities, aliasTokensByEntity)

            if (targetKey == null) {
                val displayKey = SceneEntityNormalizer.entityDisplayKey(type, name)
                val existing = entities[displayKey]
                if (existing != null) {
                    val merged = mergeInto(existing, aliases, eventIds)
                    entities[displayKey] = merged
                    aliasTokensByEntity[displayKey] = tokensOf(merged.name, merged.aliases)
                    mergedCount++
                } else {
                    entities[displayKey] = EntityDefinition(
                        type = type,
                        name = name,
                        aliases = aliases,
                        eventIds = iterateStableUnique(eventIds).sorted(),
                    )
                    aliasTokensByEntity[displayKey] = tokensOf(name, aliases)
                    newCount++
                }
                mentionAssignments[mentionKey] = displayKey
            } else {
                val merged = mergeInto(entities.getValue(targetKey), aliases, eventIds)
                entities[targetKey] = merged
                aliasTokensByEntity[targetKey] = tokensOf(merged.name, merged.aliases)
                mentionAssignments[mentionKey] = targetKey
                mergedCount++
            }
        }

        // Finalize: drop entities with no real event evidence (they never falsify).
        val nonEmpty = LinkedHashMap<String, EntityDefinition>()
        for (key in entities.keys.toSortedSet()) {
            val def = entities.getValue(key)
            if (def.eventIds.isNotEmpty()) nonEmpty[key] = def
        }
        val aliasPostings = buildAliasPostings(nonEmpty)
        return Resolution(
            entities = nonEmpty,
            aliasPostings = aliasPostings,
            mentionAssignments = mentionAssignments,
            mentionCount = mentionCount,
            mergedMentionCount = mergedCount,
            newEntityCount = newCount,
        )
    }

    private fun matchEntityKey(
        type: String,
        name: String,
        aliases: List<String>,
        entities: Map<String, EntityDefinition>,
        aliasTokensByEntity: Map<String, Set<String>>,
    ): String? {
        val nameToken = SceneEntityNormalizer.lookupText(name)
        for ((key, def) in entities) {
            if (def.type != type) continue
            val defTokens = aliasTokensByEntity[key].orEmpty()
            if (defTokens.contains(nameToken)) return key
            for (alias in aliases) {
                if (defTokens.contains(SceneEntityNormalizer.lookupText(alias))) return key
            }
        }
        return null
    }

    private fun mergeInto(existing: EntityDefinition, newAliases: List<String>, newEventIds: List<String>): EntityDefinition {
        val aliases = existing.aliases.toMutableList()
        val seenTokens = HashSet<String>()
        for (a in existing.aliases) seenTokens.add(SceneEntityNormalizer.lookupText(a))
        for (a in newAliases) {
            val t = SceneEntityNormalizer.lookupText(a)
            if (t.isEmpty() || t == SceneEntityNormalizer.lookupText(existing.name)) continue
            if (seenTokens.add(t)) aliases.add(a)
        }
        val ids = iterateStableUnique(existing.eventIds + newEventIds).sorted()
        return existing.copy(aliases = aliases.take(SceneEntityNormalizer.MAX_ENTITY_ALIASES), eventIds = ids)
    }

    private fun buildAliasPostings(entities: Map<String, EntityDefinition>): Map<String, List<String>> {
        val postings = LinkedHashMap<String, MutableList<String>>()
        for (key in entities.keys.toSortedSet()) {
            val def = entities.getValue(key)
            for (name in listOf(def.name) + def.aliases) {
                val lookup = SceneEntityNormalizer.entityLookupKey(def.type, name)
                val list = postings.getOrPut(lookup) { ArrayList() }
                if (!list.contains(key)) list.add(key)
            }
        }
        val sorted = LinkedHashMap<String, List<String>>()
        postings.keys.toSortedSet().forEach { sorted[it] = postings.getValue(it).sorted() }
        return sorted
    }

    private fun tokensOf(name: String, aliases: List<String>): Set<String> {
        val tokens = HashSet<String>()
        tokens.add(SceneEntityNormalizer.lookupText(name))
        for (a in aliases) tokens.add(SceneEntityNormalizer.lookupText(a))
        return tokens
    }

    private fun iterateStableUnique(values: List<String>): List<String> {
        val result = ArrayList<String>()
        val seen = HashSet<String>()
        for (v in values) {
            val text = ConsolidationText.normalizeFreeText(v)
            if (text.isNotEmpty() && seen.add(text)) result.add(text)
        }
        return result
    }
}
