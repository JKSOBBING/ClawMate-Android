package com.clawmate.app.datamanager

import com.clawmate.app.datamanager.CloudConsolidationTypes.CandidateFact
import com.clawmate.app.datamanager.CloudConsolidationTypes.DedupDecision
import com.clawmate.app.datamanager.CloudConsolidationTypes.DedupDecisions
import com.clawmate.app.datamanager.LocalConsolidationTypes.ConsolidatedMetadata
import com.clawmate.app.datamanager.LocalConsolidationTypes.ConsolidatedRecord
import com.clawmate.app.datamanager.LocalConsolidationTypes.ConsolidatedState

/** The splitter produces a per-`source_ref` list of bounded candidate facts. */
internal typealias ConsolidationFactSplitter = suspend (List<DailyLogEntry>) -> Map<String, List<CandidateFact>>

/** The decider returns a dedup outcome whose source (MODEL vs FALLBACK) is tracked. */
internal typealias ConsolidationFeatureDecider =
    suspend (CandidateFact, List<ConsolidatedRecord>) -> DedupOutcome

/**
 * Pure, side-effect-free **model-aware** consolidation applier for one month/file
 * group (a faithful subset port of Harmony
 * `ConsolidationService.consolidateGroup`, with the model split/dedup wired
 * through an injectable seam). It is the only place that turns pending source
 * entries + the previous persisted state into the next [ConsolidatedState] for
 * the Phase-34 model path:
 *
 *  - pending entries are those whose stable processing hash changed or that were
 *    never processed (mirrors Harmony `processed_entry_hashes` + record
 *    `source_refs` skip), so already-model-processed entries are **not** re-sent;
 *  - each pending entry is split into bounded facts; each fact is checked for an
 *    exact recent-scope match first, then (if the model is present) a strict
 *    dedup decision is applied; the decision's matched id must resolve to a real
 *    record in the current scope — a hostile/unverifiable decision falls back to
 *    the safe deterministic local outcome;
 *  - `merge_with_existing` uses only a genuine single atomic match, `new` creates
 *    a record with the next stable monotonic `record_id`, `skip_ambiguous` drops
 *    the fact; decisions are written to `model_notes` for audit.
 *
 * Nothing in here touches the network or disk; both are the repository's job.
 */
object ModelConsolidationApplier {

    /** Days of "recent" scope used when matching a fact against prior records. Kept
     * consistent with [ConsolidationEngine] so the local and model paths agree. */
    const val RECENT_MATCH_DAYS: Int = 90

    /** Cap on consolidated records per group (defensive bound, mirrors the engine). */
    const val MAX_RECORDS_PER_GROUP: Int = 2000

    /** Honest marker on facts validated from a model split (matches the client). */
    const val MODEL_SPLIT_NOTE: String = "model_batch_split"

    /** Auditable, countable result of applying pending entries to one group. */
    data class GroupOutcome(
        val state: ConsolidatedState,
        val pendingCount: Int,
        val factsSplit: Int,
        val factsCreated: Int,
        val factsMerged: Int,
        val factsSkipped: Int,
        /** Facts whose final decision came from a local/heuristic fallback. */
        val factsFallback: Int,
        /** True when the model path produced any decision / split in this group. */
        val usedModel: Boolean,
        /** True when at least one fact was forced to the fallback after a model path
         * was attempted (drives the top-level PARTIAL result). */
        val hadFallback: Boolean,
    )

    /**
     * Apply [entries] (all entries of a group) onto [previous], resolving pending
     * entries through [splitter] and [decider]. When [splitter]/[decider] are the
     * model-backed implementations (see [CloudConsolidationRepository]) the model
     * is consulted; otherwise the deterministic local split/dedup is used and
     * `usedModel` stays false. Never throws — a cancelled execution rethrows
     * [kotlinx.coroutines.CancellationException].
     */
    suspend fun applyPending(
        entries: List<DailyLogEntry>,
        previous: ConsolidatedState,
        splitter: ConsolidationFactSplitter,
        decider: ConsolidationFeatureDecider,
        modelPresent: Boolean,
        isCancelled: () -> Boolean = { false },
    ): GroupOutcome {
        var records = previous.records.toMutableList()
        var nextRecordId = nextRecordId(records)
        val pending = pendingEntries(entries, previous)
        if (pending.isEmpty()) {
            return GroupOutcome(
                state = previous,
                pendingCount = 0,
                factsSplit = 0,
                factsCreated = 0,
                factsMerged = 0,
                factsSkipped = 0,
                factsFallback = 0,
                usedModel = false,
                hadFallback = false,
            )
        }

        val factsBySource = splitter(pending)
        var factsSplit = 0
        var factsCreated = 0
        var factsMerged = 0
        var factsSkipped = 0
        var factsFallback = 0
        // The model path produced at least one split fact (model_notes marker) or a
        // validated MODEL dedup decision.
        val splitUsedModel = factsBySource.values.flatten().any { it.modelNotes == MODEL_SPLIT_NOTE }
        var usedModel = splitUsedModel
        var hadFallback = false

        for (entry in pending) {
            if (isCancelled()) throw kotlinx.coroutines.CancellationException("consolidation cancelled")
            val facts = factsBySource[entry.sourceRef].orEmpty()
            for (fact in facts) {
                factsSplit++
                // Exact match in the recent scope always wins (cheapest, deterministic).
                val scoped = selectRecentScopeRecords(records, fact)
                val exact = findExactExistingMatch(scoped, fact)
                if (exact != null) {
                    val dupDecision = CloudConsolidationTypes.DedupDecision(
                        decision = DedupDecisions.DUPLICATE,
                        matchedRecordIds = listOf(exact.recordId),
                        reason = "exact_existing_match",
                    )
                    updateExistingRecord(records, exact.recordId, fact, dupDecision)
                    factsMerged++
                    continue
                }
                val outcome = decider(fact, scoped)
                usedModel = usedModel || outcome.source == DecisionSource.MODEL
                if (outcome.source == DecisionSource.FALLBACK) {
                    factsFallback++
                    hadFallback = hadFallback || modelPresent
                }
                val decision = outcome.decision
                val fromModel = outcome.source == DecisionSource.MODEL
                val matched = findMatchedRecord(records, decision, scoped)
                when {
                    (decision.decision == DedupDecisions.DUPLICATE || decision.decision == DedupDecisions.MERGE_WITH_EXISTING) && matched != null -> {
                        updateExistingRecord(records, matched.recordId, fact, decision)
                        factsMerged++
                    }
                    decision.decision == DedupDecisions.SKIP_AMBIGUOUS -> factsSkipped++
                    // A duplicate/merge that could not be resolved to a real in-scope record
                    // (hostile/unknown matched id) becomes a NEW fact — never a dangling
                    // merge label, never an overwrite of an unrelated record.
                    (decision.decision == DedupDecisions.DUPLICATE || decision.decision == DedupDecisions.MERGE_WITH_EXISTING) -> {
                        val safeNew = decision.copy(
                            decision = DedupDecisions.NEW,
                            matchedRecordIds = emptyList(),
                            reason = "invalid_matched_id_fallback",
                            mergedSummary = "",
                            mergedFact = "",
                            mergedTags = emptyList(),
                        )
                        records.add(createNewRecord(nextRecordId, fact, safeNew, fromModel))
                        nextRecordId++
                        factsCreated++
                    }
                    else -> {
                        records.add(createNewRecord(nextRecordId, fact, decision, fromModel))
                        nextRecordId++
                        factsCreated++
                    }
                }
            }
        }

        if (records.size > MAX_RECORDS_PER_GROUP) {
            records = records.take(MAX_RECORDS_PER_GROUP).toMutableList()
        }
        records.sortWith(compareBy<ConsolidatedRecord> { it.firstSeenDate }.thenBy { it.recordId })

        val metadata = buildMetadata(entries, records, nextRecordId - 1)
        val next = ConsolidatedState(schema = previous.schema, metadata = metadata, records = records)
        return GroupOutcome(
            state = next,
            pendingCount = pending.size,
            factsSplit = factsSplit,
            factsCreated = factsCreated,
            factsMerged = factsMerged,
            factsSkipped = factsSkipped,
            factsFallback = factsFallback,
            usedModel = usedModel,
            hadFallback = hadFallback,
        )
    }

    // ---------------------------------------------------------------------
    // Pending detection + recent scope + matching (ports of Harmony util / engine)
    // ---------------------------------------------------------------------

    /** Stable processing hash for one entry: sha256(normalized text + scene id). */
    fun processingHash(entry: DailyLogEntry): String {
        val sceneId = entry.workflowMetadata["task_scene_id"]?.trim()?.toIntOrNull() ?: -1
        val seed = ConsolidationText.normalizeFreeText(entry.text) + "\u001f" + sceneId
        return ConsolidationText.sha256Text(seed)
    }

    /** Entries that changed since last processing OR were never processed. */
    private fun pendingEntries(entries: List<DailyLogEntry>, previous: ConsolidatedState): List<DailyLogEntry> {
        val processedByHash = previous.metadata.processedEntryHashes
        val processedRefs = HashSet<String>()
        for (record in previous.records) processedRefs.addAll(record.sourceRefs)
        return entries.filter { entry ->
            val currentHash = processingHash(entry)
            val previousHash = processedByHash[entry.sourceRef].orEmpty()
            if (previousHash.isNotEmpty()) {
                previousHash != currentHash
            } else {
                entry.sourceRef !in processedRefs
            }
        }
    }

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

    /** Resolve the decision's matched id — only against the current recent scope. */
    private fun findMatchedRecord(
        records: List<ConsolidatedRecord>,
        decision: DedupDecision,
        scoped: List<ConsolidatedRecord>,
    ): ConsolidatedRecord? {
        val selected = decision.matchedRecordIds.firstOrNull() ?: return null
        if (decision.matchedRecordIds.distinct().size != 1) return null
        val scopedIds = scoped.map { it.recordId }.toSet()
        if (selected !in scopedIds) return null
        return records.firstOrNull { it.recordId == selected }
    }

    private fun normalizedKey(value: String): String = ConsolidationText.normalizedSimilarityKey(value)
    private fun normalizedKeyForRecord(record: ConsolidatedRecord): String =
        ConsolidationText.normalizedSimilarityKey(record.dedupText.ifEmpty { record.normalizedFact })

    // ---------------------------------------------------------------------
    // Create / update helpers (ports with dedup decision + scene ids)
    // ---------------------------------------------------------------------

    private fun createNewRecord(
        nextRecordId: Int,
        fact: CandidateFact,
        decision: DedupDecision,
        fromModel: Boolean,
    ): ConsolidatedRecord {
        return ConsolidatedRecord(
            recordId = nextRecordId,
            firstSeenDate = fact.eventDate.ifEmpty { fact.sourceDate },
            lastSeenDate = fact.sourceDate,
            sourceRefs = listOf(fact.sourceRef),
            sourceDates = ConsolidationText.stableUnique(listOf(fact.eventDate.ifEmpty { fact.sourceDate }, fact.sourceDate)).sorted(),
            summary = ConsolidationText.summarizeText(fact.summary.ifEmpty { fact.normalizedFact }),
            normalizedFact = ConsolidationText.normalizeFreeText(fact.normalizedFact),
            rawExcerpts = listOf(fact.rawExcerpt),
            tags = ConsolidationText.stableUnique(fact.tags),
            workflowSceneIds = SceneEntityNormalizer.normalizeSceneIds(
                if (fact.workflowSceneId >= 0) listOf(fact.workflowSceneId) else emptyList(),
            ),
            dedupStatus = ConsolidationText.normalizeFreeText(decision.decision).ifEmpty { DedupDecisions.NEW },
            dedupText = fact.dedupText,
            modelNotes = if (fromModel) ConsolidationText.stableUnique(listOfNotNull(decisionNote(decision), fact.modelNotes))
            else ConsolidationText.stableUnique(listOfNotNull("local", decisionNote(decision), fact.modelNotes)),
        )
    }

    /** Update an existing record in-place with a fact + decision. */
    private fun updateExistingRecord(
        records: MutableList<ConsolidatedRecord>,
        recordId: Int,
        fact: CandidateFact,
        decision: DedupDecision,
    ) {
        val index = records.indexOfFirst { it.recordId == recordId }
        if (index < 0) return
        val record = records[index]
        var lastSeen = record.lastSeenDate
        if (fact.sourceDate > lastSeen) lastSeen = fact.sourceDate
        var sourceDates = record.sourceDates.toMutableList()
        if (sourceDates.contains(fact.eventDate).not()) {
            sourceDates.add(fact.eventDate)
            sourceDates.sort()
        }
        var sourceRefs = record.sourceRefs.toMutableList()
        if (sourceRefs.contains(fact.sourceRef).not()) sourceRefs.add(fact.sourceRef)
        var rawExcerpts = record.rawExcerpts.toMutableList()
        if (rawExcerpts.contains(fact.rawExcerpt).not()) rawExcerpts.add(fact.rawExcerpt)

        val mergedSummary = ConsolidationText.normalizeFreeText(decision.mergedSummary)
        val mergedFact = ConsolidationText.normalizeFreeText(decision.mergedFact)
        var normalizedFact = record.normalizedFact
        var summary = record.summary
        if (mergedSummary.isNotEmpty()) {
            summary = ConsolidationText.summarizeText(mergedSummary)
        }
        if (mergedFact.isNotEmpty()) {
            normalizedFact = mergedFact
        } else if (preferRicherFact(normalizedFact, fact.normalizedFact)) {
            normalizedFact = ConsolidationText.normalizeFreeText(fact.normalizedFact)
            if (mergedSummary.isEmpty()) summary = ConsolidationText.summarizeText(fact.summary.ifEmpty { fact.normalizedFact })
        }
        var tags = ConsolidationText.stableUnique(record.tags + ConsolidationText.normalizeTags(decision.mergedTags) + fact.tags)
        val sceneIds = SceneEntityNormalizer.normalizeSceneIds(
            record.workflowSceneIds + if (fact.workflowSceneId >= 0) listOf(fact.workflowSceneId) else emptyList(),
        )
        val notes = ConsolidationText.stableUnique(record.modelNotes + decisionNote(decision) + fact.modelNotes)
        records[index] = record.copy(
            lastSeenDate = lastSeen,
            sourceDates = sourceDates,
            sourceRefs = sourceRefs,
            rawExcerpts = rawExcerpts,
            normalizedFact = normalizedFact,
            summary = summary,
            tags = tags,
            workflowSceneIds = sceneIds,
            dedupStatus = ConsolidationText.normalizeFreeText(decision.decision).ifEmpty { record.dedupStatus },
            dedupText = if (mergedFact.isNotEmpty()) ConsolidationText.normalizeDedupText(mergedFact) else record.dedupText,
            modelNotes = notes,
        )
    }

    private fun decisionNote(decision: DedupDecision): String {
        val base = ConsolidationText.normalizeFreeText(decision.decision).ifEmpty { DedupDecisions.NEW }
        val reason = ConsolidationText.normalizeFreeText(decision.reason)
        return if (reason.isEmpty()) base else "$base($reason)"
    }

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

    private fun nextRecordId(records: List<ConsolidatedRecord>): Int {
        var maxId = 0
        for (record in records) if (record.recordId > maxId) maxId = record.recordId
        return maxId + 1
    }

    /** Build bounded metadata with per-source processed-hash coverage (all group entries). */
    private fun buildMetadata(
        groupEntries: List<DailyLogEntry>,
        records: List<ConsolidatedRecord>,
        latestRecordIndex: Int,
    ): ConsolidatedMetadata {
        val sourceFiles = ConsolidationText.stableUnique(groupEntries.map { it.fileName }).sorted()
        val sourceDates = ConsolidationText.stableUnique(groupEntries.map { it.sourceDate }).sorted()
        val month = groupEntries.firstOrNull()?.let { ConsolidationText.monthKeyFromDate(it.sourceDate) }.orEmpty()
        val hashes = LinkedHashMap<String, String>()
        for (entry in groupEntries) hashes[entry.sourceRef] = processingHash(entry)
        return ConsolidatedMetadata(
            sourceFiles = sourceFiles,
            sourceDates = sourceDates,
            latestRecordIndex = latestRecordIndex,
            updatedAt = ConsolidationText.epochIsoNowUtc(),
            totalRecords = records.size,
            month = month,
            processedEntryHashes = hashes,
        )
    }
}
