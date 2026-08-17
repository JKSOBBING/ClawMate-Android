package com.clawmate.app.datamanager

import com.clawmate.app.datamanager.LocalConsolidationTypes.EntityMention

/**
 * Pure, deterministic rule/fallback enrichment helpers for the Phase-36 explicit
 * generic batch event-enrichment seam. These are faithful Kotlin ports of the
 * Harmony `DataManagerUtils.ets` surfaces used by
 * `DataPipelineModelClient.enrichEvents` to (a) build the deterministic fallback
 * for every event and (b) merge model output with the deterministic baselines:
 *
 *  - [deriveRuleKeywords] / [deriveRuleDomains] / [deriveRuleEntities] feed both
 *    the fallback enrich and the model-merge baseline, so the model can only add
 *    to (never rewrite) the real deterministic terms;
 *  - [normalizeDomains] enforces the strict `eat|wear|live|travel|work|other`
 *    set and drops `other` whenever a specific domain is present (Harmony parity);
 *  - [weekdayLabelFromDate] maps a real ISO date to its `星期X` label so the
 *    prompt can ask the model to convert relative dates (this is carried in the
 *    prompt only, never used to fabricate an event).
 *
 * All functions are deterministic and network-free so the whole seam is
 * JVM-testable. `DOMAIN_RULES` is Harmony's six-domain map verbatim.
 */
object EnrichRules {

    /** Harmony `DOMAIN_RULES`: literal terms per domain over an event's real text. */
    val DOMAIN_RULES: Map<String, List<String>> = mapOf(
        "eat" to listOf(
            "餐饮", "美食", "外卖", "用餐", "咖啡", "饮料", "餐厅", "食品", "早餐", "午餐", "晚餐",
        ),
        "wear" to listOf("服装", "衣服", "裤", "裙", "鞋", "外套", "穿搭", "箱包", "背包"),
        "live" to listOf("住宿", "酒店", "宾馆", "房租", "住房", "家居", "公寓", "民宿"),
        "travel" to listOf(
            "交通", "出行", "通勤", "乘车", "网约车", "出租车", "打车", "地铁", "公交",
            "高铁", "火车", "航班", "机票", "旅行", "旅游",
        ),
        "work" to listOf("工作", "上班", "办公", "会议", "汇报", "协作", "加班", "出差"),
    )

    /** The six-value domain vocabulary the enrich seam accepts. */
    val DOMAIN_KEYS: List<String> = listOf("eat", "wear", "live", "travel", "work", "other")

    /** Harmony `WEEKDAY_LABELS` (day-of-week 0 = Sunday). */
    private val WEEKDAY_LABELS: List<String> =
        listOf("星期日", "星期一", "星期二", "星期三", "星期四", "星期五", "星期六")

    /** Concatenated lowercase text an event's rules are tested against (Harmony). */
    private fun eventRuleText(event: LocalConsolidationTypes.IndexedEvent): String {
        val summary = ConsolidationText.normalizeFreeText(event.summary)
        val fact = ConsolidationText.normalizeFreeText(event.normalizedFact)
        return if (fact.isNotEmpty()) "$summary $fact" else summary
    }

    /**
     * Port of `deriveKeywordsFromText`: collects DOMAIN_RULES terms present in the
     * text, ASCII/alnum tokens longer than 1 char, and bounded CJK/phrase clauses
     * (2..18 chars, excluding known low-value openers), then dedupes and caps at 30.
     */
    fun deriveRuleKeywords(event: LocalConsolidationTypes.IndexedEvent): List<String> {
        val text = eventRuleText(event)
        if (text.isEmpty()) return emptyList()
        val values = ArrayList<String>()
        val lower = text.lowercase()
        for ((_, terms) in DOMAIN_RULES) {
            for (term in terms) {
                if (lower.contains(term.lowercase())) values.add(term)
            }
        }
        val tokenPattern = Regex("""[A-Za-z][A-Za-z0-9_.-]*|\d+(?:\.\d+)?元?""")
        for (m in tokenPattern.findAll(text)) {
            val token = m.value
            if (token.length > 1) values.add(token)
        }
        val phrases = text.split(Regex("""[，。；、：:（）()“”\"'\s]+"""))
        for (phrase in phrases) {
            val item = ConsolidationText.normalizeFreeText(phrase)
            if (item.length in 2..18 &&
                !item.startsWith("当前页面") &&
                !item.startsWith("这张截图")
            ) {
                values.add(item)
            }
        }
        return ConsolidationText.stableUnique(values).take(30)
    }

    /**
     * Port of `deriveRuleDomains`: the order-stable set of DOMAIN_RULES domains
     * with literal text evidence; falls back to `['other']` when none matches.
     */
    fun deriveRuleDomains(event: LocalConsolidationTypes.IndexedEvent): List<String> {
        val text = eventRuleText(event).lowercase()
        val result = ArrayList<String>()
        for ((domain, terms) in DOMAIN_RULES) {
            if (terms.any { text.contains(it.lowercase()) }) result.add(domain)
        }
        return if (result.isNotEmpty()) ConsolidationText.stableUnique(result) else listOf("other")
    }

    /**
     * Port of Harmony `normalizeDomains`: filters to the strict six-value set,
     * dedupes, and drops `other` whenever any specific domain is present.
     */
    fun normalizeDomains(raw: List<String>): List<String> {
        var result = ConsolidationText.stableUnique(raw.map { ConsolidationText.normalizeFreeText(it).lowercase() })
            .filter { it in DOMAIN_KEYS }
        if (result.size > 1 && result.contains("other")) {
            result = result.filter { it != "other" }
        }
        return result
    }

    /** Concatenated text used for deterministic entity extraction (Harmony parity). */
    fun ruleEntityText(event: LocalConsolidationTypes.IndexedEvent): String = eventRuleText(event)

    /**
     * Port of Harmony `weekdayLabelFromDate`: `星期X` for a real ISO date, or
     * empty when the date is not a valid ISO `YYYY-MM-DD`.
     */
    fun weekdayLabelFromDate(value: String): String {
        val date = value.trim()
        if (!ConsolidationText.isIsoDate(date)) return ""
        return try {
            val local = java.time.LocalDate.parse(date)
            WEEKDAY_LABELS[local.dayOfWeek.value % 7] // dayOfWeek 1=Mon..7=Sun -> 星期一..星期日
        } catch (t: Throwable) {
            ""
        }
    }

    /** Order-stable unique list of scene labels for real scene ids (Harmony `enrichWorkflowSceneLabels`). */
    fun workflowSceneLabels(sceneIds: List<Int>): List<String> =
        ConsolidationText.stableUnique(sceneIds.map(SceneLabels::memorySceneLabel).filter { it.isNotEmpty() })
}
