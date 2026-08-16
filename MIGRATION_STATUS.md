# ClawMate Android Migration — Status & Feature Mapping

Source of truth (reference only, do not modify): `/root/mobiinfra-oh`
Target: this repository (`/opt/deepseek-harness/clawmate-phase3-gallery`)

> Phase 1 of N. This file records the source inventory, source-of-truth
> locations, and the chosen Android implementation strategy for each major
> feature. It is updated at the end of every phase (AGENTS.md — Collaboration
> protocol).

---

## 0. Environment & build status

| Item | Status |
| --- | --- |
| JDK | **17.0.19 (OpenJDK)** — installed and used for Phases 2–3 |
| Android SDK | **platform 34 + build-tools 34.0.0** at `/opt/deepseek-harness/android-sdk` — used for Phases 2–3 |
| Gradle | **8.9** at `/opt/deepseek-harness/gradle-dist/gradle-8.9` — used for Phases 2–3 |
| Network for dependency download | **Working** (Maven Central / Google) |

**Phase 2 and Phase 3 build verification were actually executed in this worktree**
(`/opt/deepseek-harness/clawmate-phase3-gallery`) with the toolchain above:
`:app:assembleDebug` and `:app:testDebugUnitTest` both passed (see §6 for the
exact commands and output). Because the sandbox cannot write into
`/root/.gradle` / `/root/.android`, the phase-3 build ran with
`GRADLE_USER_HOME` and `ANDROID_USER_HOME` pointed outside the read-only home.

> **Phase 1 (historical) blocker:** the original authoring machine lacked Java,
> Android SDK, and Gradle, and the sandbox could not write into
> `/root/ClawMate-Android`. All phase-1 deliverables were authored as a
> copy-ready tree. Phase 2 was built and tested in the Phase-2 worktree;
> Phase 3 was built and tested in this worktree directly.

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

**Phase 3 — executed in this worktree** (JDK 17, Android SDK platform 34,
Gradle 8.9; run as `/opt/deepseek-harness/gradle-dist/gradle-8.9/bin/gradle
--no-daemon --max-workers=1` with `ANDROID_HOME`,
`GRADLE_USER_HOME=/opt/deepseek-harness/.gradle-user-home`,
`ANDROID_USER_HOME=/opt/deepseek-harness/.android-user-home`). Exact commands
and output are in `DSH_PHASE_RESULT.md`.

- `:app:assembleDebug` — **PASS** (BUILD SUCCESSFUL, exit 0).
- `:app:testDebugUnitTest` — **PASS** (`66 tests, 0 failures`; Phase-2 + Phase-3).
- Combined `:app:assembleDebug :app:testDebugUnitTest` — **PASS** (exit 0).
- Instrumented smoke test `MainActivityTest` (5-tab shell) is present but was
  **not run** here: no emulator/device is available (`connectedAndroidTest`
  requires one).

Unit tests added in Phase 3 (deterministic; no Android runtime dependencies):
- `gallery/model/GalleryIdsTest` — stable content-URI→id mapping (same URI
  ⇒ same id, different URIs ⇒ different ids, blank ⇒ empty).
- `gallery/collection/CollectionGroupingServiceTest` — empty input, no
  cross-group duplication, same-key grouping, time-desc sort, group title/meta
  and id, chat-tab gallery-chat detection.
- `gallery/search/GallerySearchServiceTest` — empty query, title-over-summary
  ranking, tag-match reason, limit, score-desc ordering.
- `gallery/data/GalleryMapperTest` — entity↔domain round-trip, list/EXIF JSON
  serialisation, missing-EXIF null, group round-trip.
- `gallery/analysis/CapabilityEnginesTest` — OCR/vision/geocoder report
  NOT_CONFIGURED and never fabricate output.

Unit tests carried from Phase 2 (chat / cloud): SSE parsing, response mapping,
errors, repository behaviour, endpoint config (still green in Phase 3).

**Every phase after this must run**:
`./gradlew :app:assembleDebug :app:testDebugUnitTest :app:connectedAndroidTest`

---

## 7. Open items / risks

- **Gallery analysis (OCR / vision / geocoding) is NOT implemented.** The Phase-3
  slice stores real picked photos and groups/searches them, but on-device OCR
  (would need ML Kit), cloud vision (would need the configured model endpoint)
  and reverse geocoding (would need an AMap key) remain behind explicit
  NOT_CONFIGURED interfaces (`gallery/analysis/CapabilityEngines.kt`). They must
  be wired up before the gallery can auto-tag or auto-locate photos.
- **Photo-pick import and metadata are not covered by automated tests.** The
  Photo Picker / ContentResolver path needs an Android runtime (device/emulator)
  or Robolectric; only the pure URI mapping is unit-tested. This is the same
  instrumentation gap as the Phase-2 chat slice.
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

- **Phase 3 (this phase):** Gallery / collection vertical slice.
  - Android gallery domain models (`gallery/model/GalleryModels.kt`):
    `GalleryPhotoItem`, `CollectionGroup`, `GallerySearchHit`,
    `GalleryExifMetadata`, stable content-URI→id mapping (`GalleryIds`) —
    mirrored from `utils/gallery/GalleryTypes.ets` and
    `docs/gallery/storage-format.md`.
  - Room persistence (`gallery/data/`): `GalleryItemEntity`,
    `CollectionGroupEntity` and an FTS4 `gallery_items_fts` entity + `GalleryDao`.
    Mappers (`GalleryMappers`) round-trip list/EXIF JSON deterministically.
    Migrations are explicit (`GalleryDatabase.MIGRATIONS`; no destructive
    fallback) and schemas are exported to `app/schemas`.
  - Real Photo Picker / MediaStore adapter (`gallery/photo/PhotoPickerAdapter.kt`):
    uses the system Photo Picker via `PickMultipleVisualMedia` (content URIs,
    no broad storage permission requested) and `ContentResolver`/`ExifInterface`
    for date/model/dimensions/GPS. Outcomes are typed `PickResult.Picked /
    Cancelled / Unavailable`; inaccessible URIs and missing metadata are typed
    (skipped / null), never fabricated.
  - Heuristic collection grouping (`gallery/collection/CollectionGroupingService.kt`)
    ported from `datamanager/CollectionGroupingService.ets`; weighted search
    (`gallery/search/GallerySearchService.kt`) ported from
    `utils/gallery/GallerySearchService.ets`.
  - Repository + DI (`gallery/data/GalleryRepository.kt`,
    `DefaultGalleryRepository.kt`, `gallery/GalleryComponent.kt`) with
    deterministic upsert/dedupe-by-URI, group refresh and search.
  - Compose screen (`gallery/ui/GalleryScreen.kt` + `GalleryViewModel.kt`) wired
    into the 汇总 tab (`ui/ClawMateRoot.kt`): picker action, loaded item list,
    empty / loading / error states, group chips and basic search. No fake photos
    or hardcoded demo data.
  - OCR / cloud vision / reverse geocoding stay behind explicit interfaces
    (`gallery/analysis/CapabilityEngines.kt`) that report NOT_CONFIGURED; they
    are **not** implemented and the UI says so.
  - Deterministic unit tests: `GalleryIdsTest`, `CollectionGroupingServiceTest`,
    `GallerySearchServiceTest`, `GalleryMapperTest`, `CapabilityEnginesTest`.
  - Added `androidx.exifinterface` dependency; exported Room schemas.
- **Phase 2:** Chat / cloud-model vertical slice.
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
