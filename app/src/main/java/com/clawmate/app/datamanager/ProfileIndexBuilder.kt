package com.clawmate.app.datamanager

import com.clawmate.app.datamanager.LocalConsolidationTypes.IndexedEvent
import com.clawmate.app.datamanager.ProfileTypes.Bounds
import com.clawmate.app.datamanager.ProfileTypes.ProfileBuildResult
import com.clawmate.app.datamanager.ProfileTypes.ProfileBuildStatus
import com.clawmate.app.datamanager.ProfileTypes.ProfileDomainState
import com.clawmate.app.datamanager.ProfileTypes.ProfileState
import com.clawmate.app.datamanager.ProfileTypes.PROFILE_DOMAIN_DISPLAY_NAMES
import com.clawmate.app.datamanager.ProfileTypes.PROFILE_DOMAIN_KEYS
import com.clawmate.app.datamanager.ProfileTypes.Schemas
import java.io.File

/**
 * Phase-32 **profile index / claims foundation** builder.
 *
 * Reads the **real** Phase-30 index artifacts (month event partitions under
 * `indexes/events/<month>.json`) and aggregates the events into the Harmony four
 * persona domains (`eat`/`wear`/`live`/`travel`) by deterministic literal
 * evidence ([ProfileDomainUtils]). For each domain it produces a local
 * projection whose summary is derived only from the real consolidated-record
 * text and whose `claims` are empty unless a strictly-validated model fills
 * them elsewhere ([ProfileIndexRunner]). It then writes:
 *
 *  - `workflows/profile-consolidation/indexes/profile/state.json` — typed,
 *    schema/versioned profile state with `generated_at`, `event_count`,
 *    `date_range`, source event ids and (when present) claims;
 *  - `workflows/profile-consolidation/indexes/profile/profile.md` — a bounded,
 *    human-readable projection.
 *
 * All ids are stable (`${relativeState}#${recordId}` from the index), all
 * writes are atomic and size-bounded, and every read tolerates missing / corrupt
 * / oversized artifacts (recovered to an honest empty state — never thrown, so a
 * bad artifact can never falsify a run). `buildProfileDocument` is the direct
 * migration of Harmony `IndexService.buildProfileDocument`'s data contract for
 * the Android four-domain local path.
 */
class ProfileIndexBuilder(
    private val baseDir: File,
    private val store: AtomicJsonStore = AtomicJsonStore(),
) {

    /**
     * Build and persist a **local-only** profile from the persisted event index.
     * Never touches the network; with no events it writes nothing and returns an
     * honest EMPTY result (no artifact is auto-created).
     */
    fun buildLocal(): ProfileBuildResult {
        val events = loadEvents()
        if (events.isEmpty()) return ProfileBuildResult(
            eventCount = 0,
            domainCounts = emptyMap(),
            claimCount = 0,
            status = ProfileBuildStatus.EMPTY,
        )
        val projected = projectLocal(events)
        val state = buildProfileDocument(
            events = events,
            domains = projected,
        )
        writeProfile(state)
        return toResult(state)
    }

    /** Load the bounded real event index from persisted month partitions (tolerant). */
    fun loadEvents(): List<IndexedEvent> {
        val dir = DataManagerPaths.eventsDir(baseDir)
        val base = if (!dir.isDirectory) {
            emptyList()
        } else {
            val result = ArrayList<IndexedEvent>()
            dir.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".json") && it.name != DataManagerPaths.PARTITION_INDEX_FILE_NAME }
                ?.sortedBy { it.name }
                ?.forEach { file ->
                    val partition = store.readJson(file) { LocalConsolidationCodec.decodePartition(it) }
                    if (partition != null && partition.events.isNotEmpty()) {
                        result += partition.events.filter { ConsolidationText.isIsoDate(it.eventDate) }
                    }
                }
            result
        }
        // Phase-38 — overlay persisted batch event-enrichment fields (absent →
        // unchanged = default behaviour).
        return BatchEventEnrichmentOverlay.overlay(base, BatchEventEnrichmentStore(baseDir).load())
    }

    /**
     * Pure, deterministic four-domain projection (stateless, testable). Every
     * domain's summary comes only from real event text; claims are empty;
     * `generation = "local_only"`.
     */
    fun projectLocal(events: List<IndexedEvent>): Map<String, ProfileDomainState> {
        val byDomain = ProfileDomainUtils.groupByDomain(events)
        val result = LinkedHashMap<String, ProfileDomainState>()
        for (domain in PROFILE_DOMAIN_KEYS) {
            val domainEvents = byDomain[domain].orEmpty().sortedWith(compareBy({ it.eventDate }, { it.eventId }))
            val range = ProfileDomainUtils.dateRange(domainEvents)
            val state = ProfileDomainState(
                domain = domain,
                displayName = PROFILE_DOMAIN_DISPLAY_NAMES[domain] ?: domain,
                inputHash = ProfileDomainUtils.domainInputHash(domain, domainEvents),
                eventIds = domainEvents.map { it.eventId },
                eventCount = domainEvents.size,
                startDate = range.first,
                endDate = range.second,
                shortSummary = "",
                summary = if (domainEvents.isEmpty()) "" else ProfileDomainUtils.deriveLocalSummary(domainEvents),
                claims = emptyList(),
                generation = if (domainEvents.isEmpty()) "empty" else "local_only",
            )
            result[domain] = state
        }
        return result
    }

    /**
     * Assemble a [ProfileState] from real events + per-domain states — the
     * migration of Harmony `IndexService.buildProfileDocument`'s data contract
     * (schema/version, generatedAt, event_count, date range, source event ids,
     * claims). Total event count / claim count and overall date range are real
     * and countable.
     */
    fun buildProfileDocument(
        events: List<IndexedEvent>,
        domains: Map<String, ProfileDomainState>,
    ): ProfileState {
        val present = domains.values.filter { it.eventCount > 0 }
        val rangeEvents = present.flatMap { d -> d.eventIds.mapNotNull { id -> events.firstOrNull { it.eventId == id } } }
        val dateRange = ProfileDomainUtils.dateRange(rangeEvents)
        val status = overallStatus(domains)
        val claimCount = domains.values.sumOf { it.claims.size }
        return ProfileState(
            schema = Schemas.PROFILE_STATE_V1,
            generatedAt = nowIsoLocal(),
            eventCount = events.size,
            startDate = dateRange.first,
            endDate = dateRange.second,
            claimCount = claimCount,
            status = status.token,
            domains = domains,
        )
    }

    /** Honest overall status from per-domain generation tokens. */
    fun overallStatus(domains: Map<String, ProfileDomainState>): ProfileBuildStatus {
        val tokens = domains.values.map { it.generation }
        if (tokens.isEmpty()) return ProfileBuildStatus.EMPTY
        if (tokens.any { it == "model" }) return ProfileBuildStatus.MODEL
        // A local-only run (no model attempted) is LOCAL_ONLY even when some
        // domains have no events; PARTIAL is reserved for a model-involved run
        // where not every domain produced model claims (introduced by the runner).
        if (tokens.any { it == "local_only" }) return ProfileBuildStatus.LOCAL_ONLY
        return ProfileBuildStatus.EMPTY
    }

    /** Atomic, bounded write of the profile state + rendered markdown. */
    fun writeProfile(state: ProfileState): Boolean {
        val markdown = renderMarkdown(state)
        val stateWritten = store.writeJson(DataManagerPaths.profileState(baseDir), state) {
            ProfileCodec.encodeState(it)
        } != null
        val mdWritten = store.writeText(DataManagerPaths.profileMarkdown(baseDir), markdown) != null
        return stateWritten && mdWritten
    }

    /**
     * Bounded rendered markdown (migrates Harmony `renderProfileMarkdown`). Only
     * real summaries / claims / counts appear; never a source URI, token or
     * unrelated data.
     */
    fun renderMarkdown(state: ProfileState): String {
        val lines = ArrayList<String>()
        lines += "# 用户画像"
        lines += ""
        lines += "- 生成时间：${bounded(state.generatedAt, 40).ifEmpty { "-" }}"
        lines += "- 覆盖事件：${state.eventCount} 条"
        lines += "- 时间范围：${state.startDate.ifEmpty { "-" }} ~ ${state.endDate.ifEmpty { "-" }}"
        lines += "- 画像条目：${state.claimCount} 条"
        lines += "- 状态：${statusLabel(state.status)}"
        lines += ""
        for (domain in PROFILE_DOMAIN_KEYS) {
            val doc = state.domains[domain]
            lines += "## ${PROFILE_DOMAIN_DISPLAY_NAMES[domain] ?: domain}"
            lines += ""
            if (doc == null || doc.eventCount == 0) {
                lines += "当前记录还不足以形成稳定的${PROFILE_DOMAIN_DISPLAY_NAMES[domain] ?: domain}领域画像。"
                lines += ""
                continue
            }
            val summary = doc.summary.trim()
            if (summary.isNotEmpty()) lines += summary
            if (doc.claims.isEmpty()) {
                if (summary.isEmpty()) {
                    lines += "当前记录还不足以形成稳定的${PROFILE_DOMAIN_DISPLAY_NAMES[domain] ?: domain}领域画像。"
                }
            } else {
                for (claim in doc.claims) {
                    lines += "- ${bounded(claim.statement, Bounds.MAX_CLAIM_STATEMENT_CHARACTERS)}"
                }
            }
            lines += ""
        }
        return joinBounded(lines)
    }

    /** Tolerant re-read of the persisted profile state (empty on missing/corrupt/oversized). */
    fun loadProfileState(): ProfileState =
        store.readJson(DataManagerPaths.profileState(baseDir)) { ProfileCodec.decodeState(it) }
            ?: ProfileState()

    /** Readable status label for markdown. */
    private fun statusLabel(status: String): String = when (status) {
        ProfileBuildStatus.LOCAL_ONLY.token -> "本地画像（未配置云端模型）"
        ProfileBuildStatus.MODEL.token -> "画像已生成"
        ProfileBuildStatus.PARTIAL.token -> "部分画像（部分领域暂未生成）"
        ProfileBuildStatus.ERROR.token -> "模型生成失败，已保留本地画像"
        else -> "暂无画像"
    }

    private fun toResult(state: ProfileState): ProfileBuildResult {
        val generation = state.domains.mapValues { (_, d) -> d.generation }
        return ProfileBuildResult(
            eventCount = state.eventCount,
            domainCounts = state.domains.mapValues { (_, d) -> d.eventCount },
            claimCount = state.claimCount,
            generatedAt = state.generatedAt,
            status = ProfileBuildStatus.fromToken(state.status),
            domainGeneration = generation,
        )
    }

    private fun bounded(value: String, max: Int): String {
        val text = ConsolidationText.normalizeFreeText(value)
        return if (text.length <= max) text else text.substring(0, max) + "…"
    }

    private fun joinBounded(lines: List<String>): String {
        val joined = lines.joinToString("\n")
        return if (joined.length <= MAX_MARKDOWN_BYTES) joined else joined.substring(0, MAX_MARKDOWN_BYTES)
    }

    /** Now (ISO-local) mirroring Harmony `nowIsoLocal`. */
    private fun nowIsoLocal(): String =
        java.time.LocalDateTime.now(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))

    companion object {
        /** Bounded cap for the rendered markdown artifact. */
        const val MAX_MARKDOWN_BYTES: Int = 24 * 1024
    }
}
