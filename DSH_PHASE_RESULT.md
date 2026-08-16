# DSH_PHASE_RESULT — ClawMate Android, Phase 3 (Agent / ReAct vertical slice)

## Worktree

- Path: `/opt/deepseek-harness/clawmate-phase3-agent`
- Branch: `master`
- Source of truth (reference only, **not modified**): `/root/mobiinfra-oh`
- This phase extends the Phase-2 chat/cloud-model vertical slice in-place. The
  work was done **only** under this worktree; `/root/ClawMate-Android` and
  `/root/mobiinfra-oh` were not touched.

## Commit

- Message: `Phase 3 agent react vertical slice`
- Hash: `a24de8d`
- Staged files at commit: 19 files, +1945 / −25 (see "Changed files" below).

## Scope delivered (Phase 3 — Agent / ReAct)

This phase adds a bounded, cancellable Agent / ReAct capability on top of the
existing OpenAI-compatible client, with **no arbitrary shell execution** and
**no fake model replies**. Delivered pieces:

1. **Typed OpenAI tool-call request/response + streaming deltas**
   (`chat/cloud/OpenAiModels.kt`, `ChatClientTypes.kt`, `SseParser.kt`):
   - `tools` / `tool_choice` fields on `ChatCompletionsRequest`;
   - nullable message `content` + `tool_call_id` / `tool_calls` / `name` on
     `ChatMessageWire` (an assistant turn may carry only tool calls);
   - `ToolDefinitionWire` / `ToolFunctionDefinitionWire` / `ToolCallWire` /
     `ToolCallFunctionWire`;
   - **streaming tool-call deltas**: `CloudStreamChunk.toolCallDeltas`,
     `StreamToolCallDelta`, and `StreamToolCallAccumulator` (port of
     `CloudModelClient.applyStreamToolCallDeltas`) that concatenate id/name/
     arguments fragments and `compact()` empty calls; SSE chunk parser
     extraction is added in `SseParser.kt`. Ordinary content streaming is
     preserved (regression-tested).

2. **Safe tool registry with controlled-disabled HDC/GUI/overlay results**
   (`chat/agent/ToolRegistry.kt`, `ToolModels.kt`):
   - explicit safe Android adapters: `get_current_datetime`, `get_device_info`;
   - **controlled-disabled** adapters for real HarmonyOS capabilities that do
     not exist on this Android build — `gui_screen_tap`, `gui_type_text`,
     `gui_swipe`, `capture_screen`, `open_app`, `float_overlay_status` —
     each returning a typed `ToolResultKind.DISABLED` result explaining the
     required PC bridge (adb/hdc + UIAutomator/Appium) or runtime permission.
     No `Runtime.exec` / `ProcessBuilder` with model-controlled strings; no fake
     success (AGENTS.md: do not silently drop, do not pretend a mock is real).
   - argument-JSON validation, unknown-tool / adapter-failure typed errors
     (`errorJson`), result truncation to a stable bound, and a
     `ToolTerminalException` escape hatch that stops the loop.

3. **Cancellable bounded ReAct loop** (`chat/agent/AgentLoop.kt`):
   - a cold, cancellable coroutine `Flow` state machine over
     `ChatClient.completionStream`;
   - per-turn streaming of content + tool-call fragments; when a turn has no
     tool calls the accumulated text is the final answer (never faked);
   - **max-step guard** (`maxIterations`, default 30) → `reachedLimit` result;
   - **overall timeout** (`timeoutMs`) → typed `AgentException(TIMEOUT)`;
   - terminal tool results stop the loop; collector cancellation propagates into
     the HTTP stream.
   - pure `AgentActionParser` (`chat/agent/AgentActionParser.kt`) port of
     `AgentActionParser.ets` (tolerant JSON extraction/unwrapping, terminal
     `done`/`stop`/`terminate`/`abort` detection).

4. **Chat UI / ViewModel tool-state integration**
   (`chat/ui/ChatViewModel.kt`, `ChatScreen.kt`, `chat/data/*`):
   - `ChatSendEvent.ToolExecuted`; `DefaultChatRepository.sendAgentMessage`
     persists the user message, runs the ReAct loop over stored history, streams
     assistant deltas + tool traces, and persists the finished assistant message
     only on success;
   - ViewModel surfaces `activeToolTrace` / `agentRunning`, shows a tool-status
     pill in the chat UI, renders persisted tool traces under assistant messages
     and appends an actionable note when an action was disabled (no fake success).
   - Phase-2 chat screen/history was not rewritten (already working).

5. **Deterministic tests** (no network; see §Verification below).

## Verification (actually executed, with the toolchain below)

Toolchain: `ANDROID_HOME=/opt/deepseek-harness/android-sdk`,
`/opt/deepseek-harness/gradle-dist/gradle-8.9/bin/gradle`, JDK 17, Android SDK
platform 34 / build-tools 34.0.0, `--no-daemon --max-workers=1`.
`GRADLE_USER_HOME` was pointed at a writable dir under the workspace because the
default `~/.gradle` was not writable under the file sandbox at startup
(`libnative-platform.so` load failure) — making that one adjustment, the exact
requested command below runs cleanly.

| Command | Result |
| --- | --- |
| `gradle :app:assembleDebug :app:testDebugUnitTest` | **BUILD SUCCESSFUL — exit code 0**, 46 tasks (45 up-to-date, 1 executed) |
| `gradle :app:compileDebugKotlin :app:compileDebugUnitTestKotlin --rerun-tasks` | **BUILD SUCCESSFUL — exit code 0** (fresh main + test compilation of current sources; 2 benign `w:` warnings) |
| `gradle :app:testDebugUnitTest --rerun-tasks` | **BUILD SUCCESSFUL — exit code 0** (all unit tests actually executed) |

Unit-test breakdown (from `app/build/test-results/testDebugUnitTest/*.xml`):

| Suite | Tests | Failures |
| --- | --- | --- |
| ChatCompletionsClientTest (MockWebServer) | 4 | 0 |
| ChatClientMappingTest | 12 | 0 |
| SseParserTest | 13 | 0 |
| DefaultChatRepositoryTest | 5 | 0 |
| EndpointConfigTest | 2 | 0 |
| DestinationTest | 3 | 0 |
| **Phase 3:** ToolCallMappingTest | 6 | 0 |
| **Phase 3:** AgentActionParserTest | 9 | 0 |
| **Phase 3:** ToolRegistryTest | 8 | 0 |
| **Phase 3:** AgentLoopTest | 5 | 0 |
| **Phase 3:** AgentRepositoryTest | 2 | 0 |
| **Total** | **69** | **0** |

`:app:connectedAndroidTest` (instrumented smoke test) was **not run** — no
device/emulator is available; it is the Phase-1 `MainActivityTest`, unrelated to
Phase-3 code.

## Changed files (19, this commit)

- Docs: `MIGRATION_STATUS.md`, `README.md`
- Chat cloud main sources (modified): `chat/cloud/{OpenAiModels,ChatClientTypes,ChatCompletionsClient,SseParser}.kt`
- Chat data + UI (modified): `chat/data/{ChatRepository,DefaultChatRepository}.kt`,
  `chat/ui/{ChatViewModel,ChatScreen}.kt`
- **New** `chat/agent/`: `ToolModels.kt`, `ToolRegistry.kt`, `AgentActionParser.kt`, `AgentLoop.kt`
- **New** tests: `test/.../chat/agent/{ToolCallMappingTest,AgentActionParserTest,ToolRegistryTest,AgentLoopTest}.kt`,
  `test/.../chat/data/AgentRepositoryTest.kt`

Not committed (git-ignored): `.gradle/`, `app/build/`, `local.properties`,
`GRADLE_USER_HOME` contents.

## Blockers / gaps

- **No device/emulator**: `connectedAndroidTest` cannot be run here; the
  Compose instrumented smoke test remains unverified.
- **No live end-to-end**: no real (or authorized) model endpoint is bundled.
  The tool-call / streaming delta request path is exercised against fakes and
  the protocol contract; a live smoke against a configured
  `local_config.properties` is a manual integration step.
- **Controlled-disabled adapters only**: on-device GUI/HDC/overlay actions are
  intentionally not executed on Android; they surface a typed disabled result
  explaining the required bridge/permission (product-correct, no fake success).
- Gradle native-services need a writable `GRADLE_USER_HOME`; on this sandbox the
  default `~/.gradle` is unwritable, so the build ran with a workspace-local
  user home.

## Secrets scan

Pattern scans (`sk-`, `AIza`, `ghp_`, `AKIA`, `xox[baprs]-`, private-key
headers, `api_key=`/`Bearer ...`/`password=/secret=/token=` assignments) were
run over the new agent sources/tests, the full diff, and the docs. **No real
credentials, tokens, private keys, or production endpoints committed.** The only
host in the new test code is `planner.example.com` (a clearly non-production
example fixture). Endpoints/keys continue to load from the git-ignored
`app/src/main/res/raw/local_config.properties` (safe template:
`config_example.properties`).

## Next phase

Suggestions for Phase 4 (not part of this commit):

- Streaming thinking/reasoning deltas + markdown/rich rendering
  (`ChatStreamThinkingDelta` / `MarkdownParser` port).
- Session list/drawer UI + delete/rename and multi-session navigation backed by
  the existing Room schema; persist per-session agent tool selections.
- Live integration check against a configured model endpoint on a device/
  emulator, and run `:app:connectedAndroidTest`.
