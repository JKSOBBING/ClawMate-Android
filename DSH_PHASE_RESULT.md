# DSH_PHASE_RESULT — ClawMate Android, Phase 36 (schedule wiring + explicit enrich UI + generic batch event enrichment)

## Commit

- Working tree: `/root/phase36-worktree`, a git worktree of `/root/ClawMate-Android`
  based on commit `fd4a44c` (**Phase 35**). Phase-36 source changes are delivered
  as `/root/phase36.patch`; this document records the verification and migration
  status of the change. No `git commit` was made.
- Base repo `/root/ClawMate-Android` and Harmony reference `/root/mobiinfra-oh`
  are read-only-referenced: **neither was modified** by this phase.

## Context / provenance

Phase 36 continues the migration audit by closing three **real, previously
un-wired** gaps that Phase 35 explicitly left open, **without repeating**
Phase 34/35:

1. **Schedule wiring —** a **minimal replaceable Android periodic-trigger
   adapter** (`PeriodicTriggerAdapter` + `InProcessPeriodicTriggerAdapter` +
   `PeriodicSchedulingHost`). **WorkManager was not in the offline Gradle cache**
   (verified at phase start) and downloading it would violate the offline/no-
   network rule, so no `androidx.work` dependency was introduced — instead the
   Phase-35 `PeriodicConsolidationScheduler` is now driven through a small
   replaceable adapter. The **only** registration entry is an explicit Settings
   action; the schedule is **disabled by default** and is never registered from
   `Application.onCreate`, Home load, recomposition, or a repository/Application
   constructor. The scheduled job is strictly `LocalOnlyPeriodicJob` (deterministic
   local consolidation / index / profile rebuild) and never calls
   `CloudConsolidationRepository`.
2. **Entity enrichment explicit UI/host wiring —** the Phase-35
   `EntityEnrichmentRunner` is wired to an explicit Settings action in
   `TaskAutomationSection`, with a typed result + honest state and a **caller-only
   persistence** seam (`EntityEnrichmentPersistence`) that writes the model-enriched
   document **only on an explicit MODEL success**. LOCAL_ONLY / FALLBACK / EMPTY
   never write and the old artifact is preserved. The zero-network/default gate is
   unchanged.
3. **Generic model capability —** a **new bounded typed seam** for the Harmony
   `DataPipelineModelClient.enrichEvents` generic batch event-enrich path
   (keyword / domain / entity / todo extraction) was ported onto the existing
   `ChatClient` / `ConsolidationModelConfig`: `BatchEventEnrichTypes` +
   `BatchEventEnricher` + `BatchEventEnrichModelClient` +
   `BatchEventEnrichClientFactory` + deterministic `EnrichRules` helpers. It is
   strictly-JSON, real `event_id`-scoped, explicit-action-only, serial / cancellable,
   falls back to a deterministic local enrich, and carries no URI/token/secret.
   The profile **model-claim** path was already ported in Phase 32
   (`ProfileModelClient` / `ProfileModelValidator`); this phase leaves it as the
   authoritative profile-claims implementation and documents it (no duplication).

## Files changed

New production files (`app/src/main/java/com/clawmate/app/datamanager/`):

- `PeriodicTriggerAdapter.kt` — replaceable periodic-trigger contract + typed
  `PeriodicScheduleResult`; `InProcessPeriodicTriggerAdapter` wraps the Phase-35
  `PeriodicConsolidationScheduler` under the single canonical local task name.
- `PeriodicSchedulingHost.kt` — the explicit-only scheduling controller: binds a
  persisted `periodicLocalConsolidationEnabled` opt-in to the adapter; `false`
  cancels/schedules nothing, `true` registers one local-only task. Only invoked
  from an explicit Settings action.
- `BatchEventEnrich.kt` — bounded types for the generic batch event-enrich seam.
- `EnrichRules.kt` — deterministic rule fallback/merge helpers (`deriveRuleKeywords`,
  `deriveRuleDomains`, `normalizeDomains`, `workflowSceneLabels`,
  `weekdayLabelFromDate`, six-domain `DOMAIN_RULES`).
- `BatchEventEnrichModelClient.kt` — strict sanitising model client reusing
  `ChatClient`/`ConsolidationModelConfig`; real-event-id whitelist; strict-JSON
  decode; merges model terms with rule baseline.
- `BatchEventEnricher.kt` — the single explicit seam (EMPTY / LOCAL_ONLY / MODEL /
  FALLBACK); never auto-persists.
- `BatchEventEnrichClientFactory.kt` — production composition helper.

New test files:

- `app/src/test/java/com/clawmate/app/datamanager/PeriodicSchedulingHostTest.kt`
- `app/src/test/java/com/clawmate/app/datamanager/BatchEventEnrichTest.kt`
- `app/src/test/java/com/clawmate/app/datamanager/EntityEnrichmentPersistenceTest.kt`
- `app/src/test/java/com/clawmate/app/settings/SettingsSchedulingEnrichTest.kt`

Modified production files:

- `app/src/main/java/com/clawmate/app/settings/domain/AppSettings.kt` — new
  `periodicLocalConsolidationEnabled` (default `false`).
- `app/src/main/java/com/clawmate/app/settings/domain/SettingsKeys.kt` — new key +
  `ALL_KEYS` (not sensitive).
- `app/src/main/java/com/clawmate/app/settings/data/SettingsCodec.kt` — encode +
  decode round-trip for the new key.
- `app/src/main/java/com/clawmate/app/settings/ui/SettingsViewModel.kt` — injectable
  scheduling host / enrich runners (defaults = no-op, device-free), plus explicit
  `schedulePeriodicLocalConsolidation`, `runPeriodicLocalConsolidationNow`,
  `runEntityEnrichment`, `runBatchEventEnrichment` actions.
- `app/src/main/java/com/clawmate/app/settings/ui/SettingsViewModelFactory.kt` —
  injects the filesDir-backed host/enrichers in `forContext`.
- `app/src/main/java/com/clawmate/app/settings/ui/SettingsUiState.kt` — scheduling /
  enrich state fields.
- `app/src/main/java/com/clawmate/app/settings/ui/SettingsSections.kt` — three
  explicit action groups in `TaskAutomationSection` (periodic scheduling + entity
  enrich + batch enrich). **No new Settings section** was added (keeps the
  `SettingsSectionTest` 7-section contract unchanged).

## New test coverage

`PeriodicSchedulingHostTest` (device-free, temp dirs): constructing the host
schedules nothing even with the opt-in assumed `true` (explicit-only); explicit
`false` disables and cancels any prior schedule; explicit `true` registers exactly
one canonical local-only task (REPLACE, unique name); the adapter holds no
secret/path; the scheduled local run rebuilds deterministic local artifacts,
stays local (never the model-backed repository) and is idempotent; `runNow` is a
harmless no-op when not scheduled / not enabled; cancel removes the task;
cancellation aborts a run before start.

`BatchEventEnrichTest` (device-free, scripted `ChatClient`): no events -> EMPTY
with zero network; non-explicit / incomplete-config action -> LOCAL_ONLY with zero
network (deterministic local enrich returned); MODEL — a strict-JSON reply over a
real event id is admitted (keywords/domains merged with rule baseline, `other`
dropped when a specific domain present, strict entity types, bounded todo); unknown
model event id is dropped and never creates an event (FALLBACK keeps the local
enrich); malformed JSON -> FALLBACK with deterministic local enrich; model failure
-> FALLBACK; wrong entity type / over-bounds domain / invalid deadline are dropped
not coerced; cancellation propagates.

`EntityEnrichmentPersistenceTest`: only a MODEL result with an enriched document is
persist-eligible; `writeEnriched` persists the model-enriched document on explicit
MODEL success; LOCAL_ONLY / FALLBACK / EMPTY are never persisted (old artifact
preserved byte-for-byte); write returns false when there is nothing to persist.

`SettingsSchedulingEnrichTest`: a device-free ViewModel (no host injected) reports
disabled and schedules nothing; default opt-in is false; explicit opt-in true +
schedule action registers one local task; explicit opt-in false cancels / leaves
disabled; `run now` does nothing unless scheduled and opted-in; enrich actions are
zero-network guarded by default; scheduling opt-in round-trips through the codec.

## Verification (serial, `--offline --no-daemon --max-workers=1`)

1. Focused new classes (`PeriodicSchedulingHostTest`, `BatchEventEnrichTest`,
   `EntityEnrichmentPersistenceTest`, `SettingsSchedulingEnrichTest`) — all green.
2. Full unit suite: `:app:testDebugUnitTest` — `BUILD SUCCESSFUL`, **121 test
   classes / 951 tests, 0 failures, 0 errors, 0 skipped** (aggregated from
   `app/build/test-results/testDebugUnitTest/TEST-*.xml`; the Phase-35 baseline was
   117 classes / 922 tests, +4 classes / +29 tests).
3. Build: `:app:assembleDebug` — `BUILD SUCCESSFUL`; `app/build/outputs/apk/debug/
   app-debug.apk` produced. Target APK independent SHA256:
   `90393ea0775e4f404c84cbad58d7e4ccff8f2ce5bb18b7c5cb418fd8b1ada78c`.
4. `git diff --check` — clean (no whitespace errors).

## Safety

- **Disabled by default.** The scheduler opt-in default is `false`; even a
  persisted `true` schedules nothing until the explicit Settings "应用调度" action
  is clicked. No registration from `Application.onCreate` / Home load /
  recomposition / a repository / Application constructor / a plain VM construction.
- **Local-only periodic work.** The scheduled job is `LocalOnlyPeriodicJob`
  (Phase-30/33/32 deterministic local rebuild); it never references
  `CloudConsolidationRepository` and opens no network. Unique canonical task name,
  serial, idempotent, cancellable, failure/cancel-safe, carries no secret.
- **Explicit entity enrichment, explicit-only persistence.** Enrichment never runs
  on its own; the returned enriched document is persisted **only** on an explicit
  MODEL success via the caller write seam; LOCAL_ONLY / FALLBACK / EMPTY never
  write and preserve the old artifact. zero-network/default gate retained.
- **Bounded generic batch enrich.** Strict-JSON, real `event_id` whitelist, strict
  domain/entity/todo vocabularies, deterministic rule fallback, serial/cancellable,
  no URI / image / token / secret / invented event or id; result is returned (never
  auto-written).
- **No WorkManager dependency / no network downloaded.** Only in-process adapter +
  existing shared `ChatClient`; all Gradle runs `--offline`.

## Remaining (honestly not ported / not wired)

- **A real Android `WorkManager` worker / `PeriodicWorkRequest`** — intentionally
  not added because `androidx.work` is absent from the offline Gradle cache and
  pulling it needs a network download (forbidden). The `PeriodicTriggerAdapter`
  contract is the replaceable seam a future WorkManager `Worker` can implement
  without changing the host or the app.
- **Automated background triggering** — the schedule is still explicit-action-driven
  (no system-triggered background cadence); `runPeriodicLocalConsolidationNow` is an
  explicit "run now" only.
- **Applying generic batch enrich into query/recommendation surfaces** — the
  Phase-36 batch event-enrichment seam returns a new typed surface keyed by real
  event id; wiring it into `IndexService`/Home recommendation/collection grouping
  as persisted derived fields is not yet done (remains a future phase, like
  `workflow_scene_id` surfacing).
- **Profile model-claim path** — already ported in Phase 32 (`ProfileModelClient` /
  `ProfileModelValidator`); Phase 36 documents it as the authoritative profile-claims
  implementation rather than re-porting it.

## Port-fidelity notes (honest deltas vs Harmony `enrichEvents`)

The Phase-36 batch event-enrich seam is a faithful adaptation but has two deliberate,
documented differences from the Harmony `DataPipelineModelClient.enrichEvents` path:

- **It is distinct from (not a re-port of) the Phase-35 `EntityEnrichmentRunner`.**
  The Phase-35 seam clarifies the Phase-33 **entity index candidates**
  (`indexes/entity/index.json`, record/source-ref scope). The Phase-36 seam performs
  Harmony's generic **`enrichEvents`** path directly over the raw indexed events —
  per-event keyword/domain/entity/todo extraction that feeds a different downstream
  surface and is keyed by real `event_id`, not by entity candidate. Both seams share
  the same strict-JSON + real-id-whitelist + zero-network-by-default philosophy and
  reuse the same `ChatClient` / `ConsolidationModelConfig`, but they operate on
  different inputs and outputs; Phase 36 does not duplicate the entity-index
  enrichment.

- **`profile_domains` is not emitted.** Harmony writes
  `profile_domains = normalizeDomains(domains)` into each event. The Android
  `IndexedEvent` shape carries no `profile_domains` field, and the Android profile
  pipeline derives profile domains independently in Phase 32
  (`ProfileDomainUtils.deriveProfileDomains`); so this seam's `EnrichedEventFields`
  omits it rather than fabricating a field the downstream Android profile stack
  does not consume.
- **Batching by "union of scene guide" instead of "group-by-todo-guide-key".**
  Harmony groups events by `todoGuideKey` (pure-chat → `chat`, else first scene
  label) before chunking at size 8, so one batch shares a single scene-typed todo
  guide. The Android seam chunks sequentially at size 8 and builds the per-chunk
  todo guide from the **union of the scene labels present in that chunk** (each
  scene's rule + the shared "不要输出规则外的 type" tail), which keeps the prompt
  correct and bounded for any events present. This is a bounded, safe approximation
  (the multi-scene guidance rule is preserved) rather than a re-typing of the model
  output; it does not change any whitelist/dedup/fallback invariant.

All domain/entity/todo normalization, the real `event_id` whitelist, the `other`
coercion, and the `type=buy`+pure-chat `agent_task` gate are implemented exactly per
the Harmony spec (see the audit detail appended to the Phase-36 notes).

The three seams now have real Android explicit entry points (Settings actions) and
a clean replaceable adapter / persistence boundary, while keeping every
model/background path disabled-by-default and no WorkManager dependency.
