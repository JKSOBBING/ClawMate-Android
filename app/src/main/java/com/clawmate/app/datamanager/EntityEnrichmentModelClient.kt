package com.clawmate.app.datamanager

import com.clawmate.app.chat.cloud.ChatClient
import com.clawmate.app.chat.cloud.ChatCompletionsRequest
import com.clawmate.app.chat.cloud.ChatException
import com.clawmate.app.chat.cloud.ChatMessageWire
import com.clawmate.app.datamanager.EntityEnrichmentTypes.EntityCandidate
import com.clawmate.app.datamanager.EntityEnrichmentTypes.RichEntity
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement

/**
 * Phase-35 **explicit model entity enrichment** client. It converses with the
 * existing shared OpenAI-compatible [ChatClient] (the same `ChatCompletionsClient`
 * the chat screen, Home planner and Phase-34 consolidation use) to clarify a
 * bounded set of **real** entity candidates — then **strictly whitelists** every
 * reply against the current EntitySceneIndex / record scope.
 *
 * Safety boundaries:
 *  - **Zero network unless configured + there are real candidates.** The
 *    [EntityEnrichmentRunner] short-circuits to a deterministic local result
 *    before this client is ever called ([EntityEnrichmentGate]).
 *  - **Serial, bounded, cancellable.** One request at a time; a hard
 *    prompt-character cap; coroutine cancellation propagates (a cancelled call
 *    rethrows [CancellationException]).
 *  - **Minimal real text only.** The prompt carries only bounded, desensitized
 *    candidate text (name + bounded aliases) and **real** record ids / source
 *    refs / scene ids / date. Never a URI, external path, image, token or
 *    unrelated field.
 *  - **Strict JSON + whitelist.** Types are restricted to
 *    `person`/`place`/`merchant` (a model `unknown` is dropped, never coerced).
 *    Every `record_id` / `source_ref` on each reply entity must resolve to a
 *    **real** id in the injected scope; unknown, duplicate, out-of-bounds or
 *    over-long values force the entity to be dropped. The model can never invent
 *    a fact or fabricate an id.
 */
class EntityEnrichmentModelClient(
    private val chat: ChatClient,
    private val config: ConsolidationModelConfig,
) {

    /**
     * Ask the model to enrich the bounded [candidates]. [realRecordIds] and
     * [realSourceRefs] are the whitelist of the current scope. Returns the
     * strictly-validated, bounded list of enriched entities; an empty result on
     * any failure / malformed JSON / no admitted candidate (never throws).
     */
    suspend fun enrich(candidates: List<EntityCandidate>, realRecordIds: Set<Int>, realSourceRefs: Set<String>): List<RichEntity> {
        if (candidates.isEmpty() || realRecordIds.isEmpty()) return emptyList()
        val prompt = buildPrompt(candidates)
        if (prompt.isEmpty()) return emptyList()
        val content = try {
            val request = ChatCompletionsRequest(
                model = config.modelName,
                messages = listOf(
                    ChatMessageWire(role = "system", content = SYSTEM_PROMPT),
                    ChatMessageWire(role = "user", content = prompt),
                ),
                stream = false,
                temperature = 0.0,
                maxTokens = MAX_TOKENS,
            )
            chat.completion(request).content
        } catch (e: CancellationException) {
            throw e
        } catch (e: ChatException) {
            return emptyList()
        }
        if (content.isBlank()) return emptyList()
        return validate(parseReply(content), candidates, realRecordIds, realSourceRefs)
    }

    // ---------------------------------------------------------------------
    // Prompt builder (bounded, minimal real text, no secrets)
    // ---------------------------------------------------------------------

    internal fun buildPrompt(candidates: List<EntityCandidate>): String {
        val capped = candidates
            .filterNot { it.candidateId.isBlank() || it.name.isBlank() }
            .take(EntityEnrichmentTypes.MAX_CANDIDATES_PER_PROMPT)
        if (capped.isEmpty()) return ""
        val payload = PromptEntities(
            candidates = capped.map { c ->
                PromptCandidate(
                    id = c.candidateId,
                    type = c.type,
                    name = ConsolidationText.summarizeText(c.name, EntityEnrichmentTypes.MAX_ENTITY_NAME_CHARACTERS),
                    aliases = boundedAliases(c.aliases),
                    record_ids = c.recordIds.take(EntityEnrichmentTypes.MAX_RECORD_IDS_PER_ENTITY),
                    source_refs = c.sourceRefs.take(EntityEnrichmentTypes.MAX_SOURCE_REFS_PER_ENTITY),
                    scene_ids = c.sceneIds.take(16),
                    date = c.date,
                )
            },
        )
        val encoded = json.encodeToJsonElement(PromptEntities.serializer(), payload).toString()
        return if (encoded.length <= EntityEnrichmentTypes.MAX_PROMPT_CHARACTERS) encoded else ""
    }

    private fun boundedAliases(aliases: List<String>): List<String> =
        SceneEntityNormalizer.uniqueEntityNames(aliases)
            .take(EntityEnrichmentTypes.MAX_ALIASES_PER_ENTITY)

    // ---------------------------------------------------------------------
    // Strict JSON decode + whitelist
    // ---------------------------------------------------------------------

    internal fun parseReply(text: String): List<RawEntityWire> = runCatching {
        val root = json.parseToJsonElement(text)
        val obj = root as? JsonObject ?: return@runCatching emptyList()
        val rawList = (obj["entities"] as? JsonArray) ?: return@runCatching emptyList()
        rawList.mapNotNull { el ->
            val e = el as? JsonObject ?: return@mapNotNull null
            RawEntityWire(
                id = (e["id"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                type = (e["type"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                name = (e["name"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                aliases = parseStringArray(e["aliases"]),
                recordIds = parseIntArray(e["record_ids"]),
                sourceRefs = parseStringArray(e["source_refs"]),
            )
        }
    }.getOrNull().orEmpty()

    /**
     * Whitelist the raw model entities. Each is admitted only when:
     *  - its `id` is blank or matches a real candidate id in the current scope
     *    (so the model can only enrich real candidates, never fabricate a key);
     *  - its type is in the strict `person`/`place`/`merchant` set (an `unknown`
     *    is dropped);
     *  - its name is non-blank and bounded;
     *  - its `record_ids` / `source_refs` are all real in the injected scope,
     *    deduped and bounded.
     */
    internal fun validate(
        raw: List<RawEntityWire>,
        candidates: List<EntityCandidate>,
        realRecordIds: Set<Int>,
        realSourceRefs: Set<String>,
    ): List<RichEntity> {
        if (raw.isEmpty()) return emptyList()
        // The set of (type,name) real tokens presented to the model. An enriched
        // entity must match one of these (so the model can clarify/merge a real
        // candidate but never invent a brand-new entity name).
        val realCandidateKeys = candidates.map { lookupKeyOf(it.type, it.name) }.toSet()
        val realCandidateIds = candidates.map { it.candidateId }.filter { it.isNotBlank() }.toSet()
        val entities = ArrayList<RichEntity>()
        val seenNames = HashSet<String>()
        for (r in raw) {
            if (r.id.isNotBlank() && r.id !in realCandidateIds) continue
            val type = ConsolidationText.normalizeFreeText(r.type).lowercase()
            if (type !in EntityEnrichmentTypes.ADMITTED_TYPES) continue // unknown -> dropped
            val name = SceneEntityNormalizer.normalizeEntityName(r.name)
            if (name.isEmpty() || name.length > EntityEnrichmentTypes.MAX_ENTITY_NAME_CHARACTERS) continue
            if (lookupKeyOf(type, name) !in realCandidateKeys) continue // fabricated name -> dropped
            if (r.recordIds.size != r.recordIds.distinct().size ||
                r.sourceRefs.size != r.sourceRefs.distinct().size) continue
            val recordIds = r.recordIds.filter { it in realRecordIds }
                .sorted().take(EntityEnrichmentTypes.MAX_RECORD_IDS_PER_ENTITY)
            val sourceRefs = r.sourceRefs.map(ConsolidationText::normalizeFreeText)
                .filter { it in realSourceRefs }
                .take(EntityEnrichmentTypes.MAX_SOURCE_REFS_PER_ENTITY)
            if (recordIds.isEmpty() || sourceRefs.isEmpty()) continue
            val key = SceneEntityNormalizer.lookupText(name)
            if (key.isEmpty() || !seenNames.add(key)) continue
            entities.add(
                RichEntity(
                    type = type,
                    name = name,
                    aliases = boundedAliases(r.aliases),
                    recordIds = recordIds,
                    sourceRefs = sourceRefs,
                    modelNotes = listOf("model_entity_enhancement"),
                ),
            )
        }
        return entities
    }

    private fun lookupKeyOf(type: String, name: String): String =
        SceneEntityNormalizer.entityLookupKey(type, SceneEntityNormalizer.normalizeEntityName(name))

    private fun parseStringArray(el: JsonElement?): List<String> {
        val arr = el as? JsonArray ?: return emptyList()
        return arr.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.distinct().take(64)
    }

    private fun parseIntArray(el: JsonElement?): List<Int> {
        val arr = el as? JsonArray ?: return emptyList()
        return arr.mapNotNull { (it as? JsonPrimitive)?.content?.toIntOrNull() }.distinct().take(64)
    }

    // ---------------------------------------------------------------------
    // Wire shapes (independent of on-disk codec)
    // ---------------------------------------------------------------------
    @Serializable
    private data class PromptEntities(val candidates: List<PromptCandidate> = emptyList())

    @Serializable
    private data class PromptCandidate(
        val id: String = "",
        val type: String = "",
        val name: String = "",
        val aliases: List<String> = emptyList(),
        val record_ids: List<Int> = emptyList(),
        val source_refs: List<String> = emptyList(),
        val scene_ids: List<Int> = emptyList(),
        val date: String = "",
    )

    internal data class RawEntityWire(
        val id: String = "",
        val type: String = "",
        val name: String = "",
        val aliases: List<String> = emptyList(),
        val recordIds: List<Int> = emptyList(),
        val sourceRefs: List<String> = emptyList(),
    )

    companion object {
        private val json: Json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }

        internal const val MAX_TOKENS: Int = 1200

        private const val SYSTEM_PROMPT: String =
            "你是实体归并助手。输入是在当前真实记录作用域内、从不同事件提取出的候选实体。" +
                "请对每个候选实体给出确定类型(person/place/merchant)与别名，并只引用输入中真实存在的 record_ids 与 source_refs。" +
                "输出严格 JSON：{\"entities\":[{\"id\":\"候选实体id\",\"type\":\"person|place|merchant|unknown\"," +
                "\"name\":\"实体名\",\"aliases\":[],\"record_ids\":[],\"source_refs\":[]}]}。" +
                "不要编造任何 id、记录或事实，不要输出 URI、图片、token 或无关字段。"
    }
}
