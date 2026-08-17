package com.clawmate.app.home.recommendation

import com.clawmate.app.home.domain.HomeRecommendationState
import com.clawmate.app.log.domain.SensitiveLogRedactor

/**
 * Strict validator + normalizer for the model's Home-recommendation reply —
 * directly mirrors Harmony `DataPipelineModelClient.normalizeHomeRecommendations`
 * / `HomeRecommendationService.normalizeRecommendations`, made strict per Phase-28
 * safety requirements.
 *
 * Rules (each missing/over-bound item is **dropped**, never filled with defaults):
 *  - Every item must present a non-blank [HomeRecommendationBounds.MAX_TAG_CHARACTERS]
 *    `title` and **at least one** `source_event_ids` entry.
 *  - Every source event id must be a real input id (present in the planner
 *    events). Ids that are not part of the input are dropped from the item.
 *  - Each input event may appear in **at most one** admitted recommendation
 *    (deduplication); later items referencing an already-covered event lose
 *    their duplicated ids and are dropped when no fresh id remains.
 *  - `domain` is kept only when it is one of the six allowed domains; anything
 *    else is normalised to the legitimate `other` (never fabricated content).
 *  - `title` / `desc` / `tag` are bounded; a `tag` outside 2..6 chars is
 *    discarded (as in Harmony). Lists are capped.
 *  - No default titles, descs or tags are synthesised.
 *
 * The admitted list is returned in model order with deterministic `todo-N` ids,
 * matching Harmony's `nextRecommendationId` counter.
 */
object HomeRecommendationNormalizer {

    /**
     * Normalize an untrusted model payload (`recommendations` JSON array) into a
     * bounded [List] of [HomeRecommendationState]. Always returns a list; invalid
     * or empty input yields an empty list (never fabricated content).
     *
     * @param rawItems raw parsed JSON `recommendations` entries.
     * @param plannerEvents the bounded planner events that were actually sent.
     * @param maxCount upper bound on admitted recommendations
     *   ([HomeRecommendationBounds.MAX_RECOMMENDATIONS] by default).
     */
    fun normalize(
        rawItems: List<Map<*, *>>,
        plannerEvents: List<HomeRecommendationPlannerEvent>,
        maxCount: Int = HomeRecommendationBounds.MAX_RECOMMENDATIONS,
    ): List<HomeRecommendationState> {
        val inputIds = plannerEvents.mapTo(linkedSetOf()) { it.eventId.trim() }
        val usedIds = linkedSetOf<String>()
        val covered = linkedSetOf<String>()
        val result = ArrayList<HomeRecommendationState>(minOf(maxCount, 16))
        var counter = 0

        for (raw in rawItems) {
            if (result.size >= maxCount) break
            val title = (raw["title"] as? String)?.trim() ?: ""
            if (title.isBlank()) continue

            val rawSourceIds = stringList(raw["source_event_ids"])
                .map { it.trim() }
                .filter { it in inputIds && it !in covered }
            if (rawSourceIds.isEmpty()) continue

            val domain = normalizeDomain(raw["domain"])
            val tag = boundedTag(raw["tag"])

            val item = HomeRecommendationState(
                id = nextRecommendationId(counter, usedIds),
                tag = tag,
                title = bounded(title, HomeRecommendationBounds.MAX_TITLE_CHARACTERS),
                desc = boundedDesc(raw["desc"]),
                meta = "",
                domain = domain,
                sourceEventIds = rawSourceIds.take(
                    HomeRecommendationBounds.MAX_SOURCE_EVENT_IDS_PER_RECOMMENDATION,
                ),
                completed = false,
            )
            result.add(item)
            counter++
            covered.addAll(item.sourceEventIds)
        }
        return result
    }

    /** `todo-<n>` id generator mirroring Harmony `nextRecommendationId`. */
    private fun nextRecommendationId(counter: Int, usedIds: MutableSet<String>): String {
        var index = counter + 1
        while (usedIds.contains("todo-$index")) index++
        val id = "todo-$index"
        usedIds.add(id)
        return id
    }

    /** Trim + bound a free text field to [maxLength] chars. */
    private fun bounded(value: String, maxLength: Int): String {
        if (value.isBlank()) return ""
        return if (value.length <= maxLength) value else value.substring(0, maxLength)
    }

    /** Bound an optional `desc` field (empty is honest, never defaulted). */
    private fun boundedDesc(value: Any?): String {
        val text = (value as? String)?.trim() ?: ""
        if (text.isBlank()) return ""
        return bounded(text, HomeRecommendationBounds.MAX_DESC_CHARACTERS)
    }

    /** Keep only one of the six allowed domains; anything else → `other`. */
    private fun normalizeDomain(value: Any?): String {
        val d = (value as? String)?.trim()?.lowercase() ?: ""
        return if (d in HOME_RECOMMENDATION_DOMAINS) d else "other"
    }

    /**
     * Bounded tag validity (mirrors Harmony `normalizeRecommendationTag`): only a
     * 2..6-char tag is kept; anything else yields "". Empty desc/tag are honest
     * (no defaults are synthesised).
     */
    private fun boundedTag(value: Any?): String {
        val text = (value as? String)?.trim() ?: ""
        if (text.length < 2 || text.length > HomeRecommendationBounds.MAX_TAG_CHARACTERS) {
            return ""
        }
        return text
    }

    private fun stringList(value: Any?): List<String> =
        when (value) {
            is List<*> -> value.mapNotNull { it as? String }
            is String -> emptyList()
            else -> emptyList()
        }
}

/**
 * A tiny parser for the untrusted model `recommendations` reply built on the
 * **existing** kotlinx.serialization stack (no new JSON layer). Tolerant parsing
 * is deliberate: a malformed or missing field degrades to empty (the honest
 * no-data path) rather than throwing past the pipeline's intent. Only the
 * `recommendations` top-level array is read; nothing downstream ever trusts raw
 * reply text.
 */
internal object HomeRecommendationReplyParser {
    private val json = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /**
     * Parse a model reply into the untrusted `recommendations` array as a list
     * of plain maps. Returns the empty list when the reply is blank, not an
     * object with a `recommendations` array, or otherwise un-decodable (the
     * caller treats an empty list as an honest EMPTY/error result — never
     * fabricated content).
     */
    fun parse(reply: String): List<Map<*, *>> {
        val trimmed = reply.trim()
        if (trimmed.isEmpty()) return emptyList()
        return runCatching {
            val obj = json.parseToJsonElement(trimmed) as? kotlinx.serialization.json.JsonObject
                ?: return emptyList()
            val recs = obj["recommendations"] as? kotlinx.serialization.json.JsonArray
                ?: return emptyList()
            recs.mapNotNull { raw -> (raw as? kotlinx.serialization.json.JsonObject)?.toMap() }
        }.getOrElse { emptyList() }
    }

    /** Flatten a [JsonObject] into a plain read-only [Map] of primitives. */
    private fun kotlinx.serialization.json.JsonObject.toMap(): Map<*, *> =
        this.mapValues { (_, value) ->
            when (value) {
                is kotlinx.serialization.json.JsonPrimitive -> value.content
                is kotlinx.serialization.json.JsonArray ->
                    value.map { (it as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty() }
                else -> ""
            }
        }
}
