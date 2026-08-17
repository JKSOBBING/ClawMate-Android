# DSH_PHASE_RESULT — ClawMate Android, Phase 34 (cloud/model consolidation + model-based dedup)

## Commit

- Working against base `dccb37e` (Phase 33) in worktree `/root/phase34-worktree`.
  The changes are exported as a real, reviewable diff in `/root/phase34.patch`
  (paths `a/ClawMate-Android/…` `b/ClawMate-Android/…`), applying cleanly from
  `/root` with `git apply -p1`.
- Base repo `/root/ClawMate-Android` and Harmony reference `/root/mobiinfra-oh`
  are read-only: **neither was modified**. All edits live in the phase-34 worktree.

## Context / provenance

Phase 34 migrates Harmony `ConsolidationService.ets` (cloud/model consolidation)
and `DataPipelineModelClient.ets` (model-based dedup) to Android, on top of the
Phase-30 local engine / Phase-32 profile / Phase-33 scene+entity indexes, **without
repeating** Phase 31 (NFC) or Phase 32 (profile claims) or Phase 33 (scene/entity
index). It:

1. adds a typed model contract (`DedupDecision`,
   `new|duplicate|merge_with_existing|skip_ambiguous`, `matched_record_ids` /
   `reason` / `merged_summary` / `merged_fact` / `merged_tags`) and a config gate
   that reuses the shared `EndpointConfig`/`ChatServerConfig` Planner backend;
2. adds a strict-validating `ConsolidationModelClient` that splits bounded real
   source entries into bounded candidate facts and decides a dedup decision, then
   **strictly validates** every reply — a generated fact must carry its real
   `source_ref`/`source_date`/`workflow_scene_id`; a decision token normalises to
   the strict set; any `matched_record_id` must resolve to a **real** recent-scope
   candidate id (unknown/duplicated/out-of-bounds → safe local fallback, so the
   model cannot fabricate a record or overwrite an unrelated one);
3. adds a pure `ModelConsolidationApplier` (pending-entry hash skip, exact
   recent-scope match first, then model/fallback dedup; merge targets only a
   genuine single in-scope record, `new` uses the next stable monotonic
   `record_id`, `skip_ambiguous` drops the fact; decisions audited in
   `model_notes`) and an explicit `CloudConsolidationRepository` +
   `ConsolidationModelClientFactory` — **zero network** unless an explicit,
   fully-configured, user-triggered action has real entries;
4. preserves the existing `state.json`/markdown layout, backward-compatible
   `model_notes` + `processed_entry_hashes`, stable monotonic ids, the recent
   window, and Phase-33 `workflow_scene_ids`; a failed model run never overwrites
   the previous valid state.

The Harmony code is read-only reference (/root/mobiinfra-oh was **not** modified).

## Files changed

New production files (`app/src/main/java/com/clawmate/app/datamanager/`):

- `CloudConsolidationTypes.kt` — `DedupDecision`, `CandidateFact`, bounded
  split/dedup prompt payloads, `ConsolidationPath` (`MODEL`/`PARTIAL`/`LOCAL`),
  `ModelConsolidationResult`, `AuditedDecision`.
- `ConsolidationModelConfig.kt` — shared-`EndpointConfig` config gate +
  `ConsolidationModelGate` (`Allow`/`LocalOnly`/`NotConfigured`).
- `ConsolidationModelClient.kt` — model split + dedup with strict validation,
  heuristic fallback, serial/bounded/cancellable calls, `DedupOutcome`/
  `DecisionSource`.
- `ModelConsolidationApplier.kt` — pure per-group model-aware consolidation.
- `CloudConsolidationRepository.kt` — explicit entry point with gate + local
  fallback + persistence.
- `ConsolidationModelClientFactory.kt` — production composition seam (wraps the
  shared `ChatCompletionsClient`).

Modified production files:

- `LocalConsolidationTypes.kt` — `ConsolidatedRecord.model_notes` +
  `ConsolidatedMetadata.processed_entry_hashes` (backward-compatible defaults).
- `ConsolidationText.kt` — `epochIsoNowUtc`, `sha256Text`, `SceneLabels`
  (`memorySceneLabel`).
- `ConsolidationCodec.kt` — `rebindRecords` keeps `model_notes` bounded/unique.

New test file:

- `app/src/test/java/com/clawmate/app/datamanager/CloudConsolidationTest.kt` —
  focused offline JVM tests.

## New test coverage

`CloudConsolidationTest` (device-free, over temp dirs): zero network when
unconfigured / no entries / non-explicit action; explicit gate; bounded prompts
with no URI/token/auth-secret leakage; strict JSON malformed / unknown decision /
unknown matched id / over-long fact; new/duplicate/merge_with_existing/
skip_ambiguous (including via the deterministic applier); model-failure fallback
that preserves the prior valid state; stable monotonic record ids + processed-hash
skip (idempotent repeat); Phase-33 `workflow_scene_id` preservation through model
facts; hostile merge-to-nonexistent-id never mislabels or overwrites; auditable
never-secret `model_notes`; cooperative cancellation.

## Verification (serial, `--offline --no-daemon --max-workers=1`)

Toolchain: JDK 17.0.19, Android SDK platform 34 at `/opt/deepseek-harness/android-sdk`
(via `local.properties` `sdk.dir`), Gradle 8.9.

1. Focused new tests: `:app:testDebugUnitTest --tests CloudConsolidationTest`
   — `BUILD SUCCESSFUL`, **21 tests, 0 failures, 0 errors**.
2. Existing datamanager suites (`LocalConsolidationTest`, `ProfileIndexTest`,
   `EntitySceneIndexTest`, `FileDailyLogEventSourceTest`, etc.) re-run green.
3. Full unit suite: `:app:testDebugUnitTest` — `BUILD SUCCESSFUL`, **115 test
   classes / 896 tests, 0 failures, 0 errors, 0 skipped** (aggregated from the
   `app/build/test-results/testDebugUnitTest/TEST-*.xml` reports; the Phase-33
   baseline was 114 classes / 875 tests, this phase adds 1 class / 21 tests).
4. Build: `:app:assembleDebug` — `BUILD SUCCESSFUL`; `app/build/outputs/apk/debug/app-debug.apk`
   produced.
5. `git diff --check` — clean (no whitespace errors).

## Safety

- **Zero network by default.** The model path runs only for an explicit,
  fully-configured, user-triggered consolidation with real entries
  (`ConsolidationModelGate.Allow`); every other disposition short-circuits to the
  deterministic local engine (typed `LOCAL`) and never sends a request. Launch,
  Home load, recomposition and background/WorkManager never trigger it.
- **No fabricated records / no unrelated overwrite.** A hostile or unverifiable
  decision (unknown / duplicated / out-of-bounds matched id) falls back to a `new`
  fact; the persisted state is only ever updated through a genuine real in-scope
  record, and a failed model run preserves the previous valid state.
- **Bounded, serial, cancellable, error-redacted** model calls (one request at a
  time, batched entries, hard prompt-char caps); no URI / image / token / external
  path / secret in prompts, notes, logs or artifacts.
- **Backward compatible + atomic** persistence; stable monotonic `record_id`s,
  `processed_entry_hashes` skip, recent window and Phase-33 `workflow_scene_ids`
  preserved; repeat runs idempotent.
- No new credentials; Phase-31 NFC and Phase-33 scene/entity behavior untouched;
  no `Runtime.exec`/adb/hdc/coordinate GUI automation; no external-storage scanning.

## Remaining (honestly not ported / not fully equivalent)

- **Periodic scheduling** of consolidation/index/profile rebuild (no WorkManager /
  background job; explicit user action only).
- The **model-backed entity-resolution/enrichment path** (Phase 34 migrates model
  consolidation/dedup, **not** the separate entity-enrichment model path; Android
  keeps the deterministic local resolver + typed model gate).
- **`workflow_scene_id` enrichment** (per-event scene labels / reweighting) is not
  yet surfaced into query/recommendation surfaces.
