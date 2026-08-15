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
| JDK | **Not installed** on this machine |
| Android SDK | **Not installed** on this machine |
| Gradle | **Not installed** on this machine |
| Network for dependency download | Not verified (no build attempted) |

**Blocker for build verification:** This machine lacks Java, the Android SDK,
and Gradle, so a `./gradlew` build cannot be executed here. Per AGENTS.md,
repository-level checks that do not need those tools are run instead and
recorded, and the blocker is documented here. The skeleton is a standard
Android Gradle project (AGP 8.5.2, Kotlin 1.9.24, Compose BOM) intended to
compile on any machine with Android Studio / JDK 17+. Install the Gradle
wrapper and SDK, then run `./gradlew :app:assembleDebug :app:testDebugUnitTest`
to verify.

> ⚠️ **Write-access blocker for this delivery:** the sandbox running the agent
> that produced phase 1 could write only to its own workspace, not to this
> repository. All phase-1 deliverables (this file, the skeleton, `.gitignore`)
> were therefore authored in the agent workspace and must be copied into
> `/root/ClawMate-Android` and committed (see `PHASE1_SHORTLIST` / commit hash
> in section 9).

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
  values from git-ignored `app/src/main/res/raw/local_config.properties`;
  `config.example.properties` is the committed safe template with empty values.
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

Phase 1 (repository-level, no JDK/SDK/Gradle needed):
- JSON/XML/Kotlin files reviewed for well-formedness; build files follow the
  standard AGP/Catalog layout.
- Unit tests authored (JUnit4): `EndpointConfigTest`, `DestinationTest` — run
  once the SDK/JDK is available via `./gradlew :app:testDebugUnitTest`.
- Instrumented smoke test `MainActivityTest` (Compose) for the 5-tab shell.

**Next phases must run** (on a machine with the toolchain):
`./gradlew :app:assembleDebug :app:testDebugUnitTest :app:connectedAndroidTest`

---

## 7. Open items / risks

- Gradle wrapper (`gradlew`, `gradle/wrapper/*`) is **not included** — it must
  be generated (`gradle wrapper`) on the first machine with Gradle installed.
- Launcher icon is a placeholder adaptive icon; real media brought over from
  the HarmonyOS `resources` later.
- Embedding is stubbed off even in the HarmonyOS source — treat as optional.
- `data_extraction_rules` currently excludes backup of app DB/prefs to avoid
  leaking locally-stored personal data; revisit policy with product before
  enabling backup.

---

## 8. Changelog

- **Phase 1 (this phase):** source inventory; feature mapping; Android strategy
  per feature; compiling-for-the-toolchain Android skeleton (applicationId
  `com.clawmate.app`, 5-tab Compose navigation shell, Material3 theme, resources,
  unit + instrumented test source sets, network/backup security config);
  `.gitignore`; endpoint config with no secrets in source control.

---

## 9. Phase 1 delivery notes

- **Blocker:** no JDK / Android SDK / Gradle on the authoring machine, and the
  sandbox could not write into `/root/ClawMate-Android`. All phase-1 artifacts
  were authored as a copy-ready tree. Install them and commit with a short note.
  Verify build per section 6 before starting phase 2.
