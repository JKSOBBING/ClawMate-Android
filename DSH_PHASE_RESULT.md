# DSH_PHASE_RESULT — ClawMate Android, Phase 35 (periodic local-consolidation scheduler seam + explicit model entity-enrichment seam)

## Commit

- Working on `0e46581` (**Phase 35**) in `/root/ClawMate-Android` at HEAD.
  The Phase-35 source changes are in `/root/phase35.patch`; this document records
  the verification and migration status in the target working tree.
- Base repo `/root/ClawMate-Android` and Harmony reference `/root/mobiinfra-oh`
  are read-only-referenced: **neither was modified** by this documentation patch.

## Context / provenance

Phase 35 adds two **explicit seams** on top of the Phase-33 entity index / Phase-34
model consolidation, on the Android target. It delivers no WorkManager dependency
and no UI wiring: both seams are disabled-by-default, in-process, JVM-testable
entry points that a future UI / Application host and an actual WorkManager worker
can wire independently. It:

1. adds the **periodic local-consolidation scheduler seam** — a disabled-by-default,
   in-process controller (`PeriodicConsolidationScheduler` + pure
   `SchedulingPolicy`) with **no WorkManager dependency**, explicit `LOCAL_ONLY` +
   `NOT_REQUIRED` opt-in, and serial / idempotent / cancellable execution of
   deterministic local consolidation/index/profile rebuilds. It never references
   the model-backed `CloudConsolidationRepository`;
2. adds the **explicit model entity-enrichment seam** — `EntityEnrichmentRunner`
   + strict-validating `EntityEnrichmentModelClient` + `EntityEnrichmentClientFactory`
   that reuse the shared `ChatClient` / `ConsolidationModelConfig`. It subjects a
   bounded candidate set (from the real local entity scope) to bounded strict JSON
   with a **real candidate-id / source-ref whitelist** (unknown / duplicate /
   out-of-bounds / over-long values dropped), is **zero network by default**
   (local-only unless configured + explicit action + real candidates), and **does
   not auto-persist** — it returns a new enriched document to the caller.

The Harmony code is read-only reference (/root/mobiinfra-oh was **not** modified).

## Files changed

New production files (`app/src/main/java/com/clawmate/app/datamanager/`):

- `SchedulingPolicy.kt` — pure policy surface: `SchedulerMode.DISABLED` (the only
  production default) / `LOCAL_ONLY`, `NetworkConstraint.NOT_REQUIRED`, task-name +
  conflict policy, bounded/clamped interval. No secrets, no network, no WorkManager.
- `PeriodicConsolidationScheduler.kt` — the single explicit registration seam:
  `register` / `cancel` / `isScheduled` / `runScheduledOnce` / `runAllScheduledOnce`,
  all serial through one `Mutex`, idempotent local jobs, cooperative cancellation
  (`isCancelled`), and a `DISABLED` policy schedules nothing.
- `EntityEnrichment.kt` — `EntityEnrichmentTypes`: bounded candidates (`EntityCandidate`),
  strict whitelisting caps, strict `person|place|merchant` types (model `unknown` is
  dropped), `RichEntity`, honest `EnrichmentStatus` (`EMPTY`/`LOCAL_ONLY`/`MODEL`/
  `FALLBACK`), and a never-persisted `EnrichmentResult`.
- `EntityEnrichmentModelClient.kt` — serial/bounded/cancellable model call with
  **strict JSON + real candidate-id/source-ref whitelist validation**; hostile,
  malformed, unknown/mismatched or over-long replies are dropped, never written.
- `EntityEnrichmentRunner.kt` — the single explicit enrichment seam: loads the real
  local scope, computes the deterministic local entity document, and only for an
  explicit + fully-configured action with real candidates invites the model; the
  merged enriched document is **returned, not persisted**; failure/EMPTY/LOCAL_ONLY
  preserve the existing artifact byte-for-byte.
- `EntityEnrichmentClientFactory.kt` — production composition helper (wraps the
  shared `ChatClient`/`ChatCompletionsClient` behind an `EntityEnrichmentModelClient`
  for a fully-validated `ConsolidationModelConfig`).

New test files:

- `app/src/test/java/com/clawmate/app/datamanager/PeriodicConsolidationSchedulerTest.kt`.
- `app/src/test/java/com/clawmate/app/datamanager/EntityEnrichmentTest.kt`.

## New test coverage

`PeriodicConsolidationSchedulerTest` (device-free, over temp dirs): disabled-by-default
policy schedules nothing; explicit `LOCAL_ONLY` + `NOT_REQUIRED` registration;
task-name uniqueness (REPLACE/KEEP conflict policy); serial single-run and run-all
(same mutex, never parallel); idempotent repeat runs reproduce identical artifacts;
cooperative cancellation aborts before start / between stages via `CancellationException`;
cancelled/failed runs leave no half-registration or half-artifact; the seam never
touches the model-backed repository.

`EntityEnrichmentTest` (device-free): EMPTY (no real candidates → zero network);
LOCAL_ONLY (not configured / not explicit → old artifact unchanged, zero network);
zero network by default; bounded prompt packing with no URI/token/secret leakage;
strict JSON malformed / unknown type / unknown or duplicate or out-of-bounds
record id / source ref / over-long values all dropped; real ids/source refs admitted;
`workflow_scene_ids`/`entity_mentions`/`source_ref`/`date` preserved from the real
local scope; FALLBACK keeps the deterministic local document on model failure; a
MODEL success returns a new enriched document without writing the persisted index;
honest never-secret model notes; cooperative cancellation.

## Verification (serial, `--offline --no-daemon --max-workers=1`)

Toolchain: JDK 17.0.19, Android SDK platform 34 at `/opt/deepseek-harness/android-sdk`
(via `local.properties` `sdk.dir`), Gradle 8.9.

1. Focused new tests: `:app:testDebugUnitTest --tests PeriodicConsolidationSchedulerTest`
   and `--tests EntityEnrichmentTest` — `BUILD SUCCESSFUL`, **0 failures, 0 errors**.
2. Existing datamanager suites (`CloudConsolidationTest`, `LocalConsolidationTest`,
   `ProfileIndexTest`, `EntitySceneIndexTest`, `FileDailyLogEventSourceTest`, etc.)
   re-run green.
3. Full unit suite: `:app:testDebugUnitTest` — `BUILD SUCCESSFUL`, **117 test
   classes / 922 tests, 0 failures, 0 errors, 0 skipped** (aggregated from the
   `app/build/test-results/testDebugUnitTest/TEST-*.xml` reports; the Phase-34
   baseline was 115 classes / 896 tests, this phase adds 2 classes / 26 tests).
4. Build: `:app:assembleDebug` — `BUILD SUCCESSFUL`; `app/build/outputs/apk/debug/app-debug.apk`
   produced. Target APK independent SHA256:
   `dd9672c979a6a7b1c1cba6ff5bc1331828d3eab4bf20cf9c8b59f465edbdf425`.
5. `git diff --check` — clean (no whitespace errors).

This patch only updates migration documentation (`MIGRATION_STATUS.md`,
`DSH_PHASE_RESULT.md`); **no source, no tests, and no build outputs were changed**
and nothing new is generated.

## Safety

- **Disabled by default.** The scheduler's production default is
  `SchedulerMode.DISABLED` — no task is ever registered or run unless an explicit
  caller builds a `LOCAL_ONLY` + `NOT_REQUIRED` policy. Nothing runs on launch,
  Home load, recomposition, or a repository/Application constructor.
- **No WorkManager dependency / no network from the seam.** There is no
  WorkManager worker and no network in either seam; the enrichment model path is
  **zero network unless** configured + explicit action + real candidates, and it
  **does not auto-persist** — the enriched document is returned to the caller.
- **Real-id / real-source whitelist.** Both seams admit only real in-scope ids and
  source refs; unknown / duplicate / out-of-bounds / over-long values and any
  URI/image/token/secret are dropped. The model can never invent a fact, record,
  or unrelated overwrite; a failed/EMPTY/LOCAL_ONLY run preserves the prior valid
  artifact.
- **Serial / idempotent / cancellable / error-redacted.** One task at a time,
  deterministic local rebuilds are idempotent, cancellation is cooperative and
  propagates, and config carries no secret.

## Remaining (honestly not ported / not wired)

- **Real WorkManager worker wiring** — the scheduler is an in-process JVM-testable
  seam; no Android `WorkManager` dependency and no `PeriodicWorkRequest` worker
  exists yet.
- **Explicit UI / Application wiring** — neither seam is invoked from a UI entry
  point, `Application.onCreate`, or any screen; there is still no user-facing
  schedule/enrich action.
- **`DataPipelineModelClient` generic batch event-enrich / profile-model claims** —
  the model entity-enrichment seam covers the Phase-33 entity-index scope only; the
  separate generic batch event-enrichment and profile model-claim paths are not
  ported; `workflow_scene_id` enrichment is not yet surfaced into
  query/recommendation surfaces.

The two seams are deliberate extension points for the real WorkManager worker
line, the UI/Application host line, and the generic `DataPipelineModelClient`
enrich/profile lines — all of which remain un-wired.
