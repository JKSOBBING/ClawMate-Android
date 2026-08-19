# Phase 46 — ClawMate Android: wire periodic local consolidation to real Android OS scheduling

## Baseline / scope

- **Base commit**: `a58b994` (Phase 45, `ClawMate-Android`).
- **Worktree**: `/root/phase46-worktree`, branch `phase46-platform-scheduler`.
- **Deliverables**: `phase46.patch` (this diff), `phase46.result.md` (this file).
- **Repository**: `/root/ClawMate-Android` (read-only here); Harmony reference: `/root/mobiinfra-oh` (read-only).

The audited gap: Android already has `PeriodicConsolidationScheduler`, `PeriodicSchedulingHost`,
`PeriodicTriggerAdapter`, `SchedulingPolicy`, and the Settings explicit enable / apply-schedule /
run-now buttons — but they only ever used the in-process `InProcessPeriodicTriggerAdapter`, so
enabling "本地周期整合" never registered anything with the OS. There is **no `androidx.work`** in
the offline Gradle cache and adding a dependency would violate the offline/no-network rule.

## Harmony对照 (read-only reference, `/root/mobiinfra-oh`)

The Harmony project has **no automatic periodic consolidation scheduler**. Consolidation
(`ConsolidationService.consolidateAll`) is invoked by explicit user actions in
`SettingsCenterPage.ets` / Home ("生成推荐"). The only background scheduling primitive in Harmony is
`backgroundTaskManager`:
- `EntryAbility.onBackground()` calls `backgroundTaskManager.startBackgroundRunning(context,
  BackgroundMode.DATA_TRANSFER, wantAgent)` **only when `AgentExecutionController.isExecutionActive()`**
  (i.e. while an explicit agent task is actively running), and
- `EntryAbility.onForeground()` calls `stopBackgroundRunning(...)` to end it.

So the Harmony trigger semantics are: **background long-running work exists only while an explicitly
started task is executing, and stops when the app returns to foreground; nightly/scheduled periodic
consolidation is not auto-triggered in the Harmony source.** The Android periodic-local-consolidation
schedule is therefore a *new, explicit, opt-in capability* — the Android Task explicitly requires
"用户开启后仍会在应用离开前台或设备重启后自动运行", which is a stronger contract than Harmony
provides. This Phase implements it in a way consistent with the existing Android "explicit opt-in +
disabled by default" policy.

## Platform API choice: JobScheduler (not AlarmManager)

Selected **JobScheduler** for the periodic trigger:

| Criterion | JobScheduler (chosen) | AlarmManager + BroadcastReceiver |
|---|---|---|
| New dependency | none — `android.app.job.*` framework, API 21+ (minSdk=26) | none |
| Device-reboot persistence | `JobInfo.setPersisted(true)` re-registers natively; no boot receiver | requires `RECEIVE_BOOT_COMPLETED` + manual boot receiver to re-schedule |
| Battery | framework-batched, 15-min min period; no device wake to exact alarm | exact/windowed alarms wake device; more fiddly to batch |
| Permission | none | `SCHEDULE_EXACT_ALARM` is restricted on Android 12+ |
| One-instance guarantee | JobScheduler never starts a 2nd instance of the same job id | caller must de-dupe time windows manually |

The canonical job's default interval is
`SchedulingPolicyDefaults.DEFAULT_INTERVAL_MINUTES = 12h` (`clamped` by
`SchedulingPolicy.effectiveIntervalMinutes` to ≤24h), well above the 15-min JobScheduler floor, and
the job needs **no network** (`NetworkConstraint.NOT_REQUIRED`), so JobScheduler's
`NETWORK_TYPE_NONE` + `setRequiresBatteryNotLow(false)` + `setRequiresCharging(false)` are exactly
right.

## Registration / cancel / restore / run call chains (the "reachable task" proof)

1. **Register (explicit opt-in + explicit schedule action only).**
   `SettingsSections` → `vm.schedulePeriodicLocalConsolidation()` → `PeriodicSchedulingHost.scheduleIfEnabled(enabled)`
   → `JobSchedulerTriggerAdapter.schedule(policy, LocalOnlyPeriodicJob)`:
   - if `!policy.enabled` → `gateway.cancel(JOB_ID)` + `scheduler.cancel(taskName)` → `Disabled`;
   - if enabled → `gateway.schedule(JOB_ID, intervalMs)` → `AndroidJobSchedulerGateway` builds
     `JobInfo(4601, ComponentName("...PeriodicLocalConsolidationJobService"))` with
     `setPersisted(true) + setPeriodic(interval) + NETWORK_TYPE_NONE`, and calls
     `JobScheduler.schedule(...)`; on `RESULT_SUCCESS` it also registers the job in the in-memory
     `PeriodicConsolidationScheduler` so `isScheduled()`/`runScheduledOnce` stay truthful. On
     `RESULT_FAILURE` it returns `Disabled` and records `lastFailure` — it never claims success.
2. **OS fire (app backgrounded / device idle).**
   `PeriodicLocalConsolidationJobService.onStartJob(params)`:
   - gates on `PeriodicSchedulingRuntimeRegistry.isOptedIn()` (mirrored from the persisted settings);
     if false/unknown it `jobFinished(false)` and cancels (stale/disabled user never runs);
   - runs `LocalOnlyPeriodicJob(baseDir).runOnce { cancellationRequested }` on `Dispatchers.Default`
     (serial through the scheduler mutex; the same job id cannot fire twice concurrently);
   - writes a typed `JobRunOutcome` into `PeriodicSchedulingRuntimeRegistry` and
     `AppLogger.i("PeriodicJob", …)`; failure → `recordError` + `jobFinished(reschedule=true)`
     (retriable); cancellation → `jobFinished(false)`.
   - `onStopJob` sets the cancellation flag, cancels the coroutine, returns `false`.
     The job reuses `LocalConsolidationIndexRunner` + Phase-33 scene/entity + Phase-32 local profile
     (all inside `LocalOnlyPeriodicJob`). **No second consolidation engine.**
3. **Cancel (opt-in off / apply disabled).** `scheduleIfEnabled(false)` → `Disabled` →
   `gateway.cancel(JOB_ID)` removes the OS job and the in-memory registration.
4. **Restart / boot recovery (never runs work).**
   `ClawMateApplication.onCreate` → background thread →
   `PeriodicSchedulingRestoreCoordinator.reconcile(context)`:
   - persisted opt-in `true` ∧ OS job pending → `already_registered` (no-op; mirror opt-in only);
   - persisted opt-in `true` ∧ no OS job → re-register the persisted schedule, `restored`
     (scheduling decision only — zero model/network/consolidation execution);
   - persisted opt-in `false`/unknown → cancel stale OS job, `cancelled`.
   `JobInfo.setPersisted(true)` also survives device reboot natively, so the OS job persists across
   reboots without a boot receiver, and the app-level restore just reconciles the transient
   in-memory view.

## Default-off & network/data boundary

- **Disabled by default**: `AppSettings.periodicLocalConsolidationEnabled = false` (unchanged),
  `SchedulingPolicy.mode = DISABLED` default (unchanged). The adapter never schedules on
  `Application.onCreate` / Home load / recomposition / repository construction — only the explicit
  Settings `scheduleIfEnabled` path schedules.
- **No network / no model / no side effects**: the scheduled job runs only the deterministic
  local-only `LocalOnlyPeriodicJob` (daily-log consolidate → event/index → local profile). It never
  references `CloudConsolidationRepository`, never auto-calls a cloud model, chat, GUI, media scan,
  or TTS. The `JobInfo` uses `NETWORK_TYPE_NONE`. If a job could not express a safe boundary it is
  reported (failure/unverified) rather than faked.

## Controls around duplicate / replace / cancel / failure

- **Unique id**: one canonical `JOB_ID = 4601` and one canonical task name
  `clawmate.periodic.local.consolidation`; `REPLACE` conflict policy; scheduling twice targets the
  same id (JobScheduler replaces same-id jobs).
- **No parallel run**: in-process `PeriodicConsolidationScheduler` serializes via one `Mutex`; the
  OS never starts a second instance of the same job id.
- **Failure retry / honesty**: framework reject → `Disabled` + `lastFailure` (no fake success);
  in-service exception → `recordError` + `jobFinished(reschedule=true)` (next period retries); the
  registration stays intact for a retry (no auto-cancel on a single transient failure).
- **Restart retry/report**: restore re-binds; written outcomes land in `PeriodicSchedulingRuntimeRegistry`
  and the in-app log viewer.

## Tests (pure JVM, no device)

`app/src/test/java/com/clawmate/app/datamanager/JobSchedulerTriggerAdapterTest.kt` — 14 tests using
a fake `JobSchedulerGateway` and the pure seam, covering:
- opt-in schedules exactly one persisted job (single canonical id); opt-out disables + cancels;
- replace reuses the single job id / cannot double-schedule; cancel removes OS job + registration;
- framework schedule rejection is reported honestly (Disabled + lastFailure), never faked;
- runOnce serial / no-overlap; runOnce with nothing scheduled is a harmless no-op;
- restore coordinator: re-register-when-opted-in-no-job (no run), cancel-when-off, no-op-when-registered;
- service-boundary decisions (not-opted-in cancels rather than runs; persisted-job SDK support;
  failure keeps registration retriable).

A real `JobService.onStartJob/onStopJob` and the fired-persisted-job-after-reboot path cannot be
run on the JVM (device only). This boundary is explicitly covered by testing the decision seam the
service uses (opt-in gate, outcome/error recording, restore reconcile) and is disclosed here.

## Verification (all executed offline, this worktree)

Build toolchain: JDK 17.0.19, Android SDK platform 34 / build-tools 34.0.0, Gradle 8.9
(`--offline --no-daemon --max-workers=1`). Because this session's file sandbox blocks writes to
`/root/.gradle` (and thus the `gradlew` wrapper's lock and `~/.android/debug.keystore`), the same
Gradle 8.9 distribution was invoked directly with a **workspace-local `GRADLE_USER_HOME`** and
`-Duser.home=…` for the debug keystore — the identical toolchain the wrapper would use (this is the
same established pattern Phase 41 recorded in `MIGRATION_STATUS.md`).

- `:app:testDebugUnitTest --offline` → **BUILD SUCCESSFUL** — **1082 tests, 0 failures, 0 errors**
  (includes the 14 new `JobSchedulerTriggerAdapterTest` cases).
  - Exact aggregate from `app/build/test-results/testDebugUnitTest/*.xml`:
    `tests=1082 failures=0 errors=0`.
- `:app:assembleDebug --offline` → **BUILD SUCCESSFUL**.
- `git diff --check` → **clean (no whitespace errors)**.
- **APK SHA256**: `e7683c554f03a74b1415d10796922fc1deaa3c798f7f4f406c25da049df53a52`
  (`app/build/outputs/apk/debug/app-debug.apk`, 53,449,778 bytes).
- **Commit**: none made (per task instructions).

## Gaps / honest limitations

1. **On-device OS firing is not JVM-testable.** The framework firing the persisted `JobInfo` after
   the app leaves foreground or reboots, and `JobService`'s lifecycle, require a device/emulator
   (unavailable; no adb/hdc. The sandbox also forbids running an emulator). The JVM suite tests the
   exact decision seam the service executes (opt-in gate cancels-vs-runs, serially, outcome/error
   recording, restore reconcile) plus the pattern that a faked gateway drives the same
   `PeriodicConversationScheduler`; this boundary is explicitly documented so nothing is claimed
   beyond what the tests prove.
2. **Debug APK signing**: the sandbox blocks `/root/.android/debug.keystore` writes, so the build is
   signed via a workspace `.android/` shadow (copied keystore) with `-Duser.home`; this is a
   build-environment detail, not an app change.
3. The periodic job runs only the local-only path; cloud/model consolidation is intentionally not
   auto-scheduled (boundary by design).

## Files changed (this phase)

- `app/src/main/AndroidManifest.xml` — register `PeriodicLocalConsolidationJobService`
  (`BIND_JOB_SERVICE`, `exported=false`).
- `app/src/main/java/com/clawmate/app/ClawMateApplication.kt` — startup restore reconcile (no run).
- `app/src/main/java/com/clawmate/app/settings/ui/SettingsViewModel.kt` — mirror opt-in on save.
- `app/src/main/java/com/clawmate/app/settings/ui/SettingsViewModelFactory.kt` — real JobScheduler host.
- `app/src/main/java/com/clawmate/app/datamanager/` — new: `PlatformPeriodicScheduling.kt`,
  `JobSchedulerTriggerAdapter.kt`, `PeriodicLocalConsolidationJobService.kt`,
  `PeriodicSchedulingRuntimeRegistry.kt`, `PeriodicSchedulingRestoreCoordinator.kt`,
  `PeriodicSchedulingBootstrap.kt`, `PeriodicPlatformHostFactory.kt`,
  `platform/JobSchedulerGateway.kt`.
- `app/src/test/java/com/clawmate/app/datamanager/JobSchedulerTriggerAdapterTest.kt` — 14 JVM tests.
- `.gitignore` — ignore the workspace-local `.ghome/` gradle home.
- `MIGRATION_STATUS.md` — Phase 46 note.
