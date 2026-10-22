# ClawMate Android Migration — Status & Feature Mapping

Source of truth (reference only, do not modify): `/root/mobiinfra-oh`
Target: this repository (`/root/ClawMate-Android`)

## Current UI parity pass (2026-08-20)

This pass uses the HarmonyOS pages under `/root/mobiinfra-oh/entry/src/main/ets`
as the visual reference for the five-tab shell and the core Home, Collection,
Chat, Task, and Settings flows.

Implemented in Android:

- Main tab order and labels match `main/MainTabConfig.ets`; the shell uses the
  Harmony light palette (`#8066FF` accent, `#F4F8FF` page background, white
  bottom bar, `#EEF0FF` selected indicator).
- Home has the source default digital-avatar asset and the profile/recommendation
  cards; Chat has the source-style empty welcome state with avatar, greeting,
  and three prompt cards; prompts only fill the composer and do not fake a send.
- Collection/summary has the source title and a real-data overview card showing
  stored count and sync state; photo picking, grouping, scan gates, and detail
  states remain explicit and honest.
- Task has the source `自定义任务` header and background; malformed persisted
  task-index records are sanitized without discarding valid user records, and
  missing code-defined defaults are restored from bundled resources while
  preserving user-edited task state.
- Settings keeps the source section order, labels, card spacing, and letter
  badges; detail pages expose Android capability boundaries as
  `NOT_CONFIGURED` / `DISABLED` rather than claiming unsupported Harmony
  integrations work.
- Home, Collection, Task, and Settings now share an Android `ClawMateHeader`
  that mirrors HarmonyOS `AppHeader`: source-matched semantic icon, title,
  optional subtitle, and the privacy-protection status affordance.
- Launcher resources now include the exact Harmony brand bitmap for the
  adaptive icon and a standard `mipmap-anydpi` legacy fallback, with both
  `android:icon` and `android:roundIcon` declared.
- Home, Task, and Settings retain their Harmony-style page header during
  loading, empty, and error states instead of replacing the whole page with a
  headerless centered placeholder.
- Settings primary navigation now contains the same five HarmonyOS sections;
  Android-only NFC, WeChat auto-summary, and runtime-log features are exposed
  as explicit actions inside their owning sections rather than extra top-level
  cards.
- Chat voice controller property initialization was reordered so its initial
  non-null state cannot observe a JVM-default null `voicingMessageId`.
- Chat session create/select/delete operations now catch repository failures and
  render actionable in-page errors instead of allowing a storage exception in a
  `viewModelScope` coroutine to crash the process.
- Home's Harmony-first viewport now keeps the source order (avatar/status,
  completeness, four lifestyle pillars, recommendations); the real indexed
  profile-generation card remains available below recommendations instead of
  displacing the primary source blocks.
- Chat send now includes active-session recovery inside the same exception
  boundary as the streaming request, so storage failures reset the sending
  state and surface the existing error banner instead of escaping the
  coroutine.
- Chat now mirrors the Harmony top bar with a real `MobiAgent` header, persisted
  cloud/local mode selector (switching creates the corresponding session), and
  a direct entry to the existing in-app log viewer.
- Shared Android page headers now match the Harmony privacy shield treatment;
  the Home header also exposes the honest default `PC 未连接` status, while
  Settings detail headers show the active section title like Harmony.
- The Android cold-start window background now uses the Harmony page token
  `#F4F8FF`, avoiding a white-to-blue flash before Compose renders.
- Collection first viewport now follows the Harmony order with horizontally
  scrollable scene tabs (`全部`, `购物`, `外卖`, `娱乐`, `聊天`, `生活`, `社交`,
  `差旅`) before the real-data overview; selecting a scene filters stored items
  using their category, title, summary, and tags.

Verification executed for this pass:

- `:app:testDebugUnitTest` — `BUILD SUCCESSFUL`.
- `:app:assembleDebug` — `BUILD SUCCESSFUL`.
- `ChatModeContractTest` — persisted mode ids, labels, and legacy fallback
  behavior covered by JVM regression tests.
- `DefaultWorkflowRepositoryTest.partial persisted index restores missing
  code-defined defaults` — partial task indexes recover the complete default
  task set without overwriting user state.
- `git diff --check` — passed.
- Collection scene-tab layout and filtering compile-tested through the full
  debug unit-test and APK build above.
- Collection first viewport ordering now matches the Harmony reference more
  closely: scene tabs, stored-count overview, indexed collection groups, then
  scan/search/monthly analysis and photo rows.
- Fixed Compose space allocation in Chat and Task: the message/task lists now
  consume only remaining height, keeping the chat composer and task controls
  visible. Collection now uses one outer scroll container instead of a nested
  list that could be clipped by the preceding analysis panels.
- APK: `app/build/outputs/apk/debug/app-debug.apk` (SHA-256 recorded in the
  handoff message for each rebuild; latest rebuild SHA-256 is
  `d4c62cc3315c9f7d0ea2cbbc62cdb1662a5cd2b6a0fb93c0be438629e753f800`.
- APK-level resource verification with `aapt2 dump` / `unzip -l` confirmed
  package `com.clawmate.app`, launchable app metadata, both `icon` and
  `roundIcon` resolving to `@mipmap/ic_launcher`, the exact brand/avatar
  bitmaps, and all five tab vector resources present in the APK.
- Final APK resource verification repeated the same package/icon/avatar/tab
  checks. Latest SHA-256:
  `22582ee912aa6a9affe218d107613f8bf025b52dd3a30f73a0c0d43db7f7c97e`.

Remaining platform differences and verification gaps:

- The SDK `adb` binary is available and its server starts successfully, but the
  current environment reports no connected Android device and no AVD. Screenshot
  comparison, install verification, and click-by-click runtime regression have
  therefore not been executed here.
- The five Harmony tab SVGs are now converted to Android VectorDrawable assets
  and used directly by the bottom bar. Some page header/category illustrations
  still use Android Material icons or text badges where no Android asset adapter
  exists yet; those remain visual approximations.
- Chat's core session, streaming, attachment, voice, and empty-state flow is
  present, but the Harmony top-bar mode selector and direct runtime-log action
  do not yet have equivalent Android state/route contracts; Android exposes
  supported configuration and logs through Settings instead of adding inert
  controls.
- HDC/GUI automation, Harmony native MNN behavior, and PC-side services remain
  controlled Android adapters with explicit disabled/not-configured states.

> Phase 1 of N. This file records the source inventory, source-of-truth
> locations, and the chosen Android implementation strategy for each major
> feature. It is updated at the end of every phase (AGENTS.md — Collaboration
> protocol).

---

## 0. Environment & build status

| Item | Status |
| --- | --- |
| JDK | **17.0.19 (OpenJDK)** — installed and used for Phase 2 |
| Android SDK | **platform 34 + build-tools 34.0.0** at `/opt/deepseek-harness/android-sdk` — used for Phase 2 |
| Gradle | **8.9** — used for Phase 2 (wrapper in this worktree) |
| Network for dependency download | **Working** (Maven Central / Google) |

**Phase 2 build verification was actually executed in this worktree** with the
toolchain above: `:app:assembleDebug` and `:app:testDebugUnitTest` both passed
(see §6 for the exact commands and output). This supersedes the Phase-1 note
below, which applied when no JDK/SDK/Gradle were installed.

**Phase 3 build verification was actually executed in this worktree** with the
same toolchain (JDK 17, SDK platform 34, Gradle 8.9, `--max-workers=1`): the
Agent/ReAct vertical slice compiles and its unit tests pass (see §6). Exact
commands and exit codes are recorded in `DSH_PHASE_RESULT.md`.

**Phase 4 (Task/Workflow) build verification was actually executed in this
worktree** with the same toolchain (JDK 17, SDK platform 34, Gradle 8.9,
`--no-daemon --max-workers=1`): the Task/Workflow vertical slice compiles and
all 129 unit tests pass (33 new Phase-4 tests). Exact commands and exit codes
are recorded in `DSH_PHASE_RESULT.md`.

**Phase 5 (Settings/Configuration) build verification was actually executed in
this worktree** with the same toolchain (JDK 17.0.19, SDK platform 34, Gradle
8.9, `--no-daemon --max-workers=1`): `:app:assembleDebug` and
`:app:testDebugUnitTest` both pass — **163 unit tests / 0 failures** (129 prior
Phases 1–4 + **34 new Phase-5 Settings tests**). Exact commands and exit codes
are recorded in `DSH_PHASE_RESULT.md`.

**Phase 6 (Home + Onboarding) build verification was actually executed in this
worktree** with the same toolchain (JDK 17.0.19, SDK platform 34, Gradle 8.9,
`--no-daemon --max-workers=1`): `:app:assembleDebug` and
`:app:testDebugUnitTest` both pass — **199 unit tests / 0 failures** (163 prior
Phases 1–5 + **36 new Phase-6 Home/Onboarding tests**; exact count recorded in
`DSH_PHASE_RESULT.md`).

**Phase 7 (Voice interaction) build verification was actually executed in this
worktree** with the same toolchain (JDK 17.0.19, SDK platform 34, Gradle 8.9,
`--no-daemon --max-workers=1`): `:app:assembleDebug` and
`:app:testDebugUnitTest` both pass — **254 unit tests / 0 failures** (199 prior
Phases 1–6 + **55 new Phase-7 voice tests**). Exact commands and exit codes are
recorded in `DSH_PHASE_RESULT.md`.

**Phase 8 (PC HDC bridge adapter) build verification was actually executed in
this worktree** with the same toolchain (JDK 17.0.19, Android SDK platform 34 /
build-tools 34.0.0, Gradle 8.9, `--no-daemon --max-workers=1`):
`:app:assembleDebug` and `:app:testDebugUnitTest` both pass — **303 unit tests
/ 0 failures** (254 prior Phases 1–7 + **49 new Phase-8 HDC-bridge tests**).
Exact commands and exit codes are recorded in `DSH_PHASE_RESULT.md`.

**Phase 9 (Gallery detail & local analysis) build verification** was also
executed in this worktree — **342 unit tests / 0 failures** (303 prior +
39 new Phase-9 gallery tests); see `DSH_PHASE_RESULT.md`.

**Phase 10 (Local model capability boundary) build verification was actually
executed in this worktree** with the same toolchain (JDK 17.0.19, Android SDK
platform 34 / build-tools 34.0.0, **Android NDK 25.2.9519653 + system CMake
3.22.1 installed via the SDK's `sdkmanager`**, Gradle 8.9,
`--no-daemon --max-workers=1`): `:app:assembleDebug` and
`:app:testDebugUnitTest` both pass — **380 unit tests / 0 failures** (342 prior
Phases 1–9 + **38 new Phase-10 local-model tests**). The APK packages
`libclawmate_local_model.so` (the truthful native stub) for arm64-v8a /
armeabi-v7a / x86_64. Exact commands and exit codes are recorded in
`DSH_PHASE_RESULT.md`.

**Phase 11 (real on-device OCR) build verification was actually executed in
this worktree** with the same toolchain (JDK 17.0.19, Android SDK platform 34 /
build-tools 34.0.0, **ML Kit `com.google.mlkit:text-recognition-chinese:16.0.1`
resolved on Google Maven**, Gradle 8.9, `--no-daemon --max-workers=1`):
`:app:assembleDebug` and `:app:testDebugUnitTest` both pass — **407 unit tests
/ 0 failures** (380 prior Phases 1–10 + **27 new Phase-11 OCR tests**). The
`text-recognition-chinese` SDK is the **bundled** variant: the Chinese + Latin
OCR models and `libmlkit_google_ocr_pipeline.so` are packaged into the APK.
ML Kit runtime / any on-device model download still require a real device
(documented; the typed mapping is covered by device-free tests). Exact commands
and exit codes are recorded in `DSH_PHASE_RESULT.md`.

**Phase 12 (coverage audit + Profile/Avatar local UI & state) build
verification was actually executed in this worktree** with the same toolchain
(JDK 17.0.19, Android SDK platform 34 / build-tools 34.0.0, Gradle 8.9,
`--no-daemon --max-workers=1`): `:app:assembleDebug` and `:app:testDebugUnitTest` both pass — **425 unit tests
/ 0 failures** (407 prior Phases 1–11 + **18 new Phase-12 avatar/profile tests**,
exact count recorded in `DSH_PHASE_RESULT.md`). The new `profile/avatar/`
slice is wired into the Home avatar card. Exact commands and exit codes are
recorded in `DSH_PHASE_RESULT.md`.

**Phase 13 (portable WeChat auto-summary settings + typed collection boundary)
build verification was actually executed in this worktree** with the same
toolchain (JDK 17.0.19, Android SDK platform 34 / build-tools 34.0.0, Gradle
8.9, `--no-daemon --max-workers=1`): `:app:assembleDebug` and
`:app:testDebugUnitTest` both pass at the end of this phase (new Phase-13
WeChat tests; the exact total is recorded in `DSH_PHASE_RESULT.md`). The new
`wechat/` slice and the WeChat Settings section are wired into the Settings
center, and the PC/HDC collection boundary is typed and stays BLOCKED /
NOT_CONFIGURED absent a verified bridge.

**Phase 14 (portable in-app Log viewer) build verification was actually executed
in this worktree** with the same toolchain (JDK 17.0.19, Android SDK platform 34 /
build-tools 34.0.0, Gradle 8.9, `--no-daemon --max-workers=1`): a single bounded
`:app:assembleDebug :app:testDebugUnitTest` both pass (new Phase-14 log tests; the
exact total is recorded in `DSH_PHASE_RESULT.md`). The new `log/` slice and the
运行日志 Settings section are wired into the Settings center.

**Phase 28 (explicit home recommendations from gallery data) was executed in
this worktree with the same toolchain (JDK 17.0.19, Android SDK platform 34,
Gradle 8.9, `--no-daemon --max-workers=1`): `:app:testDebugUnitTest` with the
focused
`--tests com.clawmate.app.home.recommendation.* --tests com.clawmate.app.home.HomeRecommendationViewModelTest`
**22 tests, 0 failures, 0 errors** (GalleryPlannerEventMapperTest 6,
HomeRecommendationPlannerTest 12, HomeRecommendationViewModelTest 4).** This
phase adds a real, explicit-user-action **"生成推荐"** Home recommendation
generator powered by real stored Gallery data through the shared
OpenAI-compatible Planner chat backend, while preserving every safety boundary:
- **Explicit button only.** Recommendations are generated **only** from the
  Home "生成推荐" button (`Button(onClick=…)` → `viewModel.generateRecommendations()`);
  never on launch and no background work / service / WorkManager.
- **Zero calls without config or real data.** `HomeRecommendationConfig.fromEndpointConfig()`
  returns null unless base URL + API key + model are configured (gate → NotConfigured,
  no call); no real gallery text (empty/blank input) short-circuits to NoData with
  zero calls.
- **Bounded real text only.** `GalleryPlannerEventMapper` maps only real stored
  `GalleryPhotoItem` text (todo/summary/normalized_fact/keywords capped); the
  Harmony protocol `image_paths: []` field is carried faithfully but its value is
  always empty — raw image URIs/bytes/keys never leave the device.
- **Strict output validation.** `HomeRecommendationNormalizer` enforces non-empty
  title, the six-domain constraint, tag/desc/title length caps, `source_event_ids`
  input membership + dedup, and a recommendation-count cap.
- **Errors redacted.** `SensitiveLogRedactor` scrubs secrets from error paths;
  typed `HomeRecommendationException` surfaces honest user-facing messages.
- **No new credentials, no Runtime.exec/ProcessBuilder/adb/hdc/coordinate GUI
  automation.** See `DSH_PHASE_RESULT.md` for the focused test command and results.

**Phase 29 (bounded daily-log event source into the Home recommendation
pipeline) was executed in this worktree with the same toolchain (JDK 17.0.19,
Android SDK platform 34, Gradle 8.9, `--no-daemon --max-workers=1`):
`:app:testDebugUnitTest` with focused new tests passed (see §6 / `DSH_PHASE_RESULT.md`).**
This phase brings the **first bounded daily-log input + local event-index
foundation** into the Phase-28 explicit Home recommendation flow, honestly
marking the remaining Harmony work as Partial/Deferred (no full
ConsolidationService/IndexService port):
- **Pure daily-log parser** (`datamanager/DailyLogParser`): parses Harmony metadata
  headers (`workflow_metadata` / `latest_entry_index`) and numbered `1. …` entries
  from app-private `filesDir/workflows/daily-log/YYYY-MM-DD/*.md`, with strict
  bounds (date, file name, entry count, per-entry/total chars, metadata size).
  Bad files are skipped with countable `DailyLogParseError`s — never thrown, so a
  bad file cannot falsify a whole refresh.
- **Bounded mapper + read-only source** (`datamanager/DailyLogPlannerEventMapper`,
  `DailyLogEventSource`): maps typed entries into the existing
  `HomeRecommendationPlannerEvent` shape with **stable source refs**
  (`YYYY-MM-DD/file.md#N`) as event ids, bounded real text, `imagePaths` empty,
  source label `DailyLog`, and no URI/image/network. The app-private file source
  only reads existing files (never creates, never scans external storage).
- **Explicit mixed input.** The Home "生成推荐" button now reads stored gallery
  metadata **and** daily-log events; the repo blends them into the planner and the
  output normalizer validates `source_event_ids` over **both** id namespaces. Old
  gallery-only behavior stays compatible (single-arg `generate`). With no gallery
  **and** no daily-log it short-circuits to NoData with zero network calls; never
  read on Home load/recomposition/background.
- **External-import API, not auto-creation.** `DailyLogWorkspace` documents how a
  workflow/import layer will write daily-log files; the pipeline never creates
  them. No shell/adb/hdc/GUI capabilities were added.
- **MIGRATION_STATUS** top data-manager row already marks the full
  ConsolidationService/IndexService (cloud consolidation/dedup/profile-claims/
  index-partitions/periodic background scheduling) as **Partial/Deferred** — Phase
  29 implements only parsing + planner input, not those remaining items.

**Phase 32 (real four-domain profile index / claims foundation) was executed in
this worktree with the same toolchain (JDK 17.0.19, Android SDK platform 34,
Gradle 8.9, `--offline --no-daemon --max-workers=1`): the focused new classes
`ProfileIndexTest` / `ProfileIndexBlenderTest` / `ProfileGenerationViewModelTest`
= **26 tests, 0 failures, 0 errors**, plus the full `:app:testDebugUnitTest` and
`:app:assembleDebug`, both `BUILD SUCCESSFUL` (see §18 and `DSH_PHASE_RESULT.md`).**

**Phase 33 (workflow-scene + entity index/resolver foundation) was executed in
this worktree with the same toolchain: the focused new class
`EntitySceneIndexTest` = **17 tests, 0 failures, 0 errors**, plus a full
`:app:testDebugUnitTest` and `:app:assembleDebug`, both `BUILD SUCCESSFUL` (see
§19 and `DSH_PHASE_RESULT.md`). It ports Harmony `IndexService`'s
`buildWorkflowSceneDocument` / `buildEntityDocument` (deterministic local
path) into `workflow-scene/index.json` + `entity/index.json`, and propagates
real `workflow_scene_ids` (from the daily-log `task_scene_id`) through the
Phase-30 consolidation into indexed events (backward compatible with old
artifacts).**

**Phase 35 (periodic local-consolidation scheduler seam + explicit model
entity-enrichment seam) was executed in this worktree with the same toolchain: the
focused new classes `PeriodicConsolidationSchedulerTest` / `EntityEnrichmentTest`
run green, and the full `:app:testDebugUnitTest` = **117 test classes / 922 tests,
0 failures, 0 errors, 0 skipped**, plus `:app:assembleDebug` `BUILD SUCCESSFUL`
with target APK independent SHA256
`dd9672c979a6a7b1c1cba6ff5bc1331828d3eab4bf20cf9c8b59f465edbdf425` (see §21 and
`DSH_PHASE_RESULT.md`). It adds two **disabled-by-default explicit seams** — an
in-process periodic local-consolidation scheduler (no WorkManager dependency,
explicit `LOCAL_ONLY` + `NOT_REQUIRED`, serial/idempotent/cancellable) and an
explicit model entity-enrichment path (reuses the shared `ChatClient`/
`ConsolidationModelConfig`, bounded strict JSON with a real candidate-id /
source-ref whitelist, zero network by default, does not auto-persist).**

**Phase 36 (schedule wiring + explicit entity-enrichment UI + generic batch event
enrichment) was executed in this worktree with the same toolchain: the focused new
classes `PeriodicSchedulingHostTest` / `BatchEventEnrichTest` /
`EntityEnrichmentPersistenceTest` / `SettingsSchedulingEnrichTest` run green, and
the full `:app:testDebugUnitTest` = **121 test classes / 951 tests, 0 failures, 0
errors, 0 skipped**, plus `:app:assembleDebug` `BUILD SUCCESSFUL` with target APK
independent SHA256
`90393ea0775e4f404c84cbad58d7e4ccff8f2ce5bb18b7c5cb418fd8b1ada78c` (see §22 and
`DSH_PHASE_RESULT.md`). It wires the Phase-35 seams to real **explicit Android
Settings actions** behind `periodicLocalConsolidationEnabled` (default `false`, no
auto-scheduling), a **minimal replaceable `PeriodicTriggerAdapter`** (+ in-process
impl, since no WorkManager is in the offline cache), a **caller-only**
`EntityEnrichmentPersistence` that writes only on explicit MODEL success, and a
new bounded typed **generic batch event-enrichment** seam
(`BatchEventEnricher` / `BatchEventEnrichModelClient` / `EnrichRules`) porting the
Harmony `DataPipelineModelClient.enrichEvents` path (strict JSON, real event-id
whitelist, deterministic rule fallback, serial/cancellable, explicit-action only).**

This phase advances the data-manager row from local consolidation/index to a
real four-domain **profile index / claims** layer: it reads the persisted
event partitions, projects the four persona domains (eat/wear/live/travel) by
literal evidence, and materialises `indexes/profile/state.json` + a bounded
`profile.md`. Model claims (when configured) are strictly validated against real
event ids via the shared OpenAI-compatible Planner backend, with a typed
PARTIAL/ERROR that always preserves the deterministic local projection. A
local-only run carries empty claims (`NOT_CONFIGURED`/`LOCAL_ONLY`) and is never
passed off as model claims. A new Home "生成画像" button is the only entry
(explicit tap only; zero network when unconfigured or no events), and
`ProfileIndexHomeProfileSource`/`ProfileIndexBlender` fill only un-populated Home
pillars with bounded summary/claims + real evidence ids (local-first, never
overwriting user curation). **Still honestly not ported:** **periodic scheduling**
of the rebuild, **full cloud consolidation / model-based dedup**, **`workflow_scene_id`
enrichment**, and the **entity resolver** (`entity/index.json` + alias postings).

**Phase 27 (explicit profile-affinity "同频报告") was executed in this worktree
with the same toolchain (JDK 17.0.19, Android SDK platform 34, Gradle 8.9,
`--no-daemon --max-workers=1`): `:app:testDebugUnitTest` with the focused
`ProfileAffinityAnalyzerTest` **14 tests, 0 failures, 0 errors**.** This phase
adds a real, explicit-user-action cloud report on top of the existing
ProfileShareActivity import flow, faithfully porting the Harmony
`runProfileShareAffinityAnalysis` / `buildProfileAffinityMessages` behaviour to
the shared OpenAI-compatible Planner chat backend — while preserving every
safety boundary:

- **Typed config gate.** `ProfileAffinityConfig.fromEndpointConfig()` returns
  null unless the Planner base URL + API key + model are all configured and the
  URL normalizes to a usable endpoint, so no network call can ever fire without
  credentials.
- **Explicit button only.** The report is generated **only** from the explicit
  "生成同频报告" button after a successful import — never on launch or
  automatically post-import.
- **Two bounded profile texts only.** The two-message prompt carries only the
  local profile summary (`buildLocalAffinityProfileText`, `shortSummary`-based,
  capped at `MAX_PROFILE_INPUT_CHARACTERS`) and the imported remote profile
  text (also capped by `limitProfileText`); source URIs, tokens, and unrelated
  local data are never transmitted.
- **Output bounded + errors redacted.** `MAX_REPORT_CHARACTERS` caps the report;
  `SensitiveLogRedactor` scrubs `sk-*`/`Bearer`/keys from error paths; typed
  `ProfileAffinityException` reasons surface honest user-facing messages.
- **Lifecycle cancellation.** `onDestroy` cancels the in-flight coroutine and
  dismisses the dialog.
- **No new credentials, no Runtime.exec/ProcessBuilder/adb/hdc/coordinate GUI
  automation;** existing `ChatCompletionsClient` behaviour is unchanged. See
  `DSH_PHASE_RESULT.md` for the focused test command and results.

**Phase 26 (status-only live agent-status overlay) was executed in this
worktree with the same toolchain and a single Gradle worker
(`--no-daemon --max-workers=1`).** The overlay migration becomes materially
real with a safe, bounded, **status-only** renderer while keeping every safety
gate:

- `OverlayStateRegistry` gains a thread-safe, device-free agent-status field: a
  tiny `OverlayAgentState` enum (IDLE/RUNNING/TOOL/ERROR/COMPLETED) plus a
  sanitized, **≤160-char** status line (control chars collapsed) that never
  stores screenshots, prompts, tokens, or secrets.
- `ChatViewModel` publishes send/tool/completed/error/cancel transitions into
  that registry (purely observational — chat behavior & logging unchanged).
- `OverlayCapabilityFlags.rendererConfigured` and the Android/Registry overlay
  permission detectors now report `true` **only** because a real status-only
  renderer exists; `READY` still requires explicit opt-in **and** the observed
  `SYSTEM_ALERT_WINDOW` grant.
- `OverlayFloatService` renders and live-refreshes that bounded status text
  while the explicitly started service is alive; it halts when the gate drops
  from READY, is `FLAG_NOT_TOUCHABLE` (never intercepts input), never captures
  screens, never executes commands, and never claims edge-flow/GUI control.
- A pure `OverlayServiceStartPolicy` + an injectable `OverlayServiceController`
  drive an explicit Settings start/stop action, which is enabled only at gate
  READY and stops when opt-in is turned off — never started on launch or
  recomposition.

Not ported (documented, honest): Harmony edge-flow glow (`EdgeFlowWindow.ets`)
and agent GUI controls remain **unsupported**. See `DSH_PHASE_RESULT.md` for the
focused overlay/settings test command and results.

**Phase 25B (wire WeChat collection gate to the typed adapter) was executed in
this worktree with the same toolchain and a single Gradle worker
(`--no-daemon --max-workers=1`).** The old always-blocked
`WechatCollectionGate.collect` boundary is replaced by a suspend
`collect(request)` API that invokes `WorkflowDeviceAdapter.wechatCollect`
(`wechat_collect`, added in Phase 25A) **only** when strict validation, the
`summaryEnabled` opt-in and a health-verified (control-ready) adapter all pass;
otherwise it returns an honest typed `BLOCKED` / `NOT_CONFIGURED` with zero
bridge calls. The attached device-free tests (fake adapter) prove the no-call
gates, the single valid call, the fixed mapping of
`HdcActionResult.Success`/`BridgeError`/`TransportError`/`Unsupported` to the
typed `WechatCollectionResult` vocabulary, secret-redaction/bounding of surfaced
messages and cancellation propagation. No scheduling, background work, arbitrary
command API, or UI redesign was added. See `DSH_PHASE_RESULT.md` for the focused
test command and results.

> **Phase 43 (chat context budget + multi-session) build verification was
> actually executed in this worktree** with the same toolchain (JDK 17.0.19,
> Android SDK platform 34, Gradle 8.9, `--no-daemon --max-workers=1`,
> `--offline`): the context-compression budget was wired into the real
> `DefaultChatRepository` request build (plain send + agent loop) and consumed
> from the user's Settings thresholds; the multi-session Room/UI flow (new /
> list / drawer / switch / delete / restart restore) landed on the existing DAO
> (no new database). `:app:testDebugUnitTest` passes **1038 tests / 0 failures**
> (19 of them new Phase-43 tests: 11 `ChatContextBudgetTest` + 8
> `MultiSessionRepositoryTest`) and `:app:assembleDebug` produces the APK.
> Exact commands and results are in `phase43.result.md`.

> **Phase 1 (historical) blocker:** the original authoring machine lacked Java,
> Android SDK, and Gradle, and the sandbox could not write into
> `/root/ClawMate-Android`. All phase-1 deliverables were authored as a
> copy-ready tree. Phase 2 was built and tested in this worktree directly.

---

## 1. Inventory summary

The HarmonyOS app is a single-`entry` ArkTS application. App shell:

- `AppScope/app.json5` → bundleName `com.clawmate.app`, `EntryAbility`.
- `entry/src/main/module.json5` → requests INTERNET, GET_NETWORK_INFO,
  KEEP_BACKGROUND_RUNNING (dataTransfer), SYSTEM_FLOAT_WINDOW, MEDIA_LOCATION,
  MICROPHONE. Declares profile-share `skills` for `.mobi-profile` / UTD
  `com.mobiinfra.profile-json` file-open intents.
- `entry/src/main/ets/pages/Index.ets` (~6k lines) is the single app root: an
  onboarding UI-gate followed by a 5-tab bottom bar.
- Navigation tabs (from `pages/main/MainTabConfig.ets`):
  0 首页 Home · 1 汇总 Summary · 2 聊天 Chat (center) · 3 任务 Task · 4 设置
  Settings.
- App-level glue: `entryability/EntryAbility.ets` (background long-running
  task + two-layer float overlay during agent execution), `EntryBackupAbility`.
- Build target: HarmonyOS NEXT, API 20–22 (`targetSdkVersion 6.0.2(22)`);
  native ABI `arm64-v8a`, `x86_64`.

---

## 2. Feature mapping → Android strategy

Legend for **Strategy**: **Direct** (port 1:1), **Adapt** (platform
equivalent with adapter), **Blocked/Disabled** (no Android equivalent; expose
a controlled disabled state), **Defer** (later phase).

| # | Feature | Source of truth (file) | Strategy | Android plan |
| --- | --- | --- | --- | --- |
| 1 | **Onboarding** | `pages/onboarding/FirstLaunchOnboarding.ets`, `OnboardingTypes.ets`, spec `docs/superpowers/specs/2026-07-02-first-launch-onboarding-design.md` | Adapt | **Phase 6 shipped** an Android `first-launch` gate before the 5-tab shell: a real Compose `FirstLaunchOnboardingScreen` with the three capability preview slides + a usage-guide page (`home/domain/OnboardingTypes.kt` ports the pure contract; version key `firstLaunchOnboardingVersion` = `2026-07-core-preview-usage-guide`). Completion version persisted to an app-private DataStore `clawmate_home` preferences file via `home/data/DataStoreOnboardingRepository.kt`; works on a configuration-free fresh install with no network. |
| 2 | **Home / recommendations** | `pages/home/HomePage.ets`; `datamanager/HomeRecommendationService.ets`, `DataManagerTypes.ets`, `IndexService.ets`, `ConsolidationService.ets` | Adapt + **data source Defer** | **Phase 6 shipped** the Compose Home UI (`home/domain/HomeDomain.kt`, `home/ui/HomeScreen.kt`): four persona pillars (理解你/守护你/陪伴你/成就你 as 吃/穿/住/行), honest profile loading/empty/error states, recommendation list with completion toggles + 未完成/全部 filter + pagination + expand, and an **honest NOT_CONFIGURED avatar section** (avatar generation is DISABLED on Android — never fabricated). Recommendation *content* still requires the upstream data-manager pipeline (a later phase); completion flags persist via `home/data/DataStoreHomeRecRepository.kt`. |
| 3 | **Chat & agent loop** | `pages/chat/*` (ChatPage, ChatPageTypes, ChatMessageDataSource, ChatSessionController); `chat/ChatSessionTypes.ets`; `utils/CloudModelClient.ets` (SSE at :725); `utils/AgentLoopRunner.ets`, `ChatReactAgent.ets`, `ChatToolRegistry.ets`, `AgentActionParser.ets`, `AgentExecutionController.ets`; spec `docs/superpowers/specs/2026-06-10-chat-session-management-design.md` | Adapt | Compose `LazyColumn` chat; Repository + ViewModel; OpenAI-compatible `/v1/chat/completions` via OkHttp SSE exposed as `Flow<String>`; ReAct tool loop; Markdown renderer; Room persistence (source uses JSON files in `filesDir/chat-sessions/`). |
| 4 | **Gallery / collection** | `utils/gallery/*` (GalleryAnalyzer, GalleryAutoScanner, GalleryOcrService, GallerySearchService, GalleryMonthlyInsights, GalleryMemorySync, GalleryStorage, GalleryTypes); `pages/gallery/GalleryPage.ets`; `pages/collection/CollectionPage.ets`, `CollectionGroupDetailPage.ets`; `datamanager/CollectionGroupingService.ets`; docs `docs/gallery/*` | Adapt | Android Photo Picker / MediaStore with URI grants (no broad storage permission). **Phase 3 shipped** the picker + EXIF read + Room persistence + collection grouping + search. **Phase 9 shipped** a real item detail flow (content URI / date / EXIF / analysis state / tags / summary / collection membership), explicit edit (title/summary/tags) + delete-by-item with confirmation (Room-persisted, groups recompute), and Android reverse geocoding via the platform `Geocoder`. **Phase 11 shipped** a real on-device Chinese OCR adapter (`gallery/ocr/AndroidOcrEngine`) using ML Kit `com.google.mlkit:text-recognition-chinese` (bundled model) over the picked content URI (ContentResolver → bounded Bitmap → ML Kit), with typed success/empty/cancel/model-download/error/unavailable states, an explicit **Analyze OCR** action in the detail panel, and Room persistence of only real recognized text + `analyzed` status. Cloud vision (MiniCPM-V) stays NOT_CONFIGURED. **Phase 15 shipped** the portable monthly-insights pure port over stored Room items (`gallery/insights/GalleryMonthlyInsights.kt`), an `observeMonthlyInsights` repository derivation, and a 月度洞察 panel in the existing Gallery screen (real Loading / Empty / Error states + honest NOT_CONFIGURED cloud AI-summary/embedding boundary). The optional cloud AI month summary / remote embedding still require a configured client (rows D1/D12). |
| 5 | **Data manager** | `datamanager/*` (DataManagerFacade, DataManagerStorage, DataManagerTypes, DataManagerUtils, DataPipelineModelClient, ConsolidationService, IndexService) | Adapt | Source is JSON-file rooted at `filesDir/` (`workflows/...`, `gallery-log/...`) with SHA-256 fingerprints. On Android prefer Room for structured sets + file store for media; port DataManagerFacade as a single entry point (Repository namespace). **Phase 29** ships the **first daily-log input + local event-index foundation** (`datamanager/`): pure `DailyLogParser` (Harmony metadata header + numbered `1. …` entries, strict date/file/count/char/metadata bounds, countable honest errors, never thrown), `DailyLogPlannerEventMapper` + read-only app-private `DailyLogEventSource` over `filesDir/workflows/daily-log/YYYY-MM-DD/*.md` (no external storage/URI/image/network, stable source-ref ids, `imagePaths` empty), and `DailyLogWorkspace` as the documented external-import write API. The Phase-28 Home "生成推荐" flow now blends stored gallery + daily-log events. **Phase 30** adds the **local consolidation + index foundation** (`datamanager/`): a deterministic, no-cloud-model `ConsolidationEngine` (faithful port of `ConsolidationService.consolidateGroup` + `DataManagerUtils.ets` helpers, exact + normalized dedup against a recent scope, stable monotonic `record_id`s), `ConsolidationRepository` (scan existing daily-log → consolidate each month/file group → persist per-group `outputs/<month>/<file>.state.json` + markdown, rebuild `state-index.json`), `IndexService` + `DailyLogLocalIndex` (rebuild bounded month event partitions, partition-index, event manifest, temporal docs; stable event ids `${relativeState}#${recordId}`; read-only bounded query by date/month/source/keyword), `AtomicJsonStore` (atomic temp+rename, bounded size), and `LocalConsolidationIndexRunner` as the single explicit, zero-network entry point. **Remaining data-manager items stay Partial/Deferred (honestly not ported):** Harmony **cloud/periodic** consolidation + model-based dedup, profile/claim derivation, `workflow_scene_id` enrichment, and any background scheduling — Phase 30 is strictly deterministic-local and user-triggered. **Phase 32** adds the **profile index / claims foundation** (`datamanager/` + Home): reads the Phase-30 event partitions, aggregates the four persona domains (eat/wear/live/travel) by literal evidence, and materialises `indexes/profile/state.json` + bounded `profile.md`; model claims (when configured) are strictly validated against real event ids via the shared OpenAI-compatible Planner backend, with a typed PARTIAL/ERROR that always preserves the deterministic local projection; a local-only run has empty claims (`NOT_CONFIGURED`/`LOCAL_ONLY`) and is never passed off as model claims. A new Home "生成画像" button is the only entry (explicit tap only; zero network when unconfigured or no events), and `ProfileIndexHomeProfileSource`/`ProfileIndexBlender` fill only un-populated Home pillars with bounded summary/claims + real evidence ids (local-first, never overwriting user curation). **Phase 33** adds the **workflow-scene + entity index/resolver foundation** (`datamanager/`): real `workflow_scene_ids` now flow from the daily-log `task_scene_id` metadata through the Phase-30 consolidation into indexed events (backward compatible with old artifacts), and a deterministic **local-only** resolver materialises `indexes/workflow-scene/index.json` (`postings: sceneId -> eventIds`) and `indexes/entity/index.json` (canonical entities + alias postings + mention assignments) from real event mentions + **conservative bounded** labelled-name extraction — unknown types/empty/over-long dropped, no URI/image/token, same-type names/aliases merge stably, different types never merge, and a model gate keeps Android zero-network/local-only unless an explicit fully-configured action invites it. **Phase 34** adds the **cloud/model consolidation + model-based dedup** path (`cloud` row): a `ConsolidationModelConfig` gate + strict-validating `ConsolidationModelClient` (bounded real-text facts/dedup prompts) and an explicit `CloudConsolidationRepository`/`ModelConsolidationApplier` that splits bounded entries into bounded facts and applies strict `new|duplicate|merge_with_existing|skip_ambiguous` decisions with real matched-id membership, falling back to the deterministic local engine on any no-config / no-entry / bad-JSON / failure / unverifiable-match (typed `LOCAL`/`PARTIAL`), preserving prior valid state and Phase-33 scene ids + stable monotonic ids + processed-hash skips (see §20). **Phase 35** adds two **explicit seams** (see §21): a **disabled-by-default, in-process periodic local-consolidation scheduler seam** (`PeriodicConsolidationScheduler` + pure `SchedulingPolicy`; **no WorkManager dependency**, explicit `LOCAL_ONLY` + `NOT_REQUIRED` opt-in, serial/idempotent/cancellable, local-only, never touches the model-backed repository) and an **explicit model entity-enrichment seam** (`EntityEnrichmentRunner` + strict-validating `EntityEnrichmentModelClient` + `EntityEnrichmentClientFactory`, reusing the shared `ChatClient`/`ConsolidationModelConfig`; bounded strict JSON with a real candidate-id/source-ref whitelist, **zero network by default**, and does **not** auto-persist — it returns a new enriched document to the caller). **Phase 36** wires these seams to real **explicit Android Settings actions** and adds the **generic batch event-enrichment** model seam: a **minimal replaceable `PeriodicTriggerAdapter`** (+ in-process impl + `PeriodicSchedulingHost`) drives the scheduler from an explicit "应用调度" action behind a new opt-in `periodicLocalConsolidationEnabled` (default `false`; nothing schedules on its own), a **caller-only persistence** seam (`EntityEnrichmentPersistence`) writes the entity-enrichment output only on an explicit MODEL success, and a new bounded typed seam (`BatchEventEnricher` / `BatchEventEnrichModelClient` / `EnrichRules`) ports the generic `DataPipelineModelClient.enrichEvents` keyword/domain/entity/todo extraction over real indexed events (strict JSON, real `event_id` whitelist, deterministic rule fallback, serial/cancellable, explicit-action only, not auto-persisted). No `WorkManager` dependency was added (absent from the offline cache); the adapter is the replaceable seam for a future `Worker`. The profile model-claim path remains the Phase-32 `ProfileModelClient` (see §22). |
| 6 | **Workflows / tasks** | `utils/Workflow*.ets` (WorkflowRunner ~2k lines, WorkflowStorage, WorkflowTypes, WorkflowJsonRepair, WorkflowSceneConfig, WorkflowDateContext); `pages/task/TaskPage.ets`, `WorkflowFileViewer.ets`; default configs `resources/rawfile/workflows/configs/*.json` | Adapt + **GUI part Blocked** | Workflow JSON schema (metadata/defaults/context/steps; step types gui_task/gui_action/tool/loop/if) ports directly. Execution needs screenshot-driven VLM "decider" loop → on Android drive via adb/UIAutomator or a PC-side service; on-device GUI automation for arbitrary apps = platform blocker (see #11). |
| 7 | **Settings** | `pages/settings/SettingsCenterPage.ets`, `SettingsCenterTypes.ets`, `SettingsCenterControls.ets`, `SettingsCenterTheme.ets`; key inventory in `pages/Index.ets:357–431` | Adapt | 5 sections: 个人与隐私(Personal/Privacy) · 数据采集(Data collection) · 模型与智能体(Model & agents) · 任务与自动化(Task automation) · 存储与关于(Storage & about). Source uses `PersistentStorage.persistProp` → **Phase 5 shipped** an Android `settings/` slice: DataStore-backed typed repository + `SensitiveValueUtils` masking port, Compose `SettingsScreen` with five-section navigation, and honest NOT_CONFIGURED/DISABLED states for unconfigured endpoints. |
| 8 | **Voice / digital avatar** | `utils/voice/*` (MimoVoiceClient, HarmonySpeechRecognizerAsrProvider, HarmonyAudioPlayback, HarmonyPcmStreamPlayback, PcmWav, VoiceTtsPresets); `pages/chat/ChatVoiceOrchestrator.ets`, `DigitalAvatarView.ets`, `VoiceInteractionTypes.ets` | Adapt | **Phase 7 shipped** the chat voice-interaction vertical slice (`chat/voice/`): typed domain models + deterministic normalization (`VoiceInteractionTypes.kt`), ASR transcript merge + duplicate-final suppression (`VoiceTranscriptLogic.kt`), PCM16→WAV builder (`PcmWav.kt`), voice presets (`VoiceTtsPresets.kt`), voice-input start/stop guards (`VoiceOrchestratorLogic.kt`). ASR = Android `android.speech.SpeechRecognizer` adapter with explicit permission/unavailable/error/cancel states and **no hidden mic access** (RECORD_AUDIO surfaced in the UI, requested only on the mic tap). Output = Android `TextToSpeech` adapter (lifecycle-safe init, speak/pause/stop/replay, typed unavailable/error states). Cloud MiMO streaming/TTS stays behind an explicit **NOT_CONFIGURED** interface (`CloudMimoVoiceClient`) — never claimed configured or faked. Chat UI gained mic/listening/transcript + speak/stop/replay controls; a real final ASR transcript becomes ordinary editable chat input. Digital avatar remains DISABLED (not part of this slice). |
| 9 | **Local model** | `entry/src/main/cpp/*` (napi_init.cpp, HIAIModelManager.cpp, MNN headers, llm.hpp), `entry/src/main/ets/config/ModelDownloadConfig.ets`, model-scan/validation in `pages/Index.ets` | **Adapter (Phase 10 shipped the capability boundary + native stub)** | Source loads an `MNN` LLM via N-API (`libentry.so`) exports `loadModel`/`generate`/`chat`/`reset`/`unloadModel` and a HiAI (`HIAIModelManager`) path; a model dir is loadable when it has `config.json` + a weight file (`.mnn`/`.om`/`.safetensors`/`.weight`) under `filesDir/model` (legacy) or `filesDir/modelscope/{owner}__{repo}`. **Phase 10 shipped** an Android local-model capability boundary (`localmodel/`): typed states NOT_CONFIGURED / UNSUPPORTED / LOADING / READY / ERROR with typed error kinds, an app-private path validator (no traversal / no arbitrary deletion), a deterministic inventory scanner (probe seam), and an explicit "加载/测试" action in the 模型与智能体 Settings panel that reports **READY only on a real native load success**. Android MNN/HiAI runtime is absent → the JNI/CMake adapter (`app/src/main/cpp/`, `libclawmate_local_model.so`) is a truthful stub returning typed NOT_CONFIGURED/UNSUPPORTED; it **never fabricates** model output. Real MNN Android prebuilts + an `Llm`-equivalent C++ adapter are documented as the missing pieces. |
| 10 | **Cloud model** | `utils/CloudModelClient.ets`, `CloudDeciderPrompt.ets`, `CloudLogOutput.ets`, `gallery/GalleryEmbeddingService.ets` | Direct/Adapt | OpenAI-compatible `/v1/chat/completions` already used → OkHttp + kotlinx.serialization. Supports Planner/Decider separate base URLs, `/v1/chat/completions`, cancellation, retries. Preserve streaming + error states. |
| 11 | **HDC / GUI automation** | `utils/MobileGuiTask*.ets`, `HdcWorkflowBridge.ets`, `PcServerMonitor.ets`; `python/hdc_server.py`, `python/harmony_agent.py` | **Blocked → Adapter (Phase 8 shipped the safe client + adapter)** | Harmony auto-agent drives a physical device via **PC-side Python over HDC** (`hdc fport` + HTTP bridge on :9124, in-app TCP :9126). On Android **Phase 8** ships a safe PC HDC bridge client: a typed OkHttp `HdcBridgeClient` for `GET /api/health` + `POST /api/workflow` (normalized URLs, bounded timeouts, cancellation, tolerant parsing, typed errors) and a `WorkflowDeviceAdapter` boundary (`app_start`/`screenshot`/`gui_action`/`execute_decider_action` only). **No** `Runtime.exec`/`ProcessBuilder`/shell/adb/hdc/AccessibilityService/`/api/run_cmd`. `TaskComponent` starts DISABLED (default `DisabledWorkflowDeviceAdapter`) and only an explicitly injected, health-verified adapter makes protocol calls; the settings UI runs a health check **only on an explicit button tap** and connects only after a real `ok` + control-ready reply. Workflow `gui_action` steps return honest bridge results/errors only when a configured adapter is registered; otherwise they stay `BLOCKED`. |
| 12 | **Python bridge** | `entry/src/main/python/*` (hdc_server.py :9124, harmony_agent.py, serve_model.py :9123, wechat_collect/*) | **Blocked → Adapter (Phase 8 HTTP-over-LAN client)** | Not runnable on-device on Android. Keep as a PC-side component; Android app talks to it over HTTP (localhost/LAN) via the Phase-8 `OkHttpHdcBridgeClient`. Do not bundle Python in the APK. A verified bridge + adapter is required for workflow GUI steps; until then they are `BLOCKED`. |
| 13 | **Native C++ / MNN** | `entry/src/main/cpp/**` (CMakeLists, MNN/llm includes, libentry N-API), `entry/libs/arm64-v8a` | **Adapt → stub (Phase 10)** | **Phase 10** added an Android JNI/CMake scaffold (`app/src/main/cpp/CMakeLists.txt` + `local_model_jni.cpp`) that compiles the `libclawmate_local_model.so` stub via the Android NDK (25.2.9519653) + system CMake. **No MNN-for-Android prebuilt or OHOS N-API/HiAI library is vendored** (the OHOS `.so`/headers are not copyable and MNN-for-Android is a sizeable external dependency), so the stub returns typed NOT_CONFIGURED/UNSUPPORTED and documents exactly what a real adapter needs: `libMNN.so` + MNN headers per ABI plus an `Llm`-equivalent C++ loader. The stub verifiably packages into `app-debug.apk` (all three ABIs). |
| 14 | **Profile share / knock** | `utils/ProfileKnockShareService.ets`, `module.json5` (UTD `com.mobiinfra.profile-json`, MIME `application/vnd.mobiinfra.profile+json`, `.mobi-profile`) | Adapt | Android `ACTION_VIEW`/`ACTION_SEND` + `<intent-filter>` + `FileProvider`. Phase 1 ships `ProfileShareActivity` (intent receivable stub); import/merge pipeline later. Source uses ShareKit knock-to-knock → Android NFC as a coarse approximation (defer). |
| 15 | **Float window / overlay** | `entryability/EntryAbility.ets`, `pages/EdgeFlowWindow.ets`, `pages/FloatWindow.ets`, `utils/AgentExecutionController.ets` | **Blocked → Adapter** | Harmony `SYSTEM_FLOAT_WINDOW` two-layer overlay during active agent → Android needs `SYSTEM_ALERT_WINDOW` (overlay permission) + MediaProjection for capture-safe hiding. Requires user opt-in; controlled disabled state until then. **Defer to agent-loop phase.** |
| 16 | **Background / notifications** | `entryability/EntryAbility.ets` (backgroundTaskManager DATA_TRANSFER + wantAgent) | Adapt | Android foreground service + notification channel (`FOREGROUND_SERVICE_DATA_SYNC`, POST_NOTIFICATIONS) while an agent/workflow is running. |

---

## 3. Networking & security

- **Keep secrets out of source control.** The HarmonyOS `config/DefaultEndpointConfig.ets`
  contains real endpoints and API keys (internal model server IPs, HDC bridge
  `http://<lan-ip>:9124`, an avatar image API key, a voice TTS API key, and
  doubao/mimo models). **None of these values — endpoints, ports, or keys —
  are copied into this repository, and the referenced secret values are
  intentionally not reproduced in this document either.**
  `GallerySettingsDefaults.ets` also contains hard-coded keys — do not copy.
- Android side: `com.clawmate.app.config.EndpointConfig` loads environment
  values (endpoint base URLs, API keys, `cloudPlannerModel`) from git-ignored
  `app/src/main/res/raw/local_config.properties`;
  `config_example.properties` is the committed safe template with empty values.
- Protocol: OpenAI-compatible HTTP/SSE (`/v1/chat/completions`) is preserved.
  Cleartext is enabled for the LAN model/HDC bridge via
  `res/xml/network_security_config.xml` to mirror the HarmonyOS
  `network_config.json`; **lock this down to HTTPS before release.**
- Treat all imported profile data and model output as untrusted input (port
  `utils/PromptSafe.ets` guardrails in later phases).

---

## 4. Shared utilities to preserve

`utils/AppLogger.ets` → kotlin `Log`; `utils/JsonCompat.ets` → kotlinx.serialization
(write tolerant parsers); `utils/DateUtils.ets`; `utils/SensitiveValueUtils.ets`
(mask detection for settings); `utils/TextEncodingCompat.ets`;
`utils/MarkdownParser.ets` (→ a Markdown/Compose renderer); Workflow JSON repair
and scene-config as pure functions (good unit-test candidates).

---

## 5. Platform blockers & controlled disabled states

These HarmonyOS capabilities have no 1:1 Android equivalent and must not be
silently dropped:
1. On-device GUI automation of arbitrary apps (Huawei agent via PC/HDC) →
   Android requires root / Shizuku / AccessibilityService or a PC bridge.
   **Phase 8 ships a safe PC HDC bridge client + adapter** (typed HTTP calls to
   the PC Python bridge, no `Runtime.exec`/`ProcessBuilder`/shell/adb/hdc/
   AccessibilityService/`/api/run_cmd`). It stays `<devices> off` (DISABLED)
   until a caller explicitly registers a health-verified adapter; workflow GUI
   steps report `BLOCKED` with a "requires a configured PC HDC bridge" reason.
2. System float overlay during agent execution (`SYSTEM_FLOAT_WINDOW`) → needs
   `SYSTEM_ALERT_WINDOW` runtime permission; gate behind user consent.
3. Harmony CoreSpeechKit → `android.speech.SpeechRecognizer`.
4. HiAI/NNRT NPU path for local model 
   **Phase 10:** neither the OHOS N-API/HiAI runtime nor MNN-for-Android is
   vendored, so the native adapter is a truthful stub returning typed
   NOT_CONFIGURED/UNSUPPORTED; the capability boundary still fully validates
   model inventory + app-private paths and reports a typed ERROR/UNSUPPORTED
   state instead of fabricating output.
5. Gallery OCR runtime: ML Kit Chinese text recognition is **wired and bundled**
   (Phase 11) but its runtime + any on-device model handling must be exercised
   on a real device/emulator; this worktree has none, so the real detector is
   documented (the deterministic result mapping is unit-tested device-free).
6. ShareKit "knock-to-knock" share → approximate with Android Share sheet / NFC.
7. **Harmony native / AppStorage log sources (Phase 14):** Harmony `LogView.ets`
   reads `native.getLogs()/clearLogs()` (a C++ ring buffer in `libentry.so`) and
   `AppStorage` cloud/gallery log strings. Neither has an Android equivalent we
   own. Phase 14 therefore implements a real in-app ring buffer fed by actual
   app code paths (app launch / settings save / HDC result / chat / gallery OCR)
   and surfaces them under application / cloud-Agent / gallery / workflow tabs.
   It does **not** claim logcat, a C++ buffer, or captured event logs it cannot
   honestly provide, so the viewer's empty state is truthful.

---

## 6. Verification

**Phase 2 — executed in this worktree** (JDK 17, Android SDK platform 34,
Gradle 8.9). Exact commands and output are in `DSH_PHASE_RESULT.md`.

- `./gradlew :app:assembleDebug` — **PASS**.
- `./gradlew :app:testDebugUnitTest` — **PASS** (all unit tests green).
- Instrumented smoke test `MainActivityTest` (5-tab shell) is present but was
  **not run** here: no emulator/device is available (`connectedAndroidTest`
  requires one).

Unit tests added in Phase 2 (deterministic; no network unless noted):
- `chat/cloud/SseParserTest` — SSE framing (multi-line, chunk boundaries, CRLF,
  blank/ignored lines, flush) and chunk mapping (`[DONE]`, content delta,
  finish reason, empty/malformed payloads).
- `chat/cloud/ChatClientMappingTest` — `/v1/chat/completions` URL normalisation
  and OpenAI-style error-body extraction.
- `chat/cloud/ChatCompletionsClientTest` — real OkHttp against a MockWebServer:
  non-streaming mapping, SSE stream → deltas, HTTP error → typed `ChatException`,
  malformed SSE → typed `ChatException`.
- `chat/data/DefaultChatRepositoryTest` — repository behavior with an in-memory
  fake DAO: message persistence (no fake replies), title derivation, missing
  config throws `CONFIG` and stores no assistant reply, stream failure persists
  user not assistant, repeated sends accumulate in one session.
- `config/EndpointConfigTest` — extended for the new `cloudPlannerModel` key.

**Every phase after this must run**:
`./gradlew :app:assembleDebug :app:testDebugUnitTest :app:connectedAndroidTest`

**Phase 3 — executed in this worktree** (JDK 17, Android SDK platform 34 /
build-tools 34.0.0, Gradle 8.9, `--no-daemon --max-workers=1`).
`DSH_PHASE_RESULT.md` records the exact commands and exit codes. In short:
- `:app:testDebugUnitTest` — **PASS** (all unit tests green).
- `:app:assembleDebug` — **PASS** (no offline SDK changes; re-run in the same
  command as the test pass).
- `:app:connectedAndroidTest` — **not run** (no device/emulator available).

Unit tests added in Phase 3 (deterministic; no network unless noted):
- `chat/agent/ToolCallMappingTest` — tool-call wire serialization/round trip,
  streaming tool-call delta accumulation (`StreamToolCallAccumulator`), compact
  of empty calls, and SSE chunk parser `tool_calls` extraction.
- `chat/agent/AgentActionParserTest` — pure action parsing (action/parameters,
  unwrap of wrapper payloads, prose tolerance, action lowercasing, terminal
  detection, missing-action/malformed errors, `coordinates` fallback, nested
  number arrays).
- `chat/agent/ToolRegistryTest` — safe adapter execution, invalid-args error,
  unknown-tool error, adapter-thrown error, **controlled-disabled GUI adapters**
  (explain the PC bridge / permission), terminal adapters, result truncation.
- `chat/agent/AgentLoopTest` — normal content streaming regression, tool-call
  execution then final answer, **max-step guard** (reached-limit, no loop), and
  **overall timeout → typed `AgentException(TIMEOUT)`**; cancellation test.
- `chat/data/AgentRepositoryTest` — `sendAgentMessage` persists user + streamed
  assistant (with tool trace), and persists user only on model-level failure.

**Phase 4 — executed in this worktree** (JDK 17, Android SDK platform 34 /
build-tools 34.0.0, Gradle 8.9, `--no-daemon --max-workers=1`). Exact commands
and exit codes are recorded in `DSH_PHASE_RESULT.md`. In short:
- `:app:assembleDebug :app:testDebugUnitTest` — **PASS** (exit code 0).
- 129 unit tests green (94 prior + **33 new Phase-4 Task/Workflow tests**).
- `:app:connectedAndroidTest` — **not run** (no device/emulator available).

Unit tests added in Phase 4 (deterministic; no network):
- `task/json/WorkflowJsonParserTest` — metadata/defaults/context/steps parsing,
  **nested `loop`/`if` with branch steps**, unknown-step preservation (`raw`),
  `times` as String, malformed JSON → tolerant `Err`.
- `task/json/WorkflowJsonRepairTest` — container-object extraction, fence
  stripping, truncated-JSON closing, CJK punctuation, literal casing.
- `task/runner/WorkflowRunnerTest` — **GUI/HDC `gui_task`/`gui_action` → BLOCKED**
  (never faked as success), **`tool` VLM → NOT_CONFIGURED**, deterministic
  `sleep`/`echo`, real condition-based `if` branch, **cancellation → CANCELLED**,
  **max-step and per-loop bounds**, unknown step type → BLOCKED, `loop.index/
  loop.last` resolution.
- `task/data/WorkflowTaskIndexCodecTest` — deterministic persistence mapping,
  tolerant decode of extra/unknown fields, malformed → null.
- `task/data/DefaultWorkflowRepositoryTest` — bundles configs from a
  bundled source dir into app-private storage, derives default tasks from
  config metadata (title=description, source=name), and persists user edits /
  enabled toggles / run status deterministically across repository instances.

**Phase 6 — executed in this worktree** (JDK 17.0.19, Android SDK platform 34 /
build-tools 34.0.0, Gradle 8.9, `--no-daemon --max-workers=1`). Exact commands
and exit codes (including the final unit-test count) are recorded in
`DSH_PHASE_RESULT.md`. In short:
- `:app:assembleDebug :app:testDebugUnitTest` — **PASS** (exit code 0).
- `:app:connectedAndroidTest` — **not run** (no device/emulator available).

Unit tests added in Phase 6 (deterministic; no network):
- `home/OnboardingTypesTest` — 4 slides (3 capability + usage guide), versioned
  completion key, `shouldShowFirstLaunchOnboarding`, last-page detection,
  button text, index clamping.
- `home/OnboardingViewModelTest` — starts on first slide, next/skip/finish,
  completion persists the version, section navigation.
- `home/HomePersistenceRoundTripTest` — onboarding + recommendation-completion
  round-trips, fresh-install defaults, blank-id rejection, apply-flags without
  inventing recommendations.
- `home/RecommendationListLogicTest` — empty list, pending-only filter,
  show-all ordering, 4-per-page pagination, index clamping, completion grouping.
- `home/HomeViewModelTest` — empty profile ready state, loaded flags applied,
  toggle persistence, error state on loader failure.
- `home/HomeModelMappingTest` — avatar NOT_CONFIGURED/disabled guarantees
  (never a fabricated image/label), status-token parsing, four default pillars,
  completeness, recommendation colors, empty-profile honesty.

**Phase 7 — executed in this worktree** (JDK 17.0.19, Android SDK platform 34 /
build-tools 34.0.0, Gradle 8.9, `--no-daemon --max-workers=1`). Exact commands
and exit codes (including the final unit-test count) are recorded in
`DSH_PHASE_RESULT.md`. In short:
- `:app:assembleDebug :app:testDebugUnitTest` — **PASS** (exit code 0).
- **254 unit tests green** (199 prior + **55 new Phase-7 voice tests**).
- `:app:connectedAndroidTest` — **not run** (no device/emulator available).

Unit tests added in Phase 7 (deterministic; no network/device):
- `chat/voice/VoiceInteractionTypesTest` — metadata normalization (kind/status/
  provider/transcript/confidence clamp), transcript append to input, replay
  guards (`voiceReplayAvailable` / `voiceReplayGlobalBlockNeeded`), status
  labels, ASR metadata factory.
- `chat/voice/VoiceSessionControllerTest` — idle/listening/transcribing/save-to-
  idle/speaking/paused/fail transitions and busy semantics.
- `chat/voice/VoiceTranscriptLogicTest` — cumulative-final replacement, clause
  append, punctuation compaction, partial merge, duplicate-final suppression
  (port of `HarmonySpeechRecognizerAsrProvider`).
- `chat/voice/VoiceOrchestratorLogicTest` — voice-input start/stop generation
  and can-proceed guards.
- `chat/voice/VoiceTtsPresetsTest` — MiMO voice whitelist + normalisation, role
  presets, selection snapshot.
- `chat/voice/VoiceUnavailableMappingTest` — unavailable/permission/error
  mapping into typed pure statuses (error metadata, TTS synthesizing→ready,
  error labels).
- `chat/voice/PcmWavTest` — PCM16→WAV header + chunk concat correctness.
- `chat/voice/CloudMimoVoiceTruthTest` — cloud MiMO synthesis always returns
  **NOT_CONFIGURED** and never fabricates audio; streaming is explicitly
  unconfigured.

**Phase 8 — executed in this worktree** (JDK 17.0.19, Android SDK platform 34 /
build-tools 34.0.0, Gradle 8.9, `--no-daemon --max-workers=1`). Exact commands
and exit codes (including the final unit-test count) are recorded in
`DSH_PHASE_RESULT.md`. In short:
- `:app:assembleDebug :app:testDebugUnitTest` — **PASS** (exit code 0).
- **303 unit tests green** (254 prior + **49 new Phase-8 PC/HDC-bridge tests**).
- `:app:connectedAndroidTest` — **not run** (no device/emulator available).

Unit tests added in Phase 8 (deterministic; MockWebServer is in-process, no
live remote endpoint):
- `task/hdc/HdcBridgeParsingTest` — tolerant health/workflow envelope parsing
  (numeric-as-string, wrong-typed/missing fields, tunnel/target/ports, honest
  error/partial-degradation never claims connected).
- `task/hdc/HdcUrlNormalizerTest` — deterministic URL normalization (trim,
  scheme-relative `//`, scheme-less→http, trailing-slash collapse/dedup,
  empty-port drop, http(s)-only rejection).
- `task/hdc/OkHttpHdcBridgeClientTest` — GET health vs POST workflow mapping,
  HTTP non-2xx → HTTP, malformed JSON, invalid URL (no request), connection
  refused → IO, call timeout → TIMEOUT, cancellation → CANCELLED (MockWebServer).
- `task/hdc/WorkflowDeviceAdapterTest` — default disabled adapter only
  Unsupported/never configured; bridge-backed adapter becomes configured only
  after a live `ok` + control-ready health; safe action mapping; **never sends
  `run_cmd`**.
- `task/runner/WorkflowRunnerAdapterIntegrationTest` — default adapter keeps
  `gui_action`/`gui_task` BLOCKED; a configured adapter lets a `gui_action`
  succeed honestly and surfaces bridge/transport errors as FAILED; a
  configured-but-unverified adapter still BLOCKS.
- `settings/SettingsHdcHealthTest` — app launch performs no network and starts
  not-connected; the health check runs only on explicit click; connected is
  reported only after a real ok reply; failures keep the bridge disabled.

**Phase 9 — executed in this worktree** (JDK 17.0.19, Android SDK platform 34 /
build-tools 34.0.0, Gradle 8.9, `--no-daemon --max-workers=1`). Exact commands
and exit codes are recorded in `DSH_PHASE_RESULT.md`. In short:
- `:app:assembleDebug :app:testDebugUnitTest` — **PASS** (exit code 0).
- **342 unit tests green** (303 prior + **39 new Phase-9 gallery tests**).
- `:app:connectedAndroidTest` — **not run** (no device/emulator available; the
  platform Geocoder path runs on-device only and is intentionally not faked in
  unit tests).

Unit tests added in Phase 9 (deterministic; no device, no live geocoder):
- `gallery/geocode/AndroidGeocoderMappingTest` — pure coordinate validity/
  placeholder rejection and `AddressFields -> GalleryLocationInfo` mapping
  (blanks omitted, never fabricated).
- `gallery/geocode/AddressFormatterTest` — hierarchy join/omission, feature-name
  anchor, city/province/district selection; `ReverseGeocodeError` typed messages
  and `ReverseGeocodeOutcome -> ReverseGeocodeResult` adapter.
- `gallery/data/GalleryRepositoryEditDeleteTest` — explicit title/summary/tags/
  location edits persist via the DAO contract; delete removes only the one item
  and recomputes groups; missing-id edits are no-ops; item-group membership and
  group recomputation follow item-set changes (in-memory fake DAO, no Room).
- `gallery/ui/GalleryViewModelEditDeleteTest` — edit/delete through the
  ViewModel persist to the repository and clear the detail on delete;
  reverse-geocode resolution stores an address **only** on a real resolved
  result; a failed lookup surfaces `LocationViewState.Failed` and never
  claims/persists a location; no-coordinate items report honestly.
- `gallery/ui/GalleryDetailLabelsTest` — deterministic detail label mapping
  (analysis state, location failure text, tags text, title fallback, content URI,
  capture time) with an honest "unknown/—" fallback.
- `gallery/analysis/CapabilityEnginesTest` (extended) — `CapabilitySummary`
  `hasUnsupported`/`hasUnconfigured` and the typed `Resolved` outcome mapping.

**Phase 10 — Local model capability boundary — executed in this worktree**
(JDK 17.0.19, Android SDK platform 34 / build-tools 34.0.0, Android NDK
25.2.9519653 + system CMake 3.22.1 installed via the SDK's `sdkmanager`,
Gradle 8.9, `--no-daemon --max-workers=1`). Exact commands and exit codes are
recorded in `DSH_PHASE_RESULT.md`. In short:
- `:app:assembleDebug :app:testDebugUnitTest` — **PASS** (exit code 0)**; the
  APK packages `libclawmate_local_model.so` (the truthful native stub) for
  arm64-v8a / armeabi-v7a / x86_64.
- **380 unit tests green** (342 prior + **38 new Phase-10 local-model tests**).
- `:app:connectedAndroidTest` — **not run** (no device/emulator available;
  native load smoke tests are documented as unavailable on device).

Unit tests added in Phase 10 (deterministic; no device, no live model, no .so):
- `localmodel/LocalModelTypesTest` — typed-state normalization
  (NOT_CONFIGURED / UNSUPPORTED / ERROR), READY-never-assumed-from-disk,
  status/error labels, honest default state.
- `localmodel/LocalModelPathValidatorTest` — app-private path validation:
  `filesDir/model` + `filesDir/modelscope/<repo>` accepted; `..`/`.`/escape/
  non-whitelisted base / filesDir-root rejected; safe `resolveModelDir`.
- `localmodel/LocalModelInventoryTest` — deterministic inventory scan via an
  in-memory probe seam: config.json + weight-file detection, recursion, ABI/
  extensions (`.mnn`/`.om`/`.safetensors`/`.weight`), ModelScope repo decoding.
- `localmodel/LocalModelCapabilityTest` — **no-fake-output** boundary: READY
  only on a genuine native load Success; missing native library → UNSUPPORTED;
  missing config/weight → ERROR; invalid path → ERROR; unknown model honest.
- `localmodel/LocalModelNativeTest` — native result-token parsing
  (`READY`/`OK`→Success, `UNSUPPORTED`/`NATIVE_LIBRARY_MISSING`→Unsupported,
  unknown→Failed), and the JVM fallback that never throws and never fakes READY
  when the .so is absent.

**Phase 11 — Real on-device OCR — executed in this worktree**
(JDK 17.0.19, Android SDK platform 34 / build-tools 34.0.0, ML Kit
`com.google.mlkit:text-recognition-chinese:16.0.1` resolved on Google Maven,
Gradle 8.9, `--no-daemon --max-workers=1`). Exact commands and exit codes are
recorded in `DSH_PHASE_RESULT.md`. In short:
- `:app:assembleDebug :app:testDebugUnitTest` — **PASS** (exit code 0).
- **407 unit tests green** (380 prior Phases 1–10 + **27 new Phase-11 OCR
  tests**).
- The `text-recognition-chinese` SDK is the **bundled** ML Kit variant — the
  Chinese + Latin OCR models and `libmlkit_google_ocr_pipeline.so` are packaged
  into `app-debug.apk` (all three ABIs).
- `:app:connectedAndroidTest` — **not run** (no device/emulator; the real ML Kit
  detector and any model download need a device).

Unit tests added in Phase 11 (deterministic; no device, no ML Kit runtime):
- `gallery/ocr/OcrMappingTest` — pure result mapping: real text (trimmed) vs
  honest empty; typed URI-access/decode/engine failures; model-download state;
  unavailable state; and **no-fake-output** guarantees (blank/null → empty,
  exceptions/unknown classifier → typed failure, never text).
- `gallery/ocr/OcrBitmapDecodeTest` — bounded-decode planning: power-of-two
  downscale, custom bounds, disabled bound, and honest memory-budget refusal for
  an oversized decode.
- `gallery/ui/OcrAnalysisViewModelTest` — the user-triggered Analyze action:
  real text persisted with `analyzed` status; genuine empty persisted; error /
  model-download / unavailable persist nothing and never claim text; approaching
  a previously-analyzed item reflects the stored real result.
- `gallery/data/GalleryRepositoryEditDeleteTest` (extended) — `updateOcr`
  persists real recognized text + analyzed status through Room.
- `gallery/analysis/CapabilityEnginesTest` + `GalleryDetailLabelsTest` — updated
  for the richer typed `OcrResult`/`OcrErrorKind` model and the honest "已分析
  （本地 OCR）" label.

---

## 7. Open items / risks

- **MNN-for-Android absent build:** the local-model native adapter is a
  truthful stub (`libclawmate_local_model.so`). A real on-device model load
  requires linking a prebuilt MNN Android library (per ABI) plus an
  `Llm`-equivalent C++ loader; until then the capability boundary reports
  typed NOT_CONFIGURED / UNSUPPORTED and never fabricates output. See
  `app/src/main/cpp/CMakeLists.txt` and `local_model_jni.cpp` for the exact
  missing pieces.
- **On-device OCR needs a real device to verify live output:** Phase 11 wires
  ML Kit Chinese text recognition (bundled model) end-to-end and unit-tests the
  deterministic result mapping device-free, but a real photo's recognized text
  and any on-device model-download path can only be exercised on a device/
  emulator. This worktree has none, so the live detector is documented as
  requiring device verification (`connectedAndroidTest`).
- **NDK installed for this worktree:** Android NDK 25.2.9519653 + CMake 3.22.1
  were installed into `/opt/deepseek-harness/android-sdk` via `sdkmanager`.
  This is build infrastructure only (never committed); the project pins
  `ndkVersion` so any NDK 25.2.x satisfies the build.
- **Compose compiler plugin:** Phase 1 declared `org.jetbrains.kotlin.plugin.compose`
  against Kotlin 1.9.24, which fails to resolve (that plugin exists for
  Kotlin ≥ 2.0.0). Fixed in Phase 2 by using the Kotlin 1.9.x Compose compiler
  via `composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }`
  (matches Compose BOM 2024.06.00). A future phase may upgrade to Kotlin 2.x and
  the `kotlin.plugin.compose` plugin.
- The Gradle wrapper (`gradlew`, `gradle/wrapper/*`) is generated in this
  worktree (`gradle wrapper --gradle-version 8.9`) and committed, so builds are
  self-contained (e.g. `./gradlew :app:testDebugUnitTest`).
- Launcher icon is a placeholder adaptive icon; real media brought over from
  the HarmonyOS `resources` later.
- Embedding is stubbed off even in the HarmonyOS source — treat as optional.
- `data_extraction_rules` currently excludes backup of app DB/prefs to avoid
  leaking locally-stored personal data; revisit policy with product before
  enabling backup.
- The cloud endpoint is only exercised against the OpenAI-compatible protocol;
  no live endpoint is bundled. Runtime verification against a real model server
  is left as a manual integration step for a configured build.
- **Avatar image generation (Phase 12) is deliberately not wired:** the local
  profile/state is real, but the actual "数字分身" image requires a Seedream
  cloud provider (base URL + API key) or an alternative generator and a consent
  flow. `AvatarGenerationGate` keeps this an honest NOT_CONFIGURED /
  CONFIGURED_BUT_NO_PROVIDER state — no fake avatar is ever produced.
- **Git commit is blocked by the sandbox (Phase 12):** this worktree's git
  metadata lives under `/root/ClawMate-Android/.git/`, which this environment
  denies all writes to (`git add`/`git commit`/`git hash-object -w` fail with
  Permission denied), and a wider sandbox mode requires an approval channel that
  is unavailable (fails closed). All Phase-12 changes are present in the working
  tree and verified by the build, but **no commit hash is claimed**. To commit,
  run `git add -A && git commit -m "Phase 12 coverage audit"` in
  `clawmate-phase12-coverage` from an environment that may write to that `.git`.
- **Gallery monthly insights are now ported (Phase 15).** WeChat summary
  validation was shipped earlier (Phase 13). See §10 D2 and the Phase-15
  changelog entry.

---

## 8. Changelog

- **Phase 38 (this phase):** Genuinely wires the Phase-37 fact-splitting downstream
  into the **existing** model-aware consolidation engine and persists outputs /
  index / profile; persists the Phase-36 batch event-enrichment result into a
  versioned atomic artifact with a real read boundary (see §24).
  1. **Fact-split → persisted model consolidation.** A downstream coordinator
     (`FactSplitConsolidationCoordinator`) consumes the Phase-37
     `FactSplitRunner` candidate facts (keyed by real `source_ref`) into a new
     `CloudConsolidationRepository.consolidateWithSplitFacts` resume-method that
     reuses the exact same engine + persistence as `consolidate` (grouping,
     `ModelConsolidationApplier`, per-group state/markdown/state-index, index +
     profile rebuild). Wired to a discoverable explicit Settings action「事实拆分→
     模型整合并持久化」. Default disabled / zero network; MODEL/LOCAL_ONLY/FALLBACK/
     EMPTY states; a missing real-ref falls back to the deterministic heuristic split
     (never dropped); model failure preserves the prior valid persisted state; serial /
     cancellable / idempotent.
  2. **Persisted batch event-enrichment.** `BatchEventEnrichmentStore` writes the
     Phase-36 enabler keys (keywords/domains/entities/todo/enrichment_method) into a
     versioned, atomically-written `indexes/events/event-enrichment.json` artifact,
     strictly by real `event_id`, **only** on an explicit MODEL success (LOCAL_ONLY /
     FALLBACK / EMPTY never write; old artifact preserved). `IndexedEvent` gained
     optional enrich fields with empty defaults (old JSON still decodes). A read-side
     overlay (`BatchEventEnrichmentOverlay`) is wired into `DailyLogLocalIndex.query`
     and `ProfileIndexBuilder.loadEvents`, so the event-query / profile boundaries read
     the persisted fields after an explicit action and keep old behaviour with no
     artifact. Never writes a URI / path / token / secret; no auto model trigger.
  3. **WorkManager still absent — dependency unchanged.** `androidx.work` +
     transitives remain absent from the offline Gradle cache (re-verified this phase);
     the `PeriodicTriggerAdapter`/`SchedulingPolicy` contract stays the replaceable
     seam and no `Worker`/`PeriodicWorkRequest` was fabricated. All model/background
     paths remain explicit-action-only and default-disabled.
- **Phase 39 (this phase):** Closes the last Phase-38 functional gap by folding the
  persisted batch event-enrichment fields into the **Home planner's actual input
  chain**, so it consumes the enriched `IndexedEvent` keywords/domains/todo (not just
  making them query-visible). `DailyLogEnrichmentResolver` bridges a raw
  `DailyLogEntry.sourceRef` → overlaid `IndexedEvent` (via `ProfileIndexBuilder
  .loadEvents`, which already runs the Phase-38 overlay), producing bounded planner
  enrich fields; `GalleryBackedHomeRecommendationRepository` overlays them onto the
  daily-log planner events by the real sourceRef (the plan's `eventId`) before the
  model call, using the existing `HomeRecommendationPlanner` shape. Old behaviour is
  preserved with no artifact / corrupt artifact / unmatched or blank-fields event; a
  model-produced unknown id can never reach Home (strict real-id lookup). The model
  boundary is unchanged: enrichment is persisted only by an explicit Settings action
  and Home merely reads the artifact on the explicit「生成推荐」button — never on
  launch / recomposition / background. WorkManager remains **not ported** (offline
  cache still lacks `androidx.work` + `lifecycle-service`); no scheduler was faked.
- **Phase 37 (this phase):** Ports the Harmony **fact-batching** path
  (`DataPipelineModelClient.splitEntriesIntoFacts` / `splitEntryIntoFacts`) onto an
  explicit Android seam and pins the periodic-schedule host contract (see §23).
  1. **Explicit fact-splitting seam** — `FactSplitRunner` + `FactSplitTypes` reuse the
     Phase-34 `ConsolidationModelClient` (same shared `ChatClient` +
     `ConsolidationModelConfig`). It reads the **real** daily-log scope and returns
     candidate facts keyed by the entries' real `source_ref`; strict JSON, a real-ref
     whitelist (unknown / duplicate / out-of-scope `source_ref` refused), bounded
     batch/chars/count, `event_date` that is non-ISO or > the `source_date` replaced
     by `source_date`, and model-fact atomicization (Harmony `atomicizeCandidateFacts`).
     Failure / malformed JSON always falls back to the deterministic local split —
     never a fabricated fact. Default `EMPTY`/`LOCAL_ONLY` (zero network) unless an
     explicit + fully-configured action; serial / cancellable; the result is returned
     (never auto-persisted). Wired to an explicit Settings "日志事实拆分" action.
  2. **Phase-34 split fidelity fixes** — `ConsolidationModelClient` gained the
     missing singular `splitEntryIntoFacts`, faithful `event_date` bounds + fallback,
     and a shared `deterministicHeuristicSplit`/atomic-clause helper; `onSourceFallback`
     now also fires on a request failure / malformed reply so an explicit seam can
     report a full-failure honestly.
  3. **Periodic-schedule host contract (WorkManager still absent)** — added A3 host
     tests pinning the unique canonical work name, the `NOT_REQUIRED` network
     constraint + `LOCAL_ONLY` mode, and the local-only worker that never references
     the model-backed repository. A real `WorkManager` `Worker`/`PeriodicWorkRequest`
     is still intentionally not introduced (the `androidx.work` artifacts + several
     transitives are absent from the offline cache and would need a network download),
     so the `PeriodicTriggerAdapter` remains the replaceable contract (§22/§23).
- **Phase 36 (this phase):** Wires the Phase-35 seams into real **explicit Android
  Settings actions** and adds the **generic batch event-enrichment** model seam
  (see §22).
  1. **Periodic local-consolidation scheduling — wired, explicit-only, default
     disabled.** No `WorkManager` dependency exists in the offline Gradle cache, so
     this phase adds a **minimal replaceable `PeriodicTriggerAdapter`** (+
     in-process impl) and an explicit `PeriodicSchedulingHost` bound to a new
     opt-in setting `periodicLocalConsolidationEnabled` (default `false`). The only
     registration path is the explicit Settings "应用调度" action; even a persisted
     `true` schedules nothing on its own. The scheduled job is `LocalOnlyPeriodicJob`
     (deterministic local rebuild) and never calls `CloudConsolidationRepository`.
  2. **Explicit model entity-enrichment wiring** — the Phase-35 `EntityEnrichmentRunner`
     is now reachable from an explicit Settings action with honest state, and a
     **caller-only persistence** seam (`EntityEnrichmentPersistence`) writes the
     model-enriched document **only on an explicit MODEL success**; LOCAL_ONLY /
     FALLBACK / EMPTY never write and preserve the old artifact.
  3. **Generic batch event enrichment** — a new bounded typed seam
     (`BatchEventEnricher` / `BatchEventEnrichModelClient` / `EnrichRules`) ports the
     Harmony `DataPipelineModelClient.enrichEvents` keyword / domain / entity / todo
     extraction over real indexed events, reusing the shared `ChatClient` /
     `ConsolidationModelConfig`: strict JSON, real `event_id` whitelist, strict
     vocabularies, deterministic rule fallback, serial / cancellable, explicit-action
     only, no URI/token/secret, and not auto-persisted. The profile model-claim path
     remains the Phase-32 `ProfileModelClient` (not re-ported).
- **Phase 35:** Two explicit, **disabled-by-default** seams (see §21).
  1. **Periodic local-consolidation scheduler seam** — `PeriodicConsolidationScheduler`
     (in-process controller) + pure `SchedulingPolicy`: **no WorkManager dependency**,
     production default `SchedulerMode.DISABLED`; an explicit `LOCAL_ONLY` + `NOT_REQUIRED`
     policy is required to register a task; unique task-name registration with
     REPLACE/KEEP conflict policy; all runs serial through one `Mutex`, deterministic
     local rebuilds are **idempotent**, and cooperative `isCancelled` checks abort
     before start / between stages via `CancellationException`. It is local-only and
     never references the model-backed `CloudConsolidationRepository`. Nothing runs
     on launch / Home / recomposition / a repository/Application constructor.
  2. **Explicit model entity-enrichment seam** — `EntityEnrichmentRunner` +
     `EntityEnrichmentModelClient` + `EntityEnrichmentClientFactory`, reusing the shared
     `ChatClient` / `ChatCompletionsClient` and `ConsolidationModelConfig`. It enriches
     the Phase-33 local entity scope: bounded candidates, bounded strict JSON, a **real
     candidate-id / source-ref whitelist** (unknown / duplicate / out-of-bounds /
     over-long, and any URI/image/token/secret, are dropped), strict `person|place|
     merchant` types (model `unknown` dropped), status `EMPTY`/`LOCAL_ONLY`/`MODEL`/
     `FALLBACK`. **Zero network by default** (local-only unless configured + explicit
     action + real candidates) and **does not auto-persist** — it returns a new enriched
     document to the caller. **Still not wired (honestly not ported):** a real
     WorkManager worker, explicit UI / Application wiring for both seams, and the generic
     `DataPipelineModelClient` batch event-enrich / profile model-claims path.
- **Phase 34 (this phase):** Cloud/model consolidation + model-based dedup port —
  `CloudConsolidationTypes` (`DedupDecision`, `CandidateFact`, typed
  `ConsolidationPath`/`ModelConsolidationResult`), `ConsolidationModelConfig`
  (reuses `EndpointConfig`/`ChatServerConfig`) + `ConsolidationModelGate`,
  strict-validating `ConsolidationModelClient` (bounded real-text split/dedup
  prompts, no URI/image/token/path, serial + cancellable, unknown decision/`
  unknown/duplicated/out-of-bounds matched id → safe fallback), `ModelConsolidationApplier`
  (pending-hash skip + exact-match first + model/fallback dedup preserving
  `workflow_scene_ids` and stable monotonic ids, decisions audited in
  `model_notes`), and explicit `CloudConsolidationRepository` /
  `ConsolidationModelClientFactory` (zero network unless an explicit,
  fully-configured, user-triggered action with real entries; failed model runs
  preserve the prior valid state; idempotent repeat runs). Backward-compatible
  `ConsolidatedRecord.model_notes` + `ConsolidatedMetadata.processed_entry_hashes`.
  Still not ported: periodic scheduling / WorkManager, and the separate
  model-backed entity-resolution/enrichment path.
- **Phase 33:** Workflow-scene + entity index / resolver foundation —
  real `workflow_scene_ids` (from the daily-log `task_scene_id`) now flow through
  the Phase-30 consolidation into indexed events (backward compatible with old
  artifacts), and deterministic local builders materialise
  `indexes/workflow-scene/index.json` (`postings: sceneId -> eventIds`) and
  `indexes/entity/index.json` (canonical person/place/merchant entities + alias
  postings + mention assignments). `EntitySceneIndexRunner` writes exactly these
  two artifacts atomically and preserves profile state / `profile.md` / Home data;
  unknown/empty/over-long mentions and any URI/image/token are dropped; a typed
  model gate keeps Android zero-network / local-only unless an explicit,
  fully-configured, user-triggered action invites it. Still not ported: periodic
  scheduling, full cloud consolidation / model-based dedup, and the model-backed
  entity resolution / `workflow_scene_id` enrichment path (deterministic local
  resolver is authoritative).
- **Phase 29 (this phase):** Bounded daily-log event source fed into the Phase-28
  explicit Home recommendation flow — the first daily-log input + local event-index
  foundation, honestly marked Partial/Deferred relative to the full Harmony
  data-manager pipeline.
  - **`datamanager/DailyLogParser.kt`** — pure, device-independent parser for
    `filesDir/workflows/daily-log/YYYY-MM-DD/*.md`: Harmony metadata header
    (`workflow_metadata` / `latest_entry_index`) + numbered `1. …` entries, strict
    date/file-name/entry-count/per-entry/total-char + metadata-size bounds. Bad
    files → countable `DailyLogParseError`s (never thrown), so one bad file cannot
    falsify a whole refresh.
  - **`datamanager/DailyLogPlannerEventMapper.kt`** — maps typed entries to the
    existing `HomeRecommendationPlannerEvent`: stable source-ref ids
    (`YYYY-MM-DD/file.md#N`), bounded real text, source `DailyLog`, `imagePaths`
    empty, `domains` empty (honesty).
  - **`datamanager/DailyLogEventSource.kt`** — read-only app-private source (only
    reads existing files, never creates/rewrites, no external storage/URI/image/
    network), plus a test factory.
  - **`datamanager/DailyLogWorkspace.kt`** — documented external-import write API
    for future workflow/import layers (files are never auto-created by the
    recommendation pipeline).
  - **`home/recommendation/HomeRecommendationRepository` (mod)** — `generate`
    now accepts gallery items + daily-log entries and blends them into bounded
    planner events; single-arg gallery-only API preserved for compatibility. The
    output normalizer validates `source_event_ids` over both id namespaces.
  - **`home/ui/HomeViewModel`(mod)**, `HomeViewModelFactory`, `HomeScreen`,
    `ClawMateRoot` — wire an app-private daily-log source that is read **only** on
    the explicit button action (never Home load/recomposition/background). No
    gallery + no daily-log → NoData with zero network calls.
  - Tests: `datamanager/DailyLogParserTest`, `datamanager/DailyLogPlannerEventMapperTest`,
    `datamanager/FileDailyLogEventSourceTest`, `home/HomeDailyLogExplicitActionTest`,
    `home/recommendation/HomeRecommendationDailyLogIntegrationTest`. Exit codes and
    exact counts recorded in `DSH_PHASE_RESULT.md`.
  - **Remaining data-manager work (Partial/Deferred, not claimed):** the Harmony
    cloud consolidation/dedup/profile-claims (`ConsolidationService`), indexing/
    partitions (`IndexService`), and any periodic background scheduling are **not
    ported** in Phase 29 — only parsing and planner input were delivered. No new
    credentials, no Runtime.exec/ProcessBuilder/adb/hdc/coordinate GUI automation.
- **Phase 27 (this phase):** Explicit profile-affinity "同频报告" ported to the
  Android target. After a `.mobi-profile` share is imported into the local avatar
  profile, the import dialog offers an explicit **"生成同频报告"** button that
  calls the shared OpenAI-compatible Planner chat backend with a faithful
  two-message prompt carrying only the two **bounded** profile texts
  (`MAX_PROFILE_INPUT_CHARACTERS` per profile), `temperature = 0.2`,
  `maxTokens = 2048`, mirroring Harmony `runProfileShareAffinityAnalysis`. The
  returned Markdown report is bounded by `MAX_REPORT_CHARACTERS`; error paths are
  redacted via `SensitiveLogRedactor` and mapped to typed, user-safe reasons. The
  `ProfileAffinityConfig.fromEndpointConfig()` gate returns null when the
  Planner backend is not configured (no call ever fires). The report is never
  generated automatically and only after the explicit user action; `onDestroy`
  cancels the in-flight coroutine. Reuses the existing Planner chat config/URL/
  key/model — **no new credentials**, no hard-coded endpoints, no
  Runtime.exec/ProcessBuilder/adb/hdc, no coordinate GUI automation, and the
  existing `ChatCompletionsClient` behaviour is unchanged.
  - **`profile/affinity/ProfileAffinityTypes.kt`** — `ProfileAffinityConfig`
    (validated config gate over `EndpointConfig`), `ProfileAffinityBounds`
    (`MAX_PROFILE_INPUT_CHARACTERS`, `MAX_REPORT_CHARACTERS`),
    `ProfileAffinityErrorReason`, typed `ProfileAffinityException`
    (secret-redacted `message`, honest `userMessage`), `ProfileAffinityResult`,
    and `buildLocalAffinityProfileText` (bounded, deterministic local profile
    summary built from `LocalAvatarProfile.shortSummary`).
  - **`profile/affinity/ProfileAffinityAnalyzer.kt`** — builds the exact
    two-message prompt, caps both profile inputs, calls `ChatClient.completion`,
    enforces the report bound, and maps `ChatException` to typed reasons.
  - **`profile/affinity/ProfileAffinityFactory.kt`** — minimal manual factory that
    wraps the real `ChatCompletionsClient` (same OkHttp construction as chat) with
    no hard-coded endpoint/secret; tests inject a fake `ChatClient`.
  - **`profile/ProfileShareActivity.kt`** (mod) — retains the imported local/remote
    profile facts, adds the explicit "生成同频报告" action, bounded success/failure
    dialogs and a retry path, plus `onDestroy` lifecycle cancellation.
  - **`profile/affinity/ProfileAffinityAnalyzerTest.kt`** — 14 device-free tests
    (config gate, two-message prompt with no URI/key, temperature/maxTokens,
    input/output bounds, empty/error mapping, factory wiring). The
    `buildLocalAffinityProfileText` fixture was corrected to mirror a real local
    profile (only `shortSummary` populated, `summary` blank) so it verifies the
    real production fallback instead of shadowing it.

- **Phase 22 (this phase):** Real but tightly controlled AccessibilityService
  action executor for the safe semantic allowlist (row B1). Android finally
  performs a small, explicit set of on-device semantic actions; the coordinate/
  gesture boundary (Phase 19) is preserved and higher-level arbitrary-app GUI
  automation stays a PC-bridge gap.
  - **`accessibility/SafeAccessibilityAction.kt`** — closed typed allowlist
    (`click_text`, `click_content_description`, `back`, `scroll_forward`,
    `scroll_backward`, `global_back`, `global_home`) with length-bounded inputs
    (text 200, content_description 200, package 512). Everything else — including
    the Phase-19 `tap`/`swipe`/`type_text`/`open_app`/`capture_screen` — is
    rejected. Pure `SafeAccessibilityActionValidation` maps a raw wire name +
    params to a validated action or an explicit rejection.
  - **`accessibility/SafeAccessibilityExecutor.kt`** — the gated executor core:
    `SafeAccessibilityExecutionGate` derives readiness from (opt-in, observed
    system service grant, live foreground package, allowlist); the
    `SafeAccessibilityActionExecutor` platform seam is the only place that may
    touch `AccessibilityNodeInfo`/`performGlobalAction`; and
    `ControlledSafeAccessibilityDispatcher` is the single entry point that
    validates + preflights + (only when ready) performs, returning honest typed
    outcomes `NOT_CONFIGURED` / `BLOCKED_SERVICE` / `BLOCKED_PACKAGE` /
    `UNSUPPORTED` / `ERROR` / `SUCCESS` (SUCCESS only when the seam truly
    performed; **no fake success**).
  - **`accessibility/AccessibilityAutoAgentService.kt`** (mod) — exposes
    `executeSafeAction(optedIn, name, params)`, the only path by which the
    service acts: it re-derives the gate from the live foreground package and a
    connected service and delegates to `AndroidSafeAccessibilityExecutor` (node
    clicks by text/content-description, bounded scroll, `GLOBAL_ACTION_BACK` /
    `GLOBAL_ACTION_HOME`). Records the observed foreground package in
    `AccessibilityStateRegistry` so Settings can mirror it.
  - **`res/xml/accessibility_autoagent_config.xml`** (mod) — keeps
    `canPerformGestures="false"` and **no screenshot flag**; enables
    `canRetrieveWindowContent="true"` so the safe executor can read the
    allowlisted foreground window's node tree (read-only, never auto-forwarded,
    never exfiltrated). Phase-19 `AccessibilityGate` coordinates stay BLOCKED.
  - **Settings test/demo boundary** (任务与自动化) — `SafeAccessibilityExecutorBoundary`
    + `SettingsViewModel.safeAccessibilityExecutorBoundary()` /
    `safeAccessibilityDemoDispatch()`: a read-only panel shows the closed action /
    foreground-package allowlists and live readiness, and a `触发预检` button runs
    the same typed gate/validator through a **no-perform seam** so it reports an
    honest preflight/rejection and can never trigger a real system action or
    fabricate SUCCESS.
  - **Device-free tests (21 new)**: `SafeAccessibilityExecutorTest` (16) +
    `SettingsSafeAccessibilityExecutorTest` (5) — allowlist, bounded inputs,
    package mismatch, disabled/not-configured, success/failure mapping with no
    fake success; existing `AccessibilityGateTest` (14) still passes, proving
    Phase-19 behavior is preserved. Build: `:app:assembleDebug` +
    `:app:testDebugUnitTest` `--max-workers=1` → **BUILD SUCCESSFUL**, **623
    tests, 0 failures, 0 errors**.

- **Phase 21 (this phase):** Config-driven Gallery cloud adapter (rows D1/D2
  configurable part) — no new credentials, no silent upload.
  - **`gallery/cloud/GalleryCloudTypes.kt`** — typed config + bounds + honest
    error model. `GalleryEmbeddingConfig.fromSettings` (remote mode + URL + key
    + model present → ready; else NOT_CONFIGURED), `GalleryMonthlySummaryConfig`
    (explicit `galleryAiSummaryEnabled` opt-in + planner URL/key/model present →
    ready), `normalizeEmbeddingUrl` (port of `CloudModelClient.normalizeEmbeddingUrl`),
    `GalleryCloudBounds` (embedding input 512 chars, embedding dims 4096, response
    body caps, summary char cap), and `GalleryCloudErrorRedactor` (scrubs
    bearer / `sk-*` / `key=` secrets via the shared `SensitiveLogRedactor`).
  - **`gallery/cloud/GalleryEmbeddingClient.kt`** — OpenAI-compatible
    `/v1/embeddings` client (port of `CloudModelClient.createEmbedding` /
    `GalleryEmbeddingService.embedTextRemote`) over the existing OkHttp +
    kotlinx stack. Validates config up front, clips input to 512, bounds the
    response body and vector dimension, returns `GalleryEmbeddingResult` or a
    redacted `GalleryCloudException` (HTTP/IO/MALFORMED/OVER_BOUNDS/EMPTY).
  - **`gallery/cloud/GalleryMonthlySummaryClient.kt`** — optional AI month
    summary that **reuses the existing OpenAI-compatible `ChatCompletionsClient`**
    (via the `ChatClient` contract) with the exact Harmony `summarizeGalleryMonth`
    prompt, parsing `{title,summary,suggestion}` into `GalleryMonthSummaryResult`
    (parse-failure falls back to raw text, as the source does). Bounded response,
    redacted errors, EMPTY when no payload (never fabricated).
  - **`gallery/cloud/GalleryCloudAdapter.kt`** — config-driven gate + executor with
    honest NOT_CONFIGURED / UNSUPPORTED / READY gates and executors that
    short-circuit to a redacted ERROR before any I/O when not configured. No
    cloud call when not configured; no credentials inferred; `huawei_local`
    embedding stays UNSUPPORTED.
  - **Explicit opt-in setting** `gallery_ai_summary_enabled`
    (`galleryAiSummaryEnabled`, persisted via `SettingsCodec`, rendered in the
    Settings 数据采集 → 月度 AI 总结 group). Reuses the Planner endpoint/model/key
    (no new credential fields). Remote embedding reuses the existing gallery
    embedding settings.
  - **Wiring into Gallery insights (behind settings):** `GalleryInsightsViewModel`
    now takes an injectable suspend adapter provider; the `Loaded` cloud states
    reflect the real gate. The 月度洞察 panel adds explicit `生成/刷新总结` and
    `生成向量` buttons that invoke the cloud adapter **only from a user tap**,
    mapping success → READY and failure → redacted ERROR. The deterministic
    local insight path ([`GalleryMonthlyInsights`]) always remains and never
    uploads.
  - **Device-free tests** (`app/src/test/java/com/clawmate/app/gallery/cloud/` +
    `ui/insights/GalleryInsightsCloudViewModelTest.kt` +
    `settings/SettingsCodecTest.kt`): config gating (opt-in off / missing fields /
    remote-only / huawei_local UNSUPPORTED), no-call behaviour (adapters never hit
    the network when not configured), embedding input/response/dimension bounds,
    vector JSON parsing + secret redaction, summary prompt/bounds/JSON-parsing +
    error redaction, ViewModel gate states + redacted ERROR path, and the new
    settings key round-trip.
  - Build: `:app:assembleDebug` + `:app:testDebugUnitTest` `--max-workers=1` →
    **BUILD SUCCESSFUL**; test counts recorded in `DSH_PHASE_RESULT.md`.

- **Phase 15 (this phase):** Portable Gallery monthly insights (pure port).
  - **Pure monthly bucketing / tag-count / LLM-input port**
    (`gallery/insights/GalleryMonthlyInsights.kt`) of the HarmonyOS
    `entry/src/main/ets/utils/gallery/GalleryMonthlyInsights.ets` (row D2,
    previously **Portable-pending**). It maps directly over the real room-backed
    `GalleryPhotoItem`s already stored (Phases 3/9/11) — it derives calendar-month
    buckets from `date` (falling back to `createdAt`, then the honest 时间未知
    bucket), per-month tag counts (sorted desc, Harmony-stable ties), a
    deterministic local summary, up to 6 representative items, and a
    `buildLlmInput` text block bounded to 80 samples — all from **stored Room
    data only**. It never scans media, fabricates an insight, or makes a cloud
    call: building LLM input only string-formats the items passed in.
  - **Observable derivation** (`GalleryRepository.observeMonthlyInsights`) wired
    through `DefaultGalleryRepository` as a pure `map` over `observeItems()`, so
    the insights update automatically when stored items change.
  - **Real empty / loading / error insights state in the existing Gallery UI**
    (`GalleryScreen` + new `ui/insights/MonthlyInsightsPanel.kt` +
    `GalleryInsightsViewModel`): a 月度洞察 card renders honest Loading while the
    derivation is pending, an honest Empty state when there are no stored items,
    month chips + per-month total, deterministic category-count bars/chips, the
    local summary, up to 6 representative items (tap to open), and a truthful
    NOT_CONFIGURED boundary for the cloud AI monthly summary / vector embedding
    (`insights/GalleryMonthlyInsightCloudState.kt`) — no fabricated summary.
  - **Deterministic unit tests**
    (`app/src/test/java/com/clawmate/app/gallery/insights/` +
    `app/src/test/java/com/clawmate/app/gallery/ui/insights/` + a repository
    derivation test): bucketing (date / createdAt fallback / unknown),
    newest-month-first ordering, within-month newest-first, tie-order stability,
    tag cleaning/dedup + category fallback, 6-representative cap, 80-line
    LLM-input cap + determinism + "无" empty block, ViewModel Loading→Loaded/
    Empty/Error state machine, and repository-item-driven derivation. 490 unit
    tests, 0 failures, 0 errors.

- **Phase 14:** Portable in-app Log viewer (bounded ring buffer).
  - **Real bounded in-app log ring buffer** (`log/`) ported from the HarmonyOS
    `entry/src/main/ets/pages/LogView.ets` and `utils/CloudLogOutput.ets`
    (bounded, append-only + eviction). On Android we implement one honest,
    in-memory, capacity-bounded buffer
    (`log/data/RingBufferInAppLogRepository`, default 512 records, oldest
    evicted FIFO) instead of pretending to read Harmony `native.getLogs()`; no
    logcat / C++ ring buffer is claimed.
  - **Real app events wired** into the logger where code paths actually run:
    app launch (`ClawMateApplication`), settings saved + HDC health-check result
    (`SettingsViewModel`), chat send/completed/error (`ChatViewModel`), and
    gallery OCR run/empty/model-download/failed (`GalleryViewModel`). Nothing is
    fabricated; events that never happen (e.g. an unconfigured cloud call) simply
    do not appear.
  - **Compose Log screen** (`log/ui/LogScreen.kt` + `LogViewModel`) reachable from
    an existing Settings entry (new 运行日志 section, `SettingsSectionId.RuntimeLogs`):
    source tabs (应用/云端 Agent/图库分析/任务自动化), severity filter, free-text
    query, Refresh, auto-refresh ticker, auto-scroll, Copy visible text,
    clear-source / clear-all, and an **honest empty state** that never fabricates
    records.
  - **Sensitive-value redaction** (`log/domain/SensitiveLogRedactor.kt`): log
    messages are cleaned before insertion (authorization/Bearer/Basic, `api_key`,
    `sk-*`, GitHub tokens, AWS credentials, generic `password=/secret=/token=`),
    so a raw secret can never be stored or copied from the viewer.
  - **Deterministic unit tests** (`app/src/test/java/com/clawmate/app/log/` +
    extended `SettingsSectionTest`): redactor, ring-buffer retention/filter/clear/
    redaction-on-insert, and pure `filterLogs`. Device-free.
  - Harmony `native.getLogs()/clearLogs()` and the Harmony AppStorage cloud/gallery
    log strings have **no direct Android equivalent**; the Android viewer instead
    surfaces the real wired sources above. See §5 for the honest boundary note.

- **Phase 12 (this phase):** Coverage audit + Profile / Avatar local UI & state.
  - **Coverage audit** compared the complete HarmonyOS `ets` feature inventory under
    `/root/mobiinfra-oh/entry/src/main/ets` against the current Android target
    (Phases 1–11) and produced the concrete Phase-12 coverage matrix in §10.
    Focus areas beyond Phases 1–11: Profile / Avatar, AutoAgent, float/edge/log,
    gallery embedding & monthly insights, and WeChat auto-summary. Every gap is
    marked **Done** (ported), **Boundary** (honest typed disabled/configured
    state), **Portable-pending** (pure logic that could be ported later), or
    **Blocked** (Harmony-only or platform blocker) — see §10.
  - **Implemented: Profile / Avatar local UI & state** (`profile/avatar/`) —
    the task's preferred highest-value real gap:
    - Typed avatar domain (`domain/AvatarDomain.kt`) ported from
      `datamanager/avatar/AvatarTypes.ets`: `AvatarProfileSnapshot`,
      `AvatarDomainSnapshot`, `AvatarConsentSnapshot`, `AvatarGenerationMode`,
      `AvatarState`/`AvatarStatusToken`, `AvatarVersionViewModel`, the four
      persona-domain defaults, `localProfileCompleteness` (0..100) and
      `avatarErrorMessage` normalisation. Pure and deterministic.
    - Locally editable, device-persisted profile (`data/DataStoreProfileAvatarRepository`
      + `domain/LocalAvatarProfile`): display name / short summary / gender / age /
      per-domain (吃/穿/住/行) summaries & claims, stored in app-private DataStore
      via kotlinx-serialization with tolerant decode → honest empty fallback. No
      cloud, no secrets, no generated image.
    - Truthful avatar-generation boundary (`domain/AvatarGenerationGate`): derives
      `NOT_CONFIGURED` vs `CONFIGURED_BUT_NO_PROVIDER` from the configured avatar
      service (base URL + API key) and explains it; it never invokes Seedream and
      **never fabricates a generated avatar**. The real image generation
      (Harmony `SeedreamAvatarProvider`) remains the documented remaining gap.
    - Composable `ProfileAvatarScreen` + `ProfileAvatarHost` + `ProfileAvatarViewModel`
      (read profile + draft editing + honest save validation + snackbar), wired so
      the Home avatar card now opens the 数字分身 screen instead of being a dead
      disabled button (HomeScreen `onOpenAvatar`).
  - **Secret / binary scan** found no secrets committed and no new large
    Harmony-only binaries (no MNN/HiAI `.so` copied; the local-model stub from
    Phase 10 is unchanged).
  - Deterministic unit tests (see §6): avatar domain normalisation/state gate/
    completeness (no fake avatar), repository save round-trips, and avatar
    provider-gate + settings wiring. Device-free.

- **Phase 11 (this phase):** Real on-device Chinese OCR adapter.
  - Added `com.google.mlkit:text-recognition-chinese:16.0.1` (bundled ML Kit
    model) to the dependency set; it resolves on Google Maven and packages the
    Chinese + Latin OCR models + `libmlkit_google_ocr_pipeline.so` into the APK.
  - New `gallery/ocr/AndroidOcrEngine` replaces the OCR NOT_CONFIGURED boundary:
    opens the picked content URI via ContentResolver, decodes a bounded-size
    Bitmap, runs ML Kit Chinese text recognition on a background dispatcher,
    maps success/empty/cancel/model-download/error/unavailable to the typed
    `OcrResult`/`OcrErrorKind` states, and closes recognizer/bitmap/stream.
  - Pure, device-free mapping (`gallery/ocr/OcrMapping`) + bounded-decode planning
    (`gallery/ocr/OcrBitmapDecode`) so behaviour is deterministic and testable
    without a device; no fake text is ever produced.
  - Gallery detail panel gains an explicit **Analyze OCR** action with
    loading/success/empty/error/model-unavailable states; only real recognized
    text (+ the `analyzed` status) is persisted to Room (`updateOcr`) on success.
  - Cloud vision stays NOT_CONFIGURED and reverse geocoding is unchanged.
  - No broad storage permission, no network endpoint/API key, no background scan
    of all media — only explicit user-triggered analysis of a selected item.
  - Device tests remain unavailable; documented that ML Kit runtime / model
    download need a device. 27 new deterministic unit tests (407 total green).

- **Phase 10:** Local model capability boundary + native stub.
  - **Typed local-model domain** (`localmodel/LocalModelTypes.kt`): the Android
    boundary ports the Harmony local-model decision logic (`Index.ets`
    `modelLoadableStatus`/`isModelWeightFile`/`hasModelWeightFile` +
    `entry/src/main/cpp/*` N-API surface) into typed states
    **NOT_CONFIGURED / UNSUPPORTED / LOADING / READY / ERROR** with typed error
    kinds (INVALID_PATH / MISSING_CONFIG / MISSING_MODEL_FILE / NOT_READY /
    NATIVE_LIBRARY_MISSING / LOAD_FAILED). Pure normalization never reports
    READY from disk state alone.
  - **App-private path validation** (`localmodel/LocalModelPathValidator.kt`):
    model dirs must live under `filesDir/model` or
    `filesDir/modelscope/{owner}__{repo}`; `..`/`.`/escape/non-whitelisted
    base/filesDir-root are rejected before any load; no arbitrary file/dir
    deletion is ever offered. Keep model files app-private, never committed.
  - **Inventory scanner** (`localmodel/LocalModelInventory.kt`): discovers
    loadable model dirs (config.json + a weight file `.mnn`/`.om`/
    `.safetensors`/`.weight`) via an injectable `ModelFolderProbe` seam so
    scanning is deterministic and device-free. No model output is fabricated.
  - **Honest capability boundary** (`localmodel/LocalModelCapability.kt`):
    reconciles inventory + path validation + the native adapter; the explicit
    `loadAndTest(modelId)` reaches **READY only when the native load returns a
    genuine Success**, otherwise UNSUPPORTED / typed ERROR. Missing MNN/LLM on
    Android always yields UNSUPPORTED — never a fake loaded model.
  - **JNI/CMake native adapter** (`app/src/main/cpp/`, build via AGP
    `externalNativeBuild cmake`): `local_model_jni.cpp` + `CMakeLists.txt`
    compile `libclawmate_local_model.so` with the installed Android NDK
    (25.2.9519653, CMake 3.22.1) for arm64-v8a / armeabi-v7a / x86_64. Because
    MNN-for-Android and the OHOS N-API/HiAI runtime are absent it is a
    **truthful stub** returning typed `UNSUPPORTED`/`NATIVE_LIBRARY_MISSING`
    results; the docs note exactly what a real adapter needs (MNN prebuilt +
    an `Llm`-equivalent loader). The `.so` is verified packaged into
    `app-debug.apk`.
  - **Settings panel** (`localmodel/LocalModelViewModel.kt` +
    `LocalModelPanel.kt`, wired into the 模型与智能体 section): shows the typed
    status, the discovered model inventory, and an explicit **"加载/测试 …"**
    action per loadable model; READY is reported only after a real native load
    success. Cloud chat endpoint usage is unchanged. App launch performs no
    network and no model load.
  - **Deterministic unit tests** (38 new, see §6): state normalization,
    path validation, inventory mapping, missing-library/missing-file
    no-fake-output guarantees, and native result-token parsing. Device/native
    smoke tests documented as unavailable (no emulator, no on-device MNN).
  - **Environment:** Android NDK 25.2.9519653 + system CMake 3.22.1 installed
    via the SDK's `sdkmanager` (single compatible NDK; no model binaries, no
    parallel installs). All Phase 1–9 code and cloud chat paths preserved.

- **Phase 9 (this phase):** Gallery detail flow + local analysis boundaries.
  - **Real gallery item detail flow** from the existing gallery list
    (`gallery/ui/GalleryDetailScreen.kt`, opened via `PhotoRow` click). Shows
    only real data: content URI, archive date, EXIF capture time/device/
    dimensions, analysis state, tags/summary and collection membership — no
    fabricated values. Deterministic label mapping in
    `gallery/ui/GalleryDetailLabels.kt`.
  - **Explicit safe edit operations** (`GalleryRepository` + `DefaultGalleryRepository`
    + `GalleryDao`): `updateTitle` / `updateSummary` / `updateTags` / `updateLocation`,
    and **delete-by-item** (`deleteItem(id)` removing only that single row) with
    a UI confirmation dialog. Edits/deletes are persisted to Room; collection
    groups recompute after a delete (and stay recomputable via `refreshGroups`).
    No broad files, no URI/Photo-Picker grants are ever removed.
  - **Android reverse geocoding** (`gallery/geocode/`):
    `AndroidGeocoderClient` wraps the platform `android.location.Geocoder`
    (no network key, no endpoint config). Typed outcomes/errors
    (`ReverseGeocodeOutcome` / `ReverseGeocodeErrorKind` UNAVAILABLE / PERMISSION /
    LOOKUP_FAILED / IO / NOT_CONFIGURED) with an honest single-line formatter
    (`AddressFormatter`) and pure, device-free mapping (`AndroidGeocoderMapping`).
    Wired as an **explicit injected capability** via `GalleryComponent.reverseGeocoder`
    (`AndroidReverseGeocoder`). The detail panel exposes a "解析/重试" action and
    **never claims a location when a lookup fails** — resolved places are
    persisted to a new `address`/`locationProvider` item column.
  - **OCR engine boundary (Phase 9 historical; superseded by Phase 11)**: ML Kit
    `text-recognition-chinese` was originally held behind the explicit `OcrEngine`
    NOT_CONFIGURED boundary with a typed `Unavailable` message because it could not
    be verified on a device. **Phase 11** wires the real on-device adapter
    (`gallery/ocr/AndroidOcrEngine`) with ML Kit Chinese text recognition and the
    typed success/empty/cancel/model-download/error/unavailable mapping; the
    pre-Phase-11 boundary text is kept here for history only. Cloud vision remains
    NOT_CONFIGURED. Documented in `CapabilityEngines.kt`.
  - **Capability UI reflects actual states**: `CapabilitySummary` now exposes
    `hasUnconfigured`/`hasUnsupported`, and `GalleryScreen` renders Ready /
    NotConfigured / Unsupported per-capability text; unsupported/geocoder-unavailable
    states surface a retry/unsupported message in the detail panel.
  - **Storage/schema**: `GalleryItemEntity` gains `address`, `locationProvider`,
    `analysisState` columns; `GalleryDatabase` bumped **v1 → v2** with an
    explicit, never-destructive `ALTER TABLE ADD COLUMN` migration; Room v2 schema
    exported and committed.
  - **Preservation**: Photo Picker URI-grant import flow and the existing
    search/grouping services are unchanged; no broad storage permissions added.
  - Deterministic unit tests (39 new, see §6) for detail mapping, edit/delete
    persistence, geocoder result/error mapping, capability states and group
    recomputation. No live geocoder/device tests are included.

- **Phase 8 (this phase):** Safe PC HDC bridge adapter.
  - Typed bridge models + tolerant parsing (`task/hdc/HdcBridgeModels.kt`, port
    of `utils/HdcWorkflowBridge.ets` / `utils/WorkflowTypes.ets`, reference-only):
    `HdcBridgeStatus` / `HdcBridgeResponse` / `HdcBridgeHealth`
    (`hdc_connected`, `target`, `tunnel_ready`, `fport_ready`, `rport_ready`,
    ports, tunnel/PC/app URLs, loop flags) and the health envelopes — every
    member parses tolerantly (missing/wrong-typed/numeric-as-string degrade to
    safe defaults) and **`controlReady` requires `ok` + both `fport_ready` and
    `rport_ready`**, so a partial reply never claims a usable device.
  - Deterministic URL normalization (`task/hdc/HdcUrlNormalizer.kt`): trim,
    collapse scheme-relative `//`, scheme-less → default `http`, strip trailing
    slashes, drop an empty trailing port, reject anything but `http`/`https`;
    builds `…/api/health` and `…/api/workflow`. Pure total function, never
    throws.
  - Typed client boundary (`task/hdc/HdcBridgeClient.kt`): `health()` and
    `workflow(action, payload)` returning `HdcBridgeResult.{Response,Health,
    Error}` with typed `HdcBridgeErrorKind` (HTTP / MALFORMED_JSON / TIMEOUT /
    IO / CANCELLED / INVALID_URL / UNKNOWN).
  - OkHttp implementation (`task/hdc/OkHttpHdcBridgeClient.kt`): normalizes and
    rejects invalid URLs with no request; bounded connect/read/write/call
    timeouts; cooperative cancellation cancels the in-flight `Call`; maps HTTP
    non-2xx, malformed JSON, socket/call timeouts, connection-refused/DNS, and
    cancellation to typed errors. **No secret logging** — only the action name,
    the response `summary()`, and the health URL host. Encodes the
    `{action,payload}` envelope with kotlinx-serialization (strings, numbers,
    booleans, null, nested maps/lists).
  - Safe device-control boundary (`task/hdc/WorkflowDeviceAdapter.kt`): only
    `health` / `startApp` / `screenshot` / `guiAction` / `executeDeciderAction` —
    **never** `/api/run_cmd`, shell, `adb`, `hdc`, AccessibilityService, or
    spawned processes. The default `DisabledWorkflowDeviceAdapter` (used unless
    an explicit adapter is injected) reports every action `Unsupported` and is
    never configured.
  - Health-gated bridge adapter (`task/hdc/BridgeBackedWorkflowDeviceAdapter.kt`):
    maps the safe protocol calls through the client; `isConfigured` becomes true
    **only after a live `ok` + control-ready health reply**; a bridge `error`
    envelope is an honest `BridgeError`, transport failures are typed
    `TransportError`.
  - Runner integration (`task/runner/WorkflowRunner.kt`): `gui_action`
    device-control steps route through the adapter only when `isConfigured`; a
    configured adapter returns honest SUCCESS / FAILED / BLOCKED results/errors,
    otherwise the default keeps them `BLOCKED` and never fabricates device
    control. `gui_task` (screenshot+VLM decider loop) stays honestly
    `UNSUPPORTED` — the safe adapter exposes only discrete protocol calls.
  - Explicit DI gate (`task/TaskComponent.kt`): starts with the disabled
    adapter; a caller may `setDeviceAdapter(...)` with a health-verified adapter
    (rebuilding the runner) or null to revert. No network call happens on app
    launch.
  - Settings UI (`settings/ui/`): the HDC section now has an explicit
    **"测试 HDC 健康"** button that performs a live `GET /api/health` only when
    the user taps it (`SettingsViewModel.testHdcHealth()` with an injectable
    `hdcClientFactory` for tests); app launch performs **no network**. A connected
    (`hdcTestConnected`) state is reported only after a real successful response;
    loading/error/disabled states are driven by explicit test state. On success
    a health-verified adapter is registered with `TaskComponent`.
  - Deterministic unit tests (49 new, see §6): HdcBridgeParsing, HdcUrlNormalizer,
    OkHttpHdcBridgeClient (MockWebServer), WorkflowDeviceAdapter,
    WorkflowRunnerAdapterIntegration, and SettingsHdcHealth (app-launch-no-network,
    explicit-click-only, connect-only-after-real-ok).
- **Phase 7 (this phase):** Voice interaction vertical slice.
  - Typed voice domain models + deterministic normalization
    (`chat/voice/VoiceInteractionTypes.kt`, port of
    `pages/chat/VoiceInteractionTypes.ets`): `VoiceInteractionStatus`,
    `ChatVoiceInputKind`, `ChatVoiceMetadataStatus`, `TtsVoiceRuntimeStatus`,
    `VoiceSessionSnapshot`, `ChatVoiceMetadata`,
    `appendVoiceTranscriptToInput`, normalize/present helpers, the factory
    `createAsr/Tts/VoiceError` metadata builders, `voiceStatusLabel`,
    `voiceReplayAvailable` / `voiceReplayGlobalBlockNeeded`, and a pure
    `VoiceSessionController` state machine.
  - ASR transcript normalization (`chat/voice/VoiceTranscriptLogic.kt`, port of
    `HarmonySpeechRecognizerAsrProvider.ets`): cumulative/committed merge,
    punctuation compaction, duplicate-final suppression.
  - Android ASR adapter (`chat/voice/asr/AndroidSpeechRecognizerAsrAdapter.kt`)
    around `android.speech.SpeechRecognizer` with explicit
    **permission / unavailable / error / cancel** states, **no hidden mic access**:
    it never auto-requests RECORD_AUDIO — it returns `PermissionDenied` until the
    UI has surfaced and the user granted the runtime permission; partial/final
    transcripts only ever carry bytes from the platform recognizer.
  - Android TTS adapter (`chat/voice/tts/AndroidTextToSpeechTtsAdapter.kt`)
    around `android.speech.TextToSpeech`: lifecycle-safe init (waits for
    `onInit`/SUCCESS), speak / pause / stop / replay, typed unavailable/error
    states; no permission required.
  - Pure PCM16→WAV builder (`PcmWav.kt`) and MiMO TTS voice presets
    (`VoiceTtsPresets.kt`), voice-input start/stop generation guards
    (`VoiceOrchestratorLogic.kt`).
  - Cloud streaming/TTS MiMO voice is kept behind an explicit
    **NOT_CONFIGURED** interface (`CloudMimoVoiceClient`): `synthesizeSpeech`
    always returns `NotConfigured`, `streamingConfigured()` is `false` — no fake
    audio is ever produced or claimed.
  - Orchestration (`chat/voice/ChatVoiceController.kt`) wires ASR + Android TTS
    into the chat session: toggling mic starts a real on-device ASR turn; a real
    **final** transcript is appended to the editable chat input via
    `appendVoiceTranscriptToInput`; speak/pause/stop/replay drive TTS with
    lifecycle-safe init and typed states.
  - Chat UI integration (`ChatViewModel` + `ChatScreen`): mic button (requests
    RECORD_AUDIO only on tap), hearing/transcribing banner with live partial
    transcript, and speak/stop/replay controls on assistant bubbles. Existing
    streaming/agent/chat behavior preserved; missing-cloud-config still shown;
    no fake transcript or audio.
  - Deterministic unit tests (`chat/voice/*Test`, 55 new) covering
    status/metadata normalization, transcript append, replay guards, ASR merge/
    duplicate suppression, orchestration start/stop guards, TTS presets,
    unavailable/permission/error mappings, PCM→WAV, and MiMO NOT_CONFIGURED
    truthfulness (see §6). Instrumented voice tests are documented as
    unavailable without a device (no emulator).
- **Phase 6 (this phase):** Home / Onboarding vertical slice.
  - First-launch onboarding gate (`home/domain/OnboardingTypes.kt` ports the
    pure contract from `pages/onboarding/OnboardingTypes.ets` — 4 slides,
    versioned completion key `firstLaunchOnboardingVersion`
    (`2026-07-core-preview-usage-guide`), `shouldShow…`, button text, skip
    target). Real Compose `FirstLaunchOnboardingScreen`
    (`home/ui/onboarding/`) renders the three capability preview slides
    (ClawMate / 数字分身 / 自动执行) + a usage-guide page with 跳过 / 下一页 /
    进入首页. Completion version persists via `DataStoreOnboardingRepository`
    (app-private `clawmate_home` Preferences); the shell (`ClawMateRoot`) gates
    the 5-tab root behind it, and onboarding works on a configuration-free fresh
    install with no network.
  - Android home domain (`home/domain/`): `HomeDomain.kt` (four honest default
    pillars 吃/穿/住/行 + `homeProfileCompleteness`), `HomeRecommendation.kt`
    (view model + persisted state + per-domain colors),
    `HomeProfile.kt` (aggregate with loading/ready/error states),
    `RecommendationListLogic.kt` (pure pagination/filter/completion reductions),
    and `AvatarStatus.kt` (statuses + an honest `AvatarSection.notConfigured`).
  - App-private persistence (`home/data/`): `OnboardingRepository` +
    `HomeRecRepository` interfaces, DataStore-backed implementations
    (`DataStoreOnboardingRepository`, `DataStoreHomeRecRepository`), and
    deterministic fakes for tests. Recommendation completion flags are
    persisted per-id; only flags are stored, never content/endpoints/secrets.
  - Real Compose Home (`home/ui/HomeScreen.kt` + `HomeViewModel`): the
    avatar section (honestly NOT_CONFIGURED/DISABLED, never fabricated), the
    completeness bar, the four persona pillars, and the recommendation list
    with completion toggles, 未完成/全部 filter, 4-per-page pagination and
    expand. Profile loading/empty/error states are surfaced.
    Recommendation content is loaded through a `HomeProfileSource` seam whose
    current implementation returns the honest empty profile until the
    data-manager pipeline lands.
  - The obsolete Phase-1 `ui/home/HomeScreen.kt` placeholder was removed and the
    首页/聊天/汇总/任务/设置 routes are preserved (only the Home tab content and
    the pre-tab onboarding gate changed in `ClawMateRoot.kt`).
  - Deterministic unit tests (`home/`): onboarding version/section navigation,
    skip/finish, persistence round-trips, recommendation completion +
    pagination, empty/disabled and not-configured guarantees, and stable model
    mapping (see §6).
  - Avatar generation/import and recommendation *content* remain out of scope
    for this phase and are reported as NOT_CONFIGURED / absent on Android — never
    faked.
- **Phase 5 (this phase):** Settings / Configuration vertical slice.
  - Android settings domain (`settings/domain/`): typed `AppSettings` snapshot,
    `SettingsSectionId` + `SETTINGS_SECTIONS` (the five sections 个人与隐私 /
    数据采集 / 模型与智能体 / 任务与自动化 / 存储与关于), full `SettingsKeys`
    inventory, deterministic normalizers (URLs, models, bounded ints/doubles,
    booleans, embedding mode), and a `SensitiveValueUtils` port of the source's
    masking/restore rules.
  - DataStore-backed repository (`settings/data/DataStoreSettingsRepository.kt`)
    with `SettingsCodec` round-trips and `FakeSettingsRepository` for tests:
    values are typed and normalized; **a value still carrying the mask marker is
    never persisted as a literal secret** and connectivity flags are never
    stored (always derived at runtime).
  - Integration seam `EndpointBridge` + `EndpointConfig.applyCloudEndpoint`:
    editing the cloud planner base URL/model/API key in Settings updates the
    runtime config that `ChatServerConfig` / the chat cloud client reads, and a
    masked value is never pushed to runtime config.
  - Real Compose `SettingsScreen` + section detail composables (grouped cards,
    text fields, switches, sensitive reveal/edit, save/reset bar, loading /
    error / saved states, app version + storage summary). Missing/unconfigured
    endpoints (Planner, Decider, HDC, gallery vision, PC backend) are shown as
    NOT_CONFIGURED / DISABLED — never claimed connected. Destructive cleanups are
    surfaced as explicit disabled buttons and never delete directories.
  - Deterministic unit tests (see §6): normalization, masking/restore,
    persistence round-trips, section selection, and safe disabled/unconfigured
    states.
  - Phase-1 `ui/settings/SettingsScreen.kt` placeholder retained as a non-routed
    reference (same pattern as the Summary/Task placeholders).
- **Phase 4 (this phase):** Task / Workflow vertical slice.
  - Android workflow domain models (`task/model/WorkflowModels.kt`,
    `WorkflowSceneConfig.kt`) ported from `utils/WorkflowTypes.ets` /
    `WorkflowSceneConfig.ets`; the 7 collection scenes are mirrored verbatim.
  - Tolerant, deterministic JSON parsing (`task/json/WorkflowJsonParser.kt`,
    `WorkflowJsonRepair.kt`): metadata/defaults/context/steps including nested
    `loop` / `if` / then-else structures; **unknown step types preserved in
    `raw`** (never dropped) and reported as unsupported; malformed/broken JSON
    is repaired or degrades to a typed `Err` so one corrupt card never crashes
    the Task screen.
  - Bundled workflow configs copied into `res/raw/` (no secrets — two WeChat
    configs' sample `contact_name` replaced with a neutral placeholder), and a
    file-backed repository (`task/data/DefaultWorkflowRepository.kt`) that
    installs them into app-private `filesDir/workflows/…` and persists user
    edits (editable JSON), enabled toggles and run status in a versioned
    `task-index.json` (deterministic, tolerant codec).
  - Bounded, honest runner (`task/runner/WorkflowRunner.kt`): only deterministic
    non-GUI steps execute (`sleep`, `loop`, `if`, and a test `echo` tool); every
    `gui_task` / `gui_action` GUI/HDC/open-app step is reported as
    `UNSUPPORTED/BLOCKED` and every VLM `tool` as `NOT_CONFIGURED` — **a run is
    never fabricated as successful when blocked**. Bounds: max total steps,
    per-loop cap, wall-clock timeout; cooperative cancellation → `CANCELLED`.
  - Real Compose `TaskScreen` (scene/filter bar, workflow cards with enabled
    toggle + last status, expandable step summary + honest run outcome,
    loading/empty/error/reload states) wired into the 任务 tab via a manual
    `TaskComponent` (Phase-4 replaces the Phase-1 placeholder).
  - Deterministic unit tests (see §6) for JSON parsing/repair, nested step
    handling, repository persistence mapping, unsupported GUI/HDC + NOT_CONFIGURED
    states, and runner cancellation/bounds.
- **Phase 3 (this phase):** Agent / ReAct vertical slice.
  - Typed tool-call & tool-result models in the OpenAI layer (`OpenAiModels.kt`,
    `ChatClientTypes.kt`, `SseParser.kt`): `tools`/`tool_choice` on the request,
    nullable `content` + `tool_calls`/`tool_call_id`/`name` on messages,
    `ToolCallWire`, and **streaming tool-call deltas**
    (`StreamToolCallDelta` + `StreamToolCallAccumulator` port of
    `CloudModelClient.applyStreamToolCallDeltas`). Ordinary content streaming is
    preserved (no regression).
  - Safe Android tool registry (`chat/agent/ToolRegistry.kt`): explicit safe
    adapters (`get_current_datetime`, `get_device_info`) and
    **controlled-disabled** adapters for Harmony GUI/HDC/overlay actions
    (`gui_screen_tap`, `gui_type_text`, `gui_swipe`, `capture_screen`,
    `open_app`, `float_overlay_status`) that return a typed
    `ToolResultKind.DISABLED` explaining the required PC bridge / permission —
    no arbitrary shell commands, no fake automation.
  - Pure action parser (`chat/agent/AgentActionParser.kt`) port of
    `AgentActionParser.ets` (JSON extraction/unwrapping, terminal detection).
  - Cancellable ReAct loop (`chat/agent/AgentLoop.kt`) as a cold Flow state
    machine over the existing `ChatClient`: max-step guard, overall timeout,
    typed `AgentException`, no fake model output; terminal tool results stop the
    loop.
  - Repository agent path `DefaultChatRepository.sendAgentMessage`
    (+ `ChatSendEvent.ToolExecuted`) and minimal ViewModel/UI integration to
    surface tool execution (`activeToolTrace`) and controlled-disabled/error
    notes; Phase 2 chat screen not rewritten.
  - Deterministic unit tests for tool-call mapping, action parsing, max-step/
    cancellation, disabled adapters and normal content regression (see §6).
- **Phase 2 (this phase):** Chat / cloud-model vertical slice.
  - Kotlin chat message/session models (`ChatModels.kt`) mirroring
    `ChatSessionTypes.ets` / `ChatPageTypes.ets` (roles, kinds, title/preview).
  - OpenAI-compatible `/v1/chat/completions` client (`ChatCompletionsClient.kt`)
    on OkHttp + kotlinx.serialization: non-streaming, SSE streaming (`Flow`),
    cancellation, connect/read timeouts, HTTP + malformed-event error mapping.
  - Deterministic SSE parser (`SseParser.kt`) — incremental, chunk-boundary
    tolerant, `[DONE]+delta+finish_reason` + malformed-event typing.
  - Room persistence (`ChatDatabase.kt`, `ChatDao`) for sessions/messages.
  - `DefaultChatRepository` — persisted send/stream flow; no fake replies;
    missing-config surfaces as `ChatException(CONFIG)`.
  - Real `ChatViewModel` + Compose `ChatScreen` with history, incremental
    streaming, empty, loading, error, and missing-config states; wired into the
    聊天 tab.
  - Deterministic unit tests for SSE parsing, response mapping, errors and
    repository behavior (see §6).
  - Phase-1 build fix (Compose compiler config for Kotlin 1.9.24) and
    `EndpointConfig` `cloudPlannerModel` key.
- **Phase 1:** source inventory; feature mapping; Android strategy per feature;
  compiling-for-the-toolchain Android skeleton (applicationId
  `com.clawmate.app`, 5-tab Compose navigation shell, Material3 theme, resources,
  unit + instrumented test source sets, network/backup security config);
  `.gitignore`; endpoint config with no secrets in source control.

---

## 9. Phase 1 delivery notes

- **Blocker:** no JDK / Android SDK / Gradle on the authoring machine, and the
  sandbox could not write into `/root/ClawMate-Android`. All phase-1 artifacts
  were authored as a copy-ready tree. Install them and commit with a short note.
  Verify build per section 6 before starting phase 2.

---

## 10. Phase 12 coverage audit (features beyond Phases 1–11)

This phase ran a full source-to-target comparison of the HarmonyOS `ets`
inventory (under `/root/mobiinfra-oh/entry/src/main/ets`) against the current
Android target, calling out user-facing gaps in the named focus areas. Each row
gives the source of truth, the Android strategy, its status, and the exact
remaining gap.

Legend — **Status**:
- **Done** — a real Android implementation is ported/wired.
- **Boundary** — honest typed disabled/configured state exposed; underlying real
  capability kept deliberately unimplemented (no fake capability).
- **Portable-pending** — pure logic that maps directly onto existing Android data
  and could be ported in a later phase.
- **Blocked** — Harmony-only platform dependency or Android platform blocker with
  no safe equivalent; must stay an honest controlled state.

| # | Focus / feature | Harmony source of truth | Android strategy | Status | What exists on Android (Phase 12) + remaining gap |
| --- | --- | --- | --- | --- | --- |
| A1 | **Profile snapshot & local profile** | `datamanager/avatar/AvatarTypes.ets`, `AvatarPromptBuilder.ets`, `pages/home/HomePage.ets` | Direct port + DataStore | **Done** | `profile/avatar/` ports the typed AvatarTypes contracts and provides a locally-editable, device-persisted profile (display name / summary / gender / age / 吃穿住行 domains) with a pure completeness reducer. **Phase 16** feeds the persisted profile into the Home pipeline via `ProfileBackedHomeProfileSource` + `HomeProfileBuilder` (a faithful `DataManagerFacade.homeProfileDomains/.../`domainClaimStatements` port that maps the four curated domain snapshots onto the Home pillars / completeness). **Phase 28** ships a real, explicit-user-action **Home recommendation generator from real Gallery data** (`home/recommendation/`): the Home "生成推荐" button plans recommendations through the shared OpenAI-compatible Planner chat backend via `GalleryBackedHomeRecommendationRepository` + `HomeRecommendationPlanner` (config gate + no-data short-circuit with ZERO calls, bounded real gallery text only, strict output validation + `source_event_ids` membership/dedup, redacted errors, no fabricated recommendations/images). No new credentials; endpoints/keys come from existing `EndpointConfig`. **Phase 29** adds the **first bounded daily-log input + local event-index foundation** for that same explicit flow: `datamanager/DailyLogParser` (metadata header + numbered `1. …` entries, strict date/file/count/char/metadata bounds, countable honest errors, never thrown), `DailyLogPlannerEventMapper` + a read-only app-private `DailyLogEventSource` (only reads existing `filesDir/workflows/daily-log/YYYY-MM-DD/*.md`, no external storage/URI/image/network), stable source-ref ids (`date/file.md#N`), and `DailyLogWorkspace` as the documented external-import write API (files are never auto-created). The button now reads stored gallery **and** daily-log events; empty-both short-circuits to NoData with zero calls; `source_event_ids` validation spans both id namespaces; gallery-only compatibility is preserved. Remaining gap (Partial/Deferred, not claimed): the Harmony **cloud consolidation/dedup/profile-claims/index-partitions and periodic background scheduling** (`ConsolidationService`/`IndexService`) are **not ported** — Phase 29 delivers only parsing + planner input; avatar generation stays a documented boundary. |
| A2 | **Avatar generation (image)** | `SeedreamAvatarProvider.ets`, `AvatarService.ets`, `AvatarStorage.ets`, `AvatarForeground*.ets` | Wired provider (Phase 24) | **Done (configured provider + local result path)** | **Phase 24** closes the A2 cloud-generation gap: a real `profile/avatar/data/generation/` slice ports the verified Seedream HTTP contract (`SeedreamAvatarProvider.ets`) to OkHttp on the existing stack. `AvatarGenerationClient`/`SeedreamAvatarProvider` POST the exact wire body (`model/prompt/sequential_image_generation/response_format/[output_format unless seedream-4-5]/size/stream/watermark/[image]`) with `Authorization: Bearer`, honour connect/read timeouts + coroutine cancellation, parse only a real `data[0].b64_json`/`url` image (bounded base64 decode; validated URL download with content-type + size gates), and redact secrets. The honest gate is now `NOT_CONFIGURED`/`PARTIALLY_CONFIGURED`/`READY`; the screen runs a user-initiated `生成数字分身` with `READY → RUNNING → SUCCESS/ERROR` (never faked, no network call when unconfigured or below the 20% profile-completeness floor; explicit tap = recorded text-to-image consent with a bounded prompt). Real generated bytes persist through the existing app-private `AvatarAssetRepository.persistGeneratedImage` as a `seedream-*` version (local asset result path). **696 tests, 0 failures** (device-free: config gate, no-call, request-body bounds, URL normalization, response parsing, error redaction, cancellation, state mapping, persistence seam). Gap (non-portable, honest): Harmony `AvatarForeground*` subject-segmentation/transparent crop depends on Harmony `subjectSegmentation` (no Android equivalent) and remains a documented boundary — a generated main image is still real and displayed. |
| A3 | **Home avatar card** | `pages/home/HomePage.ets` | Real screen entry | **Done** | The Home avatar card now opens the 数字分身 screen (was a dead disabled button). **Phase 24**: a configured provider can now generate a real avatar image that is persisted and displayed via the avatar asset repository (Coil image path). |
| A4 | **Profile knock share** | `utils/ProfileKnockShareService.ets`, `module.json5` | Adapter | **Phase 31: Android NFC adapter (real NDEF); Harmony knockShare discovery/system-UI not fully equivalent** | `ProfileShareActivity` receives `.mobi-profile`/`application/vnd.mobiinfra.profile+json` intents. **Phase 17** adds the portable import/merge pipeline: pure `profile/share/ProfileShareImporter` (parse + validate malformed/oversized/unsupported-version/missing-field; deterministic local-first merge of the four persona-domain summaries into `LocalAvatarProfile`), a byte-capped content read, DataStore persist and an honest success/error result dialog. **Phase 27** adds the cloud "同频报告" affinity analysis: an explicit "生成同频报告" button on the import dialog drives the planner, faithfully porting Harmony `runProfileShareAffinityAnalysis` / `buildProfileAffinityMessages` onto the shared OpenAI-compatible chat backend (typed config gate, two bounded profile texts only, redacted errors, lifecycle cancellation, never automatic). **Phase 29 note:** the "同频报告" stays a separate explicit, bounded two-profile-text flow. **Phase 31** closes the "一碰分享" NFC gap with a **real Android NFC adapter** (`profile/share/nfc/`): honest `NfcAdapter` availability (NOT_SUPPORTED / DISABLED / READY), explicit user-triggered send (`NdefMessage` write) and receive (reader mode with `onTagDiscovered`) sessions, a pure JVM state machine (SENDING / RECEIVING / SUCCESS / CANCELLED / typed ERROR), a uniform bounded UTD/MIME/version identity contract aligned with Harmony, 512 KiB payload cap both ways, NDEF-MIME + UTF-8/JSON + schema/version + app/type validation **delegated to the existing `ProfileShareImporter`** (so the NFC boundary can never bypass the shared merge rules), and duplicate-message dedupe that persists across lifecycle rebuilds/restarts. A Settings entry (`一碰分享（NFC）`) is the only UI entry and only ever runs at an explicit tap. Honest remaining boundary (non-portable): Harmony's `harmonyShare` **knockShare discovery** (peer-targeted `sendCapability`/`dataReceive` via ShareKit, with its system "knock" UX and sandbox inbox) and its **system share/launch-Want UI** have no Android equivalent — Android substitutes foreground reader-mode sessions and explicit NDEF peer contact; the Harmony file-open/share system UI remains covered by the existing `ACTION_VIEW` intent path. |
| A4b | **Profile knock share — Android NFC entry UI** | (Phase 31, Android-only) | Real screen | **Done (Phase 31)** | `profile/share/nfc/NfcProfileShareActivity` hosts the NFC screen (foreground reader-mode enabled only while the Activity is RESUMED and an explicit SEND/RECEIVE session is armed, released in `onPause`; never on launch or in background). `profile/share/nfc/NfcProfileShareScreen` renders real device state and exposes **only explicit** start-send / start-receive / stop / retry / re-probe actions. Reached only from the explicit Settings `一碰分享（NFC）` card; never from Home, launch, recomposition, or the background. |
| B1 | **AutoAgent / GUI automation** | `autoagent/AutoAgentAbility.ets` (AccessibilityExtensionAbility), `utils/ChatReactAgent.ets` | Blocked | **Partial (Phase 22 safe semantic executor)** | Chat ReAct agent loop exists (Phase 3); arbitrary-app GUI automation maps to Android AccessibilityService (needs user opt-in) or the PC HDC bridge (Phase 8 adapter). **Phase 22** ships a real, tightly controlled on-device executor for a minimal **semantic** allowlist — `click_text`, `click_content_description`, `back`, `scroll_forward`, `scroll_backward`, `global_back`, `global_home` — reached only through the typed executor (`AccessibilityAutoAgentService.executeSafeAction` + `ControlledSafeAccessibilityDispatcher`), which validates opt-in + observed system grant + the live **foreground package allowlist before every action** and returns honest `NOT_CONFIGURED`/`BLOCKED_SERVICE`/`BLOCKED_PACKAGE`/`UNSUPPORTED`/`ERROR`/`SUCCESS` (no fake success). The coordinate/gesture actions (`tap`/`type_text`/`swipe`/`open_app`/`capture_screen`) remain **Blocked** (Phase 19 `AccessibilityGate` preserved); no gestures (`canPerformGestures=false`), no screenshots, no hidden input, no shell/adb/hdc, no cross-app data extraction. AutoAgentAbility's higher-level arbitrary-app GUI automation stays a doc'd platform blocker / PC-bridge gap. |
| C1 | **Float / edge overlay** | `pages/FloatWindow.ets`, `pages/EdgeFlowWindow.ets`, `entryability/EntryAbility.ets` | Overlay boundary → status-only renderer (Phase 20 → Phase 26) | **Status-only overlay (Phase 26); edge-flow/GUI control unsupported** | Harmony `SYSTEM_FLOAT_WINDOW` two-layer overlay becomes a truthful Android overlay boundary in **Phase 20**: `SYSTEM_ALERT_WINDOW` is *declared* (manifest prerequisite so `Settings.canDrawOverlays` can honestly read true). Portable `overlay/` implements: a testable `OverlayPermissionDetector` (Android/Fake/Registry) over `Settings.canDrawOverlays`; an explicit user opt-in `overlayOptIn` setting; an `OverlayGate` (`NOT_CONFIGURED`/`BLOCKED`/`READY`); a `Settings.ACTION_MANAGE_OVERLAY_PERMISSION` permission-request action; and a lifecycle-safe `OverlayFloatService` that never starts silently (no screen capture, no GUI actions). **Phase 26 makes the migration materially real with a status-only renderer**: [OverlayStateRegistry] now holds a thread-safe, sanitized, ≤160-char agent-status text + a tiny idle/running/tool/error/completed enum; [ChatViewModel] publishes send/tool/completed/error/cancel transitions into it (chat behavior/logging unchanged); [OverlayCapabilityFlags].`rendererConfigured` becomes `true` only because that narrow status renderer exists; and [OverlayFloatService] now renders and live-refreshes that bounded status text while the **explicitly started** service is alive, halting when the gate drops from READY. The Settings UI adds an explicit start/stop action (only enabled at gate READY; turning opt-in off stops it; never at launch or recomposition). The gate still requires explicit opt-in **and** the observed `SYSTEM_ALERT_WINDOW` grant. Edge-flow glow (Harmony `EdgeFlowWindow.ets`) and agent GUI controls remain unsupported — the service never overlies input (`FLAG_NOT_TOUCHABLE`), never captures screens, never executes commands, and never claims edge-flow support. |
| C2 | **Log view** | `pages/LogView.ets`, `utils/AppLogger.ets`, `CloudLogOutput.ets` | Done (Phase 14) | **Done** | `log/` ports the LogView/CloudLogOutput bounded-ring-buffer idea to Android: a capacity-bounded in-app ring buffer (`RingBufferInAppLogRepository`, FIFO eviction), a `SensitiveLogRedactor` that scrubs secrets before insertion, a Compose `LogScreen` reachable from the Settings 运行日志 section (source tabs / severity / query / refresh / auto-refresh / auto-scroll / copy / clear / honest empty state), and real app events wired (app launch, settings save + HDC result, chat send/complete/error, gallery OCR states). No Harmony `native.getLogs()`/AppStorage cloud-gallery string is claimed — events that never occur never appear. |
| D1 | **Gallery embedding** | `utils/gallery/GalleryEmbeddingService.ets` (huawei_local via ArkData `intelligence` + remote), `CloudModelClient.createEmbedding` | Config gate (Phase 21) | **Done (configurable)** | `huawei_local` embedding (Harmony `intelligence.getTextEmbeddingModel`) has no Android equivalent → honest **UNSUPPORTED**. **Phase 21** adds a config-driven remote-embedding client (`gallery/cloud/GalleryEmbeddingClient.kt`, port of `CloudModelClient.createEmbedding`/`normalizeEmbeddingUrl`) using the existing OkHttp + kotlinx stack and the existing gallery embedding settings (mode/baseURL/key/model — no new credentials). It validates endpoint/model/key presence before use, bounds request text (512) and response (body bytes + vector dimension), redacts secrets, and reports NOT_CONFIGURED/UNSUPPORTED/ERROR/READY. No call fires when not configured / opt-in off; `huawei_local` stays UNSUPPORTED. |
| D2 | **Gallery monthly insights** | `utils/gallery/GalleryMonthlyInsights.ets` (pure) | Done (Phase 15) + cloud adapter (Phase 21) | **Done** | `gallery/insights/GalleryMonthlyInsights.kt` ports the pure bucketing / tag-count / local-summary / LLM-input builder over the real room-backed `GalleryPhotoItem`s (Phases 3/9/11). **Phase 21** wires the config-driven cloud AI month summary (`gallery/cloud/GalleryMonthlySummaryClient.kt`, port of `summarizeGalleryMonth`) reusing the existing OpenAI-compatible `ChatCompletionsClient` + Planner config, behind an explicit `galleryAiSummaryEnabled` opt-in setting (no new credentials). The Gallery insights panel shows the real gate (NOT_CONFIGURED/UNSUPPORTED/ERROR/READY) and only invokes the cloud from an explicit button tap; the deterministic local insight path always remains (row D2 pure part = Done). |
| D3 | **Gallery memory sync / auto scanner** | `GalleryMemorySync.ets`, `GalleryAutoScanner.ets` | Background sync boundary (Phase 23) | **Partial (serial opt-in boundary + local scan; cloud only via configured adapter)** | **Phase 23** realises the Harmony `GalleryAutoScanner` + `GalleryMemorySync` boundary as a serial, opt-in, cancellable, bounded coordinator (`gallery/background/GalleryBackgroundCoordinator`) on the existing coroutine + Room stack — no new WorkManager/JobScheduler/Service dependency. It requires BOTH an explicit `galleryBackgroundSyncEnabled` user setting AND a runtime photo/media-read grant (`GallerySyncGate`: NOT_CONFIGURED / BLOCKED / READY); nothing is scanned until READY. Runs are single-flight (`Mutex.tryLock`), bounded (scan-limit + batch cap + wall-clock cap), cancellable (`cancelActive()` → honest PARTIAL) and idempotent (Room URI-unique upsert — no duplicates). The optional "memory sync" calls ONLY the explicitly configured `GalleryCloudAdapter` (`generateMonthlySummary` on a bounded aggregate of actually-stored content); with no configured adapter or nothing meaningful stored, NO bytes are sent (`SKIPPED_NOT_CONFIGURED`), and no embeddings/AI summaries are invented. Honest typed states: NOT_CONFIGURED / BLOCKED / READY / RUNNING / SUCCESS / PARTIAL / ERROR. The Photo Picker least-privilege path and the deterministic local `GalleryMonthlyInsights` derivation are preserved. Manifest now declares `READ_MEDIA_IMAGES`(33+)/`READ_EXTERNAL_STORAGE`(≤32) only as the grant prerequisite; nothing requests or auto-uses them. Gap: the Harmony full-library auto-run loop (periodic background execution) is intentionally exposed as an explicit-run, not auto-scheduled (no silent scanning). |
| E1 | **WeChat auto-summary settings + validation** | `utils/wechat/WechatAutoSummaryTypes.ets` (pure normalizers + validation) | Done (Phase 13) | **Done** | `wechat/domain/WechatAutoSummaryTypes.kt` ports the constants, bounded normalizers (`days` 1..90, `max_contacts` 1..50, `swipe_speed` 0..20000, ratio 0.1..0.95, `stable_swipes` 1..10, `max_history_swipes` 1..300, `wait` 0..10, mode) and the `validateWechatAutoSummaryRequest` validator. A real Settings center section (微信自动汇总) exposes the fields via DataStore (`SettingsKeys.WECHAT_*`, `SettingsCodec`) with normalization, masking affinity for any sensitive keys, and honest saved/error states. Gap: no actual collection (see E2). |
| E2 | **WeChat auto-summary collection** | `utils/wechat/WechatAutoSummaryClient.ets`, `WechatAutoSummaryStorage.ets`, `entry/src/main/python/wechat_collect/*` | Adapter (Phase 25A) → wired gate (Phase 25B) | **Done (explicit user-triggered boundary)** | A typed `wechat/domain/WechatCollectionGate.kt` wires the safe `WorkflowDeviceAdapter.wechatCollect` (`wechat_collect` action, Phase 25A) behind an explicit `suspend collect(request)` API. **Phase 25B** preserves strict validation, the `summaryEnabled` opt-in and the health-verified adapter gate: an invalid request / disabled summary / not-configured adapter returns an honest typed `BLOCKED` / `NOT_CONFIGURED` with ZERO bridge calls. When every gate passes, `collect` invokes `wechat_collect` and maps `HdcActionResult.Success` / `BridgeError` / `TransportError` / `Unsupported` into typed `WechatCollectionResult.Success` / `Error` / `TransportError` / `Unsupported` reusing the `WechatAutoSummaryResponse` status/message vocabulary; surfaced messages are secret-redacted and bounded, and a summary is never fabricated (`Success` only on a real `ok` envelope). Attached device-free tests (fake adapter) prove the no-call gates, the single valid call, the fixed result mapping, secret-redaction/bounding and cancellation propagation. Auto-summary still runs on the PC-side Python/HDC service; Android only ever surfaces a truthful status/result through the adapter. |

### Why Phase 12 chose Profile / Avatar as the implemented gap

- It is the task's preferred "highest-value real gap" and is genuinely
  implementable on Android with **no secrets and no fake capability**: the local
  profile state, typed avatar domain, honest generation boundary and a real
  Composable page are all device-safe and device-free-testable.
- Monthly insights (D2) and WeChat settings/validation (E1) were also portable
  pure logic; WeChat validation shipped in Phase 13 and **Gallery monthly
  insights shipped in Phase 15** — neither was left silent.
- AutoAgent (B1), float/edge (C1) and WeChat *collection* (E2) remain honest
  **Blocked** states (platform/permission/server blockers), never faked.
- No Harmony-only MNN/HiAI binaries were copied; the Phase-10 native stub is
  unchanged and its dependencies are documented in §5/§7.

---

## 11. Phase 16 — A1 gap: persisted local profile feeds the Home pipeline

Phase 16 closes the last remaining part of row **A1** (profile snapshot & local
profile): the locally curated profile now **flows into the real Home
recommendation pipeline** instead of the Home always rendering the hard-coded
`暂无数据` shell.

Delivered:
- `home/domain/HomeProfileBuilder.kt` — pure, deterministic port of Harmony
  `DataManagerFacade.ets` (`homeProfileDomains` / `homeProfileDomainDescription`
  / `homeProfileDomainFullDescription` / `domainClaimStatements`) that maps the
  four `AvatarDomainSnapshot`s onto the Home pillars (`desc` / `fullDesc` /
  `claims` / `eventCount`) and derives completeness / honest empty state via the
  existing `defaultHomeDomains()` / `homeProfileCompleteness()`.
- `home/data/ProfileBackedHomeProfileSource.kt` — a real `HomeProfileSource`
  backed by the existing `ProfileAvatarRepository` (`DataStoreProfileAvatarRepository`
  in the live app), returning the derived `HomeProfileViewModel`.
- `ui/ClawMateRoot.kt` — the live default `HomeProfileSource` is now the
  profile-backed source, so Home shows the actual saved 吃/穿/住/行 profile.

Truthful boundaries kept:
- The recommendation list stays **empty** — Harmony builds it from indexed todo
  events (deferred Android) via `homeRecommendations()`, never fabricated.
- The avatar section stays NOT_CONFIGURED — no generated avatar image is invented.
- An entirely empty/incomplete profile still yields the honest
  `HomeProfileViewModel.empty` (backward compatible with Phases 1–15).

Unit tests (new): `HomeProfileBuilderTest` + `ProfileBackedHomeProfileSourceTest`
cover pillar mapping, short-summary / summary-only / no-data desc resolution,
claims extraction cap, event-count sums, completeness, the honest empty state
and source-level behaviour over the fake profile repository.

## 12. Phase 17 — A4: portable profile-share import/merge pipeline

Phase 17 closes the **portable part** of migration row **A4** (Profile knock
share) that Phase 1 deliberately left behind: the **import/merge pipeline**
around the existing `ProfileShareActivity` and `.mobi-profile` /
`application/vnd.mobiinfra.profile+json` intent handling. Only the *local,
on-device* part is ported; no NFC and no cloud behaviour is faked.

Delivered:
- `profile/share/ProfileShareTypes.kt` — the `.mobi-profile` wire model
  (port of `ProfileShareEnvelope` / `ProfileShareSnapshot` /
  `ProfileShareDomainSnapshot` in `utils/ProfileKnockShareService.ets` +
  `Index.ets`), constants (UTD `com.mobiinfra.profile-json`, MIME, `.mobi-profile`,
  `MAX_RECEIVED_PROFILE_BYTES` = `512*1024`, `PROFILE_SHARE_SCHEMA_VERSION` = `1`,
  `PROFILE_SHARE_APP_ID` = `com.clawmate.app`), the stable
  `ProfileShareErrorCode` and the typed `ProfileShareParseResult`.
- `profile/share/ProfileShareImporter.kt` — a pure, side-effect-free
  parse/validate/merge:
  * `parseAndValidate` rejects (with a stable error code) **malformed** JSON,
    **oversized** payloads (byte cap, default `512 KB`, overridable), an
    **unsupported schemaVersion**, and **missing-field / unrecognized** payloads
    (no `profileText` body, or identity matches neither the UTD `typeId` nor
    `appId`+version). Identity + envelope rules are a direct port of
    `ProfileKnockShareService.isProfileShareEnvelope`.
  * `mergeIntoLocal` applies **deterministic local-first precedence** into the
    `LocalAvatarProfile`: a shared persona-domain summary fills a local field
    **only when the local `shortSummary` is blank**; existing local values are
    never overwritten. `fullDesc` is preferred over `desc`, values are trimmed
    and capped (300 chars), unknown domain keys are ignored, first duplicate
    wins. Only the four persona domains (吃/穿/住/行) are ever touched — no
    displayName/gender/age import, no secrets.
- (mod) `profile/ProfileShareActivity.kt` — now performs a real import: reads the
  `content://` URI through the `ContentResolver` with a bounded sink (an
  oversized file is rejected before it is buffered), runs `parseAndValidate` +
  `mergeIntoLocal`, persists via the existing app-private
  `DataStoreProfileAvatarRepository`, and shows an **honest** success dialog
  (list of imported dimensions + resulting local completeness) or a typed error
  dialog. Also resolves `ACTION_SEND` / `EXTRA_STREAM` / `clipData` URIs. Kept a
  plain, lifecycle-safe `Activity` intact (existing architecture).

Honest boundaries preserved (no fake capability):
- **No NFC**: the Harmony "knock-to-knock" approximation is documented as
  deferred, not implemented.
- **No cloud**: the Harmony cloud-affinity "同频报告" LLM analysis
  (`runProfileShareAffinityAnalysis` / Planner) is **not** ported and never
  claimed; the import is purely local.
- No secret is imported; no network call is made by `ProfileShareActivity`.

Unit tests (new): `ProfileShareImporterTest` (`profile/share/`, 19 tests) cover
valid-envelope parsing, each validation error code (malformed / oversized /
unsupported-version / missing-field / unrecognized), local-first merge
precedence, unknown-domain / blank-summary ignores, `fullDesc`-over-`desc`
choice, duplicate determinism, the summary size cap, the "only four persona
domains are touched" invariant, completeness rise, and backward compatibility
(a schema-v1 ClawMate envelope with only `profileText` — no structured `profile`
— remains valid and imports zero). Build: `:app:assembleDebug` +
`:app:testDebugUnitTest` `--max-workers=1` → **BUILD SUCCESSFUL**, 521 tests,
0 failures, 0 errors.

## 13. Phase 23 — D3: serial opt-in Gallery background scan/sync boundary

Phase 23 realises the **Harmony `GalleryAutoScanner` + `GalleryMemorySync`
background scan/sync** boundary (migration row D3) as a genuinely safe Android
coordinator that **never scans or uploads silently**.

### Delivered

- `gallery/background/GalleryBackgroundState.kt` — the single honest typed
  state set `NOT_CONFIGURED / BLOCKED / READY / RUNNING / SUCCESS / PARTIAL /
  ERROR`, a `GallerySyncRunSummary` (candidates / scanned-new / skipped /
  local-stored / elapsed) and `GalleryCloudSyncStatus` (`RUN` /
  `SKIPPED_NOT_CONFIGURED` / `FAILED`).
- `gallery/background/GallerySyncPolicy.kt` — pure bounds (scan-limit 60,
  batch cap 20, work-duration cap 60 s, coalescing floor 10 min), `batchSizes`,
  `clampScanLimit`, and a pure `decideRun` scheduling gate with an honest
  `GallerySchedulingDecision` (NotConfigured / Blocked / Coalesced / Run).
- `gallery/background/GalleryMediaPermission.kt` — `GalleryMediaPermission`
  contract + a deterministic `GallerySyncGate` (explicit opt-in AND runtime
  photo-access grant ⇒ NOT_CONFIGURED / BLOCKED / READY).
- `gallery/background/GalleryMediaSource.kt` — `GalleryMediaSource` (`scanNewest`)
  + `GalleryPhotoInput` (port of the Harmony type).
- `gallery/background/GalleryBackgroundCoordinator.kt` — THE serial coordinator:
  single-flight `Mutex.tryLock`, bounded batches, cooperative `cancelActive()`,
  wall-clock deadline, Room URI-unique upsert (idempotent), and a cloud
  memory-sync step that calls **only** the explicitly configured
  `GalleryCloudAdapter.generateMonthlySummary` on a bounded aggregate of
  actually-stored content, else sends nothing.
- `gallery/background/AndroidGalleryMediaBoundary.kt` — `AndroidGalleryMediaPermission`
  (ContextCompat read of `READ_MEDIA_IMAGES` / `READ_EXTERNAL_STORAGE`),
  `MediaStoreGalleryMediaSource` (newest-first, refuses to query without a
  grant) and `FakeGalleryMediaPermission` for host tests.
- Settings: `galleryBackgroundSyncEnabled` (default off) persisted via
  `SettingsCodec`, `GALLERY_BACKGROUND_SYNC_ENABLED` key, a
  `setGalleryBackgroundSyncEnabled` ViewModel setter, and a 后台图库同步 card in
  数据采集 showing the toggle + the live gate status.
- Manifest: declares `READ_MEDIA_IMAGES` (33+) / `READ_EXTERNAL_STORAGE` (≤32)
  **only as the honest grant-observation prerequisite** — nothing requests or
  auto-uses these permissions; the Photo Picker least-privilege flow is
  untouched.

### Honest boundaries preserved (no fake capability, no silent work)

- **No silent scan/sync**: the coordinator never auto-runs; it executes only
  from an explicit user action after `GallerySyncGate` is READY. The Harmony
  periodic background run loop is deliberately surfaced as an explicit-run
  (documented), not a background timer — no silent scanning.
- **No upload when unconfigured**: with no explicitly configured
  `GalleryCloudAdapter` (or nothing meaningful stored), the cloud step is
  `SKIPPED_NOT_CONFIGURED` and **zero bytes are sent**. No embeddings / AI
  summaries are invented by the coordinator.
- **Gap**: the full periodic auto-run and any aggregated "memory" export beyond
  the existing Room + configured-adapter summary path remain future work /
  explicitly invoked only.

### Verification (Phase 23)

- `:app:assembleDebug` + `:app:testDebugUnitTest` with
  `--no-daemon --max-workers=1` → **BUILD SUCCESSFUL** (see `DSH_PHASE_RESULT`).
- New device-free tests: `GalleryBackgroundCoordinatorTest` (scheduling gate,
  permission gate, dedup/idempotency, cancellation → PARTIAL, bounded batches /
  scan-limit, cloud no-call behaviour, configured-adapter RUN once, failing-cloud
  PARTIAL, single-flight refusal), `GallerySyncPolicyTest` (scheduling gate +
  coalescing + bounded batches), and a `SettingsCodecTest` opt-in round-trip.

## 14. Phase 24 — A2: configured Seedream avatar generation provider + local asset result path

Phase 24 closes migration row **A2** (Avatar generation image), turning the old
`CONFIGURED_BUT_NO_PROVIDER` boundary into a **real, configured provider** wired
into the 数字分身 screen. Source of truth (read-only): Harmony
`datamanager/avatar/SeedreamAvatarProvider.ets`, `AvatarService.ets`,
`AvatarStorage.ets`, `AvatarForeground*.ets`, `AvatarTypes.ets`,
`AvatarPromptBuilder.ets`.

Delivered:

1. **Faithful Seedream HTTP contract** (`profile/avatar/data/generation/`).
   `AvatarGenerationClient` / `SeedreamAvatarProvider` POST the exact wire body
   (`model`, `prompt`, `sequential_image_generation`, `response_format`,
   optional `output_format` suppressed for models containing `seedream-4-5`,
   `size`, `stream`, `watermark`, optional `image`) with
   `Authorization: Bearer <apiKey>` over the existing OkHttp + kotlinx stack
   (`ChatCompletionsClient` pattern). Connect/read timeouts are honoured
   (read = `config.timeoutMs`, coerced ≥ 1 s), and coroutine cancellation
   cancels the live HTTP `Call`.
2. **Only a real image is parsed/downloaded.** `SeedreamResponseParser` honours
   the first `data[0].b64_json` / `data[0].url` string (trimmed) with bounded
   base64 decode; a URL result is downloaded only via `AvatarUrlValidator`
   (absolute http/https + host, no embedded credentials) with content-type +
   `MAX_IMAGE_BYTES`(4 MB) gates. `AvatarProviderLimits`: prompt 12,000, generate
   response body 512 KB, base64 8 MB.
3. **No network call when unconfigured + no fabrication.** `AvatarGenerationGate`
   now derives `NOT_CONFIGURED` / `PARTIALLY_CONFIGURED` / `READY` from base URL
   (http/https + host), api key, model and image size. `generateAvatar()` runs
   only on an explicit 生成数字分身 tap when READY, a provider is wired, and the
   local profile reaches the 20% completeness floor; otherwise zero HTTP bytes
   and a redacted error. Typed `AvatarProviderException` reasons only.
4. **Bounded prompt + explicit consent.** `buildAvatarGenerationPrompt` builds a
   `MAX_PROMPT_LENGTH`-capped deterministic prompt from the local profile; the
   explicit user action is the recorded text-to-image consent (no photo used).
5. **Local asset result path.** Real generated bytes persist through the existing
   app-private `AvatarAssetRepository.persistGeneratedImage` as a `seedream-<seq>`
   version record (provider `"seedream"`), newest-first, validated (byte cap +
   actual image decode), displayed via Coil.
6. **Honest run lifecycle on the screen.** `ProfileAvatarViewModel` injects an
   `AvatarProvider` (production `SeedreamAvatarProvider.withDefaultClient()`),
   adds `generateAvatar()`, `AvatarRunState` (`IDLE / RUNNING / SUCCESS / ERROR`)
   and a `generationError` scrubbed of the api key / `sk-*`. The 数字分身 card
   shows NOT_CONFIGURED / READY plus READY → RUNNING → SUCCESS / ERROR.
7. **Redacted errors / no secrets / no adb/hdc / no background generation.** The
   api key is never logged, stored, or echoed; no credentials added (existing
   Settings fields feed the config); no adb/hdc; generation runs only from the
   explicit user tap (never backgrounded/scheduled).

### Verification (Phase 24)

- `:app:assembleDebug` + `:app:testDebugUnitTest` with `--no-daemon --max-workers=1`
  → **BUILD SUCCESSFUL**; `app-debug.apk` produced (see `DSH_PHASE_RESULT`).
- **696 tests, 0 failures, 0 errors** (aggregated from test-results XML).
- New/updated device-free tests: `AvatarGenerationClientTest` (MockWebServer:
  request URL/auth/body, base64 + url-fallback parsing, content-type/size
  enforcement, HTTP-error redaction, unconfigured no-call, parse error,
  oversized-response reject, validated download, timeout mapping),
  `AvatarUrlValidatorTest`, `SeedreamResponseParserTest`,
  `SeedreamRequestBodyBuilderTest`, `AvatarGenerationPromptTest`,
  `AvatarPersistenceSeamTest`, `ProfileAvatarGenerationTest` (no-call-when-
  unconfigured, missing-provider, low-completeness, success persists real bytes,
  error redaction, cancellation non-success), plus gate-state updates in
  `AvatarDomainTest` / `ProfileAvatarViewModelTest` /
  `AvatarAssetRepositoryTest`.

Known non-portable gap (honest): Harmony `AvatarForeground*.ets`
subject-segmentation / transparent-crop / message-thumbnail derivation depends on
the Harmony `subjectSegmentation` visual service, which has no Android
equivalent, so it remains a documented boundary — the generated main image is
never the less real and is displayed via the asset repository.

## 15. Phase 29 — bounded daily-log event source into Home recommendations

This phase adds the first **bounded daily-log input + local event-index
foundation** to the Android Home recommendation pipeline, honestly marking the
remaining Harmony data-manager work as **Partial/Deferred** (no full
`ConsolidationService`/`IndexService` port).

### What was delivered

1. **Pure daily-log parser** (`datamanager/DailyLogParser`) — device-independent,
   no Android deps:
   - Parses the Harmony metadata header (`<!-- DAILY_LOG_METADATA … -->`) for
     `workflow_metadata` + `latest_entry_index`, and numbered `1. …` entries
     separated by blank lines, from the app-private
     `filesDir/workflows/daily-log/YYYY-MM-DD/*.md` layout.
   - Strict bounds: day-directory `YYYY-MM-DD`, `.md` file name + length cap,
     per-file entry-count cap, per-entry and total-character caps, and a metadata
     size cap ([DailyLogBounds]).
   - Bad files are skipped and reported as countable `DailyLogParseError`s —
     **never thrown**, so a hostile/oversized file cannot falsify a whole refresh.
2. **Bounded mapper** (`datamanager/DailyLogPlannerEventMapper`) — maps typed
   entries into the existing `HomeRecommendationPlannerEvent` shape:
   - `eventId` = stable source ref `YYYY-MM-DD/file.md#N`; `date` = real `sourceDate`.
   - Bounded real text (`todo_text`/`summary`/`normalized_fact`); `imagePaths` is
     always empty (a raw path/URI/byte is never emitted).
   - Honest `sources = ["DailyLog"]`; `domains` empty (no category guessing).
3. **Read-only app-private source** (`datamanager/DailyLogEventSource` +
   `FileDailyLogEventSource`) — reads **only** existing daily-log text already
   present in `filesDir/workflows/daily-log`; never creates files, never scans
   external storage, no URI/image/network. A test factory (`overDirectory`)
   mirrors the behaviour over a temp dir for unit tests.
4. **External-import API, not auto-creation** (`datamanager/DailyLogWorkspace`) —
   the one documented way a future workflow/import layer writes daily-log files;
   the recommendation pipeline never auto-creates them. No shell/adb/hdc/GUI
   capabilities were added.
5. **Explicit mixed input** — the Home "生成推荐" button now reads stored gallery
   metadata **and** daily-log events (wired through `HomeViewModel`,
   `HomeViewModelFactory`, `HomeScreen`, `ClawMateRoot`). The repository blends
   them into the planner and the output normalizer validates `source_event_ids`
   over **both** id namespaces. Gallery-only behavior stays compatible; no gallery
   and no daily-log → NoData with **zero network calls**. Never read on Home
   load/recomposition/background.

### Remaining (Partial/Deferred — honestly not ported)

- Harmony **cloud/periodic consolidation and dedup** (`ConsolidationService`
  `collectSourceEntries` + model dedup), **profile/claim derivation**,
  **index partitions** (`IndexService`) and any **periodic background
  scheduling** are **not** implemented. Phase 29 delivers only the parsing and
  planner-input foundation and the bounded event source; future phases are
  required before the Harmony daily-log → IndexedEvent → HomeRecommendation flow
  is complete.

### Verification (Phase 29)

Focused serial run (JDK 17.0.19, Android SDK platform 34, Gradle 8.9,
`--no-daemon --max-workers=1`) of the new Phase-29 tests plus a full unit-test
run passed — see `DSH_PHASE_RESULT.md` for the exact commands, counts and the
commit hash.

## 16. Phase 30 — local daily-log consolidation + index foundation

Phase 30 advances the same data-manager row from *planning input only* to a
**deterministic, device-local consolidation + index** layer. It is a faithful but
**bounded local-only** subset port of Harmony `ConsolidationService.ets` +
`IndexService.ets` reduced to the *no-cloud-model* path: real daily-log text is
consolidated deterministically (no LLM enrichment/embedding), deduplicated by
exact + normalized text, persisted as bounded/atomic JSON artifacts, and exposed
through a read-only local index query. All of it is **zero-network** and only ever
runs from an explicit user action (it is not scheduled, and it never auto-creates
daily-log input).

### What was delivered (all under `app/src/main/java/com/clawmate/app/datamanager/`)

1. **Typed bounded models** — `LocalConsolidationTypes` (`ConsolidatedRecord`,
   `ConsolidatedMetadata`, `ConsolidatedState`, `IndexedEvent`, `EventPartition`,
   `EventPartitionIndex`, `IndexManifest`, `TemporalDocument`, `StateIndex`,
   `QueryOptions`, `QueryResult`, and the fixed `-local-` schema/version markers),
   all `@Serializable` with tolerant decode defaults.
2. **Pure text helpers** — `ConsolidationText` (ports `normalizeFreeText`,
   `normalizeDedupText`, `normalizedSimilarityKey`, `summarizeText`,
   `stableUnique`, `isIsoDate`, `monthKeyFromDate`, `normalizeTags`,
   `extractAmountTokens`, `sameStringSet`, `dateToTime`). Zero Android deps.
3. **Deterministic engine** — `ConsolidationEngine` (ports
   `ConsolidationService.consolidateGroup` + `DataManagerUtils` dedup/creation to
   the exact + normalized-dedup path): heuristic per-entry split, recent-scope
   matching (`RECENT_MATCH_DAYS`), `findExactExistingMatch`-equivalent matching,
   `updateExistingRecord` merge, `createNewRecord`, stable monotonic `record_id`s,
   and a defensive per-group record cap.
4. **Atomics + recovery** — `AtomicJsonStore` (temp-file + rename, bounded 4 MiB,
   tolerant read) and `ConsolidationCodec` (schema/version JSON serde; a corrupt /
   partial state artifact recovers to empty and is re-consolidated from the real
   daily-log text).
5. **Persistence + index** — `ConsolidationRepository` (scan existing daily-log →
   consolidate each month/file group → persist `outputs/<month>/<file>.state.json`
   + a markdown summary, rebuild `state-index.json`), `IndexService` (rebuild month
   event partitions, partition-index, event manifest with source sha + per-event
   input hashes, temporal month docs; stable event ids `${relativePath}#${recordId}`),
   and `DailyLogLocalIndex` (read-only query by date / month / source / keyword,
   bounded, newest-first, tolerant of missing/corrupt partitions).
6. **Explicit entry point** — `LocalConsolidationIndexRunner`, the single
   zero-network orchestrator (consolidate → index ─→ typed `RunOutcome`). It is not
   wired to a UI/background trigger here so it can only ever run from an explicit
   future call site; no launch/recomposition/WorkManager path invokes it.

### Paths (under an app-private base dir, mirroring Harmony `DataManagerPaths`)

```
workflows/daily-log/                                    # input (Phase 29, never created here)
workflows/profile-consolidation/
  outputs/<month>/<file>.md.state.json                  # per-group consolidated state
  outputs/state-index.json                              # output state index
  indexes/events/<month>.json, partition-index.json     # event partitions
  indexes/manifest.json                                 # event manifest
  indexes/temporal/<month>.json                         # temporal month docs
```

### Remaining (Partial/Deferred — honestly not ported)

- Harmony **cloud/periodic consolidation and model-based dedup**,
  **profile/claim derivation**, `workflow_scene_id` enrichment, **image referencing
  for records**, and any **background/periodic scheduling** are **not**
  implemented. Phase 30 is strictly deterministic-local and explicit-user-triggered.

### Verification (Phase 30)

Focused serial run (JDK 17.0.19, Android SDK platform 34, Gradle 8.9,
`--no-daemon --max-workers=1`) of the new `LocalConsolidationTest` passed — see
`DSH_PHASE_RESULT.md` for the exact command, counts and the commit hash.

---

## 17. Phase 31 — Android NFC profile-knock-share adapter ("一碰分享")

Phase 31 migrates the Harmony **一碰分享** (knock-share) *capability* to a real
Android NFC (NDEF) flow, closing the last documented A4 gap. It is a genuine
`NfcAdapter` adapter — no simulation, no fake device state, no adb/shell/hdc, no
coordinate GUI, no background auto-scan.

### Safety & honesty invariants preserved end to end

- **Real availability, never fabricated.** `AndroidNfcProfileShareGateway`
  probes the actual `NfcAdapter.getDefaultAdapter` + `isEnabled`:
  NOT_SUPPORTED (hardware absent) / DISABLED (turned off) / READY are all honest.
- **Explicit sessions only.** Send and receive are armed solely by explicit user
  taps; NFC reader mode is enabled only while the dedicated Activity is RESUMED
  and a SEND/RECEIVE session is armed, then released in `onPause`. Nothing is
  started from Home, on launch, on recomposition, or in the background.
- **Never bypasses the shared importer.** Incoming NDEF bytes are validated by
  `ProfileKnockEnvelopeCodec.parseAndValidateNfc` (NDEF MIME/type, ≤512 KiB,
  UTF-8/JSON, schema/version, app/type) which **delegates** all structural and
  identity checks to the existing `ProfileShareImporter`, so the NFC path reuses
  the exact same merge rule ("local-first, fill-blank-only") and cannot import
  anything the portable path would reject.
- **Bounded payload both ways.** Send builds the canonical
  `application/vnd.mobiinfra.profile+json` / `.mobi-profile` / UTD
  `com.mobiinfra.profile-json` envelope (schema v1, `com.clawmate.app`,
  `generatedAt`, `profileText`, `profile.domains`) and refuses to emit anything
  larger than 512 KiB; receive re-checks the cap. No URI/image/token/unrelated
  data is ever sent.
- **Duplicate-safety across lifecycle rebuilds.** A per-message signature is
  recorded in a persistent SharedPreferences-backed store plus an in-memory
  session cache; a repeated tag/message is not re-imported even after an Activity
  recreation or process restart, and a profile is persisted exactly once.

### New files (all under `app/src/main/java/com/clawmate/app/profile/share/nfc/`)

- `ProfileKnockNfcTypes.kt` — status enum (NOT_SUPPORTED / DISABLED / READY /
  SENDING / RECEIVING / SUCCESS / CANCELLED / ERROR), typed error enum, received
  outcome sealed class, `NfcProfileShareGateway` and `ProfileKnockDedupeStore`
  boundaries.
- `ProfileKnockEnvelopeCodec.kt` — pure envelope build/encode (bounded, ≤512 KiB) +
  incoming NDEF validate (MIME, size, UTF-8/JSON, schema/version, app/type via the
  importer) + stable message signature.
- `ProfileKnockNfcStateMachine.kt` — pure JVM state machine: availability gating,
  READY→SENDING/RECEIVING, send-write, receive-validate→dedupe→import, CANCELLED,
  typed ERROR, reset/retry. Tests inject a fake gateway + fake dedupe + fake repo.
- `NfcProfileShareViewModel.kt` — the typed ViewModel boundary the UI calls;
  exposes a `uiState: StateFlow<UiState>` and only explicit actions (`sendProfile`,
  `startReceive`, `stopSession`, `retry`, `probe`, `sendToPeerTag`).
- `NfcProfileShareScreen.kt` — Compose screen rendering real device state and only
  the explicit start/stop/retry/re-probe controls.
- `device/AndroidNfcProfileShareGateway.kt` — real NfcAdapter gateway (reader-mode
  receive via `onTagDiscovered`; NDEF write via `Ndef` / `NdefFormatable`).
- `device/PrefsProfileKnockDedupeStore.kt` — persistent duplicate-signature store.

### Modified files

- `app/src/main/AndroidManifest.xml` — declares `android.permission.NFC` and a
  **non-required** `android.hardware.nfc` `<uses-feature>` (so no-NFC devices
  still install), and registers `NfcProfileShareActivity` (not exported; singleTask).
- `app/src/main/java/com/clawmate/app/settings/ui/SettingsScreen.kt` — adds the
  single explicit `一碰分享（NFC）` entry card in the Settings list (launches the
  NFC Activity on tap; never auto-starts).

### Honest boundaries that remain non-portable

- Harmony **knockShare discovery** — `harmonyShare.on('knockShare', …)` /
  `harmonyShare.on('dataReceive', …)` target peer discovery, the system "knock"
  UX, and the `harmonyShare` sandbox inbox have **no Android equivalent**. Android
  substitutes explicit foreground reader-mode sessions and literal NDEF peer
  contact; there is no peer-targeted "send to that specific open share" gesture.
- **System share / launch-Want UI** — the Harmony file-open/share system surface
  (`module.json5` skills + `<action viewData/sendData>` wants) is covered on
  Android by the existing `ACTION_VIEW` intent flow in `ProfileShareActivity`, not
  reimplemented in the NFC path.

### Verification (Phase 31)

Serial offline runs (`--offline --no-daemon --max-workers=1`, JDK 17.0.19, SDK
platform 34, Gradle 8.9):
1. New focused class: `:app:testDebugUnitTest --tests
   "com.clawmate.app.profile.share.nfc.ProfileKnockNfcTest"` — **21 tests, 0
   failures, 0 errors** (payload build/cap, MIME & protocol + malformed/oversized/
   unsupported-version validation, state machine, no-NFC/disabled/cancelled/error,
   duplicate dedupe, importer-not-bypassed).
2. Full suite: `:app:testDebugUnitTest` — **110 test classes / 832 tests, 0
   failures, 0 errors**.
3. Build: `:app:assembleDebug` — **BUILD SUCCESSFUL** (merged manifest contains
   `android.permission.NFC`, non-required NFC feature, and `NfcProfileShareActivity`).
See `DSH_PHASE_RESULT.md` for exact commands, counts and the commit hash.

---

## 18. Phase 32 — profile index / claims foundation (four-domain profile)

Phase 32 advances the data-manager row from *local consolidation + index* to a
**real four-domain profile index / claims foundation** on top of the Phase-30
artifacts. It reads the persisted event partitions
(`indexes/events/<month>.json`), aggregates the events into the Harmony four
persona domains (`eat`/`wear`/`live`/`travel`) by deterministic literal
evidence, and materialises a typed profile into
`workflows/profile-consolidation/indexes/profile/state.json` plus a bounded
`profile.md`.

### What was delivered (all under `app/src/main/java/com/clawmate/app/datamanager/`)

1. **Typed profile models** — `ProfileTypes` (`ProfileState`, `ProfileDomainState`,
   `ProfileClaim`, `ProfileModelPayload`, `RawProfileClaim`), four-domain keys /
   display names, strict bounds (claims per domain, statement length, event-ids per
   claim, confidence/kind whitelists, min evidence), the Harmony generation tokens
   plus `local_only`, and the typed `ProfileBuildStatus` (EMPTY / LOCAL_ONLY /
   MODEL / PARTIAL / ERROR).
2. **Four-domain projection** — `ProfileDomainUtils` (ports Harmony
   `deriveProfileDomains` + `PROFILE_DOMAIN_RULES`, kept to the four Home pillars;
   `work`/`other` are excluded). Event domains are derived only from real indexed
   text; an event with no evidence maps to no domain.
3. **Builder** — `ProfileIndexBuilder`: loads real events from partitions
   (tolerant of missing/corrupt/oversized), projects each domain
   (`generation = "local_only"`, summary from real record text, claims empty),
   assembles the `ProfileState` (`buildProfileDocument` data contract:
   schema/version, `generatedAt`, `event_count`, date range, source event ids),
   renders a **bounded** markdown, and writes both artifacts atomically.
4. **Codec + paths** — `ProfileCodec` (tolerant serde; corrupt artifact recovers to
   honest empty) and `DataManagerPaths.profileState/profileMarkdown`.
5. **Strict model validator + client** — `ProfileModelConfig` (gated via the shared
   OpenAI-compatible Planner `EndpointConfig`; null → zero network),
   `ProfileModelValidator` (independent JSON decode + whitelist + evidence-reference
   check: unknown kind→observation, out-of-range confidence→low, over-length
   bounded, unknown evidence / <2 independent events / event-detail text dropped,
   malformed JSON→null), and `ProfileModelClient` (sends only bounded real event
   text via the existing `ChatCompletionsClient`; returns typed `DomainUpgrade`).
6. **Runner** — `ProfileIndexRunner`: local-only when unconfigured (zero network);
   model-backed when configured; deterministic-reuse keeps admitted model claims
   stable across unchanged re-runs; a model failure returns typed ERROR/PARTIAL and
   **never overwrites a previously persisted valid model state** (local projection
   is always preserved).
7. **Home integration** — `RealProfileGenerationRepository` (explicit "生成画像"
   action), `ProfileGenerationState`/mapper, `HomeViewModel.generateProfile()` +
   `HomeScreen` "生成画像" section + `HomeViewModelFactory` wiring, and
   `ProfileIndexHomeProfileSource` / `ProfileIndexBlender` that fill only un-populated
   Home pillars from the bounded index-derived summary / claims and real evidence
   ids (local-first, never overwriting user-curated data, no URI/image/token).

### Safety & honesty invariants

- **Zero network unless configured and real events exist.** Missing model config or
  no indexed events short-circuit to a typed local result with no request.
- **No fabricated claims.** A local-only run carries empty claims (`local_only`);
  model claims are admitted only when they reference real indexed event ids and pass
  every bound. A model failure preserves the deterministic local result as
  LOCAL_ONLY/PARTIAL/ERROR and cannot downgrade a valid persisted model state.
- **Explicit user action only.** The Home "生成画像" button is the only UI entry and
  is never invoked at launch / recomposition / background / normal Home load.
- **Corrupt/oversized artifacts recover honestly** (to empty/local) rather than
  falsifying a run.
- **Bounded output** — claims, summaries, per-claim evidence ids, and the rendered
  markdown are capped.

### Remaining (Partial/Deferred — honestly not ported)

- **Periodic scheduling** of consolidation/index/profile rebuild (no WorkManager /
  background job; explicit user action only).
- **Full cloud consolidation / model-based dedup / enrichment** (Harmony uses the
  cloud model client for split/dedup/enrich; Phase 32 remains strictly deterministic
  local for projection + only claims generation uses the model).
- The **deterministic local `workflow_scene_id` + entity resolver** are migrated in
  **Phase 33** (§19); only the model-backed entity-resolution/enrichment path stays
  Partial/Deferred.

### Verification (Phase 32)

Serial offline runs (`--offline --no-daemon --max-workers=1`, JDK 17.0.19, SDK
platform 34, Gradle 8.9):
1. New focused classes (`ProfileIndexTest` 19, `ProfileIndexBlenderTest` 4,
   `ProfileGenerationViewModelTest` 3) — **26 tests, 0 failures, 0 errors**.
2. Full suite: `:app:testDebugUnitTest` — count recorded in `DSH_PHASE_RESULT.md`.
3. Build: `:app:assembleDebug` — **BUILD SUCCESSFUL**.

## 19. Phase 33 — workflow-scene + entity index/resolver foundation

Phase 33 ports Harmony's `IndexService.buildWorkflowSceneDocument` and the
deterministic (model-free) half of `buildEntityDocument` to Android, on top of the
Phase-30/32 artifacts, and honestly leaves the cloud **model-backed** entity
resolution/enrichment path unported.

### What was delivered (all under `app/src/main/java/com/clawmate/app/datamanager/`)

1. **Real scene-id propagation** — `SceneEntityNormalizer` (ports
   `WorkflowSceneConfig.normalizeMemorySceneId/Ids`, incl. legacy 7→1) and
   `ConsolidationEngine` now read the daily-log `task_scene_id` from each entry's
   workflow metadata into `CandidateFact.workflowSceneId`, fold it into each
   record's `workflow_scene_ids` (dedup, sort, drop negatives), and `IndexService`
   projects it onto each `IndexedEvent.workflow_scene_ids`. `ConsolidatedRecord` /
   `IndexedEvent` gained backward-compatible defaults, so old Phase-30 artifacts
   (no such fields) still decode honestly.
2. **Workflow-scene index** — `WorkflowSceneIndexBuilder` writes
   `indexes/workflow-scene/index.json` (`schema`/`generated_at`/`postings`:
   sceneId -> stable event ids), deduped, sorted, bounded, from real events only.
3. **Entity index / resolver foundation** — `EntitySceneIndexTypes`
   (`EntityDocument`/`EntityDefinition`/`alias_postings`/`mention_assignments`),
   `EntityResolver` (deterministic local: same-type canonical name/aliases merge
   stably; different types never merge; unknown/empty/over-long dropped), and
   `EntitySceneIndexBuilder` (entity mentions from each event's real
   `entity_mentions` + **conservative bounded** labelled-name extraction from
   `normalized_fact`/`summary` — no URI/image/token/whole-log-segment). Writes
   `indexes/entity/index.json`.
4. **Runner + codec + paths** — `EntitySceneIndexRunner` (single explicit,
   testable rebuild: reads event partitions, atomically writes **exactly** the
   two artifacts, never touches profile `state.json`/`profile.md` or Home data),
   `EntitySceneCodec` (tolerant serde), and the new `DataManagerPaths` entries.
5. **Optional model boundary** — `EntityModelBoundary` (typed
   `BLOCKED`/`NOT_CONFIGURED`/`CONFIGURED`) reuses `ProfileModelConfig` (shared
   `EndpointConfig`); Android defaults to **local-only, zero network**, and a
   network model call is only ever invited by an explicit, fully-configured,
   user-triggered index/enrichment action — never on Home launch/load.

### Safety & honesty invariants

- **Zero network / no fabricated entities.** Empty or no-mention input produces no
  artifact and no request. Only real mention tokens or conservative labelled
  extractions are admitted; the deterministic local resolver is authoritative.
- **Backward compatible.** Old Phase-30 event partitions decode to empty scene /
  mention fields; builds stay honest.
- **Bounded** scene postings, entity/alias counts and JSON artifacts; corrupt /
  oversized artifacts recover to an empty document (never thrown).
- **No external scanning, no URI/image/token, no Runtime.exec/adb/hdc/GUI
  automation; Phase-31 NFC behavior untouched.**

### Remaining (Partial/Deferred — honestly not ported)

- **Periodic scheduling** (no WorkManager / background job; explicit user action).
- **Model-backed entity resolution/enrichment path** (Harmony uses the cloud model
  client to enrich/merge entities; Android keeps the deterministic local resolver
  and a typed model gate with **no network-invoked calls by default**). Phase 34
  ports the *model consolidation/dedup* path (see §20) but does **not** port the
  separate entity-enrichment model path.
- `workflow_scene_id` **enrichment** (per-event scene labels/reweighting) is not yet
  surfaced into query/recommendation surfaces — Phase 33 persists the postings only.

### Verification (Phase 33)

Serial offline runs (`--offline --no-daemon --max-workers=1`, JDK 17.0.19, SDK
platform 34, Gradle 8.9):
1. New focused class `EntitySceneIndexTest` — **17 tests, 0 failures, 0 errors**.
2. Existing `datamanager.*` suites (`ProfileIndexTest`, `LocalConsolidationTest`,
   `FileDailyLogEventSourceTest`, …) re-run green.
3. Full suite: `:app:testDebugUnitTest` — count recorded in `DSH_PHASE_RESULT.md`.
4. Build: `:app:assembleDebug` — **BUILD SUCCESSFUL**.

## 20. Phase 34 — cloud/model consolidation + model-based dedup

Phase 34 migrates the Harmony `ConsolidationService` **cloud/model consolidation**
and the `DataPipelineModelClient` **model-based dedup** surfaces to Android, on top
of the Phase-30 local engine / Phase-32 profile / Phase-33 scene+entity indexes,
**without repeating** Phase 31 (NFC) or Phase 32 (profile claims) or Phase 33
(scene/entity index).

### What was delivered (all under `app/src/main/java/com/clawmate/app/datamanager/`)

1. **Typed model contract** — `CloudConsolidationTypes`: `DedupDecision`
   (`decision` ∈ strict `new|duplicate|merge_with_existing|skip_ambiguous`,
   `matched_record_ids`/`reason`/`merged_summary`/`merged_fact`/`merged_tags`),
   `CandidateFact` (always carries `source_ref`/`source_date`/`workflow_scene_id`),
   bounded split/dedup prompt payloads, typed `ConsolidationPath`
   (`MODEL`/`PARTIAL`/`LOCAL`), `ModelConsolidationResult`, and an auditable
   `AuditedDecision`.
2. **Config gate** — `ConsolidationModelConfig` reuses the shared `EndpointConfig`
   + `ChatServerConfig` (base URL + key + model), and a typed
   `ConsolidationModelGate` (`Allow`/`LocalOnly`/`NotConfigured`). A model call is
   only ever invited by an **explicit, fully-configured, user-triggered
   consolidation action**.
3. **Model client** — `ConsolidationModelClient` converses with the shared
   OpenAI-compatible `ChatClient` to split bounded real source entries into bounded
   candidate facts and decide a strict dedup decision, then **strictly validates**
   every reply:
   - model facts are admitted only when `normalized_fact` is non-blank/bounded and
     carries the real `source_ref`/`source_date`/`workflow_scene_id`;
   - decision tokens normalise to the strict set; any `matched_record_id` must
     resolve to a **real** candidate id in the current recent scope — unknown /
     duplicated / out-of-bounds ids force the safe local/heuristic fallback so the
     model can never fabricate a record or overwrite an unrelated one;
   - serial (one request at a time, never parallel), batched entries + hard
     prompt-character caps, no URI/image/token/path, and coroutine cancellation
     propagates (bad JSON / request failure → typed LOCAL/PARTIAL, never a throw).
4. **Model-aware engine** — `ModelConsolidationApplier` (pure) turns pending
   entries + the previous state into the next state using the stable per-source
   `processed_entry_hashes` skip (mirrors Harmony `processed_entry_hashes`), exact
   recent-scope match first, then the model/fallback dedup; `merge_with_existing`
   targets only a genuine single in-scope record, `new` uses the next stable
   monotonic `record_id`, `skip_ambiguous` drops the fact, and decisions are written
   to `model_notes` for audit (never a secret). Phase-33 `workflow_scene_ids` are
   preserved through the model fact.
5. **Explicit repository + production seam** — `CloudConsolidationRepository`
   (`explicitAction` + config gate; zero network when unconfigured / no entries /
   not an explicit action; a failed model run preserves the previous valid state)
   persists the same `outputs/<month>/<file>.md.state.json` + markdown layout, and
   `ConsolidationModelClientFactory` wires the shared
   `ChatCompletionsClient`. It is deliberately **not** invoked on launch / Home
   load / recomposition / background/WorkManager.
6. **Backward-compatible schema** — `ConsolidatedRecord.model_notes` +
   `ConsolidatedMetadata.processed_entry_hashes` were added with defaults so old
   Phase-30 artifacts decode honestly; `ConsolidationCodec.rebindRecords` keeps them
   bounded/unique.

### Safety & honesty invariants

- **Zero network by default.** The model path runs only for an explicit,
  fully-configured, user-triggered consolidation with real entries. Every other
  run is the deterministic local engine (real changes, no fabricated output).
- **No fabricated records / no unrelated overwrite.** A hostile decision
  (unknown/duplicated/out-of-bounds matched id) never creates a record with a
  merge label and never touches an unrelated record — it falls back to `new`.
- **Bounded, serial, cancellable, error-redacted** model calls; no URI/image/token/
  external-path in prompts; no secrets in prompts, notes, logs or artifacts.
- **Backward compatible + atomic** writes; a failed model run preserves the prior
  valid state; repeat runs are idempotent via processed hash skips.

### Verification (Phase 34)

Serial offline runs (`--offline --no-daemon --max-workers=1`, JDK 17.0.19, SDK
platform 34, Gradle 8.9):
1. New focused class `CloudConsolidationTest` — tests/failures/errors recorded in
   `DSH_PHASE_RESULT.md`.
2. Existing `datamanager.*` suites (`LocalConsolidationTest`, `ProfileIndexTest`,
   `EntitySceneIndexTest`, `FileDailyLogEventSourceTest`, …) re-run green.
3. Full suite: `:app:testDebugUnitTest`.
4. Build: `:app:assembleDebug`.
5. `git diff --check` clean.

## 21. Phase 35 — periodic local-consolidation scheduler seam + explicit model entity-enrichment seam

Phase 35 adds two **explicit, disabled-by-default seams** on top of the Phase-33
entity index and Phase-34 model consolidation. It deliberately delivers **no
WorkManager dependency and no UI wiring**: both seams are in-process, JVM-testable
extension points that a future Android host (Application/UI) and a real
`WorkManager` worker line must wire independently. The seams are the honest
porting surface for Harmony's periodic scheduling and its model-assisted
entity-resolution/enrichment behavior, without claiming any of those real
integrations are complete.

### The periodic local-consolidation scheduler seam

- `SchedulingPolicy.kt` — pure, secret-free policy surface. Production default is
  `SchedulerMode.DISABLED`; a task is registered only when a caller explicitly
  builds `mode = LOCAL_ONLY` **and** `networkConstraint = NOT_REQUIRED`. No base
  URL / key / token / path is ever held here.
- `PeriodicConsolidationScheduler.kt` — the single explicit registration seam
  (`register` / `cancel` / `isScheduled` / `scheduledTaskNames` /
  `runScheduledOnce` / `runAllScheduledOnce`). It has **no WorkManager
  dependency** and starts no Android worker and opens no network.
  - Unique task-name registration with `REPLACE`/`KEEP` conflict policy; one task
    name can never be scheduled twice; a `DISABLED` policy registers nothing.
  - All runs execute **serially** through a single `Mutex` (never parallel).
  - Each local run is **idempotent** (deterministic local rebuilds of the same
    input produce the same artifacts) and **cancellable** — the caller supplies
    `isCancelled` and the job checks it between stages, rethrowing
    `CancellationException`.
  - The background path **never** touches the model-backed
    `CloudConsolidationRepository`; only deterministic local consolidation /
    index / profile rebuild work runs.
  - Nothing here executes on launch, Home load, recomposition, or a
    repository/Application constructor — it acts only when an explicit caller
    invokes `register` and later a `run*` method.

### The explicit model entity-enrichment seam

- `EntityEnrichment.kt` — `EntityEnrichmentTypes`: bounded `EntityCandidate`
  packing (hard candidate/record/source-ref/alias/char caps), strict
  `person|place|merchant` types admitted (model `unknown` dropped), `RichEntity`,
  typed `EnrichmentStatus` (`EMPTY`/`LOCAL_ONLY`/`MODEL`/`FALLBACK`), and a
  never-persisted `EnrichmentResult`.
- `EntityEnrichmentModelClient.kt` — serial / bounded / cancellable model call that
  **strictly validates every reply** with bounded strict JSON and a **real
  candidate-id / source-ref whitelist**: any unknown, duplicate, out-of-bounds or
  over-long id / source ref is dropped; no URI/image/token/secret is written.
- `EntityEnrichmentRunner.kt` — the single explicit enrichment seam. It loads the
  real local entity scope, computes the deterministic local document, and only for
  an **explicit, fully-configured, user-triggered action with real candidates**
  invites the model (reusing the shared `ChatClient` and `ConsolidationModelConfig`
  via `EntityEnrichmentClientFactory`). The strictly-validated result is merged
  into a **new** enriched document that is **returned to the caller — it is not
  auto-persisted**; the existing artifact stays byte-for-byte unchanged unless the
  caller explicitly persists it. A failed / empty / local-only run preserves the
  prior valid document.
- **Zero network by default.** Unless configured + explicit action + real
  candidates, every disposition short-circuits to local-only (typed
  `LOCAL_ONLY`/`EMPTY`/`FALLBACK`) and never sends a request.

### Still not wired (honestly not ported)

- **Real WorkManager worker wiring** — the scheduler is an in-process JVM-testable
  seam; the Android app still has **no `WorkManager` dependency** and no
  `PeriodicWorkRequest` worker.
- **Explicit UI / Application wiring** — neither seam is reached from any UI entry
  point, `Application.onCreate`, or a screen; there is still no user-facing schedule
  or enrich action.
- **`DataPipelineModelClient` generic batch event-enrich / profile model-claims**
  — the entity-enrichment seam covers the Phase-33 entity-index scope only; the
  separate generic batch event-enrichment path and the profile model-claim path
  are not ported, and `workflow_scene_id` enrichment is not yet surfaced into
  query/recommendation surfaces.

### Verification (Phase 35)

Serial offline runs (`--offline --no-daemon --max-workers=1`, JDK 17.0.19, SDK
platform 34, Gradle 8.9):
1. Focused new classes `PeriodicConsolidationSchedulerTest` /
   `EntityEnrichmentTest` — green, recorded in `DSH_PHASE_RESULT.md`.
2. Existing `datamanager.*` suites (`CloudConsolidationTest`,
   `LocalConsolidationTest`, `ProfileIndexTest`, `EntitySceneIndexTest`,
   `FileDailyLogEventSourceTest`, …) re-run green.
3. Full suite: `:app:testDebugUnitTest` — **117 test classes / 922 tests, 0
   failures, 0 errors, 0 skipped**.
4. Build: `:app:assembleDebug` — target APK independent SHA256
   `dd9672c979a6a7b1c1cba6ff5bc1331828d3eab4bf20cf9c8b59f465edbdf425`.
5. `git diff --check` clean.

This phase changed documentation only (`MIGRATION_STATUS.md`; see also
`DSH_PHASE_RESULT.md`); no source, no tests, and no build outputs were changed.

## 22. Phase 36 — schedule wiring + explicit entity-enrichment UI + generic batch event enrichment

Phase 36 closes the three **real, previously un-wired** gaps that Phase 35 left
open, without repeating Phase 34/35:

### 1. Schedule wiring (minimal replaceable Android adapter, explicit-only)

- **No WorkManager dependency** exists in the offline Gradle cache (verified at
  phase start), and downloading it violates the offline/no-network rule. Instead
  this phase adds a **minimal replaceable `PeriodicTriggerAdapter`** (+
  `PeriodicScheduleResult` + `InProcessPeriodicTriggerAdapter`) that wraps the
  Phase-35 `PeriodicConsolidationScheduler` under the single canonical local task
  name, and an explicit `PeriodicSchedulingHost`.
- A new opt-in setting `periodicLocalConsolidationEnabled` (default **false**) is
  the only persisted switch. The **only** registration entry is the explicit
  Settings "应用调度" action (`schedulePeriodicLocalConsolidation`): `true`
  registers one `LOCAL_ONLY + NOT_REQUIRED` local task, `false` cancels it and
  schedules nothing. Constructing the host or the ViewModel never schedules — a
  persisted `true` is inert until the explicit action is clicked. Nothing runs on
  `Application.onCreate`, Home load, recomposition, or a repository/Application
  constructor.
- The scheduled job is `LocalOnlyPeriodicJob` (Phase-30/33/32 deterministic local
  consolidation / index / profile rebuild): serial, idempotent, cancellable,
  never calls `CloudConsolidationRepository`; unique task name; failure/cancel-safe.
- An explicit "立即运行一次本地重构" action (`runPeriodicLocalConsolidationNow`)
  triggers one run only when the task is actually scheduled and still opted-in.
- **Replaceability:** a future WorkManager `Worker` can implement the
  `PeriodicTriggerAdapter` contract without changing the host or app wiring.

### 2. Explicit model entity-enrichment wiring (caller-only persistence)

- The Phase-35 `EntityEnrichmentRunner` is now reachable from an explicit Settings
  action (`runEntityEnrichment`) with honest typed state
  (`enrichBusy` / `enrichResultMessage`); it never runs from launch/Home/
  recomposition and stays zero-network unless configured + explicit action + real
  candidates.
- A new caller-only persistence seam (`EntityEnrichmentPersistence`) writes the
  model-enriched `EntityDocument` **only on an explicit MODEL success**
  (`canPersist` checks status == MODEL and a non-null document). LOCAL_ONLY /
  FALLBACK / EMPTY never write and the prior artifact is preserved byte-for-byte.

### 3. Generic batch event enrichment (bounded typed seam)

- Ports the Harmony `DataPipelineModelClient.enrichEvents` path (keyword / domain /
  entity / todo extraction over real indexed events) into the Android app as
  `BatchEventEnrichTypes` + `BatchEventEnricher` + `BatchEventEnrichModelClient` +
  `BatchEventEnrichClientFactory`, reusing the shared `ChatClient` /
  `ConsolidationModelConfig`, plus deterministic `EnrichRules` rule helpers
  (`deriveRuleKeywords` / `deriveRuleDomains` / `normalizeDomains` /
  `workflowSceneLabels` / `weekdayLabelFromDate`, six-domain `DOMAIN_RULES`).
- The seam is **strictly-JSON**, real-`event_id`-scoped (a reply referencing a
  non-existent event id is dropped), strict domain (`eat|wear|live|travel|work|
  other`) / entity (`person|place|merchant`) / `todo.type` vocabularies, merges
  model terms with the deterministic rule baseline, falls back to a deterministic
  local enrich on any failure/malformed/unknown-id, is serial + cancellable,
  carries no URI/token/secret, and is **explicit-action only**; the result is
  returned keyed by real event id and **never auto-persisted**. An explicit
  Settings action (`runBatchEventEnrichment`) surfaces the typed outcome.
- The **profile model-claim** path is the Phase-32 `ProfileModelClient` /
  `ProfileModelValidator` (already ported); this phase documents it as the
  authoritative profile-claims implementation and does **not** duplicate it.

### Port-fidelity notes (honest deltas vs Harmony `enrichEvents`)

- The seam is **distinct from (not a re-port of) the Phase-35
  `EntityEnrichmentRunner`**: that seam clarifies the Phase-33 **entity index
  candidates** (record/source-ref scope), while this seam performs Harmony's generic
  `enrichEvents` path directly over the raw indexed events and is keyed by real
  `event_id`; both reuse the same `ChatClient`/`ConsolidationModelConfig` and
  strict-JSON/whitelist philosophy but operate on different inputs/outputs.
- `profile_domains` is **not** emitted (the Android `IndexedEvent` shape has no such
  field and the Phase-32 profile stack derives profile domains independently), so
  `EnrichedEventFields` omits it.
- Batching uses the **union of scene guides per chunk** (sequential chunks of 8)
  instead of Harmony's group-by-`todoGuideKey`; each scene rule + the shared "不要
  输出规则外的 type" tail still bound the prompt correctly.
- All domain/entity/todo normalization, the real `event_id` whitelist, the `other`
  coercion and the `type=buy`+pure-chat `agent_task` gate match the Harmony spec exactly.

### Verification (Phase 36)

Serial offline runs (`--offline --no-daemon --max-workers=1`, JDK 17.0.19, SDK
platform 34, Gradle 8.9):
1. Focused new classes `PeriodicSchedulingHostTest` / `BatchEventEnrichTest` /
   `EntityEnrichmentPersistenceTest` / `SettingsSchedulingEnrichTest` — green.
2. Full suite: `:app:testDebugUnitTest` — **121 test classes / 951 tests, 0
   failures, 0 errors, 0 skipped** (baseline 117 / 922 → +4 / +29).
3. Build: `:app:assembleDebug` — target APK independent SHA256
   `90393ea0775e4f404c84cbad58d7e4ccff8f2ce5bb18b7c5cb418fd8b1ada78c`.
4. `git diff --check` clean.

### Remaining gaps (honestly not ported)

- A **real Android WorkManager `Worker`/`PeriodicWorkRequest`** — intentionally not
  added (not in offline cache / would need a network download). The
  `PeriodicTriggerAdapter` is the replaceable seam for it.
- **Automated background cadence** — scheduling is still explicit-action-driven;
  no system-triggered background timer/cadence is registered.
- **Surfacing batch-enrich derived fields into query/recommendation/collection**
  — the Phase-36 batch event-enrichment seam returns a typed surface keyed by real
  event id; persisting those derived keywords/domains/todos into `IndexService` /
  Home recommendation / collection grouping is a future phase.

## 23. Phase 37 — explicit fact-splitting seam + schedule host contract

**What was delivered** (all under `app/src/main/java/com/clawmate/app/`):

- `datamanager/FactSplitTypes.kt` — bounded typed models: `SplitSourceScope`
  (the real `SourceEntry` scope the split accepts), `SplitFact` (the bounded
  `CandidateFact` surface), `FactSplitStatus` (`EMPTY` / `LOCAL_ONLY` / `MODEL` /
  `FALLBACK`), `FactSplitResult` (keyed by real `source_ref`, never auto-persisted),
  and bounds (batch 8, model cap, chars / facts caps).
- `datamanager/FactSplitRunner.kt` — the single explicit seam. Loads the real
  daily-log scope, computes the deterministic local split (zero-network baseline),
  and only for an explicit + fully-configured action invites the model through the
  existing Phase-34 `ConsolidationModelClient`. Refuses unknown / duplicate /
  out-of-scope `source_ref`; reports a genuine `FALLBACK` when the model reply
  fails / malforms (tracked via `onSourceFallback`); serial / cancellable.
- `datamanager/FactSplitClientFactory.kt` — production composition reusing
  `ConsolidationModelClientFactory` (same shared `ChatClient`).
- `datamanager/ConsolidationModelClient.kt` — added the missing singular
  `splitEntryIntoFacts`; faithful `event_date` fallback (non-ISO or > `source_date`
  → `source_date`) and model-fact atomicization (`normalizeFactPayloads` +
  `atomicizeCandidateFacts` parity); `onSourceFallback` now also fires on a request
  failure / malformed reply; extracted shared `deterministicHeuristicSplit` /
  `sharedAtomicClauses` helpers used by both the local and model paths.
- `settings/ui/SettingsViewModel.kt` + `SettingsViewModelFactory.kt` + `SettingsUiState.kt`
  + `SettingsSections.kt` — injectable `FactSplitRunner` and an explicit
  "日志事实拆分" Settings action (zero network unless configured + explicit; result
  returned, never auto-persisted).

**Safety & honesty invariants**

- Disabled by default; zero network unless explicit + fully-configured + real entries.
- Accepts only real daily-log source scope; the model can never invent a `source_ref`,
  an event date, or a fact.
- Malformed / failed model replies always fall back to the deterministic local split.
- Serial, cancellable; result returned for caller-only persistence; no URI / path /
  token / secret carried in or out.

**Verification (Phase 37)**

1. Focused new tests `FactSplitTest` (+ `PeriodicSchedulingHostTest` A3 additions and
   `SettingsSchedulingEnrichTest` additions) — green (serial offline).
2. Full suite `:app:testDebugUnitTest` and `:app:assembleDebug` — serial
   `--offline --no-daemon --max-workers=1` (numbers reported in the final result doc).
3. `git diff --check` clean.

**Remaining (honestly not ported)**

- **Real WorkManager worker / `PeriodicWorkRequest`** — `androidx.work` + several
  transitives (`lifecycle-service`, `sqlite-framework`, `annotation-experimental`,
  `listenablefuture`) are absent from the offline Gradle cache and would require a
  network download that conflicts with the repo's hard offline mandate. The
  `PeriodicTriggerAdapter`/`SchedulingPolicy` remain the replaceable contract.
- **Automated background cadence** — scheduling is still explicit-action-driven; no
  system-triggered timer is registered.
- **Persisting split facts downstream** — the Phase-37 seam returns candidate facts
  keyed by `source_ref`; feeding them into consolidation / a persisted fact store is
  a caller decision not yet wired.
- **Profile/entity enrichment** already covered by Phases 32/35/36 (not re-ported).

## 24. Phase 38 — downstream fact-split persistence + persisted batch event enrichment

**What was delivered** (all under `app/src/main/java/com/clawmate/app/`):

**Fact-split → persisted model consolidation (Task 1):**

- `datamanager/CloudConsolidationRepository.kt` — added
  `consolidateWithSplitFacts(explicitAction, modelConfig, modelClientProvider,
  splitFacts, isCancelled)` that reuses the **same** engine + persistence path as
  `consolidate` (grouping by month/file, `ModelConsolidationApplier.applyPending`,
  per-group state.json + markdown + state-index, `ConsolidationEngine.summarize`),
  but feeds the fact split from a caller-provided map keyed by real `source_ref`.
  A real entry whose `source_ref` is missing from the map falls back to the
  deterministic heuristic split (never dropped). The model/group loop was
  refactored into a shared `runModelGroups` so there is **no second consolidation
  engine**.
- `datamanager/FactSplitConsolidationCoordinator.kt` — the explicit seam that runs
  the Phase-37 `FactSplitRunner` (MODEL/LOCAL_ONLY/FALLBACK/EMPTY), converts the
  candidate facts into `CandidateFact`s, calls `consolidateWithSplitFacts`, then
  rebuilds the event index + profile from the freshly-persisted states. Returns a
  typed `CoordinatorResult` (MODEL / FALLBACK / LOCAL_ONLY / EMPTY + the persisted
  `ModelConsolidationResult`). Never runs from Application / Home / recomposition /
  a constructor — only an explicit caller.
- `datamanager/ConsolidationModelClientFactory.kt` — `factSplitConsolidation(baseDir)`
  production composition.
- `settings/ui/SettingsViewModel.kt` + `SettingsFactory` + `SettingsUiState` +
  `SettingsSections.kt` — injected coordinator + an explicit「事实拆分→模型整合并
  持久化」Settings action that persists outputs/index/profile on an explicit click.

**Persisted batch event-enrichment (Task 2):**

- `datamanager/BatchEventEnrichmentStore.kt` — versioned (`datamanager-event-
  enrichment-local-v1`), atomic, size-bounded artifact at
  `indexes/events/event-enrichment.json` holding per-event
  `PersistedEnrichedFields` (keywords/domains/entities/todo/enrichment_method) by
  real `event_id`. `canPersist` admits only a MODEL result; `write` persists only
  on an explicit MODEL success and never writes a URI/path/token/secret.
- `datamanager/BatchEventEnrichmentOverlay.kt` — pure read-side overlay that merges
  the persisted fields onto `IndexedEvent`s by real event id; unknown / absent ids
  keep the event unchanged (old behaviour).
- `datamanager/LocalConsolidationTypes.kt` — `IndexedEvent` gained the optional
  enrich fields (keywords/domains/todo/enrichment_method) with empty defaults so
  old Phase-30/33 JSON still decodes to an un-enriched event.
- `datamanager/DailyLogLocalIndex.kt` + `ProfileIndexBuilder.kt` — the event-query
  and profile read boundaries now overlay the persisted enrich fields after an
  explicit persisted MODEL success (absent artifact ⇒ old behaviour).
- `settings/ui/SettingsViewModel.kt` — `runBatchEventEnrichment` now persists the
  MODEL result through `BatchEventEnrichmentStore`.

**Safety & honesty invariants**

- Default disabled / zero network unless an explicit + fully-configured action and
  real input; nothing auto-runs from Application / Home / recomposition / a
  repository/VM constructor.
- Model output always strict-JSON + real-id-whitelisted; a failed / malformed /
  unknown-id run falls back to the deterministic local result and the prior valid
  persisted state is preserved.
- Fact-split = `MODEL` / `FALLBACK` (facts consolidated + persisted) / `LOCAL_ONLY` /
  `EMPTY` (nothing written). Batch enrich = `MODEL` (persisted) / `LOCAL_ONLY` /
  `FALLBACK` / `EMPTY` (never written).
- No URI / path / token / secret in any artifact; serial / cancellable /
  idempotent.

**Verification (Phase 38)**

1. Focused new tests `FactSplitConsolidationTest`, `BatchEventEnrichmentStoreTest`,
   plus `SettingsSchedulingEnrichTest` additions — green (serial offline).
2. Full suite `:app:testDebugUnitTest` — `BUILD SUCCESSFUL`, **124 test classes /
   984 tests, 0 failures, 0 errors, 0 skipped** (Phase-37 baseline 121 / 951).
3. `:app:assembleDebug` — `BUILD SUCCESSFUL`; APK debug SHA256
   `866b18424be84e3aad7fb2be579bd0050d9f1474b31c0cf92faadfc83c3d6669`.
4. `git diff --check` — clean.

**Remaining (honestly not ported)**

- **Real Android `WorkManager` `Worker` / `PeriodicWorkRequest`** — `androidx.work`
  and transitives remain absent from the offline Gradle cache (re-verified this
  phase), so no periodic worker was fabricated. The `PeriodicTriggerAdapter` /
  `SchedulingPolicy` remain the replaceable contract; the explicit Settings-driven
  schedule host is unchanged.
- **Automated background cadence** — still explicit-action-driven; no system timer.
- **`enrichedFields` surface consumed by the Home planner request directly** — the
  persisted enrich fields are currently read by the event-query / profile
  boundaries (the genuine read seams); folding them into the raw daily-log →
  Home-planner mapping (which operates on `DailyLogEntry`, not indexed events) is a
  further wiring step not required by this phase.

---

## 25. Phase 39 — Home planner consumes the persisted batch event-enrichment fields

**Goal.** Close the Phase-38 remainder: the persisted `BatchEventEnrichmentStore`
fields were already read by `DailyLogLocalIndex` / `ProfileIndexBuilder`, but the Home
recommendation planner still mapped raw `DailyLogEntry`s (no enriched keywords /
domains / todo). This phase wires the enriched `IndexedEvent` fields into the Home
planner's actual input chain so they feed recommendation / todo / plan generation.

**Home consumption chain (read-only, explicit user action only).**

- `HomeRecommendationFactory.realRepository(filesDir)` builds the repository with a
  `dailyLogEnrichment` resolver bound to `baseDir`.
- `GalleryBackedHomeRecommendationRepository.generate(photoItems, dailyLogEntries)`
  maps each `DailyLogEntry` to a bounded planner event via `DailyLogPlannerEventMapper`
  (raw, unchanged), then overlays the persisted enrichment via
  `DailyLogPlannerEventMapper.applyEnrichment`.
- `DailyLogEnrichmentResolver.resolveBySourceRef(entries)` loads the already-overlaid
  indexed events through the existing `ProfileIndexBuilder.loadEvents()` boundary (which
  runs the Phase-38 `BatchEventEnrichmentOverlay`), indexes `sourceRef → IndexedEvent`,
  and produces bounded `DailyLogPlannerEnrichment` (keywords/domains/todo) keyed by the
  real `sourceRef` — only when a real indexed event lists that `sourceRef` and carries
  non-blank enrich fields.
- The enriched keywords/domains/todo flow into the existing `HomeRecommendationPlanner`
  wire shape (`keywords`, `domains`, `todo_type`, `todo_deadline`, `todo_text`), matching
  Harmony `HomeRecommendationService` planner-event construction from enriched
  `IndexedEvent` fields.

**Old-behaviour / safety invariants preserved.**

- No artifact, corrupt artifact, unmatched `sourceRef`, or blank enrich fields ⇒ the
  entry maps with the raw daily-log mapping unchanged (old behaviour).
- Strict real-id lookup by `sourceRef == planner eventId` (the plan's `eventId`); a
  model-produced unknown id can never reach Home.
- Model boundary unchanged: enrichment is persisted only by an explicit Settings action;
  Home reads the already-written artifact only when the user taps「生成推荐」— never on
  launch / recomposition / background, and never triggers a model.

**Focused tests added (all offline over temp dirs):**

- `DailyLogEnrichmentResolverTest` — matching entry resolves keywords/domains/todo;
  no-match / empty / blank-fields yields nothing; end-to-end via a real deterministic
  index + persisted artifact.
- `HomeRecommendationEnrichmentIntegrationTest` — planner input carries enriched fields
  when artifact present; raw mapping (old behaviour) with no artifact; unknown-id
  enrichment cannot alter a planner event; `applyEnrichment` replaces raw keywords by
  real event id.

**Files changed.**

- New `datamanager/DailyLogEnrichmentResolver.kt`.
- `datamanager/DailyLogPlannerEventMapper.kt` — `applyEnrichment`.
- `home/recommendation/HomeRecommendationRepository.kt` — `dailyLogEnrichment` seam +
  overlay in `generate`.
- `home/recommendation/HomeRecommendationFactory.kt` — `realRepository(baseDir)`.
- `home/recommendation/HomeRecommendationTypes.kt` — `MAX_EVENT_DOMAINS`.
- `ui/ClawMateRoot.kt` — pass `context.filesDir` to `realRepository`.
- Tests above.

**WorkManager conclusion.** Re-verified this phase: `androidx.work` and its transitive
`lifecycle-service` remain **absent** from the offline Gradle cache (`sqlite-framework`
`2.4.0` and `annotation-experimental` are cached, but `androidx.work` + `lifecycle-service`
are not). Per the offline/no-network rule no dependency was downloaded and no
`Worker` / `PeriodicWorkRequest` was fabricated; `PeriodicTriggerAdapter` / `SchedulingPolicy`
remain the replaceable contract and all model/background paths stay explicit-action-only.

**Remaining risks / next steps.**

- Real Android `WorkManager` periodic worker (blocked on offline cache gap).
- Entities from enrichment are persisted and overlaid but are not in the planner wire
  shape (Harmony uses entities downstream for resolution); current planner input uses
  keywords/domains/todo only, which is what Phase-39 verifies.
- ENF enrich overlay is read lazily per explicit generate call (low traffic; fine for the
  low-memory single-thread profile).

---

## 26. Phase 41 — real daily-log data source (P0, work package A)

**Goal.** Close the P0 data-source gap from the Phase 40 audit (G1): nothing on Android
produced `workflows/daily-log/*`, so the entire consolidation → enrichment →
home-recommendation chain consumed empty (or pre-seeded-only) input. This phase makes the
Android **Workflow runner itself** produce a real, Harmony-format daily-log entry when a
workflow genuinely succeeds **and** exposes a real structured `summary` output — mirroring
Harmony `WorkflowRunner.appendDailySummaryLog` (header + numbered entries) that
`DailyLogParser` already parses.

**What was added (only real behaviors; nothing fabricated).**

- **`DailyLogWorkspace.appendEntry(baseDir, day, fileName, text, metadata)`** — a new
  Harmony-format single-writer append method (existing `writeDocument` import API is
  unchanged). Writes `baseDir/workflows/daily-log/YYYY-MM-DD/<name>.md` with a bounded
  `<!-- DAILY_LOG_METADATA { workflow_metadata, latest_entry_index } -->` header and
  blank-line separated `N. text` numbered entries. Repeat-runs into the same file
  **append** (stable numbered indices / source refs preserved) and climb
  `latest_entry_index`; every dimension is bounded (entry chars / file name / doc chars /
  entries-per-day) and the write is atomic (temp + rename).
- **`WorkflowRunnerImpl`** gained:
  - a real **`summary` tool step** (resolves a `text`/`summary`/`value` field from real
    workflow inputs/context over `${...}`), producing a bounded `resultSummary`;
  - a `WorkflowRunResult.resultSummary` / `WorkflowRunResult.dailyLogPath` bounded result
    fields;
  - a `FileWorkflowDailyLogSink` (over injected `baseDir`) wired as the single writer.
- On **successful** runs with a real (non-blank) summary, the runner appends the summary
  as a numbered entry with real metadata (workflow `name`/`description`). Failed /
  UNSUPPORTED / NOT_CONFIGURED / cancelled / no-summary / bare-`echo` runs write
  **nothing** — a plain debug echo or an error is never promoted to a daily-log summary.

**Production call chain (no new path / DB / format).**

- `TaskViewModel.run(task)` → `WorkflowRunnerImpl.run(definition)` (built by `TaskComponent`).
- On success+summary it writes `filesDir/workflows/daily-log/YYYY-MM-DD/<name>.md` via
  `FileWorkflowDailyLogSink` → `DailyLogWorkspace.appendEntry`.
- Home「生成推荐」(`ClawMateRoot.appPrivateDailyLogSource`) reads it via
  `DailyLogEventSourceFactory.appPrivate(context.filesDir).load()` → `DailyLogParser`, then
  `DailyLogPlannerEventMapper` feeds the planner. The writer is the **same single
  `DailyLogWorkspace`** the reader expects — nothing new.

**Focused tests added (all offline over temp dirs).**

- `DailyLogWorkspaceAppendTest` — header/`latest_entry_index` round-trip through
  `DailyLogEventSourceFactory`; repeat-append stable indices; bounds (blank, bad day/file,
  entry cap, per-day cap, distinct files).
- `WorkflowRunnerDailyLogWriteTest` — success+summary writes a parseable entry and returns
  the path; context resolution; repeat-append; BLOCKED GUI / NOT_CONFIGURED VLM / bare
  `echo` / blank-summary write nothing; no-sink runner never writes (spy).

**Honest deltas vs Harmony (kept).** Bundled GUI/VLM workflows still cannot reach SUCCESS on
Android (no HDC/VLM), so their runs stay BLOCKED/NOT_CONFIGURED and write no daily-log —
the writer only fires for a genuinely successful, summary-producing workflow. The runner
never fabricates VLM/screenshot content; negative cases are exercised in tests.

**WorkManager conclusion.** Unchanged and still out of scope: `androidx.work` remains absent
from the offline cache; no `Worker`/`PeriodicWorkRequest` was fabricated and no background or
periodic cadence was added in this phase.

## 27. Phase 42 — reachable, explicit Gallery scan/sync entry (P0/P1, work package B)

**Goal.** Close the Phase-40 audit gap (D3): the Android `MediaStoreGalleryMediaSource` +
`GalleryBackgroundCoordinator` + `GallerySyncGate`/`GallerySyncPolicy` pipeline already
existed but was **never constructed/interjected in production** — nothing wired the real
source, the coordinator, or the Room `GalleryRepository` into a runtime caller, and there was
no permission request or explicit scan trigger. This phase makes that seam **reachable as an
explicit user flow** without adding WorkManager / a second scanner / a new database.

**Harmony mapping.** `GalleryPage.ets` (`startAutoAnalyze`) requests `READ_IMAGEVIDEO` on the
explicit toolbar "自动分析" action, then `GalleryAutoScanner.scanAllImages` → `runScanSession`.
On Android the equivalent is now the Gallery screen「同步图库」button, which requests the media
permission by SDK level, then runs the serial, bounded `GalleryBackgroundCoordinator`

**What was added (real, explicit-only behaviours).**

- **Production wiring** in `GalleryComponent` — `mediaPermission(context)` (real
  `AndroidGalleryMediaPermission`), `mediaSource(context)` (real
  `MediaStoreGalleryMediaSource`), `scanGateProvider(context)` (live opt-in + permission gate),
  and `scanCoordinator(context)` (the **process singleton** `GalleryBackgroundCoordinator` with
  the real MediaStore source and the existing Room repository injected). Constructing it does
  **not** scan.
- **`GallerySyncOptinRegistry`** — mirrors the persisted Settings opt-in
  (`galleryBackgroundSyncEnabled`) onto a runtime flag at Settings save, matching the overlay
  opt-in pattern. Until the user explicitly opts in + saves, the gate is NOT_CONFIGURED.
- **`GalleryScanViewModel` + `GalleryScanUiState`** — the explicit `scanNow()` action: consults
  the coordinator's honest scheduling decision (NOT_CONFIGURED / BLOCKED / Coalesced / Run) and
  only starts a real scan on `Run`; surfaces each real `GallerySyncState` (Success/Partial/
  Error) without ever claiming a "cloud memory sync".
- **Gallery screen「同步图库」UI** — an explicit button that requests
  `READ_MEDIA_IMAGES` (API 33+) / `READ_EXTERNAL_STORAGE` (API ≤32) only on tap, then runs. A
  denied/non-granted request shows an honest BLOCKED state and never scans.

**Permission & explicit-entry guarantees.** No scan on `onCreate`, launch, recomposition or
Gallery-screen init (verified by an architecture regression test). The manifest already declared
both media permissions; they are never auto-requested. Photo-Picker least-privilege import is
unchanged. The optional cloud memory-sync step is **not** auto-invoked this phase — the
coordinator's cloud adapter stays null so it reports `SKIPPED_NOT_CONFIGURED` and sends no bytes;
a configured cloud path remains covered by the existing coordinator cloud tests.

**Focused tests added.** `GalleryScanViewModelTest` (6: permission-not-granted no-scan,
not-opted-in no-scan, authorized action writes to the real repository, idempotent re-run no
duplicates, cancel yields honest Partial, scan-limit clamp) and
`GalleryScanNoAutoEntryTest` (4: construction does not scan, opt-in registry defaults off, &
source-level Application/Component no-auto-entry checks). Total suite: **1019 tests, 0
failures, 0 errors** (Phase-41 baseline 984 → +10 new).

**Verification commands (ran offline via the system Gradle 8.9 under a workspace-local
`GRADLE_USER_HOME`, because the wrapper's `/root/.gradle` lock write is blocked by the file
sandbox).** `:app:testDebugUnitTest --offline` BUILD SUCCESSFUL (1019/0/0);
`:app:assembleDebug --offline` BUILD SUCCESSFUL; `git diff --check` clean. APK SHA256
`98d0562bc768cdc40f6bc4cb5d61ef7e0a4e2e045f8d46be82dfe698c70857a1`.

**Remaining risk / honest gaps.** WorkManager/periodic scheduling remains out of scope; the cloud
memory-sync path is not auto-reachable by design this phase (explicit-config boundary only);
the runtime opt-in flag is only synced on Settings save, so an opt-in changed but not saved will
not arm the gate (matching the "explicit opt-in" contract).

## 28. Phase 46 — wire periodic local consolidation to real Android OS scheduling (JobScheduler)

Phase 46 turns the Phase-35/36 in-process scheduling seam into a **genuinely reachable OS
schedule** without adding any dependency (no `androidx.work` in the offline cache, and adding it
is forbidden). It does **not** replace the existing in-memory seam (task #5) — it adds a real
JobScheduler-backed trigger path that reuses the same `LocalConsolidationIndexRunner`-backed
`LocalOnlyPeriodicJob`.

**Platform choice: JobScheduler.** `minSdk=26` / `compileSdk=34`, framework-only
`android.app.job.*` (no new artifact). Chosen over AlarmManager+BroadcastReceiver because
`JobInfo.setPersisted(true)` survives device reboot with no `RECEIVE_BOOT_COMPLETED` receiver and
no exact-alarm permission, and it batches for battery. Harmonie对照 (`/root/mobiinfra-oh`): the
Harmony source has **no automatic periodic consolidation scheduler** — consolidation is
explicitly triggered in `SettingsCenterPage.ets`/Home, and `backgroundTaskManager`
`DATA_TRANSFER` long-running mode is only taken *while an active agent task is executing*
(`EntryAbility.onBackground`), stopped on `onForeground`. So this Android periodic schedule is a
new, explicit, opt-in capability that the Harmony side never auto-fires.

**New platform pieces (all reuse the existing engine, no second consolidation logic):**
- `platform/JobSchedulerGateway.kt` — thin seam over `JobScheduler` (primitive `schedule(jobId, interval)`/
  `cancel`/`pendingJobIds`) so JVM tests use a fake and never touch the framework `JobInfo` POJO;
  `AndroidJobSchedulerGateway` builds the persisted periodic `JobInfo`
  (`setPersisted` + `setPeriodic` + `NETWORK_TYPE_NONE`).
- `JobSchedulerTriggerAdapter.kt` — a `PeriodicTriggerAdapter` implementation: keeps decisions in
  `PeriodicConsolidationScheduler`, and on an *enabled* `LOCAL_ONLY` policy also registers one
  persisted OS job under the single canonical `JOB_ID` (idempotent replace). A framework rejection
  returns `Disabled` + records `lastFailure` — it never fakes success. `cancel()` clears both.
- `PeriodicLocalConsolidationJobService.kt` — the `JobService` (registered in `AndroidManifest`
  with `BIND_JOB_SERVICE`, `exported=false`): gates on the persisted opt-in (cancels self if off),
  then runs `LocalOnlyPeriodicJob` serially, records an honest `JobRunOutcome`, and is cooperatively
  cancellable via `onStopJob`. Never touches `CloudConsolidationRepository`/network/chat/GUI/media/TTS.
- `PeriodicSchedulingRuntimeRegistry.kt` — the process-level opt-in mirror + honest run-outcome/
  error store (read synchronously by the background job).
- `PeriodicSchedulingRestoreCoordinator.kt` + `PlatformPeriodicSchedulingAccess` — restart/boot
  reconciliation: re-registers the persisted schedule when opt-in true ∧ no OS job; cancels a stale
  job when opt-in off; **never executes work at startup**.
- `PeriodicSchedulingBootstrap.kt` / `PeriodicPlatformHostFactory.kt` / `PlatformPeriodicScheduling.kt` —
  wiring/factory/constant holders.

**Wiring.** `SettingsViewModelFactory.forContext` now builds the host with the real JobScheduler
adapter (falls back to the in-process seam for device-free); `ClawMateApplication.onCreate`
runs non-blocking restore reconciliation on a background thread; `SettingsViewModel.save()` mirrors
the persisted opt-in into `PeriodicSchedulingRuntimeRegistry`.

**Verification (offline, JDK 17.0.19, SDK platform 34, Gradle 8.9, `--no-daemon --max-workers=1`,
workspace-local `GRADLE_USER_HOME` — the wrapper's `/root/.gradle` lock write is sandbox-blocked).**
`:app:testDebugUnitTest` BUILD SUCCESSFUL (**1082 tests, 0 failures, 0 errors**, +14 from
`JobSchedulerTriggerAdapterTest`); `:app:assembleDebug` BUILD SUCCESSFUL; `git diff --check` clean.
APK SHA256 `e7683c554f03a74b1415d10796922fc1deaa3c798f7f4f406c25da049df53a52`.

**Remaining honest gaps.** The JobService running path (on-device `onStartJob`/`onStopJob`, the
framework firing the persisted job after reboot) cannot be exercised in a JVM test; the
JVM suite covers the decision seam (opt-in gate, schedule/cancel/replace, failure honesty,
restore reconcile) via fakes and the result file states this boundary explicitly. The debug APK is
signed with the local debug keystore (workspace `.android/` shadowing the sandbox-read-only
`/root/.android`).

## 29. Phase 47 — bridge controlled Accessibility semantic executor to the agent GUI tool boundary

Phase 47 makes the already-existing, gated **safe semantic** AccessibilityService executor
(`SafeAccessibilityExecutor`, `AndroidSafeAccessibilityExecutor`, `AccessibilityAutoAgentService`,
allowlist `SAFE_ACTION_ALLOWLIST`) actually reachable from the chat agent's GUI tools, while keeping
the default behavior **rejected**.

**What is wired (only the closed safe allowlist, per the current executor).** The seven safe
semantic actions — `click_text`, `click_content_description`, `back`, `scroll_forward`,
`scroll_backward`, `global_back`, `global_home` — are exposed to the agent as `gui_*` tools
(`gui_click_text`, `gui_click_content_description`, `gui_back`, `gui_scroll_forward`,
`gui_scroll_backward`, `gui_global_back`, `gui_global_home`) via a new pure bridge
(`accessibility/GuiSafeActionBridge`) + live device gateway (`accessibility/LiveSafeActionGateway`).
The **coordinate/gesture/extraction** Phase-19 actions (`_screen_tap`/`type_text`/`swipe`/
`capture_screen`/`open_app`) are **NOT** on the safe allowlist and stay controlled-disabled; there
is no arbitrary TAP, raw gesture, shell / adb / hdc, screenshot upload, text injection or arbitrary
package control.

**Multi-gate kept on every invocation.** The action is performed only when ALL of: user's explicit
accessibility opt-in (`accessibilityOptIn`, reflected via `AccessibilityOptInHolder`),
session-level system AccessibilityService connected (`AccessibilityStateRegistry` +
`SafeActionServiceHolder` holding the live service), live foreground package allowlisted
(conservative `com.clawmate.app`), and the requested action on the closed allowlist with bounded
inputs. Any missing gate returns an explicit `NOT_CONFIGURED` / `BLOCKED_SERVICE` /
`BLOCKED_PACKAGE` / `UNSUPPORTED` result (never a fake success); default = rejected because the
opt-in defaults off.

**No information leakage.** Tool results are bounded (`≤160`-char de-identified messages, JSON with
only `ok`/`status`/`message`); success is a fixed semantic label — full `AccessibilityNodeInfo`,
screen text, screenshots, URIs or tokens are never written to logs, model context or the network,
and the GUI tool never auto-triggers network/model calls.

**Timeout/cancellation.** The node-bounded dispatch is wrapped in `withTimeout(10s)` on
`Dispatchers.Default` so it cannot block the agent main thread; a timeout folds into an honest
`ERROR`.

Harmonie对照 (`/root/mobiinfra-oh`): the reference `MobileGuiTaskTool.ets` / GUI workflow is
explicitly device/HDC/permission-gated (confirm callback + run callback, Planner/Decider config,
hdc bridge). Android's controlled Accessibility executor is the on-device, tightly-scoped analog
that brings only the safe semantic actions to the agent; HDC `run_cmd`/coordinate GUI remains
disabled.

## 30. Core UI regression pass — task icon parity

Compared the Android task card against Harmony `TaskPage.ets`: Harmony renders the complete
`task.icon` in a 54dp badge, while Android was truncating it with `take(1)` inside a 40dp badge.
Android now renders the complete icon at 54dp with the matching 13sp treatment. The settings
section letter badges were checked against Harmony and are already identical, so they were left
unchanged.

Verification (offline, JDK 17.0.19, SDK platform 34, Gradle 8.9):
`:app:testDebugUnitTest :app:assembleDebug` BUILD SUCCESSFUL; `aapt2 dump badging` confirms
`com.clawmate.app`, version `1.0.0`, target SDK `34`; manifest `icon` and `roundIcon` resolve to
the same `@mipmap/ic_launcher` resource. Latest APK SHA256:
`c5bf4d6d74fbbee6254773bb66dad66f3571480bd3e51e79166c7c51e9d651be`.

Remaining gap: no Android device/AVD is available in this environment for interactive screenshot
and crash verification; `adb` cannot start its daemon (`Operation not permitted`).

## 31. Core screen parity pass — Home and Gallery

Compared the Harmony home implementation (`HomePage.ets`) and added the four visible capability
entries below the default/avatar image: `理解你`, `守护你`, `陪伴你`, and `成就你`. Their 42dp
icon tiles, 66dp pills, labels, descriptions, and semantic colors now use Android vector
drawables derived from the corresponding Harmony media assets. The existing avatar persistence,
status, and local-image management actions remain unchanged.

The Android summary tab now uses the Harmony page title `图库分析` and subtitle
`相册、截图与文档图片洞察` instead of the generic `你的足迹`, while preserving the existing
gallery, collection, scan, search, and monthly-insight flows.

Verification (offline, JDK 17.0.19, SDK platform 34, Gradle 8.9):
`:app:testDebugUnitTest :app:assembleDebug` BUILD SUCCESSFUL; `git diff --check` clean;
`aapt2` confirms package `com.clawmate.app`, version `1.0.0`, target SDK `34`, and identical
manifest `icon`/`roundIcon` resource references. Latest APK SHA256:
`34a22cc5c587e1d1471310ec2daf47286747a434ac35f98d62639cd8d03fa5da`.

## 32. Core navigation parity pass

Replaced the default Material 3 `NavigationBar` with a fixed-size Compose bottom bar matching
Harmony `Index.ets` `TabItem`: each tab is 72dp wide and 58dp high, the icon is 21dp inside a
32dp selected circle, and the label is 13sp with the Harmony selected/unselected colors and
selected bold weight. The five routes and their click behavior are unchanged, including the
previous fix that avoids null `Destination` objects during the first frame.

Verification (offline, JDK 17.0.19, SDK platform 34, Gradle 8.9):
`:app:testDebugUnitTest :app:assembleDebug` BUILD SUCCESSFUL; `git diff --check` clean;
`aapt2` confirms package `com.clawmate.app`, version `1.0.0`, target SDK `34`, with `icon` and
`roundIcon` both resolving to `@mipmap/ic_launcher`. Latest APK SHA256:
`dad76394fd9db960158b395aef701f41296e422d2ae98388e29e14bf1e6accb3`.

The remaining verification gap is interactive device/AVD execution: this environment's `adb`
daemon cannot start (`Operation not permitted`), so screenshot, tab-tap and crash-free runtime
checks remain pending on a real Android device.

## 33. Chat screen parity pass

Compared `ChatPage.ets`: the Android chat header now places compact `云端`/`本地` mode pills
alongside `MobiAgent`, matching the Harmony 46x28dp pills instead of using full-width Material
filter chips below the header. The composer now follows Harmony's `+` + transparent multiline
input + single 82x40dp primary button structure. An empty composer invokes the existing guarded
voice-permission flow; text or pending attachments invoke the existing send flow. No cloud or
voice capability is fabricated.

Verification (offline, JDK 17.0.19, SDK platform 34, Gradle 8.9):
`:app:testDebugUnitTest :app:assembleDebug` BUILD SUCCESSFUL; `git diff --check` clean;
`aapt2` confirms package `com.clawmate.app`, version `1.0.0`, target SDK `34`, and both manifest
icon attributes resolve to `@mipmap/ic_launcher`. Latest APK SHA256:
`3c9d44524c8b7de3f5a2628a9fc3d6bac2b3d6b4cee3b2f32a0b5c4a1510d6fe`.

Interactive chat launch, voice permission, send, and tab-tap checks still require a real Android
device/AVD because the available `adb` daemon cannot start in this environment.

## 34. Task execution header parity and final rebuild

Added the Harmony execution-state header to the Android task page: `任务执行` plus the live
`已开启 x / 全部 y` count appears before scene filters and task cards. The Android-only page
does not show a non-functional `配置任务` button; configuration remains an explicit platform
gap rather than a fake action.

The first verification attempt stopped during D8 with `No space left on device`. Only generated
outputs and caches were removed (`app/build`, project `.gradle-local`, and temporary test data;
source and Git files were untouched), after which the complete command succeeded.

Final verification (offline, JDK 17.0.19, SDK platform 34, Gradle 8.9):
`:app:testDebugUnitTest :app:assembleDebug` BUILD SUCCESSFUL; `git diff --check` clean;
`aapt2` confirms package `com.clawmate.app`, version `1.0.0`, target SDK `34`, and both manifest
icon attributes resolve to `@mipmap/ic_launcher`. Final APK SHA256:
`1779eec7c4eb69c152ec016af6e14bf5684d778564a473b63835f59289d7c3f2`.

Interactive device/AVD checks remain unavailable because the local `adb` daemon cannot start;
the final runtime gap is unchanged.

## 35. Task scene grouping parity

When no scene filter is selected, the Android task list now renders each non-empty workflow scene
as a Harmony-style section: scene title, live `开启数/总数` badge, then that scene's task cards.
The existing filter, toggle, expand, run, and refresh actions remain unchanged; empty scenes are
omitted just as the Harmony execution list omits scenes with no tasks.

Verification (offline, JDK 17.0.19, SDK platform 34, Gradle 8.9):
`:app:testDebugUnitTest :app:assembleDebug` BUILD SUCCESSFUL; `git diff --check` clean;
`aapt2` confirms package `com.clawmate.app`, version `1.0.0`, target SDK `34`, and matching
manifest `icon`/`roundIcon` references. Latest APK SHA256:
`9f6f5ec5826aefbdf15e4b59980e4e66d08e94ee4ca0151323d39e27d1c17444`.

The APK has not been interactively launched here because no usable Android device/AVD is
available and the local `adb` daemon fails to start.

## 36. Gallery overview action parity

Aligned the gallery overview first viewport with the Harmony action toolbar: the summary now
shows the existing `选择图片` picker and `同步图库` action below the live stats. Both actions
reuse the existing picker and synchronization permission/opt-in gate, so the page does not
advertise fake clear or analysis operations. The scan section uses the same guarded action path.

Verification (offline, JDK 17.0.19, SDK platform 34, Gradle 8.9):
`:app:testDebugUnitTest :app:assembleDebug` BUILD SUCCESSFUL; `git diff --check` clean;
`aapt2` confirms package `com.clawmate.app`, version `1.0.0`, target SDK `34`, and matching
manifest `icon`/`roundIcon` references. Latest APK SHA256:
`07ff44d4576a01b2ff98b7c9f07f080db6ecc6912c8e96dbe4d55ce4ef67b50d`.

Interactive device/AVD checks remain unavailable because the local `adb` daemon cannot start;
chat launch, bottom-navigation taps, task loading, gallery actions, and settings navigation
still need to be exercised on a connected Android runtime.

## 37. Core regression audit and delivery verification

Re-audited the current navigation shell, chat ViewModel/voice initialization, task repository
recovery path, gallery scan gate, settings section routing, and Harmony-derived launcher asset.
No additional source change was required in this pass: the known null-safety and malformed-index
guards remain present, and the launcher bitmap matches the Harmony `Icon.png` reference.

Final verification (offline, JDK 17.0.19, SDK platform 34, Gradle 8.9):
`:app:testDebugUnitTest` BUILD SUCCESSFUL, 1126 tests with 0 failures, 0 errors, and 0 skipped;
`:app:assembleDebug` BUILD SUCCESSFUL; `git diff --check` clean. `aapt2` confirms package
`com.clawmate.app`, version `1.0.0`, target SDK `34`, and both manifest icon attributes resolve
to the same launcher resource. APK SHA256:
`07ff44d4576a01b2ff98b7c9f07f080db6ecc6912c8e96dbe4d55ce4ef67b50d`.

Runtime verification remains open: this environment has neither the `android` CLI nor `adb`,
and no device/AVD is available for screenshot comparison or click-by-click crash regression.
Android-only HDC/GUI automation, background gallery access, and native model capabilities remain
explicit adapters/disabled states where HarmonyOS APIs have no direct Android equivalent.

## 38. Home avatar hero layout parity

Compared `HomePage.ets` against `HomeScreen.kt` and corrected a confirmed first-viewport mismatch:
the Android avatar hero now uses the same full-width `#EDF5FF` section, centered 92%-width avatar,
270dp image height, 92%-width two-row capability grid, and clickable status row as HarmonyOS.
The Android-only duplicate `数字分身 · 管理本地画像` button was removed; the status row remains the
single entry to the existing Android avatar management screen. Capability card padding and icon/text
spacing now match the source values.

Verification (offline, JDK 17.0.19, SDK platform 34, Gradle 8.9):
`:app:testDebugUnitTest :app:assembleDebug` BUILD SUCCESSFUL; 1126 tests with 0 failures, 0 errors,
and 0 skipped; `git diff --check` clean. `aapt2` confirms package `com.clawmate.app`, version
`1.0.0`, target SDK `34`, and matching manifest `icon`/`roundIcon` references. Latest APK SHA256:
`4004d7b81b1d4554b71e1a45c557cc53b06e5a8da4fbd98ae21e42812bd84f78`.

Interactive screenshot and click regression remains unavailable because this environment has no
`adb`, Android CLI, device, or AVD. The remaining platform differences listed above are unchanged.

## 39. Chat header and composer parity

Compared `ChatPage.ets` with `ChatScreen.kt` and aligned two visible structures: the Android chat
header now matches HarmonyOS `MobiAgent + mode tabs + 日志` without an extra session-title row,
and the composer now uses the HarmonyOS white rounded surface, 20dp horizontal inset, 22dp bottom
inset, 26dp radius, shadow, and internal 12dp padding. Existing send, voice-permission, attachment,
streaming, and error handling paths are unchanged.

Verification (offline, JDK 17.0.19, SDK platform 34, Gradle 8.9):
`:app:testDebugUnitTest :app:assembleDebug` BUILD SUCCESSFUL; 1126 tests with 0 failures, 0 errors,
and 0 skipped; `git diff --check` clean. Latest APK SHA256:
`78515aa977e67ce8e42bccbf951d731a0952b85269ec6c952aa6bb3a6c51e767`.

Real device/AVD screenshot and tap regression remains unavailable because this environment has no
`adb` or Android CLI. The chat crash fix is covered by source-level initialization and JVM contract
checks, but cannot be claimed as device-runtime verified here.

## 40. Settings section list parity

Compared the HarmonyOS `SettingsSectionList` and `SettingsDetailHeader` with Android. The Android
settings list now centers the five source sections at 92% width with 12dp inter-item spacing, and
the detail header uses the source's 92% width and 18dp top spacing. Section order and titles remain
the same; Android-only WeChat/log capabilities stay reachable from their owning detail sections
without changing the Harmony five-item first screen.

Verification (offline, JDK 17.0.19, SDK platform 34, Gradle 8.9):
`:app:testDebugUnitTest :app:assembleDebug` BUILD SUCCESSFUL; 1126 tests with 0 failures, 0 errors,
and 0 skipped; `git diff --check` clean. Latest APK SHA256:
`2ff1d4a932c5970dabcc6cb5dfa96d11d5c2ff2cef2ad9c5a3634b279bff9f2f`.

Device/AVD screenshot, settings-section taps, and crash-free runtime checks remain unavailable in
this environment because `adb` and the Android CLI are not installed.

## 41. Summary collection route parity

Compared the HarmonyOS `CollectionPage.ets` with Android navigation and corrected a root mapping
error: HarmonyOS `汇总` opens `你的足迹`, while Android had routed it directly to `图库分析`.
Added a real `CollectionSummaryScreen` with the Harmony-style header, scene tabs, centered 86%-width
stats/sync card, indexed collection panel, and action cards. `图库分析` now opens the existing
gallery analysis page as a separate action, while `新增采集源` opens the real Android settings
center. Indexed collection data remains local and explicit; no fake model or network result was added.

Verification (offline, JDK 17.0.19, SDK platform 34, Gradle 8.9):
`:app:testDebugUnitTest :app:assembleDebug` BUILD SUCCESSFUL; 1126 tests with 0 failures, 0 errors,
and 0 skipped; `git diff --check` clean. `aapt2` confirms package `com.clawmate.app`, version
`1.0.0`, target SDK `34`, and matching manifest `icon`/`roundIcon` references. Latest APK SHA256:
`20abdc7b6cda2d7ba8c0fa4b75a267a3a42246b2b348ea72efc7cb9250f269f4`.

Device/AVD screenshot, summary-card taps, and crash-free runtime checks remain unavailable in this
environment because `adb` and the Android CLI are not installed.

## 48. Summary and task visual regression parity

Compared the Android summary and task first viewports with HarmonyOS `CollectionPage.ets` and
`TaskPage.ets`. Summary scene tabs now use the source scene colors, 38dp tab height, and the
source's selected/unselected text treatment; the sync action is now the source 44dp lavender
circle instead of a Material outlined button. Task cards now use the source's 86%-width centered
layout while retaining the existing real workflow loading, execution, cancellation, and result
states. These are UI-only changes and do not fabricate unsupported task-editor capabilities.

Added a JVM contract test for the summary scene order/labels/selected colors and included this
comparison in the existing build gate: the full offline
`:app:testDebugUnitTest :app:assembleDebug` completed successfully with 1129 tests, 0 failures,
0 errors, and 0 skipped; `git diff --check` passed. APK SHA256:
`dbc11bdaccef6470f19fa3d37b38681237d3a9a4bb2bd172f18836965859ceb9`.

Device/AVD screenshot and tap verification remain unavailable because `adb` and the Android CLI
are not installed in this environment.

## 49. Chat configuration recovery flow

Compared Android `ChatScreen.kt` with HarmonyOS `ChatPage.ets`. When the cloud model is not
configured, Android still keeps the state honest and blocks sending, but the previous dead-end
message has been replaced with an explicit `打开设置中心` action wired through `ClawMateRoot` to
the real settings tab. This preserves the source's actionable configuration flow without claiming
that an unconfigured endpoint is available.

Verification (offline, JDK 17.0.19, SDK platform 34, Gradle 8.9):
`:app:testDebugUnitTest :app:assembleDebug` BUILD SUCCESSFUL; 1129 tests with 0 failures, 0 errors,
and 0 skipped; `git diff --check` clean. `aapt2` confirms package `com.clawmate.app` and both
Manifest icon attributes resolve to `@mipmap/ic_launcher`. Latest APK SHA256:
`0d3bd97d90d304cd0a71e28e0ab78e9568edd41a9e790b32a031c87dda3c961`.

Device/AVD screenshot, navigation taps, and crash-free runtime checks remain unavailable because
`adb` and the Android CLI are not installed in this environment.

## 51. Final packaged-resource parity audit

Compared the packaged Android resources with the HarmonyOS source assets. The launcher bitmap
`app/src/main/res/drawable/ic_launcher_brand.png` is byte-identical to
`/root/mobiinfra-oh/AppScope/resources/base/media/Icon.png`, and
`digital_avatar.png` is byte-identical to the Harmony default avatar. The final APK contains the
adaptive/fallback launcher resource, all five tab icons, and the default avatar; Manifest `icon`
and `roundIcon` both resolve to `@mipmap/ic_launcher`. No source or resource replacement was
justified by this audit.

Remaining platform differences are explicit: Android uses Photo Picker/MediaStore, Android
TextToSpeech/SpeechRecognizer, JobScheduler, and controlled Accessibility adapters; Harmony-only
HDC/GUI/native integrations remain disabled or report `UNSUPPORTED`/`BLOCKED` rather than being
shown as connected. Runtime screenshots/taps remain unavailable here because `adb` and the Android
CLI are not installed.

## 52. Runtime verification environment audit

The SDK does include `/opt/deepseek-harness/android-sdk/platform-tools/adb`; the earlier status
wording saying that `adb` was not installed was imprecise and is superseded by this entry. After
running the SDK binary with the required host permission, `adb devices -l` starts successfully but
returns an empty device list. No emulator binary/AVD is available in this environment, so APK
installation, launch, navigation taps, screenshots, and device Logcat cannot be executed here.
Static APK/resource checks and the full JVM test/build gate remain the available verification.

## 50. Crash-path null-safety audit

Audited the previously reported chat voice initialization path and adjacent core-page async states.
The voice state keeps a non-null default before its first emission; the summary/gallery detail
screens now snapshot nullable detail state before rendering; gallery stored-address restoration
uses an explicit nullable branch; settings enrichment persists only through a verified persistence
target; and malformed SSE choices now produce the typed `SseParserException` path instead of any
forced non-null access. Added a regression test for a non-object SSE choice.

Verification (offline, JDK 17.0.19, SDK platform 34, Gradle 8.9):
`:app:testDebugUnitTest :app:assembleDebug` BUILD SUCCESSFUL; 1130 tests with 0 failures, 0 errors,
and 0 skipped; `git diff --check` clean. `aapt2` confirms package `com.clawmate.app`, target SDK 34,
and both Manifest icon attributes resolve to `@mipmap/ic_launcher`. Latest APK SHA256:
`677ac53ff504a34e77f20dc5b42ca08698946b49da2c7e844d233e2327f3b664`.

Device/AVD screenshot, navigation taps, and crash-free runtime checks remain unavailable because
`adb` and the Android CLI are not installed in this environment.

## 46. Home avatar status card parity

Compared the HarmonyOS `HomePage.ets` avatar hero with Android. The Android digital-avatar status
row now matches the source's 48dp white rounded pill, 16dp vertical spacing, shadow, horizontal
padding, and bold status text. The default `digital_avatar.png` was verified byte-for-byte against
`/root/mobiinfra-oh/entry/src/main/resources/base/media/digital_avatar.png` (same SHA256), so the
default image resource was retained rather than replaced. The four capability assets and existing
avatar-management click flow remain unchanged.

Verification (offline, JDK 17.0.19, SDK platform 34, Gradle 8.9):
`:app:testDebugUnitTest :app:assembleDebug` BUILD SUCCESSFUL; 1127 tests with 0 failures, 0 errors,
and 0 skipped; `git diff --check` clean. Latest APK SHA256:
`e7855439aebd7798c040c2055650aed7293a5e3f3e7e8ac2c186b4dacac65961`.

Device/AVD screenshot, home taps, and crash-free runtime checks remain unavailable in this
environment because `adb` and the Android CLI are not installed.

## 45. Settings detail control parity

Compared the HarmonyOS `SettingsDetailHeader` with Android. The Android detail-page back control
now uses the source's 42dp white rounded button, source title color, and matching 18dp top spacing;
the five primary settings sections, order, subtitles, and blue-tinted page palette remain aligned
with the HarmonyOS list. Android-only WeChat and runtime-log sections remain behind their existing
owned flows rather than being added to the first viewport.

Verification (offline, JDK 17.0.19, SDK platform 34, Gradle 8.9):
`:app:testDebugUnitTest :app:assembleDebug` BUILD SUCCESSFUL; 1127 tests with 0 failures, 0 errors,
and 0 skipped; `git diff --check` clean. Latest APK SHA256:
`0a037c1fa8f315092f5e6169d0279e48028a59c79dd8f8764decf4404c3bf78d`.

Device/AVD screenshot, settings taps, and crash-free runtime checks remain unavailable in this
environment because `adb` and the Android CLI are not installed.

## 47. Startup and packaged-resource audit

Audited the Android startup path, manifest, onboarding gate, application initialization, and
launcher resources against the migration target. `MainActivity` is the exported launcher activity,
`ClawMateApplication` uses safe empty endpoint defaults and isolates periodic-job restoration on a
background thread, and onboarding indices are clamped before use. The packaged APK reports package
`com.clawmate.app`, target SDK 34, and both `android:icon` and `android:roundIcon` resolve to
`@mipmap/ic_launcher`; the APK also contains the brand launcher bitmap and default digital-avatar
resource. No additional source change was justified by this audit.

Verification (offline, JDK 17.0.19, SDK platform 34, Gradle 8.9): previous full build remains
`BUILD SUCCESSFUL`; 1127 tests with 0 failures, 0 errors, and 0 skipped; `aapt2 dump badging`,
`aapt2 dump xmltree`, `aapt2 dump resources`, and `git diff --check` completed successfully. APK
SHA256: `e7855439aebd7798c040c2055650aed7293a5e3f3e7e8ac2c186b4dacac65961`.

Device/AVD screenshot, navigation taps, and crash-free runtime checks remain unavailable in this
environment because `adb` and the Android CLI are not installed.

## 44. Gallery analysis entry and first-viewport parity

Compared the HarmonyOS `GalleryPage.ets` entry with Android. The gallery page is now a distinct
screen opened from `汇总 > 图库分析`, with a Harmony-style 90%-width header containing back and
settings actions, the `图库分析` title/subtitle, and category filters moved into the content area
instead of appearing as a collection-page header. Back returns to the real Android summary screen;
settings uses the existing settings route. Photo Picker, MediaStore scan gates, Room-backed gallery
items, OCR/vision capability states, and error recovery remain unchanged and honest.

Verification (offline, JDK 17.0.19, SDK platform 34, Gradle 8.9):
`:app:testDebugUnitTest :app:assembleDebug` BUILD SUCCESSFUL; 1127 tests with 0 failures, 0 errors,
and 0 skipped; `git diff --check` clean. Latest APK SHA256:
`49de34e177570727fcba731a4b380b7f4aec15adee27b45f15a426359699c5e2`.

Device/AVD screenshot, gallery taps, and crash-free runtime checks remain unavailable in this
environment because `adb` and the Android CLI are not installed.

## 43. Chat header, welcome state, and task handoff parity

Compared the HarmonyOS `ChatPage.ets` first screen with Android. The Android chat header now uses
the source's 22dp top / 18dp horizontal spacing and 18sp `MobiAgent` title, the empty welcome
content uses the source's 28dp side inset, and the composer uses a real 22dp bottom margin outside
the rounded surface. Added the visible `下发任务` action above the composer; it navigates to the
real Android task tab through the root shell. Unsupported Harmony-only deep-thinking and PC-server
states remain unadvertised rather than being represented as fake capabilities.

Verification (offline, JDK 17.0.19, SDK platform 34, Gradle 8.9):
`:app:testDebugUnitTest :app:assembleDebug` BUILD SUCCESSFUL; 1127 tests with 0 failures, 0 errors,
and 0 skipped; `git diff --check` clean. Latest APK SHA256:
`03bcfccfc5d1f3eecef1c4f91941b5f1959ab26a3df36d6fbf3dc921ae3104e1`.

Device/AVD screenshot, chat taps, and crash-free runtime checks remain unavailable in this
environment because `adb` and the Android CLI are not installed.

## 42. Task execution layout and batch regression

Compared the HarmonyOS task execution branch in `TaskPage.ets` with Android. The Android
execution screen now removes the extra execution-state scene filter, uses the source's 86%-width
task execution and batch cards, keeps scene-grouped task rows, and adds a real batch execution /
stop action backed by the existing parser and bounded workflow runner. No device action is faked:
unsupported GUI/HDC steps continue to surface their honest blocked result. Added
`TaskViewModelTest` to verify loading, ordered batch execution, result persistence, and final state.

Verification (offline, JDK 17.0.19, SDK platform 34, Gradle 8.9):
`:app:testDebugUnitTest :app:assembleDebug` BUILD SUCCESSFUL; 1127 tests with 0 failures, 0 errors,
and 0 skipped; `git diff --check` clean. Latest APK SHA256:
`120d00e88e6943b955c725f8dad7adb198bb6157a40070c31b81abc1dce7eccd`.

Device/AVD screenshot, task taps, and crash-free runtime checks remain unavailable in this
environment because `adb` and the Android CLI are not installed.

## 48. Shared visual tokens and worktree synchronization

Added `ClawMateDesign` as the shared Android visual contract for the HarmonyOS-aligned page
background, surfaces, purple accent, text colors, borders, control/card radii, input/button sizes,
and spacing scale. The global Material theme and settings theme now consume the shared tokens.
The gallery and task UI changes from `fix/task-ui` were committed and fast-forwarded into `main`.

Verification (JDK 17.0.19, SDK platform 34, Gradle 8.9): `:app:assembleDebug` BUILD SUCCESSFUL;
`git diff --check` clean. APK installation via `adb install -r` succeeded on the connected device.
Visual runtime verification is pending because the device entered its lock screen after install.

## 49. HarmonyOS theme token correction

Refined the shared token layer against the authoritative HarmonyOS settings theme: primary action
blue is `#4F68FF`, purple is an explicit accent, settings cards use 18dp corners, controls use
12dp corners, and settings fields/buttons retain the source 42dp/40dp sizing. Settings controls
now consume the shared dimensions instead of duplicating them.

Verification: `:app:assembleDebug` BUILD SUCCESSFUL; `git diff --check` clean. Device visual
verification remains pending while the connected phone is locked.

## 50. Core page token adoption

Applied the shared Harmony-aligned page background and header tokens to Home, Collection Summary,
Task, and the common page header. Semantic content colors remain page-specific; shell surfaces now
use the same background, title, secondary text, and accent roles.

Verification: `:app:assembleDebug` BUILD SUCCESSFUL; `git diff --check` clean. The current APK was
installed successfully with `adb install -r`; screenshots remain blocked by the device lock screen.

## 51. Core page device verification

With the connected phone unlocked, installed the current debug APK and visually verified the
Home, Collection Summary, Chat, Task, Settings, and Model & Agents entry pages. The chat
unconfigured state is readable and actionable, the settings model form uses full-width vertical
fields, and a scroll to the bottom exposes the final model and HDC controls above the navigation
bar. No startup crash was observed during this pass.

Verification: `adb install -r` succeeded; Android CLI screenshots and layout inspection completed
for the listed routes.

## 53. Task configuration handoff verification

Verified the task execution header's `配置任务` action on the connected device. It now opens the
real Settings screen with `任务与自动化` selected, exposing the existing Workflow, Agent, HDC,
and AutoAgent controls instead of leaving the action inert. Kept the shell muted-text token
separate from the settings secondary-text token after the HarmonyOS source comparison.

Verification: `adb install -r` succeeded; task page UI dump showed `配置任务`; the tapped route
showed `设置中心 / 任务与自动化` and the expected controls. `:app:testDebugUnitTest` then exposed
and was fixed for the shell palette mismatch (`#747B87`).

## 54. Collection action entry verification

Verified both summary-page action cards on the connected device. `图库分析` opens the real gallery
analysis screen with image selection and analysis controls; `新增采集源` opens the real Settings
Center and exposes the `数据采集` section. Both routes preserve the shared shell navigation and
return affordances.

The action-card surface was also made explicitly white, matching the other Android cards and the
HarmonyOS source; purple is retained only for the per-card accent, border, and arrow.

Verification: Android UI dumps and screenshots confirmed the destination titles and controls after
each tap; no crash observed.

## 55. Shared white card contract

Unified the summary action cards with the app's other card surfaces and the HarmonyOS source. The
shared `ClawMateDesign.Card` contract now defines a white surface, 18dp radius, 12dp padding, 1dp
border, and 3dp shadow; summary cards use it directly. The existing summary UI contract test locks
these values to prevent a later purple tonal surface regression.

Verification: `:app:testDebugUnitTest :app:assembleDebug` BUILD SUCCESSFUL; `adb install -r`
succeeded; the connected-device summary screenshot shows white card interiors with only purple
accent borders/icons/arrows.

## 56. Gallery action state completion

Removed the gallery page's inert `加载更多` control because the current result projection already
renders the complete filtered set and has no pagination state. Implemented a confirmation flow for
`清空分析`; it is disabled when there are no stored items and, when confirmed, deletes only the
app's imported gallery records, never the original device photos.

Verification: `:app:testDebugUnitTest :app:assembleDebug` BUILD SUCCESSFUL; the latest APK installed
successfully and the connected-device empty gallery state shows the disabled clear action with no
fake pagination control.

## 57. WeChat collection action wiring

Connected the enabled WeChat collection status action to the existing typed
`WechatCollectionGate.collect` boundary. The settings UI now reports collecting, success, blocked,
transport, unsupported, and bridge-error messages; it never calls arbitrary commands or drives
WeChat directly. When the bridge is absent or the opt-in is off, the action remains disabled with
the existing honest status text.

Verification: `:app:testDebugUnitTest :app:assembleDebug` BUILD SUCCESSFUL; the latest APK was
installed and the settings page UI dump confirmed the default `NOT_CONFIGURED` boundary is
disabled with no automatic request.

## 52. Collection summary visual cleanup

Aligned the Android summary page's indexed-record panel and action cards with the HarmonyOS
collection layout. The panel now uses the 86%-width light surface and concise local-index state
copy; action cards keep icon, title, arrow, and two-line description in the same compact hierarchy,
preventing the previous title/description crowding and clipping.

Verification: `:app:assembleDebug` BUILD SUCCESSFUL; `adb install -r` succeeded; the summary route
was opened on the connected device and visually inspected from a fresh launch. The updated cards
show readable titles and descriptions above the bottom navigation bar.

## 58. Status-only settings actions

Marked Planner and HDC connection state rows as disabled because they report state only. Explicit
operations remain available through the HDC health test and the opt-in WeChat collection action.

Verification: pending build and connected-device check.

## 59. Model settings visual alignment

Matched the Android model settings field height to the HarmonyOS 42dp control
contract and placed the local-model capability group before remote Planner/Decider
settings, matching the HarmonyOS information hierarchy. Existing honest disabled
states remain unchanged.

Verification: `:app:testDebugUnitTest :app:assembleDebug` BUILD SUCCESSFUL with
1134 tests passing; debug APK install succeeded. Final visual inspection was
blocked by the connected phone requiring manual unlock after installation.

## 60. Shared input control adoption

Adopted the shared `ClawMateInput` contract across settings, gallery search and
detail editing, log filtering, and digital-avatar editing. The control keeps the
HarmonyOS 42dp field height, 12dp radius, input fill, disabled fill, border, and
label/placeholder treatment in one place; the theme contract test now locks the
shared dimensions.

Verification: `:app:testDebugUnitTest :app:assembleDebug` BUILD SUCCESSFUL with
1135 tests passing; debug APK install succeeded and cold launch reached
`MainActivity` without `AndroidRuntime` errors. The final settings-page screenshot
was blocked by the connected phone locking again.

## 61. Onboarding hero illustration parity

The three capability preview slides rendered only a flat colored circle even
though the data contract already carried the HarmonyOS `mediaResourceName`
(`onboarding_data_home` / `onboarding_avatar` / `onboarding_agent`). The brand
illustration PNGs had never been copied into Android, so the first-launch hero
—the visual centerpiece—was missing. Copied the three exact HarmonyOS brand
bitmaps into `res/drawable/` and rendered the hero image over the soft circle in
`FirstLaunchOnboardingScreen.PreviewSlidePage`, matching the HarmonyOS `Stack`
(soft circle behind, `ImageFit.Contain` illustration filling the card). Also gave
the primary button its HarmonyOS 34dp bottom margin (it previously sat flush to
the screen edge). The Android-only `跳过` (skip) affordance is an intentional,
tested addition and is left in place.

Verification: `:app:testDebugUnitTest :app:assembleDebug` BUILD SUCCESSFUL with
1135 tests passing (0 failures / 0 errors / 0 skipped). On-device visual
verification is pending — no device was connected at this time, so the rendered
onboarding slides have not yet been confirmed on a real screen.

## 62. Chat digital avatar parity

HarmonyOS renders an *animated* digital avatar in chat (`DigitalAvatarView.ets`):
a layered ring / ground shadow / gloss highlight / voice-state wave bars, pulsing
every 800 ms while the voice session is listening / thinking / speaking, plus a
tap bounce. Android showed only a static `R.drawable.digital_avatar` in the
welcome and *no* avatar on message bubbles — the one true visual gap in the
aligned five-tab core.

Ported it in two layers so the HarmonyOS numbers stay testable:
- `chat/voice/DigitalAvatarVisual.kt` — a pure (Android-free) contract: the
  voice-status → visual-state mapping, the per-message render model
  (user `我` / notice `压` / task `任` / assistant-chat animated `助`), every
  dp metric, and the derived ring/wave/foreground/shadow formulas with the exact
  HarmonyOS constants.
- `chat/ui/DigitalAvatar.kt` — a thin Compose renderer that reads only from that
  contract. An 800 ms pulse runs **only** while the state is genuinely animated
  and has content; non-animated targets `snapTo` so a voice that goes quiet
  always settles. Values are driven with `Animatable` re-targeting from the
  current value (this Compose BOM predates `animateFloatAsState`).

Wired into `ChatScreen`: the welcome now shows the hero avatar (brand illustration
foreground, live voice state), and assistant / task / notice bubbles gain the
in-line message avatar (only the actively-spoken message animates). User messages
keep their prior right-aligned layout.

Honesty note: the animation is driven purely by the real
`VoiceSessionSnapshot` — an idle voice renders a still avatar, and there is no
avatar image on Android, so assistant bubbles honestly fall back to the `助`
letter (no fabricated/generated face).

Verification: `:app:testDebugUnitTest :app:assembleDebug` BUILD SUCCESSFUL with
1156 tests passing (0 failures / 0 errors / 0 skipped), including the new
`DigitalAvatarVisualTest` (voice-state mapping, per-message model, derived
pulse/wave/ring values, and dp metrics). On-device verification is pending — no
device was connected, so the pulse/wave rendering has not yet been confirmed on a
real screen.

## 63. Chat local-mode honest state (fix "本地" tab cloud copy)

The chat top-bar 云端/本地 switch created a mode-mismatched not-ready pane:
`ChatViewModel.refreshConfigState()` only ever checked the **cloud planner**
endpoint (`EndpointConfig.cloudPlannerBaseUrl`) regardless of the active mode,
and `ChatScreen.MissingConfigPane` hard-coded the copy "云端模型尚未配置". So the
**本地** (`LOCAL_INFERENCE`) tab showed the cloud copy even though its backend is
the on-device local model, never the cloud planner.

Made config-state mode-aware, keeping the honest capability boundary:
- `ChatViewModel` now takes an app-private `filesDir` and an injectable
  `LocalModelCapability` (defaulting to the real boundary). `refreshConfigState`
  branches on the active mode: `CLOUD_AGENT` keeps the cloud endpoint check;
  `LOCAL_INFERENCE` queries `LocalModelCapability.refresh()` and sets
  `isConfigured` **only when the real on-device model is READY**, exposing the
  honest `LocalModelState` (status + message) in a new
  `ChatUiState.localModelStatus`. The JNI stub honestly reports
  NOT_CONFIGURED/UNSUPPORTED (never READY), so the local tab never fakes readiness.
- `newSession(mode)` (the 云端/本地 switch) now re-runs `refreshConfigState` after
  updating the mode, so the pane reflects the *new* mode's backend, not the stale
  prior value.
- `ChatScreen.MissingConfigPane` now renders per-mode copy: cloud mode keeps the
  cloud setup steps; local mode shows "本地推理不可用" + the real
  `LocalModelState.message` (e.g. "缺少 Android 端 MNN/LLM 原生推理库…") plus local
  capability-boundary steps, and a `Memory` icon. It still routes to the same
  Settings center.

`ChatViewModelFactory` passes `context.filesDir?.path`. No network, no faked
output; the local pane is driven entirely by the real disk scan + native adapter.

Also in this working tree (separate agent, valid cleanups): `Icons.AutoMirrored.Filled.ArrowBack`
replacing deprecated `Icons.Filled.ArrowBack` (Gallery/Settings), typed
`getParcelableExtra<Uri>(…, Uri::class.java)` in `ProfileShareActivity`, and an
onboarding `onboardingCompleted` interlock in `ClawMateRoot` to cut the post-completion
flash to zero.

Verification: `:app:testDebugUnitTest --rerun-tasks` BUILD SUCCESSFUL with **1160 tests
passing (0 failures / 0 errors / 0 skipped)**, including 4 new mode-aware cases in
`ChatViewModelConfigRefreshTest` (local mode with no model dir → NOT_CONFIGURED +
honest state; local mode ignores the configured cloud endpoint; local mode becomes
configured only on a real READY capability; cloud mode unaffected by the local
capability). `:app:assembleDebug` BUILD SUCCESSFUL. On-device verification is pending —
the connected phone was detached during this phase, so the 本地-tab pane has not yet
been visually confirmed on a real screen.

## Phase: shared LocalModelRegistry singleton (local tab = real local state, no load-on-switch)

The previous phase made the chat config pane mode-aware but each component still
owned its own `LocalModelCapability`: Settings "加载/测试" could load a model in one
instance while the chat tab's separate instance kept reporting NOT_CONFIGURED, and
`ChatViewModel.refreshLocalModel()` could not distinguish "not configured" from
"loaded" across components. This phase unifies them behind one shared singleton.

- **New `localmodel/LocalModelRegistry.kt`** — `object` holding a single
  `LocalModelCapability` (bound by `filesDir` via `bindFor`) plus a shared
  `MutableStateFlow<LocalModelState>`. `refresh(filesDir)` stays a read-only disk
  scan and never triggers a native load; it preserves a current READY state when the
  loaded model is still in the scanned inventory, and drops it when the model
  disappears. `loadAndTest(filesDir, modelId)` performs the real native load and is
  the only path that can reach READY; `unload()` calls `LocalModelNative.unload()`
  and resets to NOT_CONFIGURED. `installForTest`/`resetForTest` are the test seams.
- **`LocalModelCapability.filesDir`** is now a public `val` (was `private`) so the
  registry can detect an already-bound instance and avoid re-creating it.
- **`LocalModelViewModel` (Settings)** drops its private capability; `refresh()`,
  `loadTest()`, and `unload()` all delegate to the registry, so Settings and chat
  observe the same `LocalModelState`.
- **`ChatViewModel`** loses the `localModelCapability` constructor param
  (keeps `filesDir`). `refreshLocalModel()` is now strictly read-only: it calls
  `LocalModelRegistry.refresh(filesDir)` and reads `LocalModelRegistry.state.value`
  into `ChatUiState.localModelStatus` — switching to the 本地 tab can never trigger
  a native load, and the pane renders the honest `NOT_CONFIGURED`/`DISABLED`/READY
  state from the capability layer (never the cloud copy).
- **Tests**: new `LocalModelRegistryTest` (6 cases: refresh never loads; loadAndTest
  READY only on genuine native Success; missing native lib → UNSUPPORTED; READY
  stays visible across refreshes; unload clears; stale READY dropped when the model
  file disappears). `ChatViewModelConfigRefreshTest` rewritten to drive the shared
  registry (9 cases), including "local mode becomes configured only when a real
  model is READY" (a registry `loadAndTest` makes the chat VM see READY) and
  "local refresh alone never loads a model".

Verification: `:app:assembleDebug` + `:app:testDebugUnitTest --rerun-tasks` BUILD
SUCCESSFUL with **1168 tests passing (0 failures / 0 errors / 0 skipped)**.
On-device visual confirmation of the 本地 tab is still pending (no device attached).

Commit: 312f233

## 2026-10-23 — Windows validation baseline

- Branch: `codex/android-migration`; Android base `7f76afb`; Harmony reference
  `clawmate_dev` at `cd69b68` (read-only). PR creation awaits the user's review.
- Fixed local-model path comparison to use host-independent separators while
  preserving literal traversal and duplicate-separator rejection. Relative model
  resolution validates before canonicalization. This does not add MNN inference.
- Fixed the inventory test's in-memory filesystem to use host-native absolute
  paths; added a regression for absolute inventory paths, sibling directories,
  traversal and duplicate separators.
- Executed `:app:testDebugUnitTest :app:assembleDebug` with the project Gradle
  wrapper and local dev-package init script: **BUILD SUCCESSFUL**, 1169 tests,
  0 failures. This resolves the 20 failures observed on the untouched Windows
  baseline. `git diff --check` passed.
- Device PKB110 / Android 16: `adb install -r` returned `Success`; cold launch
  returned `Status: ok` (552 ms). Chat cloud and local states were inspected:
  unconfigured cloud and unavailable native inference remain explicit.
- Local SDK paths, proxy settings, screenshots and build logs stay outside Git.
- Commit subject: `fix: make model path validation portable across hosts`.

## 2026-10-23 — F10a: workflow configuration viewer

- Harmony reference: `pages/task/WorkflowFileViewer.ets` on `clawmate_dev`
  (`cd69b68`). Added a per-task configuration entry and a selectable, monospace
  original-JSON view with horizontal/vertical scrolling, loading, retry, missing
  file and empty-file states. Back returns to the task list without running or
  saving a workflow. Large documents have a clearly labelled bounded preview.
- Request versioning prevents an old asynchronous read from replacing a newer
  selection or reopening a closed screen. Four ViewModel regressions cover
  read-only behavior, failure/retry, stale reads and preview bounds.
- Executed `:app:testDebugUnitTest :app:assembleDebug` with `--offline` and the
  dev-package init script: **BUILD SUCCESSFUL**, **1173 tests, 0 failures,
  0 errors**. A preliminary run inherited the host HTTP proxy and failed existing
  local HTTP tests; disabling that proxy for this verification fixed those tests
  without changing application networking. `git diff --check` passed.
- PKB110 / Android 16: debug installation returned `Success`, launch `Status: ok`
  (533 ms). Verified the Alipay and Eleme configuration files, horizontal scrolling
  and Android Back navigation. The original `com.clawmate.app` installation and
  data were preserved; validation uses `com.clawmate.app.dev`.
- Remaining F10 scope: validated editing/saving, task creation/deletion, graph UI.
- Commit subject: `feat: add read-only workflow configuration viewer`.

## 2026-10-23 — F10b: validated workflow JSON editing

- Harmony reference: `pages/task/TaskPage.ets` JSON editor/save flow and
  `utils/WorkflowStorage.ets`. Added editing, save progress, retained drafts on
  failure, and explicit discard/keep-editing confirmation. Saving does not execute
  a workflow. Editing is unavailable for truncated previews and while tasks run.
- Authored text is checked as JSON before any write; root/branch shapes, step
  type, required IDs, duplicate IDs within a branch, length and nesting are
  validated. Unknown fields and future step types remain intact in the original
  saved text; actual runner capability errors still apply when executing.
- The repository rejects filenames escaping the app-private config directory,
  verifies the task still exists, replaces files through flushed temporary files,
  and refreshes task metadata without changing its run/enabled state.
- Added `MIGRATION_BACKLOG.md` to keep the source comparison and remaining slices
  available alongside the code. F10 creation/deletion and graph editing remain.
- Executed `:app:testDebugUnitTest :app:assembleDebug` offline with the dev-package
  init script: **BUILD SUCCESSFUL**, **1183 tests, 0 failures, 0 errors**. Ten new
  tests cover structure, unknown fields, bounds, draft/save state, failed writes,
  persistence and filename containment. `git diff --check` passed.
- PKB110 / Android 16: final APK installed successfully and launched in 528 ms.
  Verified invalid JSON and duplicate-ID errors, keep/discard editing, successful
  save, metadata update, and re-reading the exact saved text after a cold restart.
  Test edits were then restored in the dev app; original config and backup SHA-256
  matched. No original-app data was touched. No app fatal crash was observed.
- Follow-up discovered during validation: the existing tolerant JSON repair
  corrupts valid Chinese smart quotes in string values. Fix separately by parsing
  original JSON before attempting repair; some bundled task titles currently fall
  back to filenames because of this existing bug.
- Commit subject: `feat: edit and persist validated workflow JSON`.

## 2026-10-23 — preserve workflow text during JSON parsing

- Device validation exposed that the existing global punctuation repair changed
  Chinese string contents and broke legitimate smart quotes in the bundled Alipay
  and Eleme configs. Parse original input before syntax-repair fallback; retain
  uppercase-literal tolerance outside strings. The fallback now normalizes only
  structural delimiters, preserving quoted punctuation and escaped quotes.
- Added regressions for raw/fenced Chinese content, uppercase literals and all
  eleven bundled workflows (original descriptions and step counts). Updated the
  daily-log integration assertion that explicitly expected the old punctuation
  corruption: both runner output and persisted logs must now preserve the source.
- The first full run found that outdated assertion (1186 tests, 1 failure).
  Final `:app:testDebugUnitTest :app:assembleDebug` run after the fixes:
  **BUILD SUCCESSFUL**, **1187 tests, 0 failures, 0 errors**.
  `git diff --check` passed.
- PKB110 / Android 16: final APK installation `Success`, cold launch `Status: ok`
  (508 ms). The original Alipay config containing `“我的”` can be edited/saved and
  yields the real Chinese task title, which survives restart. Test whitespace was
  restored from the dev-app backup. No automated third-party app task was run.
- Existing persisted fallback titles are preserved until the user saves a config;
  new task initialization now derives titles correctly from the bundled metadata.
- Commit subject: `fix: preserve quoted workflow text during JSON repair`.

## 2026-10-23 — F04: query_events implementation (device verification pending)

- Harmony reference: `datamanager/DataManagerQueryEventsTool.ets` and
  `datamanager/IndexService.ets` at `cd69b68`. Bound a read-only `query_events`
  tool to the real Android chat Agent registry and app-private event index.
- Supports keyword AND/OR, domains, person/place/merchant names and aliases,
  exact dates, months, inclusive/open date ranges, result limits and todo deadline
  inclusion/sorting. Enriched fields now participate before filtering. Existing
  callers retain their default query limits and options.
- Validates arguments and conflicting time scopes. Missing/corrupt indexes return
  an actionable refresh error. Explicit empty indexes stay empty even if stale
  partition files remain; partition names cannot traverse outside the event folder.
  An initial full run (1197 tests, 1 failure) caught the existing tolerant codec's
  corrupt-to-empty behavior; index availability now uses strict decoding.
- Tool JSON preserves the source `ok`/`events` contract, per-result numeric IDs,
  summary/detail/domains/entities/sources/todo. It omits internal IDs, state files
  and source refs, and reports result/content truncation while remaining valid JSON
  within the registry's 4000-character boundary. It never builds indexes or collects
  new data as a side effect, and treats returned historical text as data.
- Final commands: `:app:testDebugUnitTest :app:assembleDebug
  :app:assembleDebugAndroidTest` offline, proxy disabled: **BUILD SUCCESSFUL**,
  **1197 tests, 0 failures, 0 errors**. Ten new unit tests cover validation,
  persisted query behavior, corruption, enrichment, deadlines and output bounds.
  Android test dependencies were downloaded in a separate proxy-enabled build.
  `git diff --check` passed.
- **Device tests NOT run yet.** Main dev APK installation returned `Success`.
  The auxiliary `com.clawmate.app.dev.test` APK installation reached the phone's
  “安全验证 / 拖动下方滑块完成拼图” challenge. This needs the user; it was not
  solved or bypassed. Two instrumented tests are ready for production registration
  and real Android-file query behavior using clearly labelled test-owned fixtures.
  Reinstall the final main APK and rerun those tests after verification succeeds.
- No real cloud-provider call has been validated for this feature; direct tool and
  local storage tests are distinct from model/provider verification. PR still
  awaits the user's final review.
- Commit subject: `feat: query indexed personal events from the chat agent`.

## 2026-10-23 — F06: native chat Markdown and whole-message copying

- Harmony reference: `components/MarkdownView.ets` and
  `pages/chat/ChatMessageList.ets` at `cd69b68`. Completed user and assistant chat
  messages now render headings, emphasis, lists, quotes, monospace code/language
  captions and tables. Streaming remains immediate plain text until completion,
  matching the source; task/notice text remains exact. Message storage is unchanged.
- Compose hosts a native TextView renderer with pinned Markwon core/tables 4.6.2
  (API verified against its upstream tag). No WebView or remote image loader is
  used. Image alternatives and literal HTML remain inert text. Explicit link taps
  permit only valid HTTP(S) URLs without embedded credentials; unsupported links
  and unavailable browser handling have visible error feedback.
- Added whole-message original-text copy actions for both sides, with success and
  failure feedback. Copies exclude the streaming cursor and separate tool traces.
  Native selection remains available for partial rendered-text copying.
- `:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest`:
  **BUILD SUCCESSFUL**, **1199 unit tests, 0 failures, 0 errors**. Two unit tests
  exercise URL schemes, credentials, control characters, malformed URLs, encoded
  paths, IPv6 and international hostnames. Three Android tests are compiled for
  formatting/code/link spans, table spans and inert HTML/image handling.
  `git diff --check` passed.
- **Device rendering/copy tests remain pending** the phone's auxiliary-package
  security verification described under F04. No Android test is reported as passed.
  The waiting install also held the test APK open on Windows and caused one
  packaging failure. Stopping that install client released the file lock; the final
  build above passed. Future install attempts should use staged APK copies outside
  the Gradle output directory, and must not solve/bypass the phone challenge.
- Commit subject: `feat: render chat Markdown and copy complete messages`.

## 2026-10-23 — F04/F06 connected-device verification

- The user completed the phone's installer security verification. Both staged
  APKs from `fa84678` were installed with `adb install -r`: **Success** for
  `com.clawmate.app.dev` and `com.clawmate.app.dev.test`. The original application
  and its data were preserved.
- Ran `adb shell am instrument -w -r -e class
  com.clawmate.app.QueryEventsDeviceTest,com.clawmate.app.ChatMarkdownDeviceTest,com.clawmate.app.MainActivityTest
  com.clawmate.app.dev.test/androidx.test.runner.AndroidJUnitRunner` on PKB110 /
  Android 16: **OK (6 tests)**, 46.441 seconds. This supersedes the pending
  installer status above.
- Two tests verified production memory-tool registration and structured queries
  against test-owned files on the real device. Three tests verified actual
  native formatting/code/link spans, table spans and inert HTML/image alternatives.
  The launch test verified all five navigation tabs. No provider/network call was
  made by these tests. Whole-message clipboard interaction still needs its own
  UI check; renderer tests alone do not verify it.
- Device log is saved outside Git as `f04-f06-device-tests.log`. Future installs
  continue to use staged APK copies to avoid locking Gradle packaging outputs.
- Commit subject: `docs: record connected-device query and Markdown checks`.

## 2026-10-23 — F03a: bounded chat-memory extraction contract

- Ported the pure preparation/prompt/validation contract from
  `datamanager/ChatMemoryExtraction.ets` at `cd69b68`. Only completed user chat
  messages from the last seven days become inputs. Stable session/message/part
  receipts prevent repeated extraction; local dates and chat modes stay attached.
- Long messages split at 5000 characters without breaking Unicode surrogate pairs.
  Batches have eight-part / 16000 serialized-character bounds. At most two bounded
  context messages are included; tool traces, task/system messages and assistant
  reasoning blocks are excluded. Context is explicitly not an independent source
  of facts. Imported user text is serialized as data, separate from model policy.
- Strict result validation requires exactly one result for every input, valid
  memory categories, bounded nonblank facts and matching identifiers. Missing,
  duplicated or fabricated references fail instead of being marked processed.
  Added the source memory categories 8–12 and legacy 7→1 label handling; workflow
  execution tabs remain 0–6.
- An initial test compilation caught use of the derived `isStreaming` getter as a
  constructor argument; corrected the fixture to use `streamingVersion`.
  Final offline `:app:testDebugUnitTest :app:assembleDebug
  :app:assembleDebugAndroidTest`: **BUILD SUCCESSFUL**, **1209 unit tests,
  0 failures, 0 errors**, including ten new extraction regressions.
  `git diff --check` passed.
- This is a foundation slice, not completed F03: no new UI, database reads,
  memory writes or model calls are wired yet. Device sync verification follows
  with the persistence/client/user-action slices. Existing F04/F06 device checks
  used their independently staged committed APKs.
- Commit subject: `feat: prepare and validate bounded chat memory extraction`.

## 2026-10-23 — F03b: restartable chat-memory persistence and export

- Ported `datamanager/ChatMemorySync.ets` at `cd69b68`: facts and processing
  receipts commit together, including successful empty results. Previous committed
  facts are exported first, even when the originating messages have aged out.
  Partial batch progress survives later failures; network failures are not split
  or amplified. Only invalid structured output permits individual-part fallback.
- Added a strict versioned checkpoint at `workflows/chat-memory/state.json` and
  app-private daily logs grouped by day/mode/scene, with source-compatible metadata.
  Flush-before-replace writes, canonical path checks, strict corruption errors and
  a per-directory coroutine mutex prevent false acknowledgement and duplicate
  concurrent extraction. Cancellation propagates before committing a model reply.
  Storage bounds fail explicitly rather than resetting or discarding receipts.
- Android adapter: the daily-log parser admits only 60 entries per document.
  Fixed-size overflow files (`app-chat__cloud__12__2.md`, etc.) preserve existing
  file/entry references. A 121-fact regression verifies all entries parse without
  errors and existing file contents remain stable when more facts arrive.
- The first build found a suspend-function fixture default that needed a lambda
  wrapper. Final offline `:app:testDebugUnitTest :app:assembleDebug
  :app:assembleDebugAndroidTest`: **BUILD SUCCESSFUL**, **1221 unit tests,
  0 failures, 0 errors**, including 12 new restart/failure/concurrency regressions.
  `git diff --check` passed.
- PKB110 / Android 16: both staged candidate APK installs returned **Success**.
  `ChatMemoryDeviceTest` returned **OK (1 test)**: real Android-file persistence,
  recreation without repeated extraction, local consolidation/index refresh and
  successful `query_events` lookup. It uses a clearly labelled extractor fixture
  and test-owned cache; no cloud-provider claim or user database mutation.
  Device log: `f03b-device-tests.log` outside Git.
- F03 still needs its production model client, Room reader and explicit UI action;
  this commit does not enable background chat reading or cloud requests.
- Commit subject: `feat: persist and recover chat memory synchronization`.

## 2026-10-23 — F03c: real model client and read-only Room adapter

- Added an OpenAI-compatible completion client using the existing HTTP transport
  and saved model configuration. Requests contain bounded system/data messages,
  no tools, and explicit connect/read/call timeouts. Invalid, truncated or
  tool-bearing responses get at most three attempts; network, access and
  cancellation failures propagate immediately. A single surrounding JSON fence
  is accepted without changing any JSON string contents. Invalid responses are
  never echoed into a later request.
- Added a Room transaction reader and date/kind/role-filtered DAO query. It reads
  only recent completed user/assistant chat, preserving source mode and order;
  history and the database schema remain unchanged. Updated existing test DAO
  implementations for the new query.
- Offline `:app:testDebugUnitTest :app:assembleDebug
  :app:assembleDebugAndroidTest`: **BUILD SUCCESSFUL**, **1228 unit tests,
  0 failures, 0 errors**. Seven new tests cover bounded retries, malformed/partial
  output, unexpected tools, cancellation, missing config and an actual compatible
  HTTP request against local MockWebServer. This is protocol verification, not a
  real provider availability/quality claim. `git diff --check` passed.
- PKB110 / Android 16: main/test staged APK installs **Success**;
  `ChatMemoryRoomDeviceTest` **OK (1 test)**. It uses an independent in-memory
  Room database and checks date/kind filtering, local mode and unchanged history
  counts. The user's persisted chat database is not opened by this test.
- No production model request is triggered yet: the explicit consent/settings
  action is the next slice. No endpoints, keys or private database files added.
- Commit subject: `feat: connect chat memory to cloud completion and Room`.

## 2026-10-23 — F03d: explicit settings action, consent and local refresh

- Added “聊天长期记忆” under Settings → 数据采集. The production action reads
  saved cloud settings, confirms the destination host/model and data scope before
  opening Room or creating a model client. Consent explicitly includes local-mode
  chat fragments and limited context, excludes tool traces/reasoning, and explains
  that deleting chat does not delete already-saved memory. Configuration changes
  invalidate pending consent. Nothing runs on launch/construction/recomposition.
- Sync shows progress, bounded errors, cancellation and returned counts. A separate
  zero-network local-index action checks for malformed logs before invoking the
  existing deterministic runner. It does not enable periodic scheduling or cloud
  consolidation. Updated the extraction prompt to keep ordinary preferences and
  temporary events in their topical category rather than treating every “I” as
  identity data.
- Offline `:app:testDebugUnitTest :app:assembleDebug
  :app:assembleDebugAndroidTest`: **BUILD SUCCESSFUL**, **1236 unit tests,
  0 failures, 0 errors**, including eight new consent/config-change/cancellation/
  error/index-action tests. `git diff --check` passed.
- PKB110 / Android 16: both staged APK installs **Success**;
  `ChatMemoryConsentDeviceTest` **OK (2 tests)**, 65.105 seconds. The phone requested
  a normal auxiliary-app launch confirmation; “仅本次允许” was selected. Tests
  exercised real Compose consent/cancel/confirm controls and separate index action
  with labelled injected processing, plus the unconfigured error path. No provider
  call or private chat read was made by these tests.
- Manual production-page verification: cold launch **Status: ok**, 492 ms;
  Settings → 数据采集 shows the real card; unconfigured sync says to fill/save
  model settings; local refresh completes with **0 indexed events**, matching this
  dev installation's empty daily logs. Screenshots/UI dumps saved outside Git;
  original app/data preserved. Earlier F03 storage/query and Room device tests each
  passed separately, making four F03 feature device tests in total.
- Remaining boundaries: real provider behavior needs a configured account; memory
  categories beyond the old four summary tabs are the next parity fix. Unified
  gallery/chat/global pipeline orchestration and coordination with existing
  background/model index writers remain F09; this action uses the existing runner.
- Commit subject: `feat: add consented chat memory sync and local index actions`.

## 2026-10-23 — F03: actionable empty-index result

- The existing local runner returns zero without creating an index when there
  are no daily logs. The new Settings action now skips that run on empty input
  and asks the user to sync chat or collect records, instead of claiming a fresh
  usable index. Existing artifacts are not touched in this empty-input branch.
- Updated the empty-result ViewModel regression. Combined candidate checks with
  the following category/copy slices: offline `:app:testDebugUnitTest
  :app:assembleDebug :app:assembleDebugAndroidTest` **BUILD SUCCESSFUL**,
  **1239 unit tests, 0 failures, 0 errors**; `git diff --check` passed.
- PKB110 / Android 16, real Settings → 数据采集 → 刷新本地记忆索引:
  displays “未找到可索引事件，请先同步聊天或采集足迹后重试。” as expected.
  Screenshot/UI evidence: `empty-index-actionable-result` outside Git.
- Commit subject: `fix: explain empty chat memory index refresh results`.

## 2026-10-23 — F09a: complete memory categories in summary

- Summary now uses all source memory categories from `WorkflowSceneConfig.ets`:
  0–6 and 8–12. The original four tabs/IDs/colors keep their order, and the existing
  horizontal scroller exposes life/social/travel/document/health/work/gallery/
  personal-info categories. Legacy takeout scene 7 maps to 1. Recognized newer
  scenes no longer fall into shopping; truly unknown scenes retain prior fallback.
- Selected colors come from the same category map. Chat daily-log file partitions
  use friendly “云端聊天” / “本地聊天” source labels and can share a grouping
  across overflow files instead of exposing storage filenames in that label.
- Three new grouping regressions and updated category/color contract tests passed
  in the combined candidate: offline unit/main/test APK build **BUILD SUCCESSFUL**,
  **1239 unit tests, 0 failures, 0 errors**; `git diff --check` passed.
- PKB110 / Android 16: main/test installs **Success**. Category and clipboard
  instrumented suite **OK (2 tests)**, 76.962 seconds; category test scrolls to
  “个人信息”, selects it, and scrolls back to “外卖”. Normal auxiliary-app launch
  prompts were allowed for this run only. No security challenge was bypassed.
  Manual production summary also scrolls to and selects personal info; cold launch
  **Status: ok**, 488 ms. Screenshots/UI dumps saved outside Git.
- This completes category parity, not F09's unified global/gallery orchestration.
- Commit subject: `feat: expose all memory categories in summary collections`.

## 2026-10-23 — F06: whole-message clipboard device verification

- Exposed the existing shared copy composable internally for device verification;
  behavior is unchanged. A real Compose click now tests the actual Android
  clipboard with a >6000-character fixture containing original Markdown, code,
  Chinese text, a final marker and an emoji. The entire original equals the copied
  text; it is not limited to rendered/selected text or the generic tool text cap.
- The test restores the pre-existing clipboard in a finally block and never logs
  its contents, including on assertion failure. It does not alter chat history.
- Combined candidate build/unit checks: **BUILD SUCCESSFUL**, **1239 unit tests,
  0 failures, 0 errors**. PKB110 / Android 16 category/copy suite **OK (2 tests)**;
  the copy test passed. Together with the earlier three native renderer tests this
  completes the pending four F06 device component checks. No cloud call needed.
  `git diff --check` passed; logs remain outside Git.
- Commit subject: `test: verify complete chat message copy on Android clipboard`.

## 2026-10-23 — F01a: grounded home task suggestions and detail flow

- Ported the generation/context/evidence/short-task contract from
  `pages/home/HomeTaskSuggestion.ets` and `HomePage.ets` at `cd69b68`. The explicit
  Home action sends up to 24 real indexed events and four populated profile
  summaries, using compact per-request references; internal event IDs/state paths
  and source refs are omitted. Prior tasks are supplied for duplicate avoidance.
  Nothing reads context or calls a model on construction/recomposition.
- A real completion client requires exactly three distinct suggestions with real
  referenced evidence. It supports the legacy `tasks` response key, bounded text,
  guarded single-action drafts and source-compatible Qwen applicability metadata.
  Missing/fabricated evidence, incomplete output and repeated tasks cannot replace
  a valid batch. Failed refresh/cancellation preserves previous suggestions.
- Added Home “猜你想做 · AI 任务建议”, generation/cancel/error states, evidence
  details, editable/validated task copying, and navigation to existing workflows
  or Settings. The generation control explains that indexed text/profile summaries
  are sent to the saved cloud model. Suggestion titles/reasons are labelled AI.
- **Execution boundary:** free-form phone GUI planning and Qwen handoff are not
  implemented by Android's current chat tool adapters. Details say this explicitly;
  copying is not execution, and “查看可用流程” opens the real task page for user
  selection. This is F01's generation/detail slice, not a claim of full F01 execution
  parity. P01/P06 remain open; no generated task is marked executed/completed.
- Offline `:app:testDebugUnitTest :app:assembleDebug
  :app:assembleDebugAndroidTest`: **BUILD SUCCESSFUL**, **1255 unit tests,
  0 failures, 0 errors**. Sixteen new tests cover bounded context/evidence,
  duplicates, missing refs, task validation/platform applicability, actual local
  HTTP protocol, no-config/no-input gates, cancellation and retained batches.
  `git diff --check` passed.
- PKB110 / Android 16: main/test installs **Success**; fixture-backed
  `HomeTaskSuggestionsDeviceTest` **OK (2 tests)**, 65.626 seconds. Real Compose
  controls show evidence, the unavailable execution explanation, workflow callback
  and missing-config/settings callback. Manual production Home also shows the new
  section after scrolling; unconfigured generation gives the correct error and
  “查看可用流程” opens the actual 11-workflow page. Cold launch **Status: ok**,
  472 ms. No external-app workflow or real provider request was run.
- Commit subject: `feat: generate grounded home task suggestions with evidence`.

## 2026-10-23 — F02a: independent casual emotional-support chat

- Ported the casual-entry flow and prompt rules from `HomeComfortSection.ets`,
  `HomeEmotionalSupport.ets` and `HomeComfortChatStore.ets` at `cd69b68`. Source
  conversations are in-memory for the current application/concern batch, not
  ordinary persisted chat. The Android Home entry/dialog explains this lifetime
  and the saved-cloud-model send scope. It does not read Room, profiles or inferred
  concern context, and does not run any tools.
- Added a real compatible completion client (non-streaming as in this source
  flow), twelve-turn / 700-character context bounds, input limits, cancellation,
  visible 1600-character reply truncation and reasoning-block removal. Prompt
  rules emphasize listening/corrections, avoid diagnoses, sensitive inference,
  medication advice and unsolicited task reminders, and prioritize real-world
  help for immediate danger. No default model reply is fabricated.
- UI supports native selectable messages, draft retention, failure/retry without
  duplicating user messages, stop/close and restart. Generation tokens and
  cancellation prevent a late old reply/error from replacing a new conversation.
  Retrying also preserves any newly typed unsent draft. Ordinary chat data is
  independent and untouched.
- Offline `:app:testDebugUnitTest :app:assembleDebug
  :app:assembleDebugAndroidTest`: **BUILD SUCCESSFUL**, **1266 unit tests,
  0 failures, 0 errors**. Eleven new tests cover prompt/role boundaries, paragraph/
  Unicode/reasoning handling, actual local HTTP, partial-output refusal, missing
  config, retry/drafts/cancellation and late responses. `git diff --check` passed.
- PKB110 / Android 16: both APK installs **Success**;
  `HomeComfortDeviceTest` **OK (2 tests)**, 64.442 seconds. Fixture-backed real
  Compose controls verify send/display/restart and unconfigured draft/settings
  behavior. Manual production Home opens the actual dialog with readable scope,
  lifecycle and input controls; empty send is disabled. Cold launch **Status: ok**,
  520 ms. Screenshots/UI dumps saved outside Git; no real provider call made.
- F02's grounded concern/topic suggestions are still pending. This commit only
  opens casual user-initiated support, without inferring any user's emotions from
  their records or automatically transmitting background data.
- Commit subject: `feat: add independent home emotional support conversation`.

## 2026-10-23 — F02b: grounded support topics and isolated conversations

- Added the source `HomeEmotionalSupport.ets` topic-generation flow. Android uses
  a read-only Room adapter limited to up to 48 completed first-party USER message
  snippets from seven days (including local-mode chat), with local dates and
  per-request references. Assistant/tool/system/streaming/old/future messages,
  traces, session IDs/titles and profiles are excluded from the payload. This
  deliberately does not upload third-party collected chats; such source expansion
  would need a separate explicit adapter/data-selection decision.
- The explicit Home control describes that send scope. A real compatible model
  call may return zero to three topics; empty input sends no request. Topics must
  resolve real evidence, are deduplicated/bounded, and diagnostic claims are
  rejected. The prompt permits only directly supported first-person emotional
  context, rejects attribution from quotes/others/tasks/consumption, and does not
  force a fixed number of concerns. Failed refresh retains prior valid topics.
- Selecting a topic opens its own in-memory conversation with model-generated
  opening and visible source snippets. Only the selected topic's bounded evidence
  is added as labelled background data; it is not treated as a fact/instruction.
  Casual and topic messages/drafts remain separate. New batches discard old topic
  sessions while preserving the casual draft; generation/cancellation guards still
  prevent old replies from overwriting new sessions.
- Offline `:app:testDebugUnitTest :app:assembleDebug
  :app:assembleDebugAndroidTest`: **BUILD SUCCESSFUL**, **1277 unit tests,
  0 failures, 0 errors**. Eleven new tests cover input/date bounds, first-party
  filtering, valid empty/partial results, fabricated refs/diagnostic refusal,
  selected-background limits, model gates/HTTP and conversation isolation.
  `git diff --check` passed.
- PKB110 / Android 16: staged main/test installs **Success**. Casual regression
  plus topic suite **OK (4 tests)**, 131.653 seconds: native send/restart/config
  behavior, visible topic evidence/opening and honest empty results. Fixture-backed
  processing did not contact a real provider or read private history. Manual Home
  topic entry and missing-config/settings feedback verified; cold launch
  **Status: ok**, 483 ms. Screenshots/UI evidence saved outside Git.
- Real configured-provider relevance/quality remains unverified. No automatic
  background inference or ordinary-chat persistence changes were introduced.
- Commit subject: `feat: suggest grounded support topics with isolated chats`.

## 2026-10-23 — F05/F07a: bounded image preparation and compatible request encoding

- Added shared request-only image inputs to the existing completion client.
  Ordinary text/tool serialization is unchanged byte-for-byte; explicit prepared
  user images become text/image_url content parts for both completion and SSE
  requests. Inputs are limited to four images per request and 1 MiB per image.
  Only validated JPEG/PNG data URLs are accepted; remote URLs, content/file paths,
  invalid encodings, wrong roles and oversize inputs fail before network dispatch.
- Added explicit Android content-grant/private-file preparation: bounded decode,
  EXIF rotation/mirroring, final maximum side 1280, white transparency flattening,
  bounded JPEG encoding and metadata removal. File URIs must resolve inside the
  app's private files/cache; no arbitrary path/network image fetch is performed.
  Prepared-image logging describes dimensions/byte counts without printing data.
- Full offline `:app:testDebugUnitTest :app:assembleDebug
  :app:assembleDebugAndroidTest`: **BUILD SUCCESSFUL**, **1281 unit tests,
  0 failures, 0 errors**. Four new tests cover exact ordinary-wire preservation,
  multimodal shape/bounds and actual local non-streaming/SSE HTTP requests.
  `git diff --check` passed.
- Initial device runner initialization failed because one expression-bodied test
  inferred a Bitmap return rather than void; no bitmap assertions ran then.
  Fixed explicit `runBlocking<Unit>`, rebuilt only the affected Android test APK:
  **BUILD SUCCESSFUL**, 19 seconds. Both staged installs succeeded; final PKB110 /
  Android 16 `ChatImagePreparationDeviceTest`: **OK (3 tests)**, 0.155 seconds.
  Real bitmap assertions cover odd-sized downsampling, rotation, mirroring,
  transparency, GPS/make removal and path refusal using test-owned cache files.
  Initial/final logs are preserved outside Git.
- This is shared infrastructure, not completed F05/F07: no attachment send UI,
  gallery model analysis, automatic uploads or provider-recognition claim yet.
- Commit subject: `feat: prepare bounded Android images for multimodal requests`.

## 2026-10-23 — F07b: single-photo cloud vision, preview and persistence

- Added a real gallery-model client using the saved gallery endpoint/key/model,
  independently of the planner. Photo detail now asks for explicit upload consent
  naming the destination and model before reading/preparing pixels or calling the
  service. Only compressed pixels and up to 3000 UTF-16 units of existing OCR
  (without splitting a surrogate pair) are sent; EXIF/GPS, file paths and keys
  are excluded from the request body. Missing configuration is actionable.
- Strict bounded JSON parsing preserves the source title/category/summary/details/
  tags contract and refuses malformed, incomplete or error replies. Analysis
  appears as an AI-labelled preview; only the separate save control updates the
  database. Stop/cancellation/config-change guards prevent stale results from
  replacing a new selection. A failed refresh/save retains the preview.
- A Room transaction checks both item identity and URI, replaces analysis fields
  including deliberately empty arrays, and preserves dates, location, EXIF and
  OCR. Deleted photos are never recreated by a late save. Group refresh failures
  are reported separately from a successful metadata save. Cloud results have
  their own state/label and do not claim local OCR ran.
- Offline `:app:testDebugUnitTest :app:assembleDebug
  :app:assembleDebugAndroidTest`: **BUILD SUCCESSFUL**, 4m 3s; **1291 unit tests,
  0 failures, 0 errors**. Tests cover parser/config bounds, actual local HTTP,
  Unicode context, consent, cancellation/stale replies, preview/save failures and
  OCR-state regression. `git diff --check` passed.
- PKB110 / Android 16: both staged APK installs **Success**;
  `GalleryVisionStorageDeviceTest,GalleryVisionUiDeviceTest`: **OK (3 tests)**,
  62.705 seconds. Real Room close/reopen verifies saved fields and identity;
  Compose fixture controls verify cancel/confirm/preview/save and no-config gates.
  Test fixtures use their own database; no private photo upload or real provider
  recognition was performed. Manual production gallery entry opens normally;
  cold launch **Status: ok**, 489 ms. Evidence is saved outside Git.
- This phase covers one selected photo. Batch analysis, unified gallery memory
  orchestration (F09) and configured-provider quality remain pending.
- Commit subject: `feat: analyze gallery photos with consent and saved previews`.

## 2026-10-23 — F05b: real image chat streaming and private attachment history

- Connected the existing camera/album selection to explicit destination/model
  consent and real ordinary multimodal SSE. Only confirmed images are read and
  prepared; no image enters task/tool execution. Current saved runtime settings
  are read for this path, with a private fingerprint detecting endpoint/key/model
  changes before send. Display/log labels omit credentials and source paths.
- Reuses the bounded EXIF-free preparation/client encoding from `cedce87`.
  Sending supports up to four images/1 MiB each. The source's nine-selection
  composer remains, but excess images trigger an actionable removal message;
  none are silently truncated. Image-only turns use the source default prompt.
  Unsupported local mode, missing config, unreadable images and cancelled consent
  retain the draft/selection without a request. Model HTTP/protocol/partial/empty/
  tool-call errors never become a saved successful assistant answer.
- Accepted prepared images are retained under app-private UUID names, with only
  those names stored in Room v2. Added explicit v1→v2 migration with no destructive
  fallback. History shows actual local thumbnails and unreadable-file feedback;
  deleting the session cleans its prepared files. A failed initial DB write
  cleans the unaccepted batch; provider failure retains the accepted user turn.
- Harmony `ChatSessionTypes.ets` preserves image paths but builds later context
  from text. Android follows that behavior and explicitly labels older picture
  turns as not carrying pixels in the current request; the UI asks users to
  reattach pictures for visual follow-up. Source grants, EXIF, image bytes and
  private filenames never enter later model context. No background re-upload.
- Busy session deletion now asks the user to stop the reply first. Stop removes
  unpersisted streaming fragments; cancelled image readers/late errors cannot
  overwrite a newer successful turn. Text/tool request serialization is retained.
- Initial and intermediate full checks passed (4m 9s / 3m 59s). After the final
  cancellation guard, offline `:app:testDebugUnitTest :app:assembleDebug
  :app:assembleDebugAndroidTest`: **BUILD SUCCESSFUL**, 3m 47s; **1302 unit tests,
  0 failures, 0 errors**. Eleven new tests cover actual local HTTP/SSE, current
  config, file ownership, failure/partial output, gates and cancellation races.
  `git diff --check` passed. All build logs remain outside Git.
- PKB110 / Android 16: both staged installs **Success**. The image migration/send
  tests plus existing chat-memory Room and main-navigation regressions were
  **OK (4 tests)**, 54.452 seconds. Real v1 SQLite migration preserves old text,
  traces/custom title and survives reopening; a test-owned image runs through
  actual preparation, native consent, fixture-backed streaming, thumbnail display
  and deletion. Tests use isolated databases/owned files. No real provider was
  called and no private user photo was selected.
- Manual production chat entry opens with the honest deferred-local-model state;
  cold launch **Status: ok**, 477 ms. UI/screenshot evidence is outside Git.
  Configured-provider multimodal support/recognition quality remains unverified.
- Commit subject: `feat: send confirmed chat images and preserve private history`.
