package com.clawmate.app.datamanager

import com.clawmate.app.datamanager.LocalConsolidationTypes.ConsolidatedRecord
import com.clawmate.app.datamanager.LocalConsolidationTypes.ConsolidationResult
import com.clawmate.app.datamanager.LocalConsolidationTypes.ConsolidatedState
import com.clawmate.app.datamanager.LocalConsolidationTypes.ConsolidatedMetadata

/**
 * Deterministic, **no-cloud-model** consolidation engine (a faithful subset port
 * of Harmony `ConsolidationService.consolidateGroup` + the `DataManagerUtils.ets`
 * dedup/creation helpers, reduced to the exact-normalized-dedup path).
 *
 * Everything here is pure and side-effect free: given the bounded [DailyLogEntry]s
 * for one month/file group and the previous persisted [ConsolidatedState], it
 * returns the next [ConsolidatedState] with stable, monotonic `record_id`s, exact +
 * normalized dedup against the recent scope, atomic-style replaces for seen facts,
 * and honestly-bounded record counts. No external model is ever consulted; the
 * model-based decision path is replaced by deterministic exact-normalized matches.
 */
object ConsolidationEngine {

    /** Days of "recent" scope used when matching a new fact against prior records. */
    const val RECENT_MATCH_DAYS: Int = 90

    /** Cap on consolidated records per group (defensive bound). */
    const val MAX_RECORDS_PER_GROUP: Int = 2000

    /** Internal candidate fact produced by the deterministic local split. */
    data class CandidateFact(
        val summary: String,
        val normalizedFact: String,
        val eventDate: String,
        val tags: List<String>,
        val rawExcerpt: String,
        val sourceRef: String,
        val sourceDate: String,
        val dedupText: String,
    )

    /**
     * Consolidate one bounded group of daily-log entries into the next state.
     *
     * @param entries entries for one month/file group (from [DailyLogEventSource]).
     * @param previous the previously persisted state (empty when first scan).
     * @return an honest [ConsolidatedState] — never null; entries with no usable
     *   text produce a state with records unchanged.
     */
    fun consolidate(
        entries: List<DailyLogEntry>,
        previous: ConsolidatedState,
    ): ConsolidatedState {
        // Start from the previous persisted records (tolerant recovery upstream).
        var records = previous.records.toMutableList()
        var nextRecordId = nextRecordId(records)

        // Deterministic local-only split: each real entry text -> bounded facts.
        val pending = entries.filter { ConsolidationText.normalizeFreeText(it.text).isNotEmpty() }
        val facts = ArrayList<CandidateFact>()
        for (entry in pending) {
            facts += heuristicSplit(entry)
        }

        // Exact + normalized dedup against the recent scope, replacing/merging.
        for (fact in facts) {
            val scoped = selectRecentScopeRecords(records, fact)
            val exact = findExactExistingMatch(scoped, fact)
            if (exact != null) {
                updateExistingRecord(exact, fact)
                continue
            }
            // Deterministic normalized match in the recent scope -> merge into the
            // first record whose normalized similarity key collides (prefer richer).
            val normalizedCollision = scoped.firstOrNull {
                val key = normalizedKey(fact.dedupText)
                key.isNotEmpty() && normalizedKeyForRecord(it) == key
            }
            if (normalizedCollision != null) {
                updateExistingRecord(normalizedCollision, fact)
                continue
            }
            records.add(createNewRecord(nextRecordId, fact))
            nextRecordId++
        }

        // Bounded record count guard (defensive — never throws; silently drops the
        // over-limit tail so a hostile daily-log tree cannot balloon the artifact).
        if (records.size > MAX_RECORDS_PER_GROUP) {
            records = records.take(MAX_RECORDS_PER_GROUP).toMutableList()
        }

        records.sortWith(
            compareBy<ConsolidatedRecord> { it.firstSeenDate }
                .thenBy { it.recordId },
        )

        val sourceFiles = ConsolidationText.stableUnique(entries.map { it.fileName }).sorted()
        val sourceDates = ConsolidationText.stableUnique(entries.map { it.sourceDate }).sorted()
        val month = entries.firstOrNull()?.let { ConsolidationText.monthKeyFromDate(it.sourceDate) } ?: ""
        return ConsolidatedState(
            metadata = ConsolidatedMetadata(
                sourceFiles = sourceFiles,
                sourceDates = sourceDates,
                latestRecordIndex = nextRecordId - 1,
                updatedAt = updatedAtNow(),
                totalRecords = records.size,
                month = month,
            ),
            records = records,
        )
    }

    /** Honest run summary for one full scan (across all groups). */
    fun summarize(scan: DailyLogScanResult, states: Map<String, ConsolidatedState>): ConsolidationResult =
        ConsolidationResult(
            sourceEntries = scan.entries.size,
            groupsScanned = states.size,
            groupsWithRecords = states.values.count { it.records.isNotEmpty() },
            totalRecords = states.values.sumOf { it.records.size },
            updatedAt = updatedAtNow(),
        )

    // ---- deterministic local split (port of `heuristicSplit`, atomic heuristics) ----

    private fun heuristicSplit(entry: DailyLogEntry): List<CandidateFact> {
        val text = ConsolidationText.normalizeFreeText(entry.text)
        if (text.isEmpty()) return emptyList()
        val atomic = extractAtomicClauses(text)
        if (atomic.isNotEmpty()) {
            return atomic.map { part ->
                val normalized = ConsolidationText.normalizeFreeText(part)
                CandidateFact(
                    summary = normalized,
                    normalizedFact = normalized,
                    eventDate = entry.sourceDate,
                    tags = emptyList(),
                    rawExcerpt = text,
                    sourceRef = entry.sourceRef,
                    sourceDate = entry.sourceDate,
                    dedupText = ConsolidationText.normalizeDedupText(normalized),
                )
            }
        }
        return listOf(
            CandidateFact(
                summary = ConsolidationText.summarizeText(text, 60).ifEmpty { "未命名记录" },
                normalizedFact = text,
                eventDate = entry.sourceDate,
                tags = emptyList(),
                rawExcerpt = text,
                sourceRef = entry.sourceRef,
                sourceDate = entry.sourceDate,
                dedupText = ConsolidationText.normalizeDedupText(text),
            ),
        )
    }

    /** Port of `extractAtomicClauses`: best-effort clause splitting with guards. */
    private fun extractAtomicClauses(text: String): List<String> {
        val normalized = ConsolidationText.normalizeFreeText(text)
        if (normalized.isEmpty()) return emptyList()
        val amountTokens = ConsolidationText.extractAmountTokens(normalized)
        if (amountTokens.size > 1) {
            val clauses = splitAmountClauses(normalized)
            if (clauses.isNotEmpty()) return clauses
        }
        // Marker-based segment heuristic (mirrors the Harmony marker regex intent).
        val segments = ArrayList<String>()
        val marker = Regex("""(?:账单列表显示了|具体记录有[:：]|记录有[:：]|账单如下[:：])(.*?)(?:。|$)""")
        marker.findAll(normalized).forEach { segments.add(it.groupValues[1]) }
        if (segments.isEmpty() && amountTokens.size <= 1) return emptyList()
        if (segments.isEmpty()) segments.add(normalized)
        return extractSegments(segments)
    }

    private fun extractSegments(segments: List<String>): List<String> {
        val out = ArrayList<String>()
        val seen = HashSet<String>()
        for (segment in segments) {
            val normalizedSegment = segment.replace("以及", "，")
            val parts = normalizedSegment.split(Regex("[，；。]"))
            for (part in parts) {
                val clause = ConsolidationText.normalizeFreeText(part)
                    .replace(Regex("""^(包括|显示了|具体记录有|记录有|账单如下)[:：\s]*"""), "")
                if (clause.isEmpty() || ConsolidationText.extractAmountTokens(clause).isEmpty()) continue
                val key = ConsolidationText.normalizeDedupText(clause)
                if (key.isNotEmpty() && seen.add(key)) out.add(clause)
            }
        }
        return out
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
                val key = ConsolidationText.normalizeDedupText(clause)
                if (key.isNotEmpty() && seen.add(key)) out.add(clause)
            }
        }
        return out
    }

    // ---- recent scope + exact / normalized matching (ports) ----

    private fun selectRecentScopeRecords(records: List<ConsolidatedRecord>, fact: CandidateFact): List<ConsolidatedRecord> {
        val factDate = if (ConsolidationText.isIsoDate(fact.eventDate)) fact.eventDate else fact.sourceDate
        if (!ConsolidationText.isIsoDate(factDate)) return emptyList()
        val factTime = ConsolidationText.dateToTime(factDate)
        if (factTime <= 0L) return emptyList()
        val lowerTime = factTime - (RECENT_MATCH_DAYS - 1) * 86400000L
        return records.filter { record ->
            val t = ConsolidationText.dateToTime(record.firstSeenDate)
            t in (lowerTime + 1)..factTime
        }
    }

    private fun findExactExistingMatch(records: List<ConsolidatedRecord>, fact: CandidateFact): ConsolidatedRecord? {
        val key = normalizedKey(fact.dedupText)
        val amounts = ConsolidationText.extractAmountTokens(fact.dedupText)
        for (record in records) {
            val recordKey = normalizedKeyForRecord(record)
            if (record.sourceRefs.contains(fact.sourceRef) && key.isNotEmpty() && key == recordKey) return record
            if (record.sourceRefs.contains(fact.sourceRef) && amounts.isNotEmpty() &&
                ConsolidationText.sameStringSet(amounts, ConsolidationText.extractAmountTokens(record.dedupText))
            ) return record
        }
        return records.firstOrNull { key.isNotEmpty() && normalizedKeyForRecord(it) == key }
    }

    private fun updateExistingRecord(record: ConsolidatedRecord, fact: CandidateFact): ConsolidatedRecord {
        val sourceDate = fact.sourceDate
        var lastSeen = record.lastSeenDate
        if (sourceDate > lastSeen) lastSeen = sourceDate
        var sourceDates = record.sourceDates.toMutableList()
        if (sourceDates.contains(fact.eventDate).not()) {
            sourceDates.add(fact.eventDate)
            sourceDates.sort()
        }
        var sourceRefs = record.sourceRefs.toMutableList()
        if (sourceRefs.contains(fact.sourceRef).not()) sourceRefs.add(fact.sourceRef)
        var rawExcerpts = record.rawExcerpts.toMutableList()
        if (rawExcerpts.contains(fact.rawExcerpt).not()) rawExcerpts.add(fact.rawExcerpt)
        var normalizedFact = record.normalizedFact
        var summary = record.summary
        if (normalizedFact.isEmpty() || preferRicherFact(normalizedFact, fact.normalizedFact)) {
            normalizedFact = ConsolidationText.normalizeFreeText(fact.normalizedFact)
            summary = ConsolidationText.summarizeText(fact.summary.ifEmpty { fact.normalizedFact })
        }
        var tags = ConsolidationText.stableUnique(record.tags + fact.tags)
        var dedupStatus = record.dedupStatus
        if (dedupStatus != "duplicate") dedupStatus = "merged"
        return record.copy(
            lastSeenDate = lastSeen,
            sourceDates = sourceDates,
            sourceRefs = sourceRefs,
            rawExcerpts = rawExcerpts,
            normalizedFact = normalizedFact,
            summary = summary,
            tags = tags,
            dedupStatus = dedupStatus,
        )
    }

    private fun createNewRecord(nextRecordId: Int, fact: CandidateFact): ConsolidatedRecord =
        ConsolidatedRecord(
            recordId = nextRecordId,
            firstSeenDate = fact.eventDate.ifEmpty { fact.sourceDate },
            lastSeenDate = fact.sourceDate,
            sourceRefs = listOf(fact.sourceRef),
            sourceDates = ConsolidationText.stableUnique(listOf(fact.eventDate.ifEmpty { fact.sourceDate }, fact.sourceDate)).sorted(),
            summary = ConsolidationText.summarizeText(fact.summary.ifEmpty { fact.normalizedFact }),
            normalizedFact = ConsolidationText.normalizeFreeText(fact.normalizedFact),
            rawExcerpts = listOf(fact.rawExcerpt),
            tags = ConsolidationText.stableUnique(fact.tags),
            dedupStatus = "new",
            dedupText = fact.dedupText,
        )

    private fun nextRecordId(records: List<ConsolidatedRecord>): Int {
        var maxId = 0
        for (record in records) if (record.recordId > maxId) maxId = record.recordId
        return maxId + 1
    }

    private fun normalizedKey(value: String): String = ConsolidationText.normalizedSimilarityKey(value)
    private fun normalizedKeyForRecord(record: ConsolidatedRecord): String =
        ConsolidationText.normalizedSimilarityKey(record.dedupText.ifEmpty { record.normalizedFact })

    private fun detailScore(value: String): Int {
        val text = ConsolidationText.normalizeFreeText(value)
        if (text.isEmpty()) return 0
        var score = text.length
        if (text.contains("下单时间")) score += 40
        if (text.contains("订单")) score += 20
        if (Regex("""[“"'].+?[”"']""").containsMatchIn(text)) score += 20
        if (Regex("""\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}""").containsMatchIn(text)) score += 20
        return score
    }

    private fun preferRicherFact(current: String, candidate: String): Boolean {
        val currentText = ConsolidationText.normalizeFreeText(current)
        val candidateText = ConsolidationText.normalizeFreeText(candidate)
        if (candidateText.isEmpty()) return false
        if (currentText.isEmpty()) return true
        return detailScore(candidateText) > detailScore(currentText)
    }

    private fun updatedAtNow(): String {
        val zoned = java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC)
        return zoned.toInstant().toString()
    }
}
