# ClawMate Android — Chat / cloud-model slice (Phase 2) + Agent/ReAct slice (Phase 3) + Task/Workflow slice (Phase 4)

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
./gradlew :app:connectedAndroidTest     # instrumented (device/emulator)
```

In this worktree the Phase-2, Phase-3 and Phase-4 builds and unit tests were
actually executed with a local JDK 17 / Android SDK (platform 34) / Gradle 8.9 —
commands and results (including the Phase-4 commands and exit codes) are
recorded in `MIGRATION_STATUS.md` §6 and `DSH_PHASE_RESULT.md`.

## No secrets

Endpoints/keys are not in this tree. Copy
`app/src/main/res/raw/config_example.properties` to
`app/src/main/res/raw/local_config.properties` (git-ignored) and fill in real
values (`cloudPlannerBaseUrl` / `cloudPlannerApiKey` / `cloudPlannerModel`).
With no configuration the chat tab shows a "尚未配置云端模型" state instead of
faking a reply.
