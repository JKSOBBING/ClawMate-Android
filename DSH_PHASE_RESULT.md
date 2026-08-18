# DSH_PHASE_RESULT — ClawMate Android, Phase 38 (downstream fact-split persistence + persisted batch event enrichment)

## Commit / scope

- Working tree: `/root/phase38-worktree`, a git worktree of `/root/ClawMate-Android`
  based on commit `3da9c48` (**Phase 37**). Phase-38 source changes are delivered as
  `/root/phase38.patch`; this document records verification + migration status. No
  `git commit` was made.
- Base repo `/root/ClawMate-Android` and Harmony reference `/root/mobiinfra-oh` are
  read-only-referenced: **neither was modified** by this phase.

## What was done (genuine downstream wiring — not documentation-only)

Phase 38 closes the two Phase-36/37 "returned but not persisted / not read"
gaps that the Phase-37 result explicitly left open, without re-doing Phase-34–37
(the strict fact split / entity enrichment / strict JSON are untouched):

### Task 1 — Fact-split → persisted model consolidation

The Phase-37 `FactSplitRunner` seams returned a candidate `Map` keyed by real
`source_ref` and never persisted. Now a downstream coordinator genuinely consumes
those facts into the **existing** model-aware engine:

- **`CloudConsolidationRepository.consolidateWithSplitFacts(...)`** — a new public
  method that reuses the exact same engine + persistence path as the existing
  `consolidate` (grouping by month/file, `ModelConsolidationApplier.applyPending`,
  per-group `outputs/<month>/<file>.md.state.json` + markdown + `state-index.json`,
  `ConsolidationEngine.summarize`). The model-path group loop was refactored into a
  shared `runModelGroups` so there is **no second consolidation engine**. A real
  entry whose `source_ref` is missing from the caller's map falls back to the
  deterministic heuristic split (never dropped).
- **`FactSplitConsolidationCoordinator`** — the explicit seam that runs
  `FactSplitRunner` (MODEL / LOCAL_ONLY / FALLBACK / EMPTY), converts the candidate
  facts into `CandidateFact`s, calls `consolidateWithSplitFacts`, then rebuilds the
  event index (`IndexService`) and profile (`ProfileIndexBuilder`) from the freshly
  persisted states. Returns a typed `CoordinatorResult` (MODEL / FALLBACK /
  LOCAL_ONLY / EMPTY + the persisted `ModelConsolidationResult`).
- **Discoverable explicit action** — a new Settings action「事实拆分→模型整合并持久
  化」(`runFactSplitAndConsolidate`) invokes the coordinator only from an explicit
  click; Zero network unless configured; MODEL / LOCAL_ONLY / FALLBACK / EMPTY are
  reported honestly; LOCAL_ONLY / EMPTY write nothing; a model failure on the split
  falls back to deterministic local facts and preserves the prior valid state.
  `runFactSplit` (the raw inspection action) is kept intact.

### Task 2 — Persisted batch event enrichment with a real read boundary

The Phase-36 `BatchEventEnricher` returned a keyed typed surface but persisted
nothing and was not read by any query/recommendation boundary. Now:

- **`BatchEventEnrichmentStore`** — writes the enrichment keys
  (keywords / domains / entities / todo / enrichment_method) into a **versioned,
  atomically written** app-private artifact
  (`indexes/events/event-enrichment.json`, schema
  `datamanager-event-enrichment-local-v1`), strictly by **real `event_id`**,
  **only on an explicit MODEL success** (LOCAL_ONLY / FALLBACK / EMPTY never write;
  the old artifact is preserved byte-for-byte). Never writes a URI / path / token /
  secret.
- **`IndexedEvent` gained optional enrich fields** (empty defaults) → old
  Phase-30/33 JSON still decodes (backward compatible).
- **`BatchEventEnrichmentOverlay`** — a pure read-side overlay; wired into the
  **event query** (`DailyLogLocalIndex.query`) and **profile** (`ProfileIndexBuilder
  .loadEvents`) boundaries so they read the persisted fields after an explicit
  persisted MODEL success; with no artifact they keep the old behaviour.
- **`runBatchEventEnrichment`** now persists the MODEL result through the store.

### Task 3 — WorkManager

The Phase-37 dependency gap was **re-verified** this phase: `androidx.work` and its
transitives (`lifecycle-service`, `sqlite-framework`, `annotation-experimental`,
`listenablefuture`) are still **absent from the offline Gradle cache**, so a real
`Worker` / `PeriodicWorkRequest` was **not** fabricated (doing so would need a
forbidden network download). The `PeriodicTriggerAdapter` / `SchedulingPolicy`
remain the replaceable contract; the explicit Settings-driven schedule host is
unchanged and this phase's real persisted actions are recorded under the explicit
user-triggered boundary.

## Files changed

New production (`app/src/main/java/com/clawmate/app/datamanager/`):

- `FactSplitConsolidationCoordinator.kt`
- `BatchEventEnrichmentStore.kt`
- `BatchEventEnrichmentOverlay.kt`

Modified production:

- `CloudConsolidationRepository.kt` — `consolidateWithSplitFacts` + shared
  `runModelGroups`.
- `ConsolidationModelClientFactory.kt` — `factSplitConsolidation(baseDir)`.
- `LocalConsolidationTypes.kt` — optional `IndexedEvent` enrich fields.
- `DataManagerPaths.kt` — `eventEnrichment(baseDir)` path.
- `DailyLogLocalIndex.kt`, `ProfileIndexBuilder.kt` — read-side overlay.
- `settings/ui/SettingsViewModel.kt`, `SettingsViewModelFactory.kt`,
  `SettingsUiState.kt`, `SettingsSections.kt` — injected coordinator + store +
  explicit action + status text.

New tests:

- `app/src/test/.../datamanager/FactSplitConsolidationTest.kt`
- `app/src/test/.../datamanager/BatchEventEnrichmentStoreTest.kt`
- modified `app/src/test/.../settings/SettingsSchedulingEnrichTest.kt`

## Verification (serial, `--offline --no-daemon --max-workers=1`)

1. Focused new/modified tests all green: `FactSplitConsolidationTest` (6),
   `BatchEventEnrichmentStoreTest` (6), plus pre-existing `CloudConsolidationTest`,
   `BatchEventEnrichTest`, `SettingsSchedulingEnrichTest`.
2. Full unit suite: `:app:testDebugUnitTest` — `BUILD SUCCESSFUL`, **124 test
   classes / 984 tests, 0 failures, 0 errors, 0 skipped** (Phase-37 baseline: 121
   classes / 951 tests; +3 classes / +33 tests) — aggregated from
   `app/build/test-results/testDebugUnitTest/TEST-*.xml`.
3. Build: `:app:assembleDebug` — `BUILD SUCCESSFUL`; `app/build/outputs/apk/debug/
   app-debug.apk` produced. Independent APK debug SHA256:
   `866b18424be84e3aad7fb2be579bd0050d9f1474b31c0cf92faadfc83c3d6669`.
4. `git diff --check` — clean (no whitespace errors); `local.properties` +
   `build/` outputs are git-ignored and not part of the change set.

## Safety / honesty

- **Default disabled, zero network.** Nothing auto-runs from `Application` / Home /
  recomposition / a repository / VM constructor. Model paths are reached only by an
  explicit click with a fully-configured config + real input.
- **MODEL / LOCAL_ONLY / FALLBACK / EMPTY are honestly reported**; a failed /
  malformed / unknown-id model path always falls back to the deterministic local
  result and the prior valid persisted state is preserved; LOCAL_ONLY / EMPTY write
  nothing.
- **No secret / URI / path.** No artifact carries a token, secret, URI or external
  path.
- **No second consolidation engine.** The fact-split coordinator reuses the existing
  `CloudConsolidationRepository` + `ModelConsolidationApplier`.
- **WorkManager not faked.** No `Worker`/`PeriodicWorkRequest` was fabricated given
  the confirmed offline dependency gap; the adapter contract remains the seam.

## Remaining (honestly not ported)

- Real Android `WorkManager` `Worker` / `PeriodicWorkRequest` (offline cache gap).
- Automated background cadence (still explicit-action-driven).
- Folding the persisted enrich fields into the raw daily-log → Home-planner mapping
  (which maps `DailyLogEntry`, not indexed events) — the genuine read seams are the
  event-query and profile boundaries.
