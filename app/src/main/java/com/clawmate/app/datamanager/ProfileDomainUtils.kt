package com.clawmate.app.datamanager

import com.clawmate.app.datamanager.LocalConsolidationTypes.IndexedEvent
import com.clawmate.app.datamanager.ProfileTypes.Bounds
import com.clawmate.app.datamanager.ProfileTypes.PROFILE_DOMAIN_KEYS

/**
 * Pure, deterministic four-domain projection helpers for the Phase-32 profile
 * foundation. This is a faithful Kotlin port of Harmony `DataManagerUtils.ets`
 * `deriveProfileDomains` / `PROFILE_DOMAIN_RULES`, **reduced to the four
 * persona domains** the Android Home pillar grid renders (`eat`/`wear`/`live`/
 * `travel`).
 *
 * Each event is mapped onto at most those four domains by literal keyword
 * evidence drawn from its real indexed text (summary / normalized fact / tags /
 * sources). The same text that grounds a domain claim here is the only input a
 * model may ever cite; there is no fabrication — an event with no rule evidence
 * maps to no profile domain. Harmony's `work`/`other` domains are deliberately
 * excluded so no relation/office claim can leak into a four-pillar surface.
 */
object ProfileDomainUtils {

    /**
     * Ordered literal keyword rules per domain, ported verbatim from Harmony
     * `PROFILE_DOMAIN_RULES` for the four kept domains. Matching is lowercase,
     * literal substring on the concatenated event text.
     */
    val PROFILE_DOMAIN_RULES: Map<String, List<String>> = mapOf(
        "eat" to listOf(
            "餐饮", "美食", "外卖", "用餐", "餐厅", "早餐", "午餐", "晚餐",
            "火锅", "牛肉面", "拉面", "烤肉", "汉堡", "奶茶", "咖啡", "饮料",
        ),
        "wear" to listOf(
            "服装", "衣服", "裤", "裙", "鞋", "外套", "穿搭", "箱包", "背包", "美妆", "护肤",
        ),
        "live" to listOf(
            "住宿", "酒店", "宾馆", "房租", "住房", "家居", "公寓", "民宿", "充电",
        ),
        "travel" to listOf(
            "交通", "出行", "通勤", "乘车", "网约车", "出租车", "打车", "地铁", "公交",
            "高铁", "火车", "航班", "机票", "旅行", "旅游", "邮轮", "潜水", "瀑布", "景区", "攻略",
        ),
    )

    /** Concatenated lowercase text an event's domain evidence is tested against. */
    fun eventProfileText(event: IndexedEvent): String {
        val parts = ArrayList<String>()
        parts += event.summary
        parts += event.normalizedFact
        parts += event.tags
        parts += event.sources
        parts += event.sourceRefs
        return parts.joinToString(" ").lowercase()
    }

    /** Whether [event] has literal rule evidence for [domain]. */
    fun hasProfileDomainEvidence(event: IndexedEvent, domain: String): Boolean {
        val rules = PROFILE_DOMAIN_RULES.getValue(domain)
        if (rules.isEmpty()) return false
        val text = eventProfileText(event)
        for (rule in rules) {
            if (text.indexOf(rule.lowercase()) >= 0) return true
        }
        return false
    }

    /**
     * Derive the ordered four-domain membership for [event] (port of
     * `deriveProfileDomains`), returning only the four kept domains that carry
     * literal evidence (order-stable). Empty when the event has no four-domain
     * evidence.
     */
    fun deriveProfileDomains(event: IndexedEvent): List<String> {
        val result = ArrayList<String>()
        for (domain in PROFILE_DOMAIN_KEYS) {
            if (hasProfileDomainEvidence(event, domain)) result.add(domain)
        }
        return result
    }

    /** Aggregate a list of events by their derived four-domain membership (order-stable). */
    fun groupByDomain(events: List<IndexedEvent>): Map<String, List<IndexedEvent>> {
        val byDomain = LinkedHashMap<String, MutableList<IndexedEvent>>()
        for (domain in PROFILE_DOMAIN_KEYS) byDomain[domain] = ArrayList()
        for (event in events) {
            for (domain in deriveProfileDomains(event)) {
                byDomain.getOrPut(domain) { ArrayList() }.add(event)
            }
        }
        return byDomain
    }

    /**
     * Deterministic per-domain `input_hash`, mirroring the Harmony
     * `profileDomainInputHash` for the local four-domain path: hashes the domain,
     * the local heuristic fingerprint and each real domain event's bounded text.
     * Used to detect whether a cached profile domain can be reused.
     */
    fun domainInputHash(domain: String, domainEvents: List<IndexedEvent>): String {
        val builder = StringBuilder()
        builder.append(domain).append('\u001e')
        builder.append("short_summary_v1").append('\u001e')
        builder.append("model_unavailable").append('\u001e')
        for (event in domainEvents.sortedWith(compareBy({ it.eventDate }, { it.eventId }))) {
            builder.append('\u001f')
                .append(event.eventId).append('\u001e')
                .append(event.eventDate).append('\u001e')
                .append(ConsolidationText.summarizeText(event.summary, 220)).append('\u001e')
                .append(ConsolidationText.summarizeText(event.normalizedFact, 260)).append('\u001e')
                .append(event.sources.joinToString(",").take(64))
        }
        return sha256(builder.toString())
    }

    /**
     * Stable date range (start..end) over real ISO event dates (port of
     * `eventDateRange`); empty strings when no valid dates exist.
     */
    fun dateRange(events: List<IndexedEvent>): Pair<String, String> {
        val dates = events.map { it.eventDate }.filter { ConsolidationText.isIsoDate(it) }.sorted()
        return if (dates.isEmpty()) "" to "" else dates.first() to dates.last()
    }

    /**
     * Deterministic, single-line domain summary derived only from the real event
     * text (concatenated normalized facts, deduped, bounded). Used for the
     * local-only projection; never fabricates facts the events do not state.
     */
    fun deriveLocalSummary(events: List<IndexedEvent>): String {
        val facts = ArrayList<String>()
        val seen = HashSet<String>()
        for (event in events.sortedWith(compareBy({ it.eventDate }, { it.eventId }))) {
            val text = ConsolidationText.normalizeFreeText(event.normalizedFact.ifEmpty { event.summary })
            if (text.isNotEmpty() && seen.add(text)) facts.add(text)
        }
        var joined = facts.joinToString("；")
        if (joined.length > Bounds.MAX_DOMAIN_SUMMARY_CHARACTERS) {
            joined = joined.substring(0, Bounds.MAX_DOMAIN_SUMMARY_CHARACTERS) + "…"
        }
        return joined
    }

    /** Digest helper (same CRC-free SHA-256 used by the index service). */
    private fun sha256(value: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
