package com.clawmate.app.datamanager

import com.clawmate.app.chat.cloud.ChatClient
import com.clawmate.app.chat.cloud.ChatCompletionsRequest
import com.clawmate.app.chat.cloud.ChatException
import com.clawmate.app.chat.cloud.ChatMessageWire
import com.clawmate.app.datamanager.ProfileTypes.ProfileDomainState

/**
 * Phase-32 **model claims** client. Converses with the existing shared
 * OpenAI-compatible [ChatCompletionsClient] (via the [ChatClient] contract) to
 * generate per-domain profile claims from **real indexed events**, then strictly
 * validates every returned claim (unknown domain / unknown evidence id /
 * over-length / low-confidence / malformed JSON are all dropped, never coerced).
 *
 * Safety boundaries:
 *  - **Zero network unless configured and there is real event evidence.** The
 *    caller (or [ProfileModelClient.generateDomain]) short-circuits to a typed
 *    local result whenever [ProfileModelConfig] is null or the domain has no
 *    real events — no request is ever sent.
 *  - **Only bounded real event text.** Each prompt carries only the domain's real
 *    indexed events (bounded summaries/facts + ids); never a URI, image, token or
 *    unrelated local data.
 *  - **Independent JSON decode + whitelist + evidence reference.** Output is
 *    decoded by [ProfileModelValidator], claims are admitted only when they
 *    reference real domain event ids and pass every bound.
 */
class ProfileModelClient(
    internal val chat: ChatClient,
    internal val config: ProfileModelConfig,
) : ProfileIndexRunner.DomainModelGateway {

    /**
     * Gateway contract for the profile runner: generate strictly-admitted claims
     * for one domain against its real events. Returns a typed [DomainUpgrade]
     * carrying the model result or the preserved deterministic local projection.
     */
    override suspend fun generate(
        events: List<com.clawmate.app.datamanager.LocalConsolidationTypes.IndexedEvent>,
        local: ProfileDomainState,
    ): DomainUpgrade = generateDomain(local.domain, events, local)

    /**
     * Generate the strict-admitted claims for one domain. Returns a typed domain
     * upgrade (claims + summary + shortSummary when the model path succeeds) or
     * a [DomainUpgrade.local] fallback carrying the deterministic local
     * projection when the domain has no events / model output is empty / the
     * call fails. Never throws to the caller; the honest local result is always
     * preserved.
     */
    suspend fun generateDomain(
        domain: String,
        domainEvents: List<com.clawmate.app.datamanager.LocalConsolidationTypes.IndexedEvent>,
        local: ProfileDomainState,
    ): DomainUpgrade {
        if (domainEvents.isEmpty()) {
            return DomainUpgrade.local(local)
        }
        val content = try {
            chat.completion(
                ChatCompletionsRequest(
                    model = config.modelName,
                    messages = buildMessages(domain, domainEvents),
                    stream = false,
                    temperature = 0.2,
                    maxTokens = 1024,
                ),
            ).content
        } catch (e: ChatException) {
            return DomainUpgrade.modelError(local, e)
        }
        val payload = ProfileModelValidator.decodePayload(content)
        val rawClaims = payload?.claims.orEmpty()
        val realIds = domainEvents.map { it.eventId }.toSet()
        val (valid, _) = ProfileModelValidator.validateClaims(rawClaims, realIds)
        if (!ProfileModelValidator.hasUsableClaims(valid)) {
            return DomainUpgrade.local(local, fromModel = true)
        }
        val claims = valid.map { raw ->
            val ids = raw.eventIds
            val evidence = domainEvents.filter { it.eventId in ids }
            val exampleText = raw.example.takeIf { it.isNotBlank() }
            com.clawmate.app.datamanager.ProfileTypes.ProfileClaim(
                statement = raw.statement,
                kind = raw.kind,
                confidence = raw.confidence,
                eventIds = ids,
                evidenceCount = ids.size,
                startDate = evidence.minOfOrNull { it.eventDate } ?: local.startDate,
                endDate = evidence.maxOfOrNull { it.eventDate } ?: local.endDate,
                example = if (exampleText != null) ConsolidationText.normalizeFreeText(exampleText) else "",
            )
        }
        val summary = (payload?.summary?.let(ConsolidationText::normalizeFreeText)).orEmpty()
        val shortSummary = boundedShortSummary(payload?.shortSummary.orEmpty())
        return DomainUpgrade.Model(claims = claims, summary = summary, shortSummary = shortSummary)
    }

    /** Builder of the two-message prompt (system + bounded user JSON of real events). */
    internal fun buildMessages(
        domain: String,
        events: List<com.clawmate.app.datamanager.LocalConsolidationTypes.IndexedEvent>,
    ): List<ChatMessageWire> = listOf(
        ChatMessageWire(role = "system", content = PROFILE_SYSTEM_PROMPT),
        ChatMessageWire(role = "user", content = buildUserPrompt(domain, events)),
    )

    /** Bounded user JSON payload carrying only real event text + ids. */
    internal fun buildUserPrompt(
        domain: String,
        events: List<com.clawmate.app.datamanager.LocalConsolidationTypes.IndexedEvent>,
    ): String {
        val trimmed = events.take(MAX_EVENTS_PER_CALL)
        val items = trimmed.map { event ->
            ProfilePromptEvent(
                event_id = event.eventId,
                date = event.eventDate,
                summary = ConsolidationText.summarizeText(event.summary, 100),
                normalized_fact = ConsolidationText.summarizeText(event.normalizedFact, 200),
            )
        }
        val payload = ProfilePromptPayload(
            domain = domain,
            events = items,
        )
        return prettyJson.encodeToString(ProfilePromptPayload.serializer(), payload)
    }

    private fun boundedShortSummary(value: String): String {
        val text = ConsolidationText.normalizeFreeText(value)
        return if (text.length <= SHORT_SUMMARY_MAX) text else text.substring(0, SHORT_SUMMARY_MAX)
    }

    companion object {
        /** Cap on real events sent per model call. */
        const val MAX_EVENTS_PER_CALL: Int = 40

        /** Cap on the short (4-ish) summary token. */
        const val SHORT_SUMMARY_MAX: Int = 48

        private val prettyJson: kotlinx.serialization.json.Json =
            kotlinx.serialization.json.Json { prettyPrint = true }
    }
}

/** Typed per-domain upgrade result from the model path. */
sealed interface DomainUpgrade {
    /** Admitted model claims + optional summaries; generation = "model". */
    data class Model(
        val claims: List<com.clawmate.app.datamanager.ProfileTypes.ProfileClaim>,
        val summary: String,
        val shortSummary: String,
    ) : DomainUpgrade {
        fun toState(local: ProfileDomainState): ProfileDomainState = local.copy(
            claims = claims,
            summary = summary,
            shortSummary = shortSummary,
            generation = if (claims.isEmpty()) "model_empty" else "model",
        )
    }

    /** The deterministic local projection was preserved. */
    data class Local(
        val state: ProfileDomainState,
        val fromModelFailure: Boolean,
    ) : DomainUpgrade

    companion object {
        fun local(state: ProfileDomainState, fromModel: Boolean = false): Local = Local(state, fromModel)
        fun modelError(state: ProfileDomainState, e: ChatException): Local = Local(state, true)
    }
}

/** Wire prompt payload mirroring the Harmony profile prompt input. */
@kotlinx.serialization.Serializable
internal data class ProfilePromptPayload(
    val domain: String,
    val events: List<ProfilePromptEvent>,
)

@kotlinx.serialization.Serializable
internal data class ProfilePromptEvent(
    val event_id: String,
    val date: String,
    val summary: String,
    val normalized_fact: String,
)

/** Pinned system prompt, adapted from Harmony `DATA_MANAGER_PROFILE_SYSTEM_PROMPT`. */
internal const val PROFILE_SYSTEM_PROMPT: String =
    "你是用户画像总结助手。输入是一组同一领域的个人事件。\n" +
        "请仅依据这些事件生成适合 App 前端展示的凝练用户画像，返回严格 JSON：\n" +
        "{\"short_summary\":\"严格4个中文字符的首页摘要\",\"summary\":\"该领域1-2句话的高层画像总结\"," +
        "\"claims\":[{\"statement\":\"一条短 insight，不要逐条复述事实\",\"kind\":\"preference|habit|topic|service|relation|place|observation\"," +
        "\"confidence\":\"high|medium|low\",\"event_ids\":[\"支持该结论的输入事件id\"],\"example\":\"一个最能说明该结论的事件摘要\"}]}。\n" +
        "claims 只保留 2 到 5 条高层 insight，每条必须由至少 2 条不同输入事件直接支持。\n" +
        "不要推断职业、收入、性格、身份等事件没有证明的信息；不要写金额、订单状态、具体日期或'事件 id'。\n" +
        "event_ids 必须来自输入事件，不要编造 id。每个领域最多输出 5 条；没有足够证据时返回空 summary 和空 claims。\n" +
        "不要输出 markdown 或解释。";
