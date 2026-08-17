# DSH_PHASE_RESULT — ClawMate Android, Phase 33 (workflow-scene + entity index/resolver foundation)

## Commit

- Working against base `83366b5` (Phase 32) in worktree `/root/phase33-worktree`.
  The changes are exported as a real, reviewable diff in `/root/phase33.patch`
  (paths `a/ClawMate-Android/…` `b/ClawMate-Android/…`), applying cleanly from
  `/root` with `git apply -p1`.
- Base repo `/root/ClawMate-Android` and Harmony reference `/root/mobiinfra-oh`
  are read-only: **neither was modified**. All edits live in the phase-33 worktree.

## Context / provenance

Phase 33 ports Harmony `IndexService.buildWorkflowSceneDocument` and the
deterministic (local) half of `IndexService.buildEntityDocument`
(`entity/index.json`, alias postings, mention assignments, `ENTITY_TYPE_KEYS`) to
Android, **on top of, without repeating, Phase 32** (profile/claims) or Phase 31
(NFC). It:

1. propagates real `workflow_scene_ids` from the daily-log `task_scene_id`
   metadata through the Phase-30 consolidation into indexed events (backward
   compatible with old Phase-30 artifacts);
2. builds `workflows/profile-consolidation/indexes/workflow-scene/index.json`
   (`postings: sceneId -> stable event ids`) from those real scene ids;
3. builds `workflows/profile-consolidation/indexes/entity/index.json` (canonical
   entities person/place/merchant, aliases, event ids, alias postings, mention
   assignments) via a deterministic **local-only** resolver from real event
   mentions + conservative labelled-name extraction — no fabricated entities;
4. exposes the single explicit, testable rebuild (`EntitySceneIndexRunner`) that
   atomically writes exactly the two artifacts and never touches profile
   `state.json`/`profile.md` or Home data.

The Harmony code is read-only reference (/root/mobiinfra-oh was **not** modified).

## Files changed

New production files (`app/src/main/java/com/clawmate/app/datamanager/`):

- `EntitySceneIndexTypes.kt` — `WorkflowSceneDocument` (`schema`/`generated_at`/
  `postings`), `EntityDocument`/`EntityDefinition`, `SceneEntityIndexResult`,
  typed `EntityResolutionStatus`.
- `EntitySceneCodec.kt` — tolerant, bounded serde for the scene + entity artifacts.
- `SceneEntityNormalizer.kt` — `normalizeSceneId/Ids` (incl. legacy 7→1),
  `normalizeEntityName`, `entityLookupKey/entityDisplayKey/lookupText`,
  `normalizeEntityMentions`, conservative `deriveRuleEntities`, `uniqueEntityNames`.
- `WorkflowSceneIndexBuilder.kt` — build+persist `workflow-scene/index.json`.
- `EntityResolver.kt` — deterministic local resolution (typed value-overlap merge).
- `EntitySceneIndexBuilder.kt` — collect event mentions (+ conservative extraction),
  resolve and persist `entity/index.json`.
- `EntitySceneIndexRunner.kt` — single explicit rebuild entry (reads event
  partitions, atomic two-artifact write, model gate).
- `EntityModelConfig.kt` — `EntityModelBoundary` typed model gate (blocked /
  not-configured / configured) reusing `ProfileModelConfig`.

Modified production files:

- `LocalConsolidationTypes.kt` — `IndexedEvent.workflow_scene_ids` +
  `entity_mentions`, `ConsolidatedRecord.workflow_scene_ids`, new
  `EntityMention` model, scene/entity schema constants (all with backward-compatible
  defaults so old artifacts decode honestly).
- `ConsolidationEngine.kt` — `CandidateFact.workflowSceneId` read from
  `task_scene_id` metadata; records now carry deduped/sorted real scene ids.
- `ConsolidationCodec.kt` — `rebindRecords` normalizes `workflow_scene_ids`.
- `IndexService.kt` — event projection now includes `workflow_scene_ids`.
- `DataManagerPaths.kt` — `workflowSceneIndex` + `entityIndex` paths.

## New test file

- `app/src/test/java/com/clawmate/app/datamanager/EntitySceneIndexTest.kt` —
  **17 focused, offline JVM tests** over temp dirs: empty input → zero network +
  no artifacts; events without scene/mentions never auto-create; multi-scene
  postings (dedup + sorted stable ids); rerun stability; same-type alias merge;
  different-type no-merge; unknown/empty/over-long dropped; corrupt + oversized
  artifact recovery; old Phase-30 artifact backward compatibility; bounded JSON;
  conservative rule extraction (no pseudo/log-segment entities); model gate
  (BLOCKED/NOT_CONFIGURED/CONFIGURED); runner-with-configured-model still local +
  zero network; runner never creates profile/Home artifacts; real `task_scene_id`
  → scene-index propagation through the Phase-30 pipeline; legacy 7→1 scene map.

## Verification (serial, `--offline --no-daemon --max-workers=1`)

Toolchain: JDK 17.0.19, Android SDK platform 34 at `/opt/deepseek-harness/android-sdk`
(via `local.properties` `sdk.dir`), Gradle 8.9.

1. Focused new tests: `:app:testDebugUnitTest --tests EntitySceneIndexTest`
   — `BUILD SUCCESSFUL`, **tests=18, failures=0, errors=0**.
2. Existing datamanager suites (`ProfileIndexTest`, `LocalConsolidationTest`,
   `FileDailyLogEventSourceTest`, etc.) — `BUILD SUCCESSFUL`, 0 failures.
3. Full unit suite: `:app:testDebugUnitTest` — `BUILD SUCCESSFUL`, **114 test
   classes / 875 tests, 0 failures, 0 errors, 0 skipped** (aggregated from the
   `app/build/test-results/testDebugUnitTest/TEST-*.xml` reports; the Phase-32
   baseline was 113 classes / 858 tests, this phase adds 1 class / 17 tests).
4. Build: `:app:assembleDebug` — `BUILD SUCCESSFUL`; `app/build/outputs/apk/debug/app-debug.apk`
   produced.
5. `git diff --check` — clean (no whitespace errors).

## Safety

- **Zero network by default.** Android's entity path is deterministic local-only;
  a model call is only ever invited by an explicit, fully-configured,
  user-triggered index/enrichment action (`EntityModelBoundary.gate`) — never on
  Home launch/load, and never with missing config or no real mentions.
- **No fabricated entities.** Only real event mention tokens or conservative
  labelled-name extractions are admitted; unknown types / empty / over-long /
  URI / image / token / whole-log-segment are dropped.
- **Scene ids come from real events** (`task_scene_id` metadata), never invented.
- **Backward compatible** with old Phase-30 event partitions (new fields default
  to empty on tolerant decode).
- **Bounded + atomic** scene/entity artifacts; corrupt / oversized recover to an
  honest empty document (never thrown / never falsified). `EntitySceneIndexRunner`
  writes only the two artifacts and preserves profile state / `profile.md` / Home
  data unchanged.
- No new credentials, no NFC changes (Phase-31 behaviour untouched), no
  `Runtime.exec`/adb/hdc/coordinate GUI automation, no external-storage scanning.

## Remaining (honestly not ported / not fully equivalent)

- **Periodic scheduling** of consolidation/index/profile rebuild (no WorkManager /
  background job; explicit user action only).
- **Full cloud consolidation / model-based dedup** and the **model-backed entity
  resolution/enrichment path** (Harmony uses the cloud model client; Android keeps
  the deterministic local resolver + typed model gate with **no network-invoked
  calls by default**).
- **`workflow_scene_id` enrichment** (per-event scene labels / reweighting) is not
  yet surfaced into query/recommendation surfaces — Phase 33 persists the scene
  postings only.
