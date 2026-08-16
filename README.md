# ClawMate Android — PC/HDC Bridge (Ph8) + Voice (Ph7) + Home (Ph6) + Settings (Ph5) + Task/Workflow (Ph4) + Agent (Ph3) + Chat (Ph2)

This is the Android migration target for the ClawMate HarmonyOS app (source of
truth: `/root/mobiinfra-oh`, reference-only). Phase 1 shipped the skeleton
(5-tab Compose shell, secrets-free endpoint config). **Phase 2** added the chat /
cloud-model vertical slice: chat message & session models, an OpenAI-compatible
`/v1/chat/completions` client (non-streaming + SSE streaming), Room persistence,
a real `ChatViewModel` and Compose chat screen, and deterministic unit tests.
**Phase 3** adds a bounded Agent/ReAct vertical slice on top of the same client:
typed tool-call/tool-result wire models (incl. streaming tool-call deltas), a
safe Android tool registry with controlled-disabled adapters, a cancellable
coroutine/Flow ReAct loop with max-step + timeout guards, and minimal chat-UI
integration to surface tool execution and disabled/error states.
**Phase 4** adds a bounded Task/Workflow vertical slice on the 任务 tab:
tolerant workflow JSON parsing (metadata/defaults/context/steps incl. nested
`loop`/`if` and unknown-step preservation), bundled workflow configs +
app-private persistence, a real Compose Task screen (scene/filter bar, cards,
expandable step summary), and a bounded, honest `WorkflowRunner` that reports
every GUI/HDC/device-control step as `UNSUPPORTED/BLOCKED` (and VLM tools as
`NOT_CONFIGURED`) instead of fabricating a result.
**Phase 5** replaces the Settings-tab placeholder with a truthful five-section
Settings/Configuration vertical slice: a typed DataStore-backed repository with
safe/default normalization, sensitive-value masking/reveal/edit (masked values
are never persisted), the `EndpointBridge` seam into the runtime `EndpointConfig`
the chat/gallery read, and honest NOT_CONFIGURED / DISABLED states for any
unconfigured endpoint — never claiming connectivity.
**Phase 6** replaces the Home-tab placeholder and adds a first-launch onboarding
gate before the 5-tab shell: a real Compose onboarding (three capability
preview slides + usage guide, 跳过/下一页/进入首页, versioned completion persisted
to app-private DataStore) and a truthful Home screen (four persona pillars,
completeness bar, recommendation list with completion toggles + pagination, and
an honest NOT_CONFIGURED avatar section — avatar generation is DISABLED on
Android and never fabricated).
**Phase 7** adds a truthful chat **voice-interaction vertical slice**
(`chat/voice/`): typed voice domain models / status / metadata with
deterministic normalization (port of `VoiceInteractionTypes.ets`), ASR
transcript merge + duplicate suppression (port of
`HarmonySpeechRecognizerAsrProvider.ets`), an Android `SpeechRecognizer` ASR
adapter with explicit **permission / unavailable / error / cancel** states and
**no hidden microphone access** (RECORD_AUDIO is surfaced in the UI and only
requested on the mic tap), a lifecycle-safe Android `TextToSpeech` adapter
(speak / pause / stop / replay, typed unavailable/error states), a PCM16→WAV
builder and MiMO voice presets, and cloud MiMO streaming/TTS kept behind an
explicit **NOT_CONFIGURED** interface — never claimed configured or faked. The
chat UI gains mic / listening / transcript and speak / stop / replay controls;
a real final ASR transcript becomes ordinary editable chat input.
**Phase 8** adds a truthful **PC HDC bridge adapter** (`task/hdc/`) that lets GUI
workflow steps talk to the PC-side Python bridge over HTTP — but only when a
caller explicitly supplies a configured adapter:
- A typed OkHttp `HdcBridgeClient` for `GET {base}/api/health` and
  `POST {base}/api/workflow` with normalized URLs, bounded connect/read/call
  timeouts, cooperative cancellation, tolerant response parsing, and typed
  transport errors (HTTP / malformed-JSON / TIMEOUT / IO / CANCELLED /
  INVALID_URL). No `Runtime.exec`, `ProcessBuilder`, shell, `adb`, `hdc`,
  AccessibilityService, `/api/run_cmd`, or arbitrary command execution —
  only the safe protocol calls `app_start` / `screenshot` / `gui_action` /
  `execute_decider_action`.
- A `WorkflowDeviceAdapter` boundary whose default `DisabledWorkflowDeviceAdapter`
  keeps every GUI/HDC step `UNSUPPORTED/BLOCKED` (a run is never fabricated as
  success). `TaskComponent` registers the adapter **explicitly and only after**
  a live health check reports `ok` + control-ready.
- A settings **explicit "测试 HDC 健康"** button (activity performed only on
  click; app launch performs **no network call**). A connected state is reported
  only after a real successful response; failures keep the bridge
  NOT_CONFIGURED / DISABLED and show an honest error.
- Deterministic unit tests (49 new) for tolerant parsing, URL normalization,
  the disabled adapter, runner integration (SUCCESS / FAILED / BLOCKED), errors,
  timeout/deadline handling, and cancellation.

## Layout — features added in Phase 8

```
app/src/main/java/com/clawmate/app/task/hdc/
├── HdcBridgeModels.kt          # Tolerant typed models + parsing (health/workflow envelopes)
├── HdcUrlNormalizer.kt         # Deterministic scheme://host[:port]/path normalization
├── HdcBridgeClient.kt          # Typed client boundary (HdcBridgeResult / HdcBridgeErrorKind)
├── OkHttpHdcBridgeClient.kt    # OkHttp impl: bounded timeouts, cancellation, typed errors
├── WorkflowDeviceAdapter.kt    # Safe device-control boundary + default disabled adapter
└── BridgeBackedWorkflowDeviceAdapter.kt # Health-gated protocol adapter (no run_cmd / no exec)

app/src/main/java/com/clawmate/app/task/TaskComponent.kt          (modified: explicit setDeviceAdapter; default disabled)
app/src/main/java/com/clawmate/app/task/runner/WorkflowRunner.kt  (modified: gui_action routes through a configured adapter; else BLOCKED)
app/src/main/java/com/clawmate/app/settings/ui/SettingsViewModel.kt (modified: explicit testHdcHealth() + injected client factory)
app/src/main/java/com/clawmate/app/settings/ui/SettingsSections.kt / SettingsUiState.kt (modified: explicit health button + honest status)

app/src/test/java/com/clawmate/app/task/hdc/*Test.kt             (5 new test classes)
app/src/test/java/com/clawmate/app/task/runner/WorkflowRunnerAdapterIntegrationTest.kt (new)
app/src/test/java/com/clawmate/app/settings/SettingsHdcHealthTest.kt                  (new)
```

## Layout — features added in Phase 7

```
app/src/main/java/com/clawmate/app/chat/voice/
├── VoiceInteractionTypes.kt      # Typed status/metadata + normalization, VoiceSessionController [Phase 7]
├── VoiceTranscriptLogic.kt       # ASR cumulative/committed merge + duplicate-final suppression
├── VoiceOrchestratorLogic.kt     # Voice-input start/stop generation guards
├── VoiceTtsPresets.kt            # MiMO TTS voice whitelist + role presets
├── PcmWav.kt                     # PCM16 → WAV bytes + chunk concat
├── ChatVoiceController.kt        # Orchestrator: real ASR input + Android TTS output → chat draft
├── asr/
│   └── AndroidSpeechRecognizerAsrAdapter.kt   # android.speech.SpeechRecognizer; permission/unavailable/error/cancel
└── tts/
    ├── AndroidTextToSpeechTtsAdapter.kt       # android.speech.TextToSpeech; lifecycle-safe init, speak/pause/stop/replay
    └── CloudMimoVoiceClient.kt                # MiMO cloud STREAMING/TTS — NOT_CONFIGURED (never faked)

app/src/main/java/com/clawmate/app/chat/ui/ChatViewModel.kt   (modified: hosts ChatVoiceController; voice state/badge + actions)
app/src/main/java/com/clawmate/app/chat/ui/ChatViewModelFactory.kt (modified: wires ChatVoiceController(context))
app/src/main/java/com/clawmate/app/chat/ui/ChatScreen.kt      (modified: mic/permission, listening/transcript banner, speak/stop/replay)
app/src/test/java/com/clawmate/app/chat/voice/*Test.kt        (8 new test classes, 55 tests)
```

## Layout — features added in Phase 6

```
app/src/main/java/com/clawmate/app/home/
├── domain/
│   ├── OnboardingTypes.kt          # Pure onboarding contract (slides, version key, gate fn) [Phase 6]
│   ├── HomeDomain.kt               # Four persona pillars (吃/穿/住/行) + completeness
│   ├── HomeRecommendation.kt       # Recommendation view model / persisted state / colors
│   ├── HomeProfile.kt              # Profile aggregate + loading/ready/error states
│   ├── RecommendationListLogic.kt  # Pure pagination / filter / completion reductions
│   └── AvatarStatus.kt             # Avatar statuses + honest notConfigured section
├── data/
│   ├── OnboardingRepository.kt     # + DataStoreOnboardingRepository / fake
│   ├── HomeRecRepository.kt        # + DataStoreHomeRecRepository / fake (completion flags)
│   └── HomeProfileSource.kt        # Profile source seam (empty profile until data-manager)
└── ui/
    ├── HomeScreen.kt               # Real Compose Home (avatar, pillars, rec list, pagination)
    ├── HomeViewModel.kt            # + factory (completion toggles, filter, pagination)
    └── onboarding/
        ├── FirstLaunchOnboardingScreen.kt  # 3 capability slides + usage guide
        └── OnboardingViewModel.kt          # + factory (next/skip/finish, versioned completion)

ui/ClawMateRoot.kt  (modified: onboarding gate before the 5-tab root; Home tab → home/ui)
```

## Layout — features added in Phase 5

```
app/src/main/java/com/clawmate/app/settings/
├── SettingsComponent.kt            # Manual DI (DataStore repository)            [Phase 5]
├── config/EndpointBridge.kt        # Seam: Settings <-> runtime EndpointConfig (mask-guarded)
├── domain/
│   ├── SettingsSection.kt          # SettingsSectionId + 5 section descriptors
│   ├── SettingsKeys.kt             # Typed key inventory (sensitive subset flagged)
│   ├── AppSettings.kt              # Typed snapshot + configured/connectivity facts
│   ├── SettingsNormalizers.kt      # Deterministic URL/model/int/double/bool/enum normalizers
│   └── SensitiveValueUtils.kt      # Mask/isMasked/restore port (SensitiveValueUtils.ets)
├── data/
│   ├── SettingsRepository.kt       # Repository interface
│   ├── SettingsCodec.kt            # Encode/decode + sanitizeForWrite (mask never persisted)
│   ├── DataStoreSettingsRepository.kt # Preferences-backed store
│   └── FakeSettingsRepository.kt   # In-memory store used by tests/previews
└── ui/
    ├── SettingsScreen.kt           # Section list + detail navigation + save/reset/loading/error
    ├── SettingsSections.kt         # The five section composables
    ├── SettingsControls.kt         # Group cards, inputs, sensitive input, toggles, actions
    ├── SettingsTheme.kt            # SettingsCenterTheme palette
    ├── SettingsViewModel.kt        # Draft editing, sensitive reveal, save/reset
    ├── SettingsViewModelFactory.kt
    └── SettingsUiState.kt          # UI state + StorageSummary

config: app/src/main/java/com/clawmate/app/config/EndpointConfig.kt  (modified:
        added applyCloudEndpoint, mask-guarded)
```

## Layout — features added in Phase 4

```
app/src/main/java/com/clawmate/app/task/
├── TaskComponent.kt                # Manual DI (repository + bounded runner)      [Phase 4]
├── model/
│   ├── WorkflowModels.kt           # WorkflowStep/Definition/Task + metadata/defaults/condition
│   └── WorkflowSceneConfig.kt      # The 7 collection scenes (from WorkflowSceneConfig.ets)
├── json/
│   ├── WorkflowJsonParser.kt       # Tolerant parser (nested if/loop, unknown-step preservation)
│   └── WorkflowJsonRepair.kt       # Deterministic text repair (fence, truncation, casing, CJK)
├── data/
│   ├── WorkflowRepository.kt       # Repository interface + BundledConfigReader
│   ├── DefaultWorkflowRepository.kt# file-backed, bundles configs into filesDir/workflows
│   ├── WorkflowTaskIndexCodec.kt   # deterministic task-index.json codec
│   └── AndroidBundledConfigReader.kt # reads res/raw bundled configs
├── runner/
│   ├── WorkflowVariableResolver.kt # ${steps.x.output.y} / ${context.k} / ${loop.index|last}
│   └── WorkflowRunner.kt           # bounded, honest runner (gui/hdc -> UNSUPPORTED)
└── ui/
    ├── TaskViewModel.kt            # scenes/filter, cards, expand, run, states
    ├── TaskViewModelFactory.kt
    └── TaskScreen.kt               # Compose Task screen (wired into the 任务 tab)

resource bundled configs: app/src/main/res/raw/default_*.json   (secrets-free)
```

## Layout — features added in Phase 2

```
app/src/main/java/com/clawmate/app/chat/
├── model/ChatModels.kt            # ChatMessage/ChatSession roles, kinds, title/preview rules
├── cloud/
│   ├── OpenAiModels.kt            # OpenAI /v1/chat/completions wire models (+ Phase 3 tool-call models)
│   ├── ChatClient.kt              # ChatClient interface (completion + completionStream)
│   ├── ChatCompletionsClient.kt   # OkHttp client: non-streaming, SSE streaming, cancellation,
│   │                              #                          timeouts, HTTP + malformed-event errors
│   ├── SseParser.kt               # Deterministic SSE framing + chunk mapping (incl. tool-call deltas)
│   ├── ChatServerConfig.kt        # Secrets-free config + /v1/chat/completions URL normalisation
│   └── ChatClientTypes.kt         # ChatResult/ChatException error model
├── agent/                         # Phase 3 Agent/ReAct vertical slice
│   ├── ToolModels.kt              # ToolCall/ToolDefinition wire + ToolExecutionResult domain
│   ├── ToolRegistry.kt            # Safe Android tool registry + controlled-disabled adapters
│   ├── AgentActionParser.kt       # Pure text-JSON action parsing (port of AgentActionParser.ets)
│   └── AgentLoop.kt               # Cancellable ReAct loop: max-step + timeout guards, typed errors
├── data/
│   ├── ChatDatabase.kt            # Room DB (@Entity ChatSession/ChatMessage, ChatDao)
│   ├── ChatMappers.kt             # domain <-> entity mappers + session-title derivation
│   ├── ChatRepository.kt          # ChatRepository interface + ChatSendEvent (+ ToolExecuted)
│   └── DefaultChatRepository.kt   # Room + client-backed repository (sendMessage + sendAgentMessage)
├── ChatComponent.kt               # Lightweight manual DI for the chat feature
└── ui/
    ├── ChatViewModel.kt           # Real ViewModel: send, history, incremental streaming,
    │                              #                       loading/empty/error/missing-config states
    ├── ChatViewModelFactory.kt
    └── ChatScreen.kt              # Compose chat UI (LazyColumn history, input, states, tool status)
```

The Phase-1 `ui/FeatureScreens.kt` chat placeholder was replaced by the real
`ChatScreen`; the tab shell (`ui/ClawMateRoot.kt`) now routes the 聊天 tab to it.

## How the pieces connect

1. `EndpointConfig` loads a git-ignored `raw/local_config.properties`
   (`cloudPlannerBaseUrl`, `cloudPlannerApiKey`, `cloudPlannerModel`).
2. `ChatServerConfig` normalises the base URL to `/v1/chat/completions`.
3. `ChatCompletionsClient` posts with OkHttp; the streaming path decodes SSE via
   `SseEventParser`/`ChatStreamChunkParser` and surfaces a cancellable `Flow`
   (content deltas and, in Phase 3, streaming tool-call deltas).
4. `DefaultChatRepository` persists the user message, streams the reply, and
   persists the final assistant message. Missing-config is a typed
   `ChatException(CONFIG)`; no assistant message is ever stored unless the model
   actually produced one. For the **agent path** (`sendAgentMessage`),
   `AgentLoop` runs the ReAct state machine over the same client: each turn
   streams content + tool-call fragments; tools run through the safe
   `ToolRegistry` (controlled-disabled adapters explain any PC bridge/
   permission requirement); max-step and overall-timeout guards bound the loop.
5. `ChatViewModel` drives the screen state from streamed deltas plus
   `ToolExecuted` events; `ChatScreen` renders history, incremental deltas,
   tool-execution status, empty, loading, error, and missing-config states.

## Build & verify

Prereqs: JDK 17+, Android SDK (compileSdk 34), Gradle 8.9 (a wrapper can be
generated with `gradle wrapper`).

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest        # SSE parsing, mapping, tool-call mapping, action parsing,
                                        # ReAct loop guards, disabled adapters, repository tests,
                                        # Phase 4: workflow JSON parse/repair, nested steps,
                                        # repository persistence, unsupported GUI/HDC, runner cancel/bounds
                                        # Phase 6: onboarding version/section nav, persistence round-trips,
                                        # recommendation completion + pagination, empty/disabled states
                                        # Phase 7: voice status/metadata normalization, transcript append,
                                        # replay guards, ASR merge/duplicate suppression, orchestration
                                        # guards, TTS presets, unavailable/permission/error mappings,
                                        # PCM→WAV, MiMO NOT_CONFIGURED truthfulness
                                        # Phase 8: HDC bridge tolerant parsing, URL normalization,
                                        # disabled adapter, OkHttp health/workflow mapping, timeouts,
                                        # cancellation, runner <-> adapter integration, settings
                                        # explicit health flow (no network on launch)
./gradlew :app:connectedAndroidTest     # instrumented ASR/TTS (device/emulator; not run in this worktree)
```

In this worktree the Phase-2 through Phase-8 builds and unit tests were actually
executed with a local JDK 17 / Android SDK (platform 34) / Gradle 8.9 — commands
and results (including the Phase-8 commands and exit codes) are recorded in
`MIGRATION_STATUS.md` §6 and `DSH_PHASE_RESULT.md`. `connectedAndroidTest` was
not run because no emulator/device is available (the instrumented voice tests
are documented as unavailable without a device).

## No secrets

Endpoints/keys are not in this tree. Copy
`app/src/main/res/raw/config_example.properties` to
`app/src/main/res/raw/local_config.properties` (git-ignored) and fill in real
values (`cloudPlannerBaseUrl` / `cloudPlannerApiKey` / `cloudPlannerModel`).
With no configuration the chat tab shows a "尚未配置云端模型" state instead of
faking a reply. The first-launch onboarding, Home profile/avatar sections and
the Phase-7 voice interaction read no network or never invent successful data:
onboarding works on a fresh, configuration-free install; Home reports the honest
empty / NOT_CONFIGURED (avatar DISABLED) states until real Android data sources
exist; and voice ASR uses the on-device `SpeechRecognizer` (RECORD_AUDIO surfaced
and requested only on the mic tap — no fake transcript), with output via the
on-device `TextToSpeech` engine — cloud MiMO streaming/TTS stays explicitly
**NOT_CONFIGURED** and never claims to be connected.

The Phase-8 **PC HDC bridge** is DISABLED by default and makes **no network call
on app launch**. `TaskComponent` starts with the disabled adapter, so every
GUI/HDC workflow step stays `BLOCKED` until a user explicitly taps the settings
"测试 HDC 健康" button; a connected state is reported only after a live
`GET /api/health` `ok` + control-ready reply, and the bridge-backed adapter
performs only the safe protocol calls (never `/api/run_cmd`, shell/adb/hdc,
`ProcessBuilder`, `Runtime.exec`, or AccessibilityService).
