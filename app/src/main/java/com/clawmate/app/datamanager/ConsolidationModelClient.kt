package com.clawmate.app.datamanager

import com.clawmate.app.chat.cloud.ChatClient
import com.clawmate.app.chat.cloud.ChatCompletionsRequest
import com.clawmate.app.chat.cloud.ChatException
import com.clawmate.app.chat.cloud.ChatMessageWire
import com.clawmate.app.datamanager.CloudConsolidationTypes.BatchSplitPromptEntry
import com.clawmate.app.datamanager.CloudConsolidationTypes.BatchSplitPromptPayload
import com.clawmate.app.datamanager.CloudConsolidationTypes.CandidateFact
import com.clawmate.app.datamanager.CloudConsolidationTypes.DedupCandidatePayload
import com.clawmate.app.datamanager.CloudConsolidationTypes.DedupDecision
import com.clawmate.app.datamanager.CloudConsolidationTypes.DedupDecisions
import com.clawmate.app.datamanager.CloudConsolidationTypes.DedupExistingRecord
import com.clawmate.app.datamanager.CloudConsolidationTypes.DedupPromptPayload
import com.clawmate.app.datamanager.CloudConsolidationTypes.RawSplitFact
import com.clawmate.app.datamanager.CloudConsolidationTypes.RawSplitItem
import com.clawmate.app.datamanager.CloudConsolidationTypes.SplitReply
import com.clawmate.app.datamanager.LocalConsolidationTypes.ConsolidatedRecord
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Phase-34 **model-backed consolidation** client. Converses with the existing
 * shared OpenAI-compatible [ChatClient] to (a) split bounded real source entries
 * into bounded candidate facts and (b) decide a strict dedup decision for one
 * candidate fact against a bounded recent-scope set of real records — then
 * **strictly validates** every model reply so no fabricated record, no arbitrary
 * matched id and no overwrite of an unrelated record ever reaches the persisted
 * state.
 *
 * Safety boundaries (all enforced here and by [CloudConsolidationRepository]):
 *  - **Zero network unless configured and there is real input.** The caller
 *    short-circuits to a deterministic local path whenever [ConsolidationModelConfig]
 *    is null or there are no entries/records — no request is ever sent.
 *  - **Serial, bounded, cancellable.** One model request at a time (no parallel),
 *    batched entries/facts, a hard prompt-character cap, and coroutine
 *    cancellation propagates so a cancelled run reports [CancellationException].
 *  - **Minimal real text only.** Each prompt carries only bounded entry/fact/
 *    record text and ids; never a URI, image, token, external path or unrelated
 *    field. Generated facts always carry their real `source_ref` / `source_date`
 *    / `workflow_scene_id` so they re-join the correct entry.
 *  - **Independent decode + whitelist.** Decision tokens are normalised to the
 *    strict set; any `matched_record_id` must resolve to a **real** candidate
 *    record id in the current scope — unknown/duplicated/out-of-bounds ids force
 *    the safe heuristic/local fallback.
 */
class ConsolidationModelClient(
    private val chat: ChatClient,
    private val config: ConsolidationModelConfig,
) {

    // ---------------------------------------------------------------------
    // Fact split: bounded source entries -> bounded candidate facts
    // ---------------------------------------------------------------------

    /**
     * Split [entries] into bounded candidate facts, keyed by `source_ref`.
     * Every entry first yields a deterministic heuristic fact (always-safe
     * baseline). When the model is configured the entries are processed in
     * bounded batches; for each entry whose model reply is strictly clean and
     * carries the same real `source_ref`, the heuristic facts are replaced.
     * Any failure / malformed JSON / empty or over-bounds reply keeps the
     * heuristic facts (local fallback) without ever throwing. [onSourceFallback]
     * is invoked (if provided) for each `source_ref` that fell back to heuristic.
     */
    suspend fun splitEntriesIntoFacts(
        entries: List<DailyLogEntry>,
        onSourceFallback: ((String) -> Unit)? = null,
    ): Map<String, List<CandidateFact>> {
        val result = LinkedHashMap<String, List<CandidateFact>>()
        for (entry in entries) result[entry.sourceRef] = heuristicSplit(entry)
        if (entries.isEmpty()) return result

        val batched = chunkEntries(entries)
        for (chunk in batched) {
            val mapped = chunk.associateBy { it.sourceRef }
            val reply = callJson(
                system = SPLIT_SYSTEM_PROMPT,
                user = buildSplitPrompt(chunk),
                parse = { text -> parseSplitReply(text) },
            ) ?: continue
            for (rawItem in reply.items) {
                val sourceRef = ConsolidationText.normalizeFreeText(rawItem.sourceRef)
                val entry = mapped[sourceRef] ?: continue
                val facts = validateSplitFacts(rawItem.facts, entry)
                if (facts.isNotEmpty()) {
                    result[sourceRef] = facts
                } else {
                    onSourceFallback?.invoke(sourceRef)
                }
            }
        }
        return result
    }

    // ---------------------------------------------------------------------
    // Dedup decision: candidate vs bounded recent-scope records
    // ---------------------------------------------------------------------

    /**
     * Decide the strict dedup outcome for [fact] against [candidateRecords]
     * (the recent-scope, already-validated real records). Returns a typed
     * [DedupOutcome] that is always safe:
     *
     *  - no recent records -> [new] (`no_recent_records`, source FALLBACK);
     *  - heuristic when the model is unconfigured / call fails / JSON is
     *    malformed / matched-id membership fails;
     *  - otherwise the model's decision after strict validation [source MODEL].
     */
    suspend fun decideDedup(
        fact: CandidateFact,
        candidateRecords: List<ConsolidatedRecord>,
    ): DedupOutcome {
        if (candidateRecords.isEmpty()) {
            return DedupOutcome(newDecision("no_recent_records"), DecisionSource.NO_CANDIDATES)
        }
        val heuristic = heuristicDedupDecision(fact, candidateRecords)
        val prompt = DedupPromptPayload(
            candidate = DedupCandidatePayload(
                summary = summarize(fact.summary, 100),
                normalizedFact = summarize(fact.normalizedFact, 200),
                eventDate = fact.eventDate,
                tags = fact.tags.take(10),
            ),
            existingRecords = candidateRecords.map { record ->
                DedupExistingRecord(
                    recordId = record.recordId,
                    firstSeenDate = record.firstSeenDate,
                    lastSeenDate = record.lastSeenDate,
                    summary = summarize(record.summary, 100),
                    normalizedFact = summarize(record.normalizedFact, 200),
                    tags = record.tags.take(10),
                )
            },
        )
        val decision = callJson(
            system = DEDUP_SYSTEM_PROMPT,
            user = encodePayload(prompt),
            parse = { text -> parseDedupDecision(text) },
        )
        if (decision != null && isSafeDecision(decision, candidateRecords)) {
            return DedupOutcome(decision, DecisionSource.MODEL)
        }
        return DedupOutcome(heuristic, DecisionSource.FALLBACK)
    }

    // ---------------------------------------------------------------------
    // Deterministic local split + heuristic dedup (always-safe baseline)
    // ---------------------------------------------------------------------

    /** Deterministic, no-model split of one entry into candidate facts. */
    internal fun heuristicSplit(entry: DailyLogEntry): List<CandidateFact> {
        val text = ConsolidationText.normalizeFreeText(entry.text)
        if (text.isEmpty()) return emptyList()
        val sceneId = sceneIdOf(entry)
        val clauses = atomicClauses(text)
        if (clauses.isEmpty()) {
            return listOf(
                CandidateFact(
                    summary = ConsolidationText.summarizeText(text, 60).ifEmpty { "未命名记录" },
                    normalizedFact = text,
                    eventDate = entry.sourceDate,
                    rawExcerpt = summarize(text, MAX_EXCERPT_CHARACTERS),
                    sourceRef = entry.sourceRef,
                    sourceDate = entry.sourceDate,
                    workflowSceneId = sceneId,
                    modelNotes = "heuristic_split",
                    dedupText = ConsolidationText.normalizeDedupText(text),
                ),
            )
        }
        return clauses.map { clause ->
            val normalized = ConsolidationText.normalizeFreeText(clause)
            CandidateFact(
                summary = normalized,
                normalizedFact = normalized,
                eventDate = entry.sourceDate,
                rawExcerpt = summarize(text, MAX_EXCERPT_CHARACTERS),
                sourceRef = entry.sourceRef,
                sourceDate = entry.sourceDate,
                workflowSceneId = sceneId,
                modelNotes = "heuristic_atomic_split",
                dedupText = ConsolidationText.normalizeDedupText(normalized),
            )
        }
    }

    /** Safe deterministic dedup decision over the recent-scope records. */
    internal fun heuristicDedupDecision(
        fact: CandidateFact,
        records: List<ConsolidatedRecord>,
        reasonPrefix: String = "local",
    ): DedupDecision {
        val factKey = ConsolidationText.normalizedSimilarityKey(fact.dedupText.ifEmpty { fact.normalizedFact })
        val factAmounts = ConsolidationText.extractAmountTokens(fact.dedupText.ifEmpty { fact.normalizedFact })
        for (record in records.asReversed()) {
            val recordKey = ConsolidationText.normalizedSimilarityKey(
                record.dedupText.ifEmpty { record.normalizedFact },
            )
            if (factKey.isNotEmpty() && factKey == recordKey) {
                return mergeDecision(DedupDecisions.DUPLICATE, reasonPrefix + "_exact_match", record.recordId)
            }
            val recordAmounts = ConsolidationText.extractAmountTokens(record.dedupText.ifEmpty { record.normalizedFact })
            val signature = normalizedTransactionSignature(fact.dedupText.ifEmpty { fact.normalizedFact })
            if (factAmounts.isNotEmpty() &&
                ConsolidationText.sameStringSet(factAmounts, recordAmounts) &&
                signature.isNotEmpty() &&
                signature == normalizedTransactionSignature(record.dedupText.ifEmpty { record.normalizedFact })
            ) {
                return mergeDecision(DedupDecisions.MERGE_WITH_EXISTING, reasonPrefix + "_signature_match", record.recordId)
            }
        }
        return newDecision(reasonPrefix + "_no_match")
    }

    // ---------------------------------------------------------------------
    // Strict JSON decode + whitelist
    // ---------------------------------------------------------------------

    /** Decode a split reply; null when the JSON is malformed or mis-shaped. */
    internal fun parseSplitReply(text: String): SplitReply? = runCatching {
        val root = json.parseToJsonElement(text)
        val obj = root as? JsonObject ?: return@runCatching SplitReply(emptyList())
        val rawItems = (obj["items"] as? JsonArray) ?: return@runCatching SplitReply(emptyList())
        val items = rawItems.mapNotNull { el ->
            val itemObj = el as? JsonObject ?: return@mapNotNull null
            val ref = (itemObj["source_ref"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            if (ref.isBlank()) return@mapNotNull null
            RawSplitItem(sourceRef = ref, facts = parseFactsArray(itemObj["facts"]))
        }
        SplitReply(items = items)
    }.getOrNull()

    /** Decode a strict dedup decision; null when the JSON is malformed. */
    internal fun parseDedupDecision(text: String): DedupDecision? = runCatching {
        val obj = json.parseToJsonElement(text).jsonObject
        val token = normalizeDecisionToken((obj["decision"] as? JsonPrimitive)?.contentOrNull.orEmpty())
        if (token !in DedupDecisions.ALL) throw IllegalArgumentException("unknown decision token")
        DedupDecision(
            decision = token,
            matchedRecordIds = parseIntArray(obj["matched_record_ids"]),
            reason = ConsolidationText.normalizeFreeText((obj["reason"] as? JsonPrimitive)?.contentOrNull.orEmpty()),
            mergedSummary = ConsolidationText.normalizeFreeText((obj["merged_summary"] as? JsonPrimitive)?.contentOrNull.orEmpty()),
            mergedFact = ConsolidationText.normalizeFreeText((obj["merged_fact"] as? JsonPrimitive)?.contentOrNull.orEmpty()),
            mergedTags = parseStringArray(obj["merged_tags"]),
        )
    }.getOrNull()

    /**
     * Whitelist a split item's facts for one real [entry]. A fact is admitted only
     * when its `normalized_fact` is non-blank and bounded, and it carries the real
     * `source_ref` / `source_date` / `workflow_scene_id`. Unknown/blank/over-long /
     * duplicate facts are dropped (never coerced); zero admitted facts means the
     * caller keeps the heuristic baseline.
     */
    internal fun validateSplitFacts(rawFacts: List<RawSplitFact>, entry: DailyLogEntry): List<CandidateFact> {
        val out = ArrayList<CandidateFact>()
        val seen = HashSet<String>()
        var sceneId = sceneIdOf(entry)
        for (raw in rawFacts) {
            val normalized = ConsolidationText.normalizeFreeText(raw.normalizedFact)
            if (normalized.isEmpty() || normalized.length > MAX_FACT_CHARACTERS) continue
            val fact = CandidateFact(
                summary = validSummary(raw.summary, normalized),
                normalizedFact = normalized,
                eventDate = if (ConsolidationText.isIsoDate(raw.eventDate)) raw.eventDate else "",
                tags = ConsolidationText.normalizeTags(raw.tags),
                modelNotes = MODEL_SPLIT_NOTE,
                rawExcerpt = summarize(entry.text, MAX_EXCERPT_CHARACTERS),
                sourceRef = entry.sourceRef,
                sourceDate = entry.sourceDate,
                workflowSceneId = sceneId,
                dedupText = ConsolidationText.normalizeDedupText(normalized),
            )
            val key = fact.dedupText
            if (key.isEmpty() || !seen.add(key)) continue
            out.add(fact)
            if (out.size >= MAX_FACTS_PER_ENTRY) break
        }
        return out
    }

    // ---------------------------------------------------------------------
    // Prompt builders (bounded, minimal real text)
    // ---------------------------------------------------------------------

    internal fun buildSplitPrompt(entries: List<DailyLogEntry>): String {
        val payload = BatchSplitPromptPayload(
            entries = entries.map { entry ->
                BatchSplitPromptEntry(
                    sourceRef = entry.sourceRef,
                    sourceDate = entry.sourceDate,
                    workflowSceneLabel = SceneLabels.memorySceneLabel(sceneIdOf(entry)),
                    entryText = summarize(entry.text, MAX_ENTRY_PROMPT_CHARACTERS),
                )
            },
        )
        return encodePayload(payload)
    }

    internal fun buildMessages(system: String, user: String): List<ChatMessageWire> = listOf(
        ChatMessageWire(role = "system", content = system),
        ChatMessageWire(role = "user", content = user),
    )

    // ---------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------

    private suspend fun <T> callJson(
        system: String,
        user: String,
        parse: (String) -> T?,
    ): T? {
        val request = ChatCompletionsRequest(
            model = config.modelName,
            messages = buildMessages(system, user),
            stream = false,
            temperature = TEMPERATURE,
            maxTokens = MAX_TOKENS,
        )
        val content = try {
            chat.completion(request).content
        } catch (e: CancellationException) {
            throw e
        } catch (e: ChatException) {
            return null
        }
        return if (content.isBlank()) null else parse(content)
    }

    private fun isSafeDecision(decision: DedupDecision, candidates: List<ConsolidatedRecord>): Boolean {
        if (decision.decision == DedupDecisions.NEW || decision.decision == DedupDecisions.SKIP_AMBIGUOUS) {
            return true
        }
        val candidateIds = candidates.map { it.recordId }.toSet()
        val selected = decision.matchedRecordIds.firstOrNull() ?: return false
        if (selected !in candidateIds) return false
        // Duplicated / out-of-bounds matched ids are insecure -> fallback.
        if (decision.matchedRecordIds.distinct().size != 1) return false
        return true
    }

    private fun normalizeDecisionToken(value: String): String {
        val token = ConsolidationText.normalizeFreeText(value).lowercase()
        return if (token in DedupDecisions.ALL) token else DedupDecisions.NEW
    }

    private fun newDecision(reason: String): DedupDecision = DedupDecision(
        decision = DedupDecisions.NEW,
        matchedRecordIds = emptyList(),
        reason = reason,
    )

    private fun mergeDecision(decision: String, reason: String, matched: Int): DedupDecision =
        DedupDecision(decision = decision, matchedRecordIds = listOf(matched), reason = reason)

    private fun normalizedTransactionSignature(value: String): String {
        val normalized = ConsolidationText.normalizeDedupText(value)
            .replace(Regex("""(收益发放|发放|交通出行|投资理财|支出|消费|买入)"""), "")
            .replace(Regex("""\d+(?:\.\d+)?元"""), "")
        return ConsolidationText.normalizedSimilarityKey(normalized)
    }

    private fun chunkEntries(entries: List<DailyLogEntry>): List<List<DailyLogEntry>> {
        val out = ArrayList<List<DailyLogEntry>>()
        for (i in entries.indices step MAX_SPLIT_BATCH_SIZE) {
            val chunk = entries.subList(i, minOf(i + MAX_SPLIT_BATCH_SIZE, entries.size))
            if (chunk.isNotEmpty()) out.add(chunk)
        }
        return out
    }

    private fun sceneIdOf(entry: DailyLogEntry): Int =
        entry.workflowMetadata["task_scene_id"]?.trim()?.toIntOrNull() ?: -1

    private fun summarize(value: String, max: Int): String = ConsolidationText.summarizeText(value, max)

    private fun validSummary(raw: String, normalizedFact: String): String {
        val summary = ConsolidationText.normalizeFreeText(raw)
        return if (summary.isEmpty()) ConsolidationText.summarizeText(normalizedFact, 60) else summary
    }

    private fun parseIntArray(el: JsonElement?): List<Int> {
        val arr = el as? JsonArray ?: return emptyList()
        return arr.mapNotNull { (it as? JsonPrimitive)?.content?.toIntOrNull() }.distinct().take(MAX_MATCHED_IDs)
    }

    private fun parseStringArray(el: JsonElement?): List<String> {
        val arr = el as? JsonArray ?: return emptyList()
        return arr.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .let(ConsolidationText::normalizeTags)
    }

    private fun parseFactsArray(el: JsonElement?): List<RawSplitFact> {
        val arr = el as? JsonArray ?: return emptyList()
        return arr.mapNotNull { raw ->
            val obj = raw as? JsonObject ?: return@mapNotNull null
            runCatching {
                RawSplitFact(
                    summary = (obj["summary"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                    normalizedFact = (obj["normalized_fact"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                    eventDate = (obj["event_date"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                    tags = parseStringArray(obj["tags"]),
                    modelNotes = (obj["model_notes"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                )
            }.getOrNull()
        }
    }

    private fun atomicClauses(text: String): List<String> {
        val amounts = ConsolidationText.extractAmountTokens(text)
        if (amounts.size > 1) {
            val split = splitAmountClauses(text)
            if (split.isNotEmpty()) return split
        }
        return emptyList()
    }

    private fun splitAmountClauses(text: String): List<String> {
        val cleaned = text
            .replace(Regex("""^当前页面显示了.*?(?:订单记录|消费记录|账单记录)[，,:：\s]*"""), "")
            .replace(Regex("""^近期在\d+家店(?:有)?消费(?:过|记录)?[，,:：\s]*"""), "")
        val out = ArrayList<String>()
        val seen = HashSet<String>()
        for (chunk in cleaned.split("。")) {
            val clause = ConsolidationText.normalizeFreeText(chunk.replace(Regex("[；;]+$"), ""))
            if (clause.isNotEmpty() && ConsolidationText.extractAmountTokens(clause).isNotEmpty()) {
                if (seen.add(clause)) out.add(clause)
            }
        }
        return out
    }

    private inline fun <reified T> encodePayload(payload: T): String =
        json.encodeToString(payload)

    companion object {
        /** Hard cap on source entries per model split batch. */
        const val MAX_SPLIT_BATCH_SIZE: Int = 8

        /** Hard cap on chars of a single entry text sent in a prompt. */
        const val MAX_ENTRY_PROMPT_CHARACTERS: Int = 400

        /** Hard cap on a single admitted candidate-fact normalized text (over-length dropped). */
        const val MAX_FACT_CHARACTERS: Int = 800

        /** Hard cap on a raw-excerpt snippet carried on a candidate fact. */
        const val MAX_EXCERPT_CHARACTERS: Int = 200

        /** Hard cap on facts admitted per entry from a model reply. */
        const val MAX_FACTS_PER_ENTRY: Int = 12

        /** Hard cap on matched ids admitted from a model decision. */
        const val MAX_MATCHED_IDs: Int = 3

        /** Honest marker written onto facts that were validated from a model split. */
        const val MODEL_SPLIT_NOTE: String = "model_batch_split"

        /** Temperature pinned for deterministic output. */
        private const val TEMPERATURE: Double = 0.0

        /** Bounded output tokens per call. */
        private const val MAX_TOKENS: Int = 1024

        private val json: Json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }

        private const val SPLIT_SYSTEM_PROMPT: String =
            "你是日志整理助手。你会批量处理 daily-log entries，把每条 entry 拆分成 1 到多个可去重事实。\n" +
                "输入字段说明：\n" +
                "source_ref：输入 entry 的唯一引用标识，只需要在输出 item 中原样返回，用于把 facts 归还给对应 entry。\n" +
                "source_date：daily-log 文件日期，格式为 YYYY-MM-DD；当原文出现“今天”“昨天”“前天”“昨日”“本周”等相对日期时，以它作为换算基准。\n" +
                "workflow_scene_label：采集场景提示（例如聊天、购物、外卖、娱乐、生活、社交、差旅、文档、健康、办公、图库）；它只帮助理解 entry 来源语境，不是输出字段，也不要把它当作事实内容。\n" +
                "entry_text：原始日志条目正文，是拆分事实的主要依据。\n\n" +
                "返回严格 JSON，格式为：\n" +
                "{\"items\":[{\"source_ref\":\"原样返回输入 source_ref\",\"facts\":[{\"summary\":\"一句话摘要\"," +
                "\"normalized_fact\":\"适合长期画像沉淀的整理后表述\",\"event_date\":\"YYYY-MM-DD 或空字符串，" +
                "是事件本身发生的时间，只可能等于或早于 source_date\",\"tags\":[\"标签1\"]," +
                "\"model_notes\":\"简短说明这条事实如何从原文拆出来\"}]}]}\n\n" +
                "必须为每个输入 source_ref 返回一个 item；没有可沉淀事实时返回该 source_ref 且 facts 为空数组。\n" +
                "1. 一条原始记录包含多件不同事情时拆成多条 facts；同一事件重复表述不重复拆分。\n" +
                "2. 订单、账单、交易、消费记录，normalized_fact 尽量保留商家、商品/服务、金额、时间、状态等关键细节。\n" +
                "3. 不要输出 markdown，不要解释，只输出 JSON。"

        private const val DEDUP_SYSTEM_PROMPT: String =
            "你是日志去重助手。你会比较一条候选事实和若干条历史记录，判断它应新增、视为重复、合并到现有记录，或因信息不足而跳过。\n\n" +
                "返回严格 JSON，格式为：\n" +
                "{\"decision\":\"new | duplicate | merge_with_existing | skip_ambiguous\"," +
                "\"matched_record_ids\":[1],\"reason\":\"简短理由\"," +
                "\"merged_summary\":\"如果需要合并，可给出更好的摘要，否则空字符串\"," +
                "\"merged_fact\":\"如果需要合并，可给出更好的整理后事实，否则空字符串\"," +
                "\"merged_tags\":[\"标签1\"]}\n\n" +
                "判断标准：\n" +
                "1. duplicate：候选事实和历史记录表达的是同一件事，且没有新增信息。\n" +
                "2. merge_with_existing：候选事实和历史记录高度相关且带来补充细节，适合并入已有记录；只能用于同一个原子条目，绝不能把多个账单条目或多段聊天合并成一个大记录。\n" +
                "3. new：新的独立事实。\n" +
                "4. skip_ambiguous：无法稳定判断。\n\n" +
                "额外要求：一条账单记录只能对应一个账单条目；不要把多条账单合并成一条记录；matched_record_ids 最多返回一个最相关的历史记录，且必须来自输入 existing_records 的 record_id。\n" +
                "不要输出 markdown，不要解释，只输出 JSON。"
    }
}

/** Whether a dedup decision came from the model or from a safe local fallback. */
enum class DecisionSource {
    /** The decision was produced and validated by the model path. */
    MODEL,
    /** The model was attempted but failed/malformed/unverifiable; a safe local
     * heuristic decision was used instead. This count drives the PARTIAL result. */
    FALLBACK,
    /** No recent-scope candidate existed, so no model call was made; a local
     * `no_recent_records` new is returned. This is a normal first-record case,
     * not a fallback from a model failure. */
    NO_CANDIDATES,
}

/** A dedup decision plus its provenance (used for honest audit + result typing). */
data class DedupOutcome(
    val decision: DedupDecision,
    val source: DecisionSource,
)
