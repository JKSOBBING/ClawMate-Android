# ClawMate Android Migration — Status & Feature Mapping

Source of truth (reference only, do not modify): `/root/mobiinfra-oh`
Target: this repository (`/root/ClawMate-Android`)

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
| 4 | **Gallery / collection** | `utils/gallery/*` (GalleryAnalyzer, GalleryAutoScanner, GalleryOcrService, GallerySearchService, GalleryMonthlyInsights, GalleryMemorySync, GalleryStorage, GalleryTypes); `pages/gallery/GalleryPage.ets`; `pages/collection/CollectionPage.ets`, `CollectionGroupDetailPage.ets`; `datamanager/CollectionGroupingService.ets`; docs `docs/gallery/*` | Adapt | Android Photo Picker / MediaStore with URI grants (no broad storage permission). **Phase 3 shipped** the picker + EXIF read + Room persistence + collection grouping + search, with all analysis behind explicit NOT_CONFIGURED interfaces. **Phase 9 shipped** a real item detail flow (content URI / date / EXIF / analysis state / tags / summary / collection membership), explicit edit (title/summary/tags) + delete-by-item with confirmation (Room-persisted, groups recompute), Android reverse geocoding via the platform `Geocoder` (no key, typed errors, injected), and an orchestrating capability UI. OCR = ML Kit `text-recognition-chinese` is resolvable on Maven but is a download-on-device model and there is no device to verify output → kept NOT_CONFIGURED (documented). Cloud vision (MiniCPM-V) stays NOT_CONFIGURED. |
| 5 | **Data manager** | `datamanager/*` (DataManagerFacade, DataManagerStorage, DataManagerTypes, DataManagerUtils, DataPipelineModelClient, ConsolidationService, IndexService) | Adapt | Source is JSON-file rooted at `filesDir/` (`workflows/...`, `gallery-log/...`) with SHA-256 fingerprints. On Android prefer Room for structured sets + file store for media; port DataManagerFacade as a single entry point (Repository namespace). |
| 6 | **Workflows / tasks** | `utils/Workflow*.ets` (WorkflowRunner ~2k lines, WorkflowStorage, WorkflowTypes, WorkflowJsonRepair, WorkflowSceneConfig, WorkflowDateContext); `pages/task/TaskPage.ets`, `WorkflowFileViewer.ets`; default configs `resources/rawfile/workflows/configs/*.json` | Adapt + **GUI part Blocked** | Workflow JSON schema (metadata/defaults/context/steps; step types gui_task/gui_action/tool/loop/if) ports directly. Execution needs screenshot-driven VLM "decider" loop → on Android drive via adb/UIAutomator or a PC-side service; on-device GUI automation for arbitrary apps = platform blocker (see #11). |
| 7 | **Settings** | `pages/settings/SettingsCenterPage.ets`, `SettingsCenterTypes.ets`, `SettingsCenterControls.ets`, `SettingsCenterTheme.ets`; key inventory in `pages/Index.ets:357–431` | Adapt | 5 sections: 个人与隐私(Personal/Privacy) · 数据采集(Data collection) · 模型与智能体(Model & agents) · 任务与自动化(Task automation) · 存储与关于(Storage & about). Source uses `PersistentStorage.persistProp` → **Phase 5 shipped** an Android `settings/` slice: DataStore-backed typed repository + `SensitiveValueUtils` masking port, Compose `SettingsScreen` with five-section navigation, and honest NOT_CONFIGURED/DISABLED states for unconfigured endpoints. |
| 8 | **Voice / digital avatar** | `utils/voice/*` (MimoVoiceClient, HarmonySpeechRecognizerAsrProvider, HarmonyAudioPlayback, HarmonyPcmStreamPlayback, PcmWav, VoiceTtsPresets); `pages/chat/ChatVoiceOrchestrator.ets`, `DigitalAvatarView.ets`, `VoiceInteractionTypes.ets` | Adapt | **Phase 7 shipped** the chat voice-interaction vertical slice (`chat/voice/`): typed domain models + deterministic normalization (`VoiceInteractionTypes.kt`), ASR transcript merge + duplicate-final suppression (`VoiceTranscriptLogic.kt`), PCM16→WAV builder (`PcmWav.kt`), voice presets (`VoiceTtsPresets.kt`), voice-input start/stop guards (`VoiceOrchestratorLogic.kt`). ASR = Android `android.speech.SpeechRecognizer` adapter with explicit permission/unavailable/error/cancel states and **no hidden mic access** (RECORD_AUDIO surfaced in the UI, requested only on the mic tap). Output = Android `TextToSpeech` adapter (lifecycle-safe init, speak/pause/stop/replay, typed unavailable/error states). Cloud MiMO streaming/TTS stays behind an explicit **NOT_CONFIGURED** interface (`CloudMimoVoiceClient`) — never claimed configured or faked. Chat UI gained mic/listening/transcript + speak/stop/replay controls; a real final ASR transcript becomes ordinary editable chat input. Digital avatar remains DISABLED (not part of this slice). |
| 9 | **Local model** | `entry/src/main/cpp/*` (napi_init.cpp, HIAIModelManager.cpp, MNN headers), `docs/local_mnn/prompt-loading.md` | **Blocked → Adapt later** | Source loads an `MNN` LLM via N-API (`libentry.so`) and a HiAI/NNRT path (`HIAIModelManager`). MNN has Android builds → port the C++ LLM via JNI; drop the Harmony-only HiAI/OMC pieces. In-app model download flow (`ModelDownloadConfig`) → Android DownloadManager. Controlled disabled state until JNI model loader lands. |
| 10 | **Cloud model** | `utils/CloudModelClient.ets`, `CloudDeciderPrompt.ets`, `CloudLogOutput.ets`, `gallery/GalleryEmbeddingService.ets` | Direct/Adapt | OpenAI-compatible `/v1/chat/completions` already used → OkHttp + kotlinx.serialization. Supports Planner/Decider separate base URLs, `/v1/chat/completions`, cancellation, retries. Preserve streaming + error states. |
| 11 | **HDC / GUI automation** | `utils/MobileGuiTask*.ets`, `HdcWorkflowBridge.ets`, `PcServerMonitor.ets`; `python/hdc_server.py`, `python/harmony_agent.py` | **Blocked → Adapter (Phase 8 shipped the safe client + adapter)** | Harmony auto-agent drives a physical device via **PC-side Python over HDC** (`hdc fport` + HTTP bridge on :9124, in-app TCP :9126). On Android **Phase 8** ships a safe PC HDC bridge client: a typed OkHttp `HdcBridgeClient` for `GET /api/health` + `POST /api/workflow` (normalized URLs, bounded timeouts, cancellation, tolerant parsing, typed errors) and a `WorkflowDeviceAdapter` boundary (`app_start`/`screenshot`/`gui_action`/`execute_decider_action` only). **No** `Runtime.exec`/`ProcessBuilder`/shell/adb/hdc/AccessibilityService/`/api/run_cmd`. `TaskComponent` starts DISABLED (default `DisabledWorkflowDeviceAdapter`) and only an explicitly injected, health-verified adapter makes protocol calls; the settings UI runs a health check **only on an explicit button tap** and connects only after a real `ok` + control-ready reply. Workflow `gui_action` steps return honest bridge results/errors only when a configured adapter is registered; otherwise they stay `BLOCKED`. |
| 12 | **Python bridge** | `entry/src/main/python/*` (hdc_server.py :9124, harmony_agent.py, serve_model.py :9123, wechat_collect/*) | **Blocked → Adapter (Phase 8 HTTP-over-LAN client)** | Not runnable on-device on Android. Keep as a PC-side component; Android app talks to it over HTTP (localhost/LAN) via the Phase-8 `OkHttpHdcBridgeClient`. Do not bundle Python in the APK. A verified bridge + adapter is required for workflow GUI steps; until then they are `BLOCKED`. |
| 13 | **Native C++ / MNN** | `entry/src/main/cpp/**` (CMakeLists, MNN/llm includes, libentry N-API), `entry/libs/arm64-v8a` | Adapt (later) | Reuse the MNN C++ sources via NDK/CMake + JNI instead of N-API. Need Android prebuilt MNN library + NDK. Defer to the local-model phase. |
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
4. HiAI/NNRT NPU path for local model → unavailable on Android; use MNN CPU.
5. ShareKit "knock-to-knock" share → approximate with Android Share sheet / NFC.

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

---

## 7. Open items / risks

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

---

## 8. Changelog

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
  - **OCR engine boundary**: ML Kit `text-recognition-chinese` is resolvable on
    Google Maven (16.0.x) but is the **download-on-device** (Play-Services)
    variant and there is no emulator/device here to verify real recognized text,
    so it is **not** vendored. OCR stays behind the explicit `OcrEngine`
    NOT_CONFIGURED boundary with a typed `Unavailable` message — never invented
    output. Cloud vision remains NOT_CONFIGURED. Documented in
    `CapabilityEngines.kt`.
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
