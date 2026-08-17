package com.clawmate.app.datamanager

/**
 * Pure, deterministic text/normalization helpers for the Phase-30 local
 * consolidation + index pipeline. Each function is a faithful Kotlin port of the
 * equivalent Harmony `DataManagerUtils.ets` helper, reduced to the shapes the
 * local (no-cloud-model) path needs. They have **zero Android / network deps** so
 * the whole module is unit-testable on the JVM.
 */
object ConsolidationText {

    /** Whitespace-collapse + trim (port of `normalizeFreeText`). */
    fun normalizeFreeText(value: String): String =
        value.replace(Regex("\\s+"), " ").trim()

    /**
     * Strips leading date / month-name prefixes from a fact so two texts that
     * differ only by their event date dedup together (port of `normalizeDedupText`).
     */
    fun normalizeDedupText(value: String): String =
        normalizeFreeText(value)
            .replace(Regex("""^(\d{4}-\d{2}-\d{2}|\d{1,2}-\d{1,2}号?|\d{1,2}月\d{1,2}日)[:：\s-]*"""), "")

    /** Case/punct-insensitive similarity key (port of `normalizedSimilarityKey`). */
    fun normalizedSimilarityKey(value: String): String =
        normalizeDedupText(value)
            .lowercase()
            .replace(Regex("""[^\w\u4e00-\u9fff]+"""), "")

    /** Bounded summarize (port of `summarizeText`, default max 80). */
    fun summarizeText(value: String, maxLength: Int = 80): String {
        val text = normalizeFreeText(value)
        return if (text.length <= maxLength) text else text.substring(0, maxLength) + "..."
    }

    /** Stable, order-preserving uniqueness (port of `stableUnique`). */
    fun stableUnique(values: List<String>): List<String> {
        val result = ArrayList<String>()
        val seen = HashSet<String>()
        for (value in values) {
            val text = normalizeFreeText(value)
            if (text.isNotEmpty() && seen.add(text)) result.add(text)
        }
        return result
    }

    /** Strict ISO date check (port of `isIsoDate`). */
    fun isIsoDate(value: String): Boolean =
        Regex("""^\d{4}-\d{2}-\d{2}$""").matches(value.trim())

    /** `YYYY-MM` prefix (port of `monthKeyFromDate`); empty when not an ISO date. */
    fun monthKeyFromDate(value: String): String =
        if (isIsoDate(value)) value.trim().substring(0, 7) else ""

    /** Tolerant bounded tag normalization (port of `normalizeTags`, cap 20). */
    fun normalizeTags(values: List<String>): List<String> =
        stableUnique(values.map { normalizeFreeText(it) }).take(20)

    /** Extract `N元` amount tokens, deduped + sorted (port of `extractAmountTokens`). */
    fun extractAmountTokens(value: String): List<String> {
        val pattern = Regex("""\d+(?:\.\d+)?元""")
        val matches = pattern.findAll(value).map { it.value }.toMutableList()
        return stableUnique(matches).sorted()
    }

    /** Whether two token lists are identical as sets (port of `sameStringSet`). */
    fun sameStringSet(left: List<String>, right: List<String>): Boolean =
        left.size == right.size &&
            left.sorted() == right.sorted()

    /** Parse an ISO date to a day-midnight epoch millis (0 when not an ISO date). */
    fun dateToTime(value: String): Long {
        val date = value.trim()
        if (!isIsoDate(date)) return 0L
        return try {
            java.time.LocalDate.parse(date).atStartOfDay(java.time.ZoneOffset.UTC)
                .toInstant().toEpochMilli()
        } catch (t: Throwable) {
            0L
        }
    }
}
