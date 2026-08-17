# DSH_PHASE_RESULT — ClawMate Android, Phase 32 (profile index / claims foundation)

## Commit

- Working against base `741b248` (Phase 31). The changes are exported as a real
  diff in `/root/phase32.patch` (paths `a/ClawMate-Android/…` `b/ClawMate-Android/…`).

## Context / provenance

Phase 32 migrates the Harmony **profile index / claims** part of the data-manager
pipeline (`IndexService.buildProfileDocument` / `DataManagerTypes` profile types /
`deriveProfileDomains`) to Android, **on top of, and without repeating, the
Phase-31 NFC adapter**. It reads the Phase-30 event partitions
(`indexes/events/<month>.json`), aggregates the events into the four persona
domains (`eat`/`wear`/`live`/`travel`) by deterministic literal evidence, and
materialises a typed profile into
`workflows/profile-consolidation/indexes/profile/state.json` plus a bounded
`profile.md`.

No fabrication: claims only ever reference real indexed event ids and pass strict
bounds/whitelist validation; with no model config a local-only run carries empty
claims and is honestly typed `NOT_CONFIGURED`/`LOCAL_ONLY`. A model failure
preserves the deterministic local result (typed PARTIAL/ERROR) and never
overwrites a valid persisted model state. All activity is explicit-user-triggered.

The Harmony code is read-only reference (`/root/mobiinfra-oh` was **not**
modified).

## Files changed

New production files (`app/src/main/java/com/clawmate/app/datamanager/`):

- `ProfileTypes.kt` — profile state/domain/claim/model-payload models, four-domain
  keys + display names, strict bounds, generation tokens + `local_only`, typed
  `ProfileBuildStatus` and `ProfileBuildResult`.
- `ProfileDomainUtils.kt` — four-domain evidence rules + `deriveProfileDomains`,
  `domainInputHash`, `dateRange`, `deriveLocalSummary`.
- `ProfileCodec.kt` — tolerant profile JSON serde.
- `ProfileIndexBuilder.kt` — loads real events, projects domains, builds
  `ProfileState` (`buildProfileDocument` contract), renders bounded markdown,
  writes state.json + profile.md atomically.
- `ProfileModelConfig.kt` — typed config gated by the shared OpenAI Planner
  `EndpointConfig`; plus `ProfileModelValidator` (strict JSON decode + whitelist +
  evidence-reference) and `ProfileCodecModelJson`.
- `ProfileModelClient.kt` — per-domain model generation via the existing
  `ChatCompletionsClient` (bounded real event text only), with strict validation
  and typed `DomainUpgrade`.
- `ProfileIndexRunner.kt` — local-only when unconfigured, model-backed when
  configured, deterministic-reuse, preserves local projection on failure.
- `ProfileGenerationTypes.kt` / `RealProfileGenerationRepository.kt` — the
  explicit "生成画像" Home seam and typed lifecycle state.

New production files in the Home layer:

- `app/src/main/java/com/clawmate/app/home/domain/ProfileIndexBlender.kt` —
  local-first display blend (user-curated domain wins; un-populated pillars filled
  from bounded index summary/claims + real evidence ids; never writes back).
- `app/src/main/java/com/clawmate/app/home/data/ProfileIndexHomeProfileSource.kt`
  — reads persisted profile state + curated profile and blends into Home.

Modified production files:

- `app/src/main/java/com/clawmate/app/datamanager/DataManagerPaths.kt` — profile
  subtree (`indexes/profile/state.json`, `indexes/profile/profile.md`).
- `app/src/main/java/com/clawmate/app/datamanager/AtomicJsonStore.kt` — added
  bounded `writeText` / `readText`.
- `app/src/main/java/com/clawmate/app/home/ui/HomeViewModel.kt` —
  `profileGeneration` state + explicit `generateProfile()` (refreshes home profiles
  after a successful build).
- `app/src/main/java/com/clawmate/app/home/ui/HomeViewModelFactory.kt` — injects
  the profile-generation repository.
- `app/src/main/java/com/clawmate/app/home/ui/HomeScreen.kt` — explicit "生成画像"
  section + button (running / local-only success / model success / partial /
  not-configured / error).
- `app/src/main/java/com/clawmate/app/home/domain/HomeDomain.kt` — `evidenceIds`
  field on the Home pillar card (display-only).
- `app/src/main/java/com/clawmate/app/ui/ClawMateRoot.kt` — wires
  `RealProfileGenerationRepository` + `ProfileIndexHomeProfileSource`.

## New test files

- `app/src/test/java/com/clawmate/app/datamanager/ProfileIndexTest.kt` — **19
  focused, offline JVM tests** over temp dirs exercising the real Phase-30 → 32
  pipeline: empty/no-index → zero network + no artifact + no pseudo claims; no
  events → no model call even when configured; four-domain mapping (incl. no
  `work`/`other` leakage); real date ranges + evidence ids; repeated-run stability;
  model-claim reuse + fresh-failure typed ERROR with local preserved; strict model
  validation (unknown kind / low-confidence / unknown evidence / <2 evidence /
  over-length / event-detail / malformed JSON); unconfigured zero-call local-only;
  corrupt + oversized artifact recovery; bounded markdown; pure
  `deriveProfileDomains`; `ProfileGenerationStateMapper` honesty.
- `app/src/test/java/com/clawmate/app/home/ProfileIndexBlenderTest.kt` — **4 tests**:
  user-populated domain preserved verbatim (local-first), empty domain filled from
  real index summary/claims/evidence, no data → honest empty with no fabricated
  claims, and four-domain bounded import only.
- `app/src/test/java/com/clawmate/app/home/ProfileGenerationViewModelTest.kt` —
  **3 tests**: no-repo disabling error, explicit generate surfaces a successful
  state, and profile generation is never invoked on load.

## Verification (serial, `--offline --no-daemon --max-workers=1`)

Toolchain: JDK 17.0.19, Android SDK platform 34 at `/opt/deepseek-harness/android-sdk`
(via `local.properties` `sdk.dir`), Gradle 8.9.

1. Focused new tests:

```
./gradlew --offline --no-daemon --max-workers=1 :app:testDebugUnitTest \
    --tests "com.clawmate.app.datamanager.ProfileIndexTest" \
    --tests "com.clawmate.app.home.ProfileIndexBlenderTest" \
    --tests "com.clawmate.app.home.ProfileGenerationViewModelTest"
```
Result: `BUILD SUCCESSFUL` — **tests=19+4+3=26, failures=0, errors=0**.

2. Full unit suite: `:app:testDebugUnitTest` — `BUILD SUCCESSFUL`, **113 test
   classes / 858 tests, 0 failures, 0 errors, 0 skipped** (aggregated from the
   `app/build/test-results/testDebugUnitTest/TEST-*.xml` reports; baseline Phase-31
   was 110 classes / 832 tests, this phase adds 3 classes / 26 tests).

3. Build: `:app:assembleDebug` — `BUILD SUCCESSFUL`; `app/build/outputs/apk/debug/app-debug.apk`
   produced.

## Safety

- **Zero network unless configured and real events exist.** No model config or no
  indexed events → typed local result, no request.
- **No fabricated claims.** Local-only runs carry empty claims (LOCAL_ONLY); model
  claims are admitted only when they reference real indexed event ids and pass
  every bound (kind/confidence whitelist, lengths, count, min evidence).
- **Model failure preserves determinism.** Returns typed PARTIAL/ERROR and never
  overwrites a previously persisted valid model state; the deterministic local
  projection is kept.
- **Explicit user action only.** The Home "生成画像" button is the only entry and is
  never invoked at launch / recomposition / background / normal Home load.
- **Corrupt/oversized artifacts recover honestly** (to empty/local), never falsify a
  run.
- **Local-first Home integration.** `ProfileIndexBlender` fills only un-populated
  pillars from bounded index summary/claims + real evidence ids; a user's curated
  domain is preserved verbatim; no URI / image / token / unrelated data is imported.
- No new credentials, no NFC changes (Phase 31 NFC behaviour is untouched), no
  `Runtime.exec`/ProcessBuilder/adb/hdc/coordinate GUI automation.

## Remaining (honestly not ported / not fully equivalent)

- **Periodic scheduling** of consolidation/index/profile rebuild (no WorkManager /
  background job; explicit user action only).
- **Full cloud consolidation / model-based dedup / enrichment** (Harmony uses the
  cloud client for split/dedup/enrich; Phase 32 remains deterministic-local for
  projection, with only claims generation using the model).
- **`workflow_scene_id` enrichment** and the **entity resolver**
  (`entity/index.json`, `entityMatchIds`, alias postings) — not ported; claims rely
  on literal four-domain text evidence only.
