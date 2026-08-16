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
| 1 | **Onboarding** | `pages/onboarding/FirstLaunchOnboarding.ets`, `OnboardingTypes.ets`, spec `docs/superpowers/specs/2026-07-02-first-launch-onboarding-design.md` | Adapt | 3-fixed-pages + usage guide; versioned completion key `firstLaunchOnboardingVersion` (`2026-07-core-preview`) → DataStore boolean/version. UI gate before the tab shell. |
| 2 | **Home / recommendations** | `pages/home/HomePage.ets`; `datamanager/HomeRecommendationService.ets`, `DataManagerTypes.ets`, `IndexService.ets`, `ConsolidationService.ets` | Adapt | Compose Home. Persona pillars (理解你/守护你/陪伴你/成就你) become static Card sections. Recommendation cards from `home-todo` index (deadline/no-deadline logic) → ViewModel + Room query. |
| 3 | **Chat & agent loop** | `pages/chat/*` (ChatPage, ChatPageTypes, ChatMessageDataSource, ChatSessionController); `chat/ChatSessionTypes.ets`; `utils/CloudModelClient.ets` (SSE at :725); `utils/AgentLoopRunner.ets`, `ChatReactAgent.ets`, `ChatToolRegistry.ets`, `AgentActionParser.ets`, `AgentExecutionController.ets`; spec `docs/superpowers/specs/2026-06-10-chat-session-management-design.md` | Adapt | Compose `LazyColumn` chat; Repository + ViewModel; OpenAI-compatible `/v1/chat/completions` via OkHttp SSE exposed as `Flow<String>`; ReAct tool loop; Markdown renderer; Room persistence (source uses JSON files in `filesDir/chat-sessions/`). |
| 4 | **Gallery / collection** | `utils/gallery/*` (GalleryAnalyzer, GalleryAutoScanner, GalleryOcrService, GallerySearchService, GalleryMonthlyInsights, GalleryMemorySync, GalleryStorage, GalleryTypes); `pages/gallery/GalleryPage.ets`; `pages/collection/CollectionPage.ets`, `CollectionGroupDetailPage.ets`; `datamanager/CollectionGroupingService.ets`; docs `docs/gallery/*` | Adapt | Android Photo Picker / MediaStore; on-device OCR via ML Kit (source: Harmony textRecognition); cloud vision LLM (MiniCPM-V) server call; Room + JSON for gallery-log; search via Room FTS; reverse geocoding via a map provider (source: AMap). |
| 5 | **Data manager** | `datamanager/*` (DataManagerFacade, DataManagerStorage, DataManagerTypes, DataManagerUtils, DataPipelineModelClient, ConsolidationService, IndexService) | Adapt | Source is JSON-file rooted at `filesDir/` (`workflows/...`, `gallery-log/...`) with SHA-256 fingerprints. On Android prefer Room for structured sets + file store for media; port DataManagerFacade as a single entry point (Repository namespace). |
| 6 | **Workflows / tasks** | `utils/Workflow*.ets` (WorkflowRunner ~2k lines, WorkflowStorage, WorkflowTypes, WorkflowJsonRepair, WorkflowSceneConfig, WorkflowDateContext); `pages/task/TaskPage.ets`, `WorkflowFileViewer.ets`; default configs `resources/rawfile/workflows/configs/*.json` | Adapt + **GUI part Blocked** | Workflow JSON schema (metadata/defaults/context/steps; step types gui_task/gui_action/tool/loop/if) ports directly. Execution needs screenshot-driven VLM "decider" loop → on Android drive via adb/UIAutomator or a PC-side service; on-device GUI automation for arbitrary apps = platform blocker (see #11). |
| 7 | **Settings** | `pages/settings/SettingsCenterPage.ets`, `SettingsCenterTypes.ets`, `SettingsCenterControls.ets`, `SettingsCenterTheme.ets`; key inventory in `pages/Index.ets:357–431` | Adapt | 5 sections: 个人与隐私(Personal/Privacy) · 数据采集(Data collection) · 模型与智能体(Model & agents) · 任务与自动化(Task automation) · 存储与关于(Storage & about). Source uses `PersistentStorage.persistProp` → Android DataStore/SharedPreferences. Sensitive value masking ported from `utils/SensitiveValueUtils.ets`. |
| 8 | **Voice / digital avatar** | `utils/voice/*` (MimoVoiceClient, HarmonySpeechRecognizerAsrProvider, HarmonyAudioPlayback, HarmonyPcmStreamPlayback, PcmWav, VoiceTtsPresets); `pages/chat/ChatVoiceOrchestrator.ets`, `DigitalAvatarView.ets`, `VoiceInteractionTypes.ets` | Adapt | TTS = MiMO cloud (OpenAI-compatible, WAV + PCM16 SSE streaming) via OkHttp; also Android `TextToSpeech` as fallback. ASR = source uses Harmony CoreSpeechKit → Android `android.speech.SpeechRecognizer`. Playback via `AudioTrack`. Digital avatar = layered 2.5D static image + animated ring/wave (Compose `animateFloatAsState`), text fallback. |
| 9 | **Local model** | `entry/src/main/cpp/*` (napi_init.cpp, HIAIModelManager.cpp, MNN headers), `docs/local_mnn/prompt-loading.md` | **Blocked → Adapt later** | Source loads an `MNN` LLM via N-API (`libentry.so`) and a HiAI/NNRT path (`HIAIModelManager`). MNN has Android builds → port the C++ LLM via JNI; drop the Harmony-only HiAI/OMC pieces. In-app model download flow (`ModelDownloadConfig`) → Android DownloadManager. Controlled disabled state until JNI model loader lands. |
| 10 | **Cloud model** | `utils/CloudModelClient.ets`, `CloudDeciderPrompt.ets`, `CloudLogOutput.ets`, `gallery/GalleryEmbeddingService.ets` | Direct/Adapt | OpenAI-compatible `/v1/chat/completions` already used → OkHttp + kotlinx.serialization. Supports Planner/Decider separate base URLs, `/v1/chat/completions`, cancellation, retries. Preserve streaming + error states. |
| 11 | **HDC / GUI automation** | `utils/MobileGuiTask*.ets`, `HdcWorkflowBridge.ets`, `PcServerMonitor.ets`; `python/hdc_server.py`, `python/harmony_agent.py` | **Blocked → Adapter** | Harmony auto-agent drives a physical device via **PC-side Python over HDC** (`hdc fport` + HTTP bridge on :9124, in-app TCP :9126). On Android: UIAutomator / adb over `adb reverse` or a PC-side Appium/adb service. Arbitrary cross-app GUI automation on Android requires either root / Shizuku / AccessibilityService — implement a cloud/PC bridge adapter first, plus a controlled disabled state. |
| 12 | **Python bridge** | `entry/src/main/python/*` (hdc_server.py :9124, harmony_agent.py, serve_model.py :9123, wechat_collect/*) | **Blocked → Defer** | Not runnable on-device on Android. Keep as a PC-side component; Android app talks to it over HTTP (localhost/LAN). Do not bundle Python in the APK. |
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
   Ship `<devices> off` + "requires PC bridge" message until adapter lands.
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
