package com.clawmate.app.datamanager

import com.clawmate.app.chat.cloud.ChatClient
import com.clawmate.app.chat.cloud.ChatCompletionsRequest
import com.clawmate.app.chat.cloud.ChatException
import com.clawmate.app.chat.cloud.ChatMessageWire
import com.clawmate.app.datamanager.BatchEventEnrichTypes.EnrichedEventFields
import com.clawmate.app.datamanager.LocalConsolidationTypes.EntityMention
import com.clawmate.app.datamanager.LocalConsolidationTypes.IndexedEvent
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
 * Phase-36 **explicit generic batch event-enrichment** model client. It converses
 * with the existing shared OpenAI-compatible [ChatClient] (the same backend the
 * chat screen, Home planner, Phase-34 consolidation and Phase-35 entity
 * enrichment use) to extract keywords / domains / entities / todo for a bounded
 * set of **real** indexed events — then **strictly whitelists** every reply
 * against the real event-id scope and merges it with the deterministic rule
 * baselines (Harmony `enrichEventFromPayload` parity).
 *
 * Safety boundaries:
 *  - **Zero network unless configured + there are real events.** The
 *    [BatchEventEnricher] short-circuits to a deterministic local enrich before
 *    this client is ever called ([BatchEventEnrichGate]).
 *  - **Serial, bounded, cancellable.** One request at a time; a hard
 *    prompt-character cap; chunked at [BATCH_EVENTS_PER_REQUEST]; coroutine
 *    cancellation propagates (a cancelled call rethrows [CancellationException]).
 *  - **Minimal real text only.** Each prompt carries only bounded, desensitized
 *    event text (event id + date + weekday + bounded scene labels + bounded
 *    summary / normalized fact) — never a URI, external path, image, token or
 *    unrelated field.
 *  - **Strict JSON + real-id whitelist.** Every reply `event_id` must resolve to
 *    a **real** indexed event in the injected scope; unknown / duplicate /
 *    out-of-bounds ids are dropped. Keywords/domains/entities/todo are
 *    normalized to the strict vocabularies and merged with the deterministic rule
 *    baseline so the model can never invent a new event or overwrite the real
 *    event's identity.
 */
class BatchEventEnrichModelClient(
    private val chat: ChatClient,
    private val config: ConsolidationModelConfig,
) {

    /**
     * Enrich the bounded [events]. [realEventIds] is the whitelist of the current
     * scope. Returns a map keyed by **real** event id of the strictly-validated,
     * bounded enrich fields; an empty map on any failure / malformed JSON / no
     * admitted event (never throws).
     */
    suspend fun enrich(
        events: List<IndexedEvent>,
        realEventIds: Set<String>,
    ): Map<String, EnrichedEventFields> {
        if (events.isEmpty() || realEventIds.isEmpty()) return emptyMap()
        val result = LinkedHashMap<String, EnrichedEventFields>()
        for (chunk in chunkEvents(events)) {
            val mapped = chunk.associateBy { ConsolidationText.normalizeFreeText(it.eventId) }
                .filterKeys { it.isNotEmpty() }
            if (mapped.isEmpty()) continue
            val prompt = buildPrompt(chunk)
            if (prompt.isEmpty()) continue
            val content = try {
                chat.completion(
                    ChatCompletionsRequest(
                        model = config.modelName,
                        messages = listOf(
                            ChatMessageWire(role = "system", content = systemPrompt(chunk)),
                            ChatMessageWire(role = "user", content = prompt),
                        ),
                        stream = false,
                        temperature = 0.0,
                        maxTokens = MAX_TOKENS,
                    ),
                ).content
            } catch (e: CancellationException) {
                throw e
            } catch (e: ChatException) {
                continue
            }
            if (content.isBlank()) continue
            for (raw in parseReply(content)) {
                val id = ConsolidationText.normalizeFreeText(raw.eventId)
                if (id.isEmpty() || id !in realEventIds) continue // unknown id dropped
                val event = mapped[id] ?: continue
                val fields = buildEnrichedFields(event, raw)
                if (fields != null) result[id] = fields
            }
        }
        return result
    }

    /**
     * Build the bounded, strictly-validated [EnrichedEventFields] for one real
     * [event] from a raw model reply, merging model terms with the deterministic
     * rule baseline (Harmony `enrichEventFromPayload`). Returns null when the
     * reply carried no admissible evidence for this event (the caller keeps the
     * deterministic local enrich).
     */
    internal fun buildEnrichedFields(event: IndexedEvent, raw: RawEventWire): EnrichedEventFields? {
        val keywords = ConsolidationText.stableUnique(
            raw.keywords + EnrichRules.deriveRuleKeywords(event),
        ).take(BatchEventEnrichTypes.MAX_KEYWORDS_PER_EVENT)

        val ruleDomains = EnrichRules.deriveRuleDomains(event)
        var domains = EnrichRules.normalizeDomains(raw.domains + ruleDomains)
        if (domains.isEmpty()) domains = listOf("other")

        val ruleEntities = SceneEntityNormalizer.deriveRuleEntities(EnrichRules.ruleEntityText(event))
        val entities = SceneEntityNormalizer.normalizeEntityMentions(
            raw.entities + ruleEntities,
        ).take(BatchEventEnrichTypes.MAX_ENTITIES_PER_EVENT)

        val todo = normalizeTodo(raw.todo, event)

        // As long as at least one bounded field was produced from the model, we
        // admit this event. (rule baseline alone would produce an empty model result.)
        val hasModelEvidence = raw.keywords.isNotEmpty() ||
            raw.domains.isNotEmpty() || raw.entities.isNotEmpty() || raw.todo != null
        if (!hasModelEvidence) return null

        return EnrichedEventFields(
            eventId = event.eventId,
            keywords = keywords,
            domains = domains,
            entities = entities,
            todoType = todo.type,
            todoDeadline = todo.deadline,
            todoText = todo.text,
            todoAgentTask = todo.agentTask,
            enrichmentMethod = MODEL_METHOD,
        )
    }

    // ---------------------------------------------------------------------
    // Prompt builders (bounded, minimal real text, no secrets)
    // ---------------------------------------------------------------------

    internal fun buildPrompt(events: List<IndexedEvent>): String {
        if (events.isEmpty()) return ""
        val payload = PromptEvents(
            events = events.map { event ->
                PromptEnrichEvent(
                    event_id = event.eventId,
                    event_date = event.eventDate,
                    event_weekday = EnrichRules.weekdayLabelFromDate(event.eventDate),
                    workflow_scene_labels = EnrichRules.workflowSceneLabels(event.workflowSceneIds),
                    summary = ConsolidationText.summarizeText(event.summary, SummaryMax),
                    normalized_fact = ConsolidationText.summarizeText(event.normalizedFact, FactMax),
                )
            },
        )
        val encoded = json.encodeToJsonElement(PromptEvents.serializer(), payload).toString()
        return if (encoded.length <= MAX_PROMPT_CHARACTERS) encoded else ""
    }

    /** The batch-enrich system prompt with a per-chunk todo-type guide (Harmony parity). */
    internal fun systemPrompt(events: List<IndexedEvent>): String {
        val labels = EnrichRules.workflowSceneLabels(events.flatMap { it.workflowSceneIds })
        val guide = todoGuideForLabels(labels)
        return BATCH_ENRICH_SYSTEM_PROMPT + guide
    }

    private fun todoGuideForLabels(labels: List<String>): String {
        val guides = ArrayList<String>()
        guides.add("type 表示待办事项类型，按 workflow_scene_labels 使用以下提取规则：")
        if (labels.isEmpty()) {
            guides.add("未知场景：只在行动责任明确时使用 other；不要因为普通信息记录生成 todo。")
            return guides.joinToString("")
        }
        if (labels.contains("聊天")) {
            guides.add(
                "聊天：提取两类待办。第一类是有明确时间锚点的未来计划/约定/聚餐/出行/会议/到期/必须回复时间，" +
                    "此时 todo.type 写空字符串，只填 deadline 和 text，没有可换算日期时写空 todo。" +
                    "第二类是用户明确表达要购买某商品或让对方帮忙购买，允许 type=buy，deadline 可以为空，text 写购买行动，" +
                    "agent_task 写可直接执行的 GUI Agent 任务；遇到“周末”且未说明周几时，以 event_date 之后最近的周六作为 deadline。" +
                    "text 和 agent_task 只描述该待办本身。",
            )
        }
        if (labels.contains("购物")) guides.add("购物：允许 type=buy|pay|pickup|other。")
        if (labels.contains("外卖")) guides.add("外卖：允许 type=order|pay|pickup|other。")
        if (labels.contains("娱乐")) guides.add("娱乐：允许 type=renew|book|other。")
        if (labels.contains("生活")) guides.add("生活：允许 type=pay|repair|appointment|other。")
        if (labels.contains("社交")) guides.add("社交：允许 type=attend|plan|book|other。")
        if (labels.contains("差旅")) guides.add("差旅：允许 type=book|depart|check_in|pay|reimburse|other。")
        if (labels.size > 1) guides.add("如果事件同时属于多个场景，优先使用最具体的非聊天场景规则；聊天规则只用于纯聊天场景。")
        guides.add("不要输出规则外的 type。")
        return guides.joinToString("")
    }

    // ---------------------------------------------------------------------
    // Strict JSON decode + whitelist
    // ---------------------------------------------------------------------

    internal fun parseReply(text: String): List<RawEventWire> = runCatching {
        val root = json.parseToJsonElement(text)
        val obj = root as? JsonObject ?: return@runCatching emptyList()
        val rawList = (obj["events"] as? JsonArray) ?: return@runCatching emptyList()
        rawList.mapNotNull { el ->
            val e = el as? JsonObject ?: return@mapNotNull null
            RawEventWire(
                eventId = (e["event_id"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                keywords = normalizeStringList(e["keywords"]),
                domains = normalizeStringList(e["domains"]),
                entities = normalizeEntityArray(e["entities"]),
                todo = parseTodo(e["todo"]),
            )
        }
    }.getOrNull().orEmpty()

    private fun parseTodo(el: JsonElement?): RawTodoWire? {
        val obj = el as? JsonObject ?: return null
        return RawTodoWire(
            type = (obj["type"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            deadline = (obj["deadline"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            text = (obj["text"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            agentTask = (obj["agent_task"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
        )
    }

    private fun normalizeStringList(el: JsonElement?): List<String> {
        val arr = el as? JsonArray ?: return emptyList()
        return arr.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .map(ConsolidationText::normalizeFreeText)
            .filter { it.isNotEmpty() }
            .distinct()
    }

    private fun normalizeEntityArray(el: JsonElement?): List<EntityMention> {
        val arr = el as? JsonArray ?: return emptyList()
        val out = ArrayList<EntityMention>()
        for (raw in arr) {
            val obj = raw as? JsonObject ?: continue
            val type = ConsolidationText.normalizeFreeText((obj["type"] as? JsonPrimitive)?.contentOrNull.orEmpty()).lowercase()
            val name = SceneEntityNormalizer.normalizeEntityName((obj["name"] as? JsonPrimitive)?.contentOrNull.orEmpty())
            val aliases = normalizeStringList(obj["aliases"])
            if (type in SceneEntityNormalizer.ENTITY_TYPE_KEYS && name.isNotEmpty()) {
                out.add(EntityMention(type = type, name = name, aliases = aliases))
            }
        }
        return out
    }

    /**
     * Normalize a raw todo against strict vocabularies + the event scene gate
     * (Harmony `normalizeTodo` / `normalizeAgentTask` / `shouldKeepTodoAgentTask`).
     */
    private fun normalizeTodo(raw: RawTodoWire?, event: IndexedEvent): NormalizedTodo {
        if (raw == null) return NormalizedTodo.EMPTY
        val type = normalizeTodoType(raw.type)
        val deadline = if (ConsolidationText.isIsoDate(raw.deadline)) raw.deadline.trim() else ""
        val text = ConsolidationText.normalizeFreeText(raw.text)
            .takeIf { it.length <= BatchEventEnrichTypes.MAX_TOKEN_CHARACTERS }.orEmpty()
        val agentTask = normalizeAgentTask(raw.agentTask, type, event)
        if (type.isEmpty() && deadline.isEmpty()) return NormalizedTodo.EMPTY
        return NormalizedTodo(type = type, deadline = deadline, text = text, agentTask = agentTask)
    }

    private fun normalizeTodoType(value: String): String {
        val type = ConsolidationText.normalizeFreeText(value).lowercase()
        return if (type in BatchEventEnrichTypes.TODO_TYPE_ALLOWED) type else ""
    }

    /** Port of Harmony `shouldKeepTodoAgentTask`: only pure-chat `buy` keeps an agent task. */
    private fun normalizeAgentTask(value: String, type: String, event: IndexedEvent): String {
        val labels = EnrichRules.workflowSceneLabels(event.workflowSceneIds)
        if (!(type == "buy" && labels.size == 1 && labels.first() == "聊天")) return ""
        return ConsolidationText.normalizeFreeText(value)
            .takeIf { it.length <= BatchEventEnrichTypes.MAX_TOKEN_CHARACTERS }.orEmpty()
    }

    private fun chunkEvents(events: List<IndexedEvent>): List<List<IndexedEvent>> {
        val out = ArrayList<List<IndexedEvent>>()
        for (i in events.indices step BATCH_EVENTS_PER_REQUEST) {
            val chunk = events.subList(i, minOf(i + BATCH_EVENTS_PER_REQUEST, events.size))
            if (chunk.isNotEmpty()) out.add(chunk)
        }
        return out
    }

    // ---------------------------------------------------------------------
    // Wire shapes (independent of on-disk codec)
    // ---------------------------------------------------------------------

    @Serializable
    private data class PromptEvents(val events: List<PromptEnrichEvent> = emptyList())

    @Serializable
    private data class PromptEnrichEvent(
        val event_id: String = "",
        val event_date: String = "",
        val event_weekday: String = "",
        val workflow_scene_labels: List<String> = emptyList(),
        val summary: String = "",
        val normalized_fact: String = "",
    )

    internal data class RawEventWire(
        val eventId: String = "",
        val keywords: List<String> = emptyList(),
        val domains: List<String> = emptyList(),
        val entities: List<EntityMention> = emptyList(),
        val todo: RawTodoWire? = null,
    )

    internal data class RawTodoWire(
        val type: String = "",
        val deadline: String = "",
        val text: String = "",
        val agentTask: String = "",
    )

    internal data class NormalizedTodo(
        val type: String = "",
        val deadline: String = "",
        val text: String = "",
        val agentTask: String = "",
    ) {
        companion object {
            val EMPTY = NormalizedTodo()
        }
    }

    companion object {
        /** Hard cap on events per model batch request (Harmony `BATCH_ENRICH_REQUEST_SIZE`). */
        const val BATCH_EVENTS_PER_REQUEST: Int = 8

        /** Hard cap on one prompt user payload in characters. */
        const val MAX_PROMPT_CHARACTERS: Int = 32_000

        /** Bounded per-event summary/fact prompt widths. */
        private const val SummaryMax: Int = 200
        private const val FactMax: Int = 320

        /** Honest provenance marker for model-admitted enrich fields. */
        const val MODEL_METHOD: String = "model_batch"

        private val json: Json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }

        internal const val MAX_TOKENS: Int = 1600

        private const val BATCH_ENRICH_SYSTEM_PROMPT: String =
            "你是本地个人数据索引助手。请批量为输入事件提取关键词、领域、实体和可能的待办事项信号。\n" +
                "输入字段说明：event_id 只需原样返回；event_date 是事件发生日期 YYYY-MM-DD；" +
                "event_weekday 是该日期对应的星期，用于把“这周六”“下周一”“周末”等相对日期换算为绝对日期；" +
                "workflow_scene_labels 是采集场景提示（聊天/购物/外卖/娱乐/生活/社交/差旅/文档/健康/办公/图库），只帮助判断待办动作类型，不是 domains；" +
                "summary 是事件短摘要；normalized_fact 是整理后的完整事实，抽取关键词/领域/实体/待办时优先参考它。\n" +
                "输出必须是严格 JSON，不要输出 markdown 或解释：" +
                "{\"events\":[{\"event_id\":\"原样返回\",\"keywords\":[\"关键词\"],\"domains\":[\"eat|wear|live|travel|work|other\"]," +
                "\"entities\":[{\"type\":\"person|place|merchant\",\"name\":\"名称\",\"aliases\":[\"别名\"]}]," +
                "\"todo\":{\"type\":\"场景允许的 action tag 或空字符串\",\"deadline\":\"YYYY-MM-DD 或空字符串\"," +
                "\"text\":\"触发待办的具体行动内容或空字符串\",\"agent_task\":\"可直接交给 GUI Agent 执行的任务描述或空字符串\"}}]}。\n" +
                "必须为每个输入 event_id 返回一个结果。\n" +
                "keywords 规则：提取具体、可检索的商品、服务、商户、地点、人物、应用、动作、状态、主题词或金额词；" +
                "优先保留输入原词；避免“当前页面”“截图”“记录”“信息”“详情”等泛词。\n" +
                "domains 规则：只能从 eat|wear|live|travel|work|other 中多选；购物、聊天、外卖、娱乐、生活、社交、差旅是 workflow_scene_labels，不要直接当作 domains；" +
                "如果在 eat/wear/live/travel/work 中没有 90% 以上把握明确归类，必须仅输出 other。\n" +
                "entities 规则：只抽取现实对象。person 只用于真实联系人/聊天对象/明确人物姓名；place 只用于实际地点/地址/场馆/目的地；" +
                "merchant 只用于商户/店铺/平台/品牌门店/服务商。不要把商品、服务类别、金额、日期、订单号、抽象话题、页面元素、按钮、状态词或泛化类别抽成实体。" +
                "name 和 aliases 必须来自输入文本，aliases 只放同一现实对象在输入中出现过的其他写法。\n" +
                "todo 规则：todo 只在事件明确包含用户未来可能需要处理的行动/计划/到期/预约/购买/退款/售后/出行/参与/后续事项时填写；" +
                "对常规的浏览、购买、账单、普通聊天等不关联任何未来待办的事件，必须把 todo 的四个字段都写空。" +
                "deadline 表示待办截止日期：有明确绝对日期直接填；相对日期以该事件的 event_date 和 event_weekday 为基准换算；" +
                "没有明确截止日期但有明确待办动作时 deadline 写空字符串。" +
                "text 只描述触发 todo 的具体行动，不要把同事件里的其他分享/技术讨论/页面状态混进去。" +
                "agent_task 只在纯聊天场景且 type=buy 时填写；其他情况必须为空。"
    }
}
