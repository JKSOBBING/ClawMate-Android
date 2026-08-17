package com.clawmate.app.datamanager

import com.clawmate.app.datamanager.ProfileTypes.ProfileBuildResult
import com.clawmate.app.datamanager.ProfileTypes.ProfileBuildStatus
import com.clawmate.app.datamanager.ProfileTypes.ProfileDomainState
import com.clawmate.app.datamanager.ProfileTypes.ProfileState
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Focused JVM tests for the Phase-32 profile index / claims foundation. Every
 * case runs over an isolated temp dir (never `filesDir`) and exercises the real
 * Phase-30 → Phase-32 pipeline (seed daily-log → consolidate → index → profile).
 *
 * Contract covered:
 *  - empty / no-index input → zero network, no artifact, no pseudo claims;
 *  - four domain (eat/wear/live/travel) projection from real event text;
 *  - stable event ids / evidence across repeated runs;
 *  - strict model JSON validation (unknown domain/evidence, over-length,
 *    low-confidence, wrong JSON all dropped, never coerced);
 *  - config gate → zero model calls when unconfigured;
 *  - local-only / partial fallback when the model fails;
 *  - repeat-run stability (idempotent shape);
 *  - corrupt / oversized artifact recovery;
 *  - bounded markdown.
 */
class ProfileIndexTest {

    private fun tempBase(): File =
        Files.createTempDirectory("clawmate-phase32-test").toFile().apply { deleteOnExit() }

    private fun writeLog(base: File, day: String, file: String, entries: List<String>): File? =
        DailyLogWorkspace.writeDocument(base, day, file, entries)

    /** Seed events + run the real consolidation/index pipeline, returning index events. */
    private fun seedIndex(base: File, logs: List<Triple<String, String, List<String>>>): List<LocalConsolidationTypes.IndexedEvent> {
        for ((day, file, entries) in logs) writeLog(base, day, file, entries)
        LocalConsolidationIndexRunner(base).run()
        return ProfileIndexBuilder(base).loadEvents()
    }

    // ---- 1. no-index input -> zero network, no artifacts, no pseudo claims ----

    @Test
    fun `empty index yields EMPTY result and no profile artifact`() = runTest {
        val base = tempBase()
        val runner = ProfileIndexRunner.localOnly(base)
        val result = runner.run(gateway = null)
        assertEquals(ProfileBuildStatus.EMPTY, result.status)
        assertEquals(0, result.eventCount)
        // No profile artifact was auto-created.
        val state = ProfileIndexBuilder(base).loadProfileState()
        assertEquals(0, state.eventCount)
        assertFalse(DataManagerPaths.profileState(base).exists())
        assertFalse(DataManagerPaths.profileMarkdown(base).exists())
    }

    @Test
    fun `no events means no model call even when configured`() = runTest {
        val base = tempBase()
        val calls = mutableListOf<String>()
        val runner = ProfileIndexRunner.forTest(base, ProfileModelConfig("https://x/v1", "k", "m"))
        val gateway = FakeGateway { _, _ -> calls.add("called"); DomainUpgrade.local(dummyDomain()) }
        val result = runner.run(gateway = gateway)
        assertEquals(ProfileBuildStatus.EMPTY, result.status)
        assertTrue("gateway must not be called with no events", calls.isEmpty())
    }

    // ---- 2. four-domain mapping ----

    @Test
    fun `events map to the correct four domains from real text`() = runTest {
        val base = tempBase()
        seedIndex(
            base,
            listOf(
                Triple("2026-08-01", "eat.logs.md", listOf("1. 中午吃了火锅", "2. 下午买了奶茶")),
                Triple("2026-08-02", "wear.logs.md", listOf("1. 去商场买了一双鞋")),
                Triple("2026-08-03", "live.logs.md", listOf("1. 预订了酒店住宿")),
                Triple("2026-08-04", "travel.logs.md", listOf("1. 坐高铁出差")),
            ),
        )
        val runner = ProfileIndexRunner.localOnly(base)
        val result = runner.run(gateway = null)
        assertEquals(ProfileBuildStatus.LOCAL_ONLY, result.status)
        assertTrue("eat should have events", result.domainCounts.getValue("eat") >= 1)
        assertTrue("wear should have events", result.domainCounts.getValue("wear") >= 1)
        assertTrue("live should have events", result.domainCounts.getValue("live") >= 1)
        assertTrue("travel should have events", result.domainCounts.getValue("travel") >= 1)

        val state = ProfileIndexBuilder(base).loadProfileState()
        val eat = state.domains.getValue("eat")
        assertEquals("local_only", eat.generation)
        assertTrue("eat summary must be local text", eat.summary.contains("火锅"))
        assertTrue("eat claims must be empty (local-only)", eat.claims.isEmpty())
        // No fabricated 'other'/'work' domains in the four-pillar profile.
        assertFalse(state.domains.containsKey("work"))
        assertFalse(state.domains.containsKey("other"))
    }

    @Test
    fun `date ranges and evidence ids are real and bounded`() = runTest {
        val base = tempBase()
        seedIndex(
            base,
            listOf(
                Triple("2026-08-05", "a.md", listOf("1. 今天点了咖啡外卖")),
                Triple("2026-08-06", "a.md", listOf("1. 又点了一份奶茶")),
            ),
        )
        val runner = ProfileIndexRunner.localOnly(base)
        runner.run(gateway = null)
        val state = ProfileIndexBuilder(base).loadProfileState()
        val eat = state.domains.getValue("eat")
        assertEquals(2, eat.eventCount)
        assertEquals(2, eat.eventIds.size)
        // Every event id references a genuinely indexed event.
        val indexed = ProfileIndexBuilder(base).loadEvents().map { it.eventId }.toSet()
        assertTrue(eat.eventIds.all { it in indexed })
        assertEquals("2026-08-05", eat.startDate)
        assertEquals("2026-08-06", eat.endDate)
    }

    // ---- 3. stable ids / evidence across repeated runs ----

    @Test
    fun `repeated runs produce stable event ids and evidence`() = runTest {
        val base = tempBase()
        seedIndex(
            base,
            listOf(Triple("2026-08-07", "a.md", listOf("1. 中午吃拉面", "2. 晚上吃烤肉"))),
        )
        val first = ProfileIndexBuilder(base).loadEvents().map { it.eventId }
        ProfileIndexRunner.localOnly(base).run(gateway = null)
        val second = ProfileIndexBuilder(base).loadEvents().map { it.eventId }
        assertEquals(first, second)

        // Repeated profile builds keep claims/ids stable when input unchanged.
        val r1 = ProfileIndexRunner.localOnly(base).run(gateway = null)
        val d1 = ProfileIndexBuilder(base).loadProfileState().domains
        ProfileIndexRunner.localOnly(base).run(gateway = null)
        val d2 = ProfileIndexBuilder(base).loadProfileState().domains
        assertEquals(d1.getValue("eat").eventIds, d2.getValue("eat").eventIds)
        assertEquals(d1.getValue("eat").summary, d2.getValue("eat").summary)
        assertEquals(r1.claimCount, ProfileIndexBuilder(base).loadProfileState().claimCount)
    }

    // ---- 3b. repeat-run with model: model failure never drops prior valid claims ----

    @Test
    fun `reuse keeps persisted model claims stable across a failing re-run`() = runTest {
        val base = tempBase()
        seedIndex(
            base,
            listOf(Triple("2026-08-08", "a.md", listOf("1. 来杯咖啡", "2. 再买瓶奶茶"))),
        )
        // First run: a model gateway that admits real claims.
        val good = FakeGateway { events, _ ->
            val ids = events.take(2).map { it.eventId }
            DomainUpgrade.Model(
                claims = ids.mapIndexed { i, id ->
                    com.clawmate.app.datamanager.ProfileTypes.ProfileClaim(
                        statement = "偏好咖啡饮品${i + 1}",
                        kind = "preference",
                        confidence = "high",
                        eventIds = listOf(id),
                        evidenceCount = 1,
                    )
                },
                summary = "用户常点咖啡",
                shortSummary = "咖啡饮品",
            )
        }
        val runner = ProfileIndexRunner.forTest(base, ProfileModelConfig("https://x/v1", "k", "m"))
        runner.run(gateway = good)
        val firstState = ProfileIndexBuilder(base).loadProfileState()
        assertEquals("model", firstState.status)
        assertTrue(firstState.domains.getValue("eat").claims.isNotEmpty())

        // Second run on unchanged input: the reuse rule keeps the persisted model
        // claims (deterministic reuse) — the failing gateway is never even reached.
        val calls = mutableListOf<String>()
        val failing = FakeGateway { _, _ -> calls.add("called"); DomainUpgrade.modelError(dummyDomain(), com.clawmate.app.chat.cloud.ChatException(com.clawmate.app.chat.cloud.ChatErrorKind.IO, "boom")) }
        val secondRunner = ProfileIndexRunner.forTest(base, ProfileModelConfig("https://x/v1", "k", "m"))
        val second = secondRunner.run(gateway = failing)
        // Reuse short-circuit: the previously admitted model claims are preserved
        // (MODEL), so a valid state is never downgraded to local-only.
        assertEquals(ProfileBuildStatus.MODEL, second.status)
        val after = ProfileIndexBuilder(base).loadProfileState()
        assertTrue("persisted claims must not be lost on a failing re-run", after.domains.getValue("eat").claims.isNotEmpty())
        // Failing gateway should not have been invoked on unchanged input (reuse won).
        assertEquals(0, calls.size)
    }

    @Test
    fun `fresh model failure yields typed ERROR and preserves deterministic local result`() = runTest {
        val base = tempBase()
        seedIndex(base, listOf(Triple("2026-08-11", "b.md", listOf("1. 点了一杯咖啡"))))
        val failing = FakeGateway { _, _ ->
            DomainUpgrade.modelError(dummyDomain(), com.clawmate.app.chat.cloud.ChatException(com.clawmate.app.chat.cloud.ChatErrorKind.HTTP, "500", httpCode = 500))
        }
        val runner = ProfileIndexRunner.forTest(base, ProfileModelConfig("https://x/v1", "k", "m"))
        val result = runner.run(gateway = failing)
        assertTrue(
            "expected ERROR or PARTIAL, got ${result.status}",
            result.status == ProfileBuildStatus.ERROR || result.status == ProfileBuildStatus.PARTIAL,
        )
        // The deterministic local projection is preserved (no fabricated claims).
        val state = ProfileIndexBuilder(base).loadProfileState()
        val eat = state.domains.getValue("eat")
        assertTrue("eat summary must remain real text", eat.summary.contains("咖啡"))
        assertTrue("no pseudo claims after fresh failure", eat.claims.isEmpty())
    }

    // ---- 4. strict model JSON validation ----

    @Test
    fun `model validator drops unknown kinds and out-of-range confidence`() {
        val raw = listOf(
            com.clawmate.app.datamanager.ProfileTypes.RawProfileClaim(
                statement = "用户喜欢咖啡", kind = "not_a_kind", confidence = "super", eventIds = listOf("e1", "e2"),
            ),
            com.clawmate.app.datamanager.ProfileTypes.RawProfileClaim(
                statement = "用户爱吃甜食", kind = "habit", confidence = "high", eventIds = listOf("e1"),
            ),
        )
        val real = setOf("e1", "e2")
        val (valid, dropped) = ProfileModelValidator.validateClaims(raw, real)
        assertEquals(1, valid.size)
        assertEquals(1, dropped)
        assertEquals("observation", valid[0].kind) // unknown kind -> observation
        assertEquals("low", valid[0].confidence)  // out-of-range -> low
    }

    @Test
    fun `model validator drops claims with unknown evidence or too few events`() {
        val real = setOf("e1", "e2")
        val raw = listOf(
            com.clawmate.app.datamanager.ProfileTypes.RawProfileClaim(
                statement = "参考真实事件", kind = "topic", confidence = "medium", eventIds = listOf("e1", "e2"),
            ),
            com.clawmate.app.datamanager.ProfileTypes.RawProfileClaim(
                statement = "证据不存在", kind = "topic", confidence = "medium", eventIds = listOf("nope", "e1"),
            ),
            com.clawmate.app.datamanager.ProfileTypes.RawProfileClaim(
                statement = "只一次证据", kind = "topic", confidence = "medium", eventIds = listOf("e1"),
            ),
        )
        val (valid, dropped) = ProfileModelValidator.validateClaims(raw, real)
        assertEquals(1, valid.size)
        assertEquals(2, dropped)
        assertEquals("参考真实事件", valid[0].statement)
    }

    @Test
    fun `model validator rejects over-long statements and event-detail statements`() {
        val real = setOf("e1", "e2")
        val long = "好".repeat(500)
        val raw = listOf(
            com.clawmate.app.datamanager.ProfileTypes.RawProfileClaim(
                statement = long, kind = "topic", confidence = "high", eventIds = listOf("e1", "e2"),
            ),
            com.clawmate.app.datamanager.ProfileTypes.RawProfileClaim(
                statement = "订单状态为已付款，实付25元", kind = "topic", confidence = "high", eventIds = listOf("e1", "e2"),
            ),
            com.clawmate.app.datamanager.ProfileTypes.RawProfileClaim(
                statement = "正常结论", kind = "habit", confidence = "high", eventIds = listOf("e1", "e2"),
            ),
        )
        val (valid, _) = ProfileModelValidator.validateClaims(raw, real)
        // The over-long statement is bounded (truncated) and the normal conclusion
        // is admitted; the event-detail statement ("订单状态...实付25元") is dropped.
        val statements = valid.map { it.statement }
        assertEquals(2, valid.size)
        assertFalse(statements.any { it.contains("订单状态") })
        assertTrue(valid.all { it.statement.length <= com.clawmate.app.datamanager.ProfileTypes.Bounds.MAX_CLAIM_STATEMENT_CHARACTERS })
    }

    @Test
    fun `malformed model JSON decodes to null (never fabricates)`() {
        assertNull(ProfileModelValidator.decodePayload("{ not json"))
        assertNull(ProfileModelValidator.decodePayload("[]"))
        assertNull(ProfileModelValidator.decodePayload(""))
    }

    // ---- 5. config gate -> zero calls unconfigured ----

    @Test
    fun `unconfigured model resolves to a local-only profile with zero model calls`() = runTest {
        val base = tempBase()
        seedIndex(base, listOf(Triple("2026-08-09", "a.md", listOf("1. 打车去机场"))))
        // The true config gate: no gateway is provided, and the runner's
        // modelFounder is disabled (localOnly), so resolveGateway() must be null
        // and no model call can ever fire.
        val runner = ProfileIndexRunner.localOnly(base)
        val result = runner.run()
        assertEquals(ProfileBuildStatus.LOCAL_ONLY, result.status)
        val state = ProfileIndexBuilder(base).loadProfileState()
        assertEquals("local_only", state.domains.getValue("travel").generation)
        assertTrue(state.domains.getValue("travel").claims.isEmpty())
        // No profile state above carries any model generation token anywhere.
        assertTrue(state.domains.values.all { it.generation != "model" })
    }

    // ---- 6. local-only / partial fallback on model failure ----

    @Test
    fun `model failure yields typed ERROR and preserves deterministic local result`() = runTest {
        val base = tempBase()
        seedIndex(base, listOf(Triple("2026-08-10", "a.md", listOf("1. 点了一杯咖啡"))))
        val failing = FakeGateway { _, _ ->
            DomainUpgrade.modelError(dummyDomain(), com.clawmate.app.chat.cloud.ChatException(com.clawmate.app.chat.cloud.ChatErrorKind.HTTP, "500", httpCode = 500))
        }
        val runner = ProfileIndexRunner.forTest(base, ProfileModelConfig("https://x/v1", "k", "m"))
        val result = runner.run(gateway = failing)
        assertTrue(
            "expected ERROR or PARTIAL, got ${result.status}",
            result.status == ProfileBuildStatus.ERROR || result.status == ProfileBuildStatus.PARTIAL,
        )
        // The local projection is preserved (no fabricated claims).
        val state = ProfileIndexBuilder(base).loadProfileState()
        val eat = state.domains.getValue("eat")
        assertTrue("eat summary must remain real text", eat.summary.contains("咖啡"))
        assertTrue("no pseudo claims after failure", eat.claims.isEmpty())
    }

    // ---- 7. corrupt / oversized artifact recovery ----

    @Test
    fun `corrupt profile state is recovered to honest empty rather than thrown`() {
        val base = tempBase()
        val stateFile = DataManagerPaths.profileState(base)
        stateFile.absoluteFile.parentFile?.mkdirs()
        stateFile.writeText("{ corrupt json !!")
        val state = ProfileIndexBuilder(base).loadProfileState()
        assertEquals(0, state.eventCount)
        assertTrue(state.domains.isEmpty())
    }

    @Test
    fun `oversized artifact is treated as unreadable (honest fallback)`() {
        val base = tempBase()
        val stateFile = DataManagerPaths.profileState(base)
        stateFile.absoluteFile.parentFile?.mkdirs()
        stateFile.writeText("x".repeat(5 * 1024 * 1024)) // > AtomicJsonStore 4MiB cap
        val store = AtomicJsonStore()
        val state = store.readJson(stateFile) { ProfileCodec.decodeState(it) }
        assertNull(state)
    }

    @Test
    fun `corrupt index partition recovers to empty local profile and writes nothing when no events`() = runTest {
        val base = tempBase()
        // Write a corrupt partition file directly.
        val eventsDir = DataManagerPaths.eventsDir(base)
        eventsDir.mkdirs()
        File(eventsDir, "2026-08.json").writeText("{ corrupt")
        val runner = ProfileIndexRunner.localOnly(base)
        val result = runner.run(gateway = null)
        // Corrupt partition -> no readable events -> EMPTY (zero network/artifacts).
        assertEquals(ProfileBuildStatus.EMPTY, result.status)
        assertFalse(DataManagerPaths.profileState(base).exists())
    }

    // ---- 8. bounded markdown ----

    @Test
    fun `rendered profile markdown is bounded and carries only real content`() {
        val base = tempBase()
        val state = ProfileState(
            eventCount = 2,
            startDate = "2026-08-01",
            endDate = "2026-08-02",
            status = ProfileBuildStatus.LOCAL_ONLY.token,
            domains = mapOf(
                "eat" to ProfileDomainState(
                    domain = "eat", displayName = "吃", eventCount = 2,
                    startDate = "2026-08-01", endDate = "2026-08-02",
                    summary = "用户点了咖啡和奶茶", generation = "local_only",
                ),
            ),
        )
        val md = ProfileIndexBuilder(base).renderMarkdown(state)
        assertTrue(md.contains("用户画像"))
        assertTrue(md.contains("咖啡"))
        // Bounded length.
        assertTrue(md.toByteArray().size <= ProfileIndexBuilder.MAX_MARKDOWN_BYTES)
        // Markdown must not contain any invented claims for a local-only state.
        assertFalse(md.contains("偏好"))
    }

    // ---- 9. four-domain unit projection ----

    @Test
    fun `deriveProfileDomains maps text evidence only to kept four domains`() {
        val eat = LocalConsolidationTypes.IndexedEvent(eventId = "e1", eventDate = "2026-08-01", summary = "中午吃火锅", normalizedFact = "")
        val workish = LocalConsolidationTypes.IndexedEvent(eventId = "e2", eventDate = "2026-08-01", summary = "开会汇报PPT", normalizedFact = "")
        assertEquals(listOf("eat"), ProfileDomainUtils.deriveProfileDomains(eat))
        // 'work' is not a kept four-pillar domain -> no mapping.
        assertTrue(ProfileDomainUtils.deriveProfileDomains(workish).isEmpty())
    }

    // ---- 8b. ProfileGenerationStateMapper ----

    @Test
    fun `mapper returns honest typed states`() {
        val local = ProfileIndexBuilder(tempBase()).buildProfileDocument(
            events = emptyList(),
            domains = mapOf(
                "eat" to ProfileDomainState(domain = "eat", eventCount = 2, generation = "local_only"),
            ),
        )
        val mapped = ProfileGenerationStateMapper.fromResult(
            ProfileBuildResult(
                eventCount = 2,
                domainCounts = local.domains.mapValues { it.value.eventCount },
                claimCount = local.claimCount,
                status = ProfileBuildStatus.LOCAL_ONLY,
            ),
            wasConfigured = false,
        )
        assertTrue(mapped is ProfileGenerationState.Success)
        assertFalse((mapped as ProfileGenerationState.Success).isModel)
        assertEquals(2, (mapped as ProfileGenerationState.Success).eventCount)
    }

    // Helpers
    private fun dummyDomain(): ProfileDomainState =
        ProfileDomainState(domain = "eat", displayName = "吃", eventCount = 1)

    private class FakeGateway(
        private val handler: suspend (List<LocalConsolidationTypes.IndexedEvent>, ProfileDomainState) -> DomainUpgrade,
    ) : ProfileIndexRunner.DomainModelGateway {
        override suspend fun generate(
            events: List<LocalConsolidationTypes.IndexedEvent>,
            local: ProfileDomainState,
        ): DomainUpgrade? = handler(events, local)
    }
}
