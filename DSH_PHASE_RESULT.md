# DSH_PHASE_RESULT — ClawMate Android, Phase 8 (PC HDC bridge adapter)

## Worktree

- Path: `/opt/deepseek-harness/clawmate-phase8-hdc-bridge`
- Branch: `master`
- Source of truth (reference only, not modified): `/root/mobiinfra-oh`
- This delivery was **built and tested in this worktree** (JDK 17.0.19 OpenJDK,
  Android SDK platform 34 / build-tools 34.0.0 at `/opt/deepseek-harness/android-sdk`,
  Gradle 8.9 at `/opt/deepseek-harness/gradle-dist/gradle-8.9`).
- Build state used: `GRADLE_USER_HOME=/opt/deepseek-harness/.gradle-user-home`,
  `ANDROID_USER_HOME=/opt/deepseek-harness/.android-user-home` (discarded, never
  committed). Nothing was written to `/root/ClawMate-Android`; `/root/mobiinfra-oh`
  was not modified. All Phase 1–7 code is preserved.

## Commits

First commit (feature slice):
- Message: `Phase 8 PC HDC bridge adapter`
- Hash: **`4692b18`** (`4692b1850705f1aa2319e99af18531775fb94107`) on `master`,
  created in this worktree.

The result-file update (this file, with the feature hash filled in) is then
committed as the final docs commit — both the feature commit and the
result-file commit are present in the branch history.

## Command executed (actual, in this worktree)

```
ANDROID_HOME=/opt/deepseek-harness/android-sdk \
ANDROID_SDK_ROOT=/opt/deepseek-harness/android-sdk \
GRADLE_USER_HOME=/opt/deepseek-harness/.gradle-user-home \
ANDROID_USER_HOME=/opt/deepseek-harness/.android-user-home \
/opt/deepseek-harness/gradle-dist/gradle-8.9/bin/gradle \
    --no-daemon --max-workers=1 :app:assembleDebug :app:testDebugUnitTest
```

Result: **BUILD SUCCESSFUL — exit code 0**. `:app:assembleDebug` produced
`app/build/outputs/apk/debug/app-debug.apk`; `:app:testDebugUnitTest` ran
**303 tests, 0 failures, 0 errors** (254 prior Phases 1–7 + **49 new Phase-8
PC/HDC-bridge tests**). `:app:connectedAndroidTest` was **not run** — no
device/emulator is available in this worktree.

> Note: the first run in this worktree surfaced **two failing tests** that were
> then fixed (not compile errors — the HDC Kotlin sources and the fake-transport
> tests compiled cleanly):
> 1. `OkHttpHdcBridgeClientTest > call timeout maps to TIMEOUT` — OkHttp can
>    surface a read/call deadline as a generic `InterruptedIOException` (not a
>    `SocketTimeoutException`); the classifier mapped it to `CANCELLED`. Fixed in
>    `classify(...)` to inspect the interrupted-I/O message and classify a
>    timeout as `TIMEOUT`, cancellation as `CANCELLED`.
> 2. `WorkflowDeviceAdapterTest > health ok but not control ready … — the
>    `controlReady` predicate required `ok && (fport_ready || rport_ready)` so a
>    one-sided tunnel looked usable. Corrected to require **both**
>    `fport_ready && rport_ready` (a one-sided tunnel is not usable for GUI
>    automation), matching the test intent and the honest "device truly
>    controllable only by a live ok reply" guarantee.
> After these fixes both tests pass and the full suite is green.

## Scope delivered (Phase 8)

### Typed, tolerant bridge models (`task/hdc/HdcBridgeModels.kt`)
Port of `utils/HdcWorkflowBridge.ets` / `utils/WorkflowTypes.ets` (reference
only): `HdcBridgeStatus`, `HdcBridgeResponse`, `HdcBridgeHealth`
(`hdc_connected`, `target`, `tunnel_ready`, `fport_ready`, `rport_ready`,
`rport_listening`, `rport_listen_check_supported`, tunnel/PC/app URLs, ports,
`loop_enabled` / `loop_alive`) and a tolerant parser. Missing / wrong-typed /
numeric-as-string members degrade to safe defaults; an `ok` reply is usable for
device control only when **both** `fport_ready` and `rport_ready` are true
(`controlReady`).

### Deterministic URL normalization (`task/hdc/HdcUrlNormalizer.kt`)
`healthUrl(base)` → `…/api/health`, `workflowUrl(base)` → `…/api/workflow`.
Pure total functions: trim, collapse a leading `//`, default a scheme-less
input to `http`, strip trailing slashes, drop an empty trailing port, reject any
scheme other than `http`/`https`, produce a canonical `scheme://host[:port]/path`.
Un-normalizable input returns `""`, which callers treat as `INVALID_URL` with no
request sent.

### Typed client boundary (`task/hdc/HdcBridgeClient.kt`)
`health()` and `workflow(action, payload)` returning `HdcBridgeResult`
(`Response` / `Health` / `Error` with a typed `HdcBridgeErrorKind`: HTTP,
MALFORMED_JSON, TIMEOUT, IO, CANCELLED, INVALID_URL, UNKNOWN). Plain interface so
the default `OkHttpHdcBridgeClient`, fakes, and tests share one contract.

### OkHttp client (`task/hdc/OkHttpHdcBridgeClient.kt`)
`GET {base}/api/health` and `POST {base}/api/workflow` with a normalized URL,
bounded `connect` / `read` / `write` / `call` timeouts, cooperative cancellation
(cancels the in-flight `Call`), and typed error mapping (HTTP non-2xx →
HTTP; unparsable body → MALFORMED_JSON; socket/call timeout → TIMEOUT;
connect-refused / DNS / TLS → IO; cancellation → CANCELLED; un-normalizable URL →
INVALID_URL with no request). Payloads are encoded into the `{action,payload}`
envelope via kotlinx-serialization (strings, numbers, booleans, null, nested
maps/lists). **No secret logging** — only the action name, the response
`summary()`, and the health URL host are ever surfaced; never queries, payloads,
keys, or tokens.

### Safe device-control adapter (`task/hdc/WorkflowDeviceAdapter.kt`)
A boundary exposing **only** `health` / `startApp` / `screenshot` / `guiAction`
/ `executeDeciderAction`. It must **never** send `/api/run_cmd` or arbitrary
command strings and **never** execute shell/adb/hdc/accessibility or spawn
processes. The default `DisabledWorkflowDeviceAdapter` (used unless an explicit
adapter is injected) reports every action `Unsupported` and is never configured
— this is what keeps GUI/HDC workflow steps `BLOCKED` (never fabricated).

### Health-gated bridge adapter (`task/hdc/BridgeBackedWorkflowDeviceAdapter.kt`)
Maps the safe protocol calls through `HdcBridgeClient`. `isConfigured` becomes
true only after a live `health()` reply reports `ok` + control-ready; otherwise
a bridge `error` envelope is an honest `BridgeError` and transport failures are
typed `TransportError`. No secrets held, nothing logged.

### Runner integration (`task/runner/WorkflowRunner.kt`, modified)
`gui_action` device-control steps (`screenshot`, `app_start`, `click`, …) route
through the adapter **only when `isConfigured`**: a configured adapter returns
honest SUCCESS / FAILED (bridge/transport) / BLOCKED (unsupported) results; the
default keeps every GUI/HDC step `UNSUPPORTED/BLOCKED`. The runner never
fabricates device control. `gui_task` (a screenshot+VLM decider loop) stays
honestly `UNSUPPORTED`, because the safe adapter exposes only discrete protocol
calls and does not orchestrate the macro loop.

### Explicit DI gate (`task/TaskComponent.kt`, modified)
`TaskComponent` starts with the disabled adapter. A caller may explicitly
`setDeviceAdapter(...)` with a health-verified adapter (rebuilding the runner)
or pass `null` to revert. **No network call happens on app launch.**

### Settings explicit health check (`settings/ui/`, modified)
The HDC settings section adds an explicit **"测试 HDC 健康"** button that
performs a live `GET /api/health` only when the user taps it
(`SettingsViewModel.testHdcHealth()` with an injectable `hdcClientFactory` for
tests). App launch performs **no network**. A connected state
(`hdcTestConnected`) is reported only after a real successful response;
loading / error / disabled states are driven by explicit test state. On success
a health-verified adapter is registered with `TaskComponent`; on failure the
bridge is kept NOT_CONFIGURED / DISABLED with an honest message.

## Tests added in Phase 8 (49 new; deterministic, no live endpoint)

- `task/hdc/HdcBridgeParsingTest` (7) — tolerant health/workflow envelope
  parsing: all-fields ok, honest error envelope, missing→safe defaults,
  numeric-as-string width/height, wrong-typed→defaults, health tunnel/target/
  ports, `ok`-but-partial never claims connected.
- `task/hdc/HdcUrlNormalizerTest` (11) — trim, `//` scheme-relative, scheme-less
  → http, trailing-slash strip/dedup, path-prefix preservation, empty-port drop,
  http(s)-only rejection, empty/whitespace rejection.
- `task/hdc/OkHttpHdcBridgeClientTest` (8) — MockWebServer: GET health vs POST
  workflow path/body, non-2xx → HTTP, malformed JSON → MALFORMED_JSON, invalid
  URL → INVALID_URL (0 requests), connection refused → IO, call timeout → TIMEOUT,
  cancellation → CANCELLED.
- `task/hdc/WorkflowDeviceAdapterTest` (11) — disabled adapter only
  Unsupported/never configured; configured only after live `ok` + control-ready;
  honest bridge/transport errors not configured; safe action mapping
  (`app_start` / `screenshot` / `gui_action` / `execute_decider_action`);
  **never sends `run_cmd`**.
- `task/runner/WorkflowRunnerAdapterIntegrationTest` (7) — default adapter keeps
  `gui_action`/`gui_task` BLOCKED; configured adapter lets a `gui_action` succeed
  honestly and surfaces bridge/transport errors as FAILED; a configured-but-
  unverified adapter still blocks GUI steps.
- `settings/SettingsHdcHealthTest` (5) — app launch: no test running, not
  connected, default not configured; health runs only on explicit click; connect
  only after real ok reply; failed / transport health keeps the bridge disabled.

## Changed files (this phase)

```
app/src/main/java/com/clawmate/app/task/hdc/HdcBridgeModels.kt           (new)
app/src/main/java/com/clawmate/app/task/hdc/HdcUrlNormalizer.kt          (new)
app/src/main/java/com/clawmate/app/task/hdc/HdcBridgeClient.kt           (new)
app/src/main/java/com/clawmate/app/task/hdc/OkHttpHdcBridgeClient.kt     (new)
app/src/main/java/com/clawmate/app/task/hdc/WorkflowDeviceAdapter.kt     (new)
app/src/main/java/com/clawmate/app/task/hdc/BridgeBackedWorkflowDeviceAdapter.kt (new)
app/src/main/java/com/clawmate/app/task/TaskComponent.kt                 (modified — explicit setDeviceAdapter / default disabled)
app/src/main/java/com/clawmate/app/task/runner/WorkflowRunner.kt         (modified — gui_action routes through a configured adapter; else BLOCKED)
app/src/main/java/com/clawmate/app/settings/ui/SettingsViewModel.kt      (modified — explicit testHdcHealth() + injectable client factory)
app/src/main/java/com/clawmate/app/settings/ui/SettingsSections.kt       (modified — explicit "测试 HDC 健康" button + honest status)
app/src/main/java/com/clawmate/app/settings/ui/SettingsUiState.kt        (modified — hdcTesting/hdcTestConnected/hdcTestMessage)
app/src/test/java/com/clawmate/app/task/hdc/HdcBridgeParsingTest.kt      (new)
app/src/test/java/com/clawmate/app/task/hdc/HdcUrlNormalizerTest.kt      (new)
app/src/test/java/com/clawmate/app/task/hdc/OkHttpHdcBridgeClientTest.kt (new)
app/src/test/java/com/clawmate/app/task/hdc/WorkflowDeviceAdapterTest.kt (new)
app/src/test/java/com/clawmate/app/task/runner/WorkflowRunnerAdapterIntegrationTest.kt (new)
app/src/test/java/com/clawmate/app/settings/SettingsHdcHealthTest.kt     (new)
README.md                                                               (modified)
MIGRATION_STATUS.md                                                     (modified)
DSH_PHASE_RESULT.md                                                     (rewritten — this file)
```

No Phase 1–7 source file other than the task-runner / settings-UI / TaskComponent
files listed above was modified; existing chat/agent/home/workflow/settings
behavior is preserved. The two test fixes noted above are in `OkHttpHdcBridgeClient.kt`
(timeout classification) and `HdcBridgeModels.kt` (`controlReady` requires both
`fport_ready` and `rport_ready`).

## Safety & secrecy verification

- Secret scan (grep-based across all tracked + untracked text files, excluding
  build output): **clean** — no private keys, API keys, tokens, or
  `scheme://user:pass@` credentials in this tree.
- Safety scan of the HDC bridge source: **no `Runtime.exec`, `ProcessBuilder`,
  shell invocation, `adb`/`hdc` binary use, `AccessibilityService`, `/api/run_cmd`,
  or arbitrary command execution** — only the safe protocol calls are exposed,
  and the default adapter is DISABLED until an explicit, health-verified adapter
  is injected.

## Limitations & platform blockers

- **Instrumented tests not run** — `connectedAndroidTest` requires a device or
  emulator; none is available in this worktree. `assembleDebug` and all **303**
  JVM unit tests pass in this worktree.
- **No live PC bridge endpoint is exercised at runtime.** The bridge client and
  adapter are deterministic and tested against an in-process `MockWebServer`;
  connecting to a real PC-side Python bridge requires a configured, reachable
  `hdcServerUrl` and is triggered only by the user tapping the explicit settings
  health button.
- **`gui_task` (macro screenshot+VLM decider) stays `UNSUPPORTED`.** The safe
  adapter exposes only discrete protocol calls (`app_start` / `screenshot` /
  `gui_action` / `execute_decider_action`); the full screenshot-decider loop is
  not orchestrated, reported honestly rather than fabricated.
- Benign Kotlin warnings remain (a few unused parameters in pre-existing
  scaffolding and a deprecated icon/versionCode usage), non-fatal; no compilation
  errors.

## Next phase

1. **Live bridge smoke test** against a real PC-side `python/hdc_server.py` once a
   reachable bridge is configured, driving a workflow `gui_action` end-to-end and
   confirming the settings health button → connected → configured-adapter path.
2. **`gui_task` macro orchestration** (screenshot → `execute_decider_action`
   loop) behind an explicit, health-verified adapter if a safe bounded
   implementation is wanted, or keep it honestly `UNSUPPORTED`.
3. Optionally instrumented UI tests (settings health button,
   workflow `gui_action` path) once a device/emulator is available.
