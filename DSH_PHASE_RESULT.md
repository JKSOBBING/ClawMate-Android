# DSH_PHASE_RESULT — ClawMate Android, Phase 4 (Task / Workflow vertical slice)

## Worktree

- Path: `/opt/deepseek-harness/clawmate-phase4-task`
- Branch: `master`
- Source of truth (reference only, not modified): `/root/mobiinfra-oh`
- This delivery was **built and tested in this worktree** (JDK 17.0.19,
  OpenJDK, Android SDK platform 34 / build-tools 34.0.0 at
  `/opt/deepseek-harness/android-sdk`, Gradle 8.9 at
  `/opt/deepseek-harness/gradle-dist/gradle-8.9`).
- The `/root` home is read-only under the sandbox, so the build ran with
  `GRADLE_USER_HOME=/opt/deepseek-harness/.gradle-user-home` and
  `ANDROID_USER_HOME=/opt/deepseek-harness/.android-user-home` (treated as
  discarded build state; neither is committed).
- Nothing was written to `/root/ClawMate-Android`; `/root/mobiinfra-oh` was not
  modified.

## Commit

- Message: `Phase 4 task workflow vertical slice`
- Hash: `<<FINAL_COMMIT_HASH>>` (on `master`), created in this worktree.
  (This doc is updated with the final hash after the feature commit and the
  result-file change is re-committed as the final docs commit; both the feature
  commit and the result-file update are present in the branch history.)

## Scope delivered (Phase 4)

1. **Android workflow domain models** — `task/model/WorkflowModels.kt`
   (`WorkflowStep`, `WorkflowDefinition`, `WorkflowTask`, plus
   `WorkflowMetadata` / `WorkflowDefaults` / `WorkflowCondition` contracts) and
   `task/model/WorkflowSceneConfig.kt` (the 7 collection scenes), mirrored from
   `utils/WorkflowTypes.ets` / `WorkflowSceneConfig.ets`.

2. **Tolerant, deterministic JSON parsing** — `task/json/WorkflowJsonParser.kt`
   + `WorkflowJsonRepair.kt`:
   - parses `metadata` / `defaults` / `context` / `steps`, including nested
     `loop` / `if` / `then_steps` / `else_steps`;
   - **preserves unknown step types and their raw JSON** (never silently drops);
     they surface as `UNSUPPORTED/BLOCKED` at run time;
   - repairs common malformed text (fenced blocks, truncation, literal casing,
     CJK punctuation) and degrades unrecoverable input to a typed `Err` so one
     corrupt config card never crashes the Task screen.

3. **Bundled configs + repository** — the source's 11
   `resources/rawfile/workflows/configs/*.json` were copied verbatim into
   `app/src/main/res/raw/` **after a secrets/personal-data scan**. The two
   WeChat configs' sample `contact_name: "小赵"` was replaced with a neutral
   placeholder (`联系人`) so no personal data is bundled. A file-backed
   repository (`task/data/DefaultWorkflowRepository.kt` +
   `WorkflowTaskIndexCodec.kt` + `AndroidBundledConfigReader.kt`) installs the
   configs into app-private `filesDir/workflows/…`, derives default tasks from
   config metadata (title = description, source = name — identical to the
   source), and persists **user edits (editable JSON), enabled toggles, and run
   status** in a versioned, deterministic `task-index.json` (tolerant decode of
   extra/unknown fields).

4. **Bounded, honest `WorkflowRunner`** — `task/runner/WorkflowRunner.kt` +
   `WorkflowVariableResolver.kt`:
   - only deterministic non-GUI steps run for real: `sleep` (bounded),
     `loop` (with `loop.index` / `loop.last`), `if` (condition evaluation over
     `${context.*}` / `${defaults.*}` / `${steps.*.output.*}`), and a test-only
     `echo` tool;
   - every `gui_task` / `gui_action` GUI / HDC / open-app / device-control step
     is returned as `StepOutcome.Unsupported` → overall `RunStatus.BLOCKED`;
     every VLM/cloud `tool` (`vlm_qa`, etc.) is `NOT_CONFIGURED`;
   - **a run is never fabricated as successful when blocked**; bounds: max total
     steps, per-loop capacity, wall-clock timeout; cooperative cancellation →
     `CANCELLED`. Never calls `Runtime.exec`, shell, adb, hdc, or an
     `AccessibilityService`.

5. **Real Compose Task screen** — `task/ui/TaskScreen.kt` + `TaskViewModel.kt`
   + `TaskViewModelFactory.kt`, wired into the 任务 tab via `TaskComponent.kt`
   (manually DI'd repository + runner). Provides: scene/filter chip bar
   (全部 + 7 scenes), workflow cards (title, source, enabled `Switch`, last
   status badge, last run), an expandable step-summary / honest run-outcome
   section, and loading / empty / error / reload states. A run action parses the
   task's persisted config and executes the bounded runner. The Phase-1
   placeholder `ui/TaskScreen` is retained only as a reference doc (no longer
   routed).

6. **Deterministic unit tests** — 33 new tests (see §Test count) in
   `task/json/*Test.kt`, `task/runner/WorkflowRunnerTest.kt`,
   `task/data/DefaultWorkflowRepositoryTest.kt`, and
   `task/data/WorkflowTaskIndexCodecTest.kt`.

## Commands run & exit codes

Environment: `ANDROID_HOME=/opt/deepseek-harness/android-sdk`,
`ANDROID_SDK_ROOT=/opt/deepseek-harness/android-sdk`,
`GRADLE_USER_HOME=/opt/deepseek-harness/.gradle-user-home`,
`ANDROID_USER_HOME=/opt/deepseek-harness/.android-user-home`.
Gradle binary: `/opt/deepseek-harness/gradle-dist/gradle-8.9/bin/gradle`.

| # | Command | Result |
| --- | --- | --- |
| 1 | `gradle --no-daemon --max-workers=1 :app:compileDebugKotlin` | **SUCCESS** (exit 0) — baseline + during development |
| 2 | `gradle --no-daemon --max-workers=1 :app:assembleDebug :app:testDebugUnitTest` | **SUCCESS** (exit code `0`) |
| 3 | `gradle --no-daemon --max-workers=1 :app:testDebugUnitTest --tests 'com.clawmate.app.task.*'` | **SUCCESS** (exit 0) — 33 Phase-4 task tests green |
| 4 | `gradle --no-daemon --max-workers=1 :app:connectedAndroidTest` | **not run** — no emulator/device available (same as Phases 1–3) |

## Test count

- Total unit tests green: **129** (94 prior Phases 1–3 + **33 new Phase-4
  Task/Workflow tests**); **0 failures / 0 errors** across
  `:app:testDebugUnitTest`.
- New Phase-4 tests by area:
  - `task/json/WorkflowJsonParserTest` (7) — metadata/defaults/context/steps,
    nested loop/if, unknown-step preservation, malformed tolerance, repair of
    casing/braces, `times` as String.
  - `task/json/WorkflowJsonRepairTest` (6) — object extraction, fence
    stripping, truncation, CJK punctuation, literal casing.
  - `task/runner/WorkflowRunnerTest` (11) — GUI/HDC → BLOCKED (never success),
    VLM tool → NOT_CONFIGURED, deterministic sleep/echo, real `if` branch,
    cancellation → CANCELLED, loop/time-step bounds, unknown step → BLOCKED,
    `loop.index/loop.last`.
  - `task/data/WorkflowTaskIndexCodecTest` (4) — deterministic round-trip,
    tolerant decode, malformed → null.
  - `task/data/DefaultWorkflowRepositoryTest` (5) — bundled bootstrap, task
    derivation, user-edit persistence, enabled toggle, run-status persistence.

## Changed files (this phase)

```
app/src/main/java/com/clawmate/app/task/TaskComponent.kt                     (new)
app/src/main/java/com/clawmate/app/task/model/WorkflowModels.kt              (new)
app/src/main/java/com/clawmate/app/task/model/WorkflowSceneConfig.kt         (new)
app/src/main/java/com/clawmate/app/task/json/WorkflowJsonParser.kt           (new)
app/src/main/java/com/clawmate/app/task/json/WorkflowJsonRepair.kt           (new)
app/src/main/java/com/clawmate/app/task/data/WorkflowRepository.kt           (new)
app/src/main/java/com/clawmate/app/task/data/DefaultWorkflowRepository.kt    (new)
app/src/main/java/com/clawmate/app/task/data/WorkflowTaskIndexCodec.kt       (new)
app/src/main/java/com/clawmate/app/task/data/AndroidBundledConfigReader.kt   (new)
app/src/main/java/com/clawmate/app/task/runner/WorkflowVariableResolver.kt    (new)
app/src/main/java/com/clawmate/app/task/runner/WorkflowRunner.kt              (new)
app/src/main/java/com/clawmate/app/task/ui/TaskScreen.kt                      (new)
app/src/main/java/com/clawmate/app/task/ui/TaskViewModel.kt                   (new)
app/src/main/java/com/clawmate/app/task/ui/TaskViewModelFactory.kt            (new)
app/src/main/res/raw/default_alipay_bill.json … default_xiaohongshu_history.json  (new, secrets-free)
app/src/test/java/com/clawmate/app/task/json/WorkflowJsonParserTest.kt        (new)
app/src/test/java/com/clawmate/app/task/json/WorkflowJsonRepairTest.kt        (new)
app/src/test/java/com/clawmate/app/task/runner/WorkflowRunnerTest.kt          (new)
app/src/test/java/com/clawmate/app/task/data/DefaultWorkflowRepositoryTest.kt (new)
app/src/test/java/com/clawmate/app/task/data/WorkflowTaskIndexCodecTest.kt    (new)
app/src/main/java/com/clawmate/app/ui/ClawMateRoot.kt                        (modified — route 任务 tab to TaskScreen)
app/src/main/java/com/clawmate/app/ui/FeatureScreens.kt                      (modified — note placeholder no longer routed)
README.md                                                                    (modified)
MIGRATION_STATUS.md                                                          (modified)
DSH_PHASE_RESULT.md                                                          (rewritten — this file)
```

## Limitations & platform blockers

- **GUI / HDC / accessibility automation is not implemented and not faked.**
  `gui_task` / `gui_action` steps (open-app, screenshot, tap, swipe, coordinates,
  deep links) are reported as `UNSUPPORTED` with a clear reason and the run is
  `BLOCKED`. A VLM/cloud `tool` is `NOT_CONFIGURED` (no endpoint on this
  device). There is no `Runtime.exec`/shell/adb/hdc/`AccessibilityService`, so a
  workflow is only ever `SUCCESS` when its steps are deterministic, non-GUI, and
  fully executed.
- **Cloud VLM execution boundary** — `tool: vlm_qa` and similar require a cloud
  model + screenshot pipeline; out of scope for this slice, reported
  `NOT_CONFIGURED`.
- **Daily-log / run-dir artifact generation** from the HarmonyOS runner is not
  ported (no real runs produce artifacts because runs with GUI steps are
  blocked).
- **connectedAndroidTest** not run (no device/emulator); `assembleDebug` and all
  129 JVM unit tests pass.
- **Some benign Kotlin warnings** remain (unused params/safe calls) — non-fatal,
  no compilation errors.

## Next phase

1. A **cloud/PC bridge adapter for GUI automation**: the source drives real
   devices over HDC via a PC-side Python service (`hdc_server.py` :9124,
   `harmony_agent.py`). The Android equivalent would be an HTTP bridge to a
   PC/adb service (or UIAutomator/Shizuku) — wire that adapter behind the
   `WorkflowRunner` so `gui_task`/`gui_action` can become executable, with the
   currently `UNSUPPORTED`/`NOT_CONFIGURED` states as the honest fallback.
2. **Settings tab** (the 5th major phase): port the 7 settings sections from
   `pages/Index.ets` key inventory + `SettingsCenter*` to DataStore/Compose.
3. Optionally: daily-log normalization (`WorkflowDateContext`) and run-result
   artifact persistence once GUI/HDC steps can actually run.
