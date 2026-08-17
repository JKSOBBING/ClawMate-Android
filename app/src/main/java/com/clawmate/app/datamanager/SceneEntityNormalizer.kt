package com.clawmate.app.datamanager

/**
 * Pure, network-free normalization helpers for the Phase-33 workflow-scene and
 * entity index foundation. These are faithful Kotlin ports of the relevant
 * Harmony `DataManagerUtils.ets` / `WorkflowSceneConfig.ets` surfaces
 * (`normalizeMemorySceneIds`, `normalizeEntityName`, `entityLookupKey`,
 * `entityDisplayKey`, `normalizeEntityMentions`, `deriveRuleEntities`), reduced
 * to the three Android-indexed entity types (`person`/`place`/`merchant`) and
 * the scene-id set memory-layer accepts.
 *
 * Every function is bounded and deterministic: unknown types, empty names and
 * over-long fields are dropped, never coerced, so a corrupt input can never
 * falsify the index. They have zero Android / network deps and are JVM-testable.
 */
object SceneEntityNormalizer {

    /** The three entity types the entity index admits (port of `ENTITY_TYPE_KEYS`). */
    val ENTITY_TYPE_KEYS: List<String> = listOf("person", "place", "merchant")

    /** Scene 7 (early gallery "餐饮") is unified into the existing "外卖" (1). */
    private const val LEGACY_SCENE_7: Int = 7
    private const val GALLERY_WORK_TAKEOUT_SCENE: Int = 1

    /** Max name-width cap so an over-long token can never enter the index. */
    const val MAX_ENTITY_NAME_CHARACTERS: Int = 120
    const val MAX_ENTITY_ALIASES: Int = 16

    /**
     * Normalize a single memory scene id (port of `normalizeMemorySceneId`):
     * the legacy `7` (gallery 餐饮) is migrated to `1` (外卖); others pass
     * through. Invalid / negative inputs are dropped by callers.
     */
    fun normalizeSceneId(sceneId: Int): Int =
        if (sceneId == LEGACY_SCENE_7) GALLERY_WORK_TAKEOUT_SCENE else sceneId

    /**
     * Normalize + sort + dedup a list of scene ids (port of
     * `normalizeMemorySceneIds`). Non-negative, deduped, sorted ascending.
     */
    fun normalizeSceneIds(sceneIds: List<Int>): List<Int> {
        val out = ArrayList<Int>()
        val seen = HashSet<Int>()
        for (raw in sceneIds) {
            val normalized = normalizeSceneId(raw)
            if (normalized >= 0 && seen.add(normalized)) {
                out.add(normalized)
            }
        }
        return out.sorted()
    }

    /**
     * Whitespace-collapse + trim and strip surrounding quote marks that wrap an
     * entity name (port of Harmony `normalizeEntityName`).
     */
    fun normalizeEntityName(value: String): String =
        ConsolidationText.normalizeFreeText(value)
            .replace(Regex("""^[“"'「『]+"""), "")
            .replace(Regex("""[”"'」』]+$"""), "")
            .trim()

    /** Case/punct-insensitive lookup key: `type:token` (port of `entityLookupKey`). */
    fun entityLookupKey(type: String, name: String): String =
        "$type:${lookupText(name)}"

    /** Display key preserving a canonical display name: `type:name` (port of `entityDisplayKey`). */
    fun entityDisplayKey(type: String, name: String): String =
        "$type:${normalizeEntityName(name)}"

    /** Bounded case/punct-insensitive token for a name (port of `normalizeLookupText`). */
    fun lookupText(value: String): String =
        ConsolidationText.normalizeFreeText(value).lowercase().replace(Regex("""\s+"""), "")

    /**
     * Normalize a raw entity-mention list (port of `normalizeEntityMentions`):
     * keeps only known `type`s, non-empty names, no duplicates, and bounded
     * aliases that never equal the canonical name's lookup key. Over-long names
     * are dropped (never truncated into a fake token).
     */
    fun normalizeEntityMentions(raw: List<LocalConsolidationTypes.EntityMention>): List<LocalConsolidationTypes.EntityMention> {
        if (raw.isEmpty()) return emptyList()
        val result = ArrayList<LocalConsolidationTypes.EntityMention>()
        val seen = HashSet<String>()
        for (entity in raw) {
            val type = ConsolidationText.normalizeFreeText(entity.type).lowercase()
            if (type !in ENTITY_TYPE_KEYS) continue
            val name = normalizeEntityName(entity.name)
            if (name.isEmpty() || name.length > MAX_ENTITY_NAME_CHARACTERS) continue
            val key = entityLookupKey(type, name)
            if (seen.contains(key)) continue
            val aliases = uniqueEntityNames(entity.aliases)
                .filter { entityLookupKey(type, it) != key }
                .take(MAX_ENTITY_ALIASES)
            result.add(LocalConsolidationTypes.EntityMention(type = type, name = name, aliases = aliases))
            seen.add(key)
        }
        return result
    }

    /** Conservative, bounded mention extraction from real event text (port of Harmony `deriveRuleEntities`). */
    fun deriveRuleEntities(text: String): List<LocalConsolidationTypes.EntityMention> {
        val compact = ConsolidationText.normalizeFreeText(text)
        if (compact.isEmpty()) return emptyList()
        val result = ArrayList<LocalConsolidationTypes.EntityMention>()
        val seen = HashSet<String>()
        fun add(type: String, nameRaw: String) {
            val name = normalizeEntityName(nameRaw)
            if (name.isEmpty() || name.length > MAX_ENTITY_NAME_CHARACTERS) return
            val key = entityLookupKey(type, name)
            if (seen.add(key)) result.add(LocalConsolidationTypes.EntityMention(type = type, name = name, aliases = emptyList()))
        }
        val capturer = { pattern: Regex, type: String ->
            pattern.findAll(compact).forEach { add(type, it.groupValues[1]) }
        }
        capturer(
            Regex("""(?:人物|联系人姓名|联系人|聊天对象|姓名)\s*[:：]\s*[“"']?([^，。；;\n“”"']{2,40})"""),
            "person",
        )
        capturer(
            Regex("""(?:地点|位置|目的地|地址|场馆)\s*[:：]\s*[“"']?([^，。；;\n“”"']{2,60})"""),
            "place",
        )
        capturer(
            Regex("""(?:商户|商家|门店|店铺)\s*[:：]\s*[“"']?([^，。；;\n“”"']{2,60})"""),
            "merchant",
        )
        val chatPattern =
            Regex("""(?:与|和)\s*[“"']([^“”"']{2,40})[”"']\s*的?(?:聊天|对话)""")
        chatPattern.findAll(compact).forEach { add("person", it.groupValues[1]) }
        return normalizeEntityMentions(result)
    }

    /** Bounded unique entity names (port of `uniqueEntityNames`). */
    fun uniqueEntityNames(values: List<String>): List<String> {
        val result = ArrayList<String>()
        val seen = HashSet<String>()
        for (value in values) {
            val name = normalizeEntityName(value)
            val token = lookupText(name)
            if (name.isNotEmpty() && token.isNotEmpty() && seen.add(token)) result.add(name)
        }
        return result
    }
}
