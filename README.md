# ClawMate Android — Profile→Home Pipeline (Ph16) + Profile/Avatar (Ph12) + OCR (Ph11) + Local Model (Ph10) + Gallery Detail (Ph9) + HDC Bridge (Ph8) + Voice (Ph7) + Home (Ph6) + Settings (Ph5) + Task/Workflow (Ph4) + Agent (Ph3) + Chat (Ph2)

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

**Phase 9** advances the **Gallery / collection detail + local analysis**
(汇总 tab). Tapping a photo opens a real detail flow
(`gallery/ui/GalleryDetailScreen.kt`) that shows only real data: content URI,
archive date, EXIF capture time / device / dimensions, analysis state, tags /
summary and collection membership — nothing fabricated. It adds explicit safe
edits (title / summary / tags) persisted to Room and a **delete-by-item** action
with confirmation that removes only that single stored row (the photo file and
its Photo-Picker URI grant are never touched, and groups recompute). Reverse
geocoding is now a real, **explicitly injected** Android capability
(`gallery/geocode/`) backed by the platform `android.location.Geocoder` — no
network key, no endpoint config — with typed unavailable / permission / lookup
errors; the detail panel exposes a 解析/重试 action and **never claims a location
when a lookup fails**, persisting a resolved place only after a real success.
At Phase 9 OCR remained an explicit **NOT_CONFIGURED** boundary; **Phase 11**
replaces that boundary with a real on-device Chinese OCR adapter (see below).
Cloud vision stays NOT_CONFIGURED. The capability UI reflects the real Ready /
NotConfigured / Unsupported states and shows retry/unsupported messages. Room
schema bumped to v2 (adds `address` / `locationProvider` / `analysisState`) via
an explicit non-destructive migration. Deterministic unit tests (39 new) cover
detail mapping, edit/delete persistence, geocoder result/error mapping,
capability states and group recomputation.

**Phase 10** adds a truthful **local model capability boundary** for the Harmony
on-device MNN/HiAI LLM (`entry/src/main/cpp/*` + `ModelDownloadConfig.ets` +
model-scan logic in `pages/Index.ets`):
- A typed domain (`localmodel/`) with states **NOT_CONFIGURED / UNSUPPORTED /
  LOADING / READY / ERROR** and typed error kinds. Model inventory is discovered
  under the app-private `filesDir/model` (legacy) and `filesDir/modelscope/
  {owner}__{repo}` (ModelScope), requiring `config.json` + a weight file
  (`.mnn`/`.om`/`.safetensors`/`.weight`) to be considered loadable — ported
  directly from `Index.ets`.
- An app-private **path validator** that rejects `..`/`.`/escape/non-whitelisted
  bases so model files stay in the sandbox and no arbitrary file/dir deletion is
  ever offered.
- A **JNI/CMake native adapter** (`app/src/main/cpp/`, built by AGP CMake) that
  compiles `libclawmate_local_model.so` with the Android NDK (25.2.9519653,
  CMake 3.22.1) for arm64-v8a / armeabi-v7a / x86_64. Because MNN-for-Android
  and the OHOS N-API/HiAI runtime are absent it is a **truthful stub** returning
  typed `UNSUPPORTED`/`NATIVE_LIBRARY_MISSING` results — it never fabricates a
  loaded model or output.
- An explicit **"加载/测试" action** in the 模型与智能体 Settings section: the
  selected model dir is re-validated and READY is reported **only after a real
  native load returns success**. With the stub this honestly reports
  NOT_CONFIGURED / UNSUPPORTED / ERROR. Cloud chat paths are unchanged and app
  launch performs no network and no model load.
- Deterministic unit tests (38 new) covering state normalization, path
  validation, inventory scanning, and the no-fake-output guarantee
  (missing library / missing file mapping).

**Phase 11** replaces the gallery's Phase-9 OCR `NOT_CONFIGURED` boundary with a
**real on-device Chinese OCR adapter** (`gallery/ocr/`) backed by ML Kit
`com.google.mlkit:text-recognition-chinese:16.0.1` (resolved on Google Maven,
bundled Chinese + Latin model):
- `AndroidOcrEngine` opens a picked photo's `content://` URI via the platform
  `ContentResolver` (picker URI grant — **no broad storage permission**), decodes
  a **bounded** bitmap (≤2048 px side, honest 64 MB pixel budget), runs Chinese
  text recognition on a background dispatcher, and maps every outcome to the
  typed `OcrResult` states (`Text / Empty / Cancelled / ModelDownload /
  Failed(kind) / Unavailable`) with a stable `OcrErrorKind` taxonomy.
  Only `OcrResult.Text` carries real recognized text — nothing fabricated.
- Pure, device-free `OcrMapping`/`OcrBitmapDecode` concentrate the decision logic
  so the no-fake-output guarantee and the bounded-decode planning are
  deterministic and unit-tested without a device.
- The gallery detail panel gains an explicit **Analyze OCR** action with
  loading/success/empty/error/model-download/unavailable states
  (`GalleryViewModel.analyzeOcr`); only real recognized text (or a genuine
  empty) and the `analyzed` status are persisted via `GalleryDao.updateOcr`.
  Failures / model-download / unavailable persist nothing and never claim text.
- Cloud vision stays NOT_CONFIGURED and reverse geocoding is unchanged. Live ML
  Kit runtime / any on-device model handling still require a real device
  (documented — not claimed as on-device-verified). 27 new deterministic unit
  tests (407 total green in this worktree).

**Phase 12** is a **coverage audit** plus the highest-value real gap from it: a
truthful **Profile / Avatar local UI & state** slice (`profile/avatar/`),
reached from the Home avatar card:
- A full source-to-target feature matrix comparing the HarmonyOS `ets`
  inventory against Phases 1–11 (see `MIGRATION_STATUS.md` §10), focusing on
  Profile/Avatar, AutoAgent, float/edge/log, gallery embedding & monthly
  insights, and WeChat auto-summary. Every gap is marked Done / Boundary /
  Portable-pending / Blocked with its exact remaining work.
- **Local profile UI & state** — typed avatar domain (`profile/avatar/domain/
  AvatarDomain.kt`) porting the pure `datamanager/avatar/AvatarTypes.ets`
  contracts, plus a locally edited, device-persisted profile (display name /
  short summary / gender / age / 吃穿住行 domain summaries & claims) stored in
  app-private DataStore. This is genuinely on-device, with **no cloud and no
  secrets**, and exposes a deterministic completeness reducer.
- **Honest avatar-generation boundary** (`domain/AvatarGenerationGate.kt`): the
  Home avatar card now opens a real 数字分身 screen that reports
  `NOT_CONFIGURED` / `CONFIGURED_BUT_NO_PROVIDER` for the cloud image generator
  (Seedream base URL + API key + wired provider would be needed). It **never
  fabricates a generated avatar**; the seedream generation and
  foreground-crop/thumbnail processing remain documented remaining gaps.
- The Home avatar section changes from a dead disabled button to a working
  entry point into this page. Profile persistence and the honest boundary are
  covered by deterministic unit tests. AutoAgent, float/edge overlay, WeChat
  collection, and (partially) embedding remain honest controlled/blocked states,
  and gallery monthly insights + WeChat settings normalization were documented
  as portable-pending at Phase 12 time (both since shipped — see Phase 13 and
  Phase 15 below).

**Phase 13** delivers the portable WeChat auto-summary **settings +
normalization + validation** slice (previously marked Portable-pending in the
Phase 12 inventory E1), plus a **typed PC/HDC collection boundary** (E2):
- A pure port of `utils/wechat/WechatAutoSummaryTypes.ets` into
  `wechat/domain/WechatAutoSummaryTypes.kt`: the settings keys/defaults, the
  bounded normalizers (`days` 1..90, `max_contacts` 1..50, `swipe_speed`
  0..20000, ratio 0.1..0.95, `stable_swipes` 1..10, `max_history_swipes`
  1..300, `wait` 0..10, `recent_contacts`/`target_contact` mode) and the
  `validateWechatAutoSummaryRequest` validator returning a user-presentable
  Chinese error (empty when valid).
- A **real Settings center section** (微信自动汇总, a sixth section) exposing the
  fields through the existing DataStore-backed `SettingsRepository` /
  `SettingsCodec` (`SettingsKeys.WECHAT_*`), with values normalized on change,
  honest saved/error states, and no fabricated capability.
- A **typed PC/HDC collection boundary** (`wechat/domain/WechatCollectionGate.kt`)
  that derives a truthful `BLOCKED` / `NOT_CONFIGURED` state from the Phase-8
  verified bridge. It **never drives WeChat, never sends arbitrary commands,
  and `collect()` never fakes a summary** — the Android `WorkflowDeviceAdapter`
  exposes no safe `wechat_collect` action, so even a verified generic bridge
  stays BLOCKED. Real collection remains the PC-side Python/HDC service gap.

**Phase 14** delivers the portable in-app **Log viewer** (previously marked
Portable-pending in the Phase 12 inventory C2, based on Harmony
`pages/LogView.ets` / `utils/AppLogger.ets` / `utils/CloudLogOutput.ets`):
- A **bounded in-app ring buffer** (`log/data/RingBufferInAppLogRepository.kt`,
  default 512 records, oldest-evicted FIFO) fed by real app code paths (app
  launch, settings saved + HDC health-check result, chat send / complete /
  error, gallery OCR states). It **never fabricates** a record and never
  pretends to read logcat, a native C++ buffer, or AppStorage strings we do not
  own.
- **Sensitive-value redaction on insert** (`log/domain/SensitiveLogRedactor.kt`):
  `Authorization`/Bearer/Basic, `api_key`, `sk-*`, GitHub `ghp_`/`ghs_` tokens,
  AWS credentials and generic `password=`/`secret=`/`token=` values are scrubbed
  before a record is stored, so a raw secret can never be rendered or copied
  from the viewer.
- A **Compose Log screen** (`log/ui/LogScreen.kt` + `LogViewModel`) reachable
  from a new 运行日志 Settings section (`SettingsSectionId.RuntimeLogs`): source
  tabs (应用 / 云端 Agent / 图库分析 / 任务自动化), severity + free-text filters,
  Refresh, auto-refresh ticker, auto-scroll, copy visible text, clear-source /
  clear-all, a live count / capacity footer, and an **honest empty state** that
  never shows an event that did not happen.
- **Deterministic unit tests** (`app/src/test/java/com/clawmate/app/log/` +
  extended `SettingsSectionTest`): `SensitiveLogRedactorTest`,
  `RingBufferInAppLogRepositoryTest`, `LogFilterTest`, and the seventh section /
  badge "L".

**Phase 15** delivers the portable Gallery **monthly insights** (previously
marked Portable-pending in the Phase 12 inventory D2, based on Harmony
`utils/gallery/GalleryMonthlyInsights.ets`):
- A **pure port** (`gallery/insights/GalleryMonthlyInsights.kt`) of the monthly
  bucketing / tag-count / local-summary / LLM-input builder, mapping directly
  over the real Room-backed `GalleryPhotoItem`s already stored (Phases 3/9/11).
  It buckets items into calendar months from `date` (falling back to
  `createdAt`, then an honest 时间未知 group), computes per-month tag counts
  (sorted desc, Harmony-stable tie order), produces a deterministic local
  summary, keeps up to 6 representative items, and builds an LLM-input text
  block capped at 80 samples — all from **stored data only**. It never scans
  media and never fabricates an insight; `buildLlmInput` only string-formats the
  items already loaded.
- **Observable derivation** (`GalleryRepository.observeMonthlyInsights`) and an
  in-memory `DefaultGalleryRepository` `map` over `observeItems()`, so insights
  refresh automatically when items change.
- A **real 月度洞察 panel** in the existing Gallery screen
  (`ui/insights/MonthlyInsightsPanel.kt` + `GalleryInsightsViewModel`) with
  honest Loading / Empty / Error / Loaded states: month chips + per-month total,
  deterministic category-count bars/chips, the local summary, up to 6
  representative items (tap to open the detail), and a truthful NOT_CONFIGURED
  boundary for the cloud AI monthly summary / vector embedding
  (`insights/GalleryMonthlyInsightCloudState.kt`) — no fabricated summary.
- **Deterministic unit tests** (`app/src/test/java/com/clawmate/app/gallery/
  insights/` + `ui/insights/` + a repository derivation test): bucketing
  (date / createdAt fallback / unknown), newest-month-first and within-month
  newest-first ordering, tie-order stability, tag cleaning/dedup + category
  fallback, the 6-representative cap, the 80-line LLM-input cap + determinism +
  "无" empty block, the ViewModel Loading→Loaded/Empty/Error state machine, and
  repository-item-driven derivation. 490 unit tests, 0 failures, 0 errors.

## Layout — features added in Phase 16

```
app/src/main/java/com/clawmate/app/home/domain/HomeProfileBuilder.kt           # Pure port of DataManagerFacade.ets
                                                                            #   homeProfileDomains/homeProfileDomainDescription/
                                                                            #   homeProfileDomainFullDescription/domainClaimStatements
app/src/main/java/com/clawmate/app/home/data/ProfileBackedHomeProfileSource.kt # Real HomeProfileSource: persisted local profile → home VM
(mod) app/src/main/java/com/clawmate/app/ui/ClawMateRoot.kt                   # default profile source → ProfileBackedHomeProfileSource(DataStoreProfileAvatarRepository)

app/src/test/java/com/clawmate/app/home/HomeProfileBuilderTest.kt            # pillar/completeness/claims/empty derivation
app/src/test/java/com/clawmate/app/home/ProfileBackedHomeProfileSourceTest.kt # real source over the fake profile repository
```

**Phase 16** finishes the **A1 gap** documented at Phase 12 — the persisted
local profile now **flows into the real Home recommendation pipeline**:
- A pure, deterministic `HomeProfileBuilder` maps the four locally-curated
  吃/穿/住/行 `AvatarDomainSnapshot`s onto the Home pillars (`desc` / `fullDesc` /
  `claims` / `eventCount`) following the HarmonyOS
  `datamanager/DataManagerFacade.ets` contracts (`homeProfileDomainDescription` /
  `homeProfileDomainFullDescription` / `domainClaimStatements`), reusing the
  existing `defaultHomeDomains()` and `homeProfileCompleteness()`. Because the
  Android profile is **typed on-device** (not index-derived), a pillar is
  populated by the content it actually contains — never an invented event count.
- A real `ProfileBackedHomeProfileSource` reads the app-private DataStore profile
  (the existing `ProfileAvatarRepository`) and returns the derived home VM. The
  live app now constructs this source as the default in `ClawMateRoot`, so the
  home pillars / completeness reflect exactly what the user curated in the
  数字分身 screen — no longer a hard-coded "暂无数据" shell.
- **Truthful capability boundaries preserved**: the recommendation list stays
  empty (there is no indexed-todo content source on Android — the Harmony
  `homeRecommendations()` pipeline is documented as deferred) and the avatar
  section stays NOT_CONFIGURED (no generated avatar image is ever invented).
  An entirely empty profile is still the honest `HomeProfileViewModel.empty`
  (backward compatible with Phases 1–15). Deterministic tests cover pillar
  derivation, claims/event counts, completeness, empty/incomplete states and
  source behaviour.

## Layout — features added in Phase 15

```
app/src/main/java/com/clawmate/app/gallery/insights/
├── GalleryMonthlyInsights.kt            # Pure Harmony GalleryMonthlyInsights.ets port:
│                                        #   build() bucketing / tag-counts / summary / representatives
│                                        #   + buildLlmInput() (80-sample cap)
├── GalleryMonthlyInsight.kt             # GalleryMonthInsight / GalleryMonthCategoryCount models
└── GalleryMonthlyInsightCloudState.kt   # Honest NOT_CONFIGURED boundary for cloud AI summary / embedding

app/src/main/java/com/clawmate/app/gallery/ui/insights/
├── GalleryInsightsViewModel.kt          # Loading / Empty / Loaded / Error state machine over observeMonthlyInsights
└── MonthlyInsightsPanel.kt              # 月度洞察 card: month chips, totals, category bars/chips,
                                         #   local summary, representative items, cloud NOT_CONFIGURED notice

(mod) app/src/main/java/com/clawmate/app/gallery/data/GalleryRepository.kt      # + observeMonthlyInsights()
(mod) app/src/main/java/com/clawmate/app/gallery/data/DefaultGalleryRepository.kt # pure map over observeItems()
(mod) app/src/main/java/com/clawmate/app/gallery/ui/GalleryScreen.kt              # wire the 月度洞察 panel
```

## Layout — features added in Phase 14

```
app/src/main/java/com/clawmate/app/log/
├── AppLogger.kt                   # Static in-app logger (Harmony AppLogger.ets port): i/w/e/cloud/gallery/workflow
├── LogComponent.kt                # Process-wide shared repository (manual DI, like Chat/Gallery/Settings components)
├── domain/
│   ├── InAppLogTypes.kt           # LogLevel / LogSource / immutable redacted InAppLogEntry (key for LazyColumn)
│   ├── LogFilter.kt               # Pure source+level+query filter projection (newest-first preserved)
│   └── SensitiveLogRedactor.kt    # Deterministic secret redaction applied on insert
├── data/
│   └── RingBufferInAppLogRepository.kt  # Capacity-bounded FIFO ring buffer + StateFlow for Compose
└── ui/
    ├── LogScreen.kt               # Source tabs / severity+query filter / refresh / auto-refresh / auto-scroll
    │                              #   / copy / clear-source / clear-all / honest empty state / footer counts
    ├── LogViewModel.kt            # Viewer chrome state + projects the shared buffer through pure filterLogs
    └── LogViewModelFactory.kt

(mod) app/src/main/java/com/clawmate/app/ClawMateApplication.kt     # init LogComponent + real "App" launch event
(mod) app/src/main/java/com/clawmate/app/chat/ui/ChatViewModel.kt   # real send / complete / error cloud-agent events
(mod) app/src/main/java/com/clawmate/app/gallery/ui/GalleryViewModel.kt # real OCR run/empty/model/failed gallery events
(mod) app/src/main/java/com/clawmate/app/settings/domain/SettingsSection.kt # seventh 运行日志 section (badge "L")
(mod) app/src/main/java/com/clawmate/app/settings/ui/SettingsScreen.kt  # route the 运行日志 section to LogScreen
(mod) app/src/main/java/com/clawmate/app/settings/ui/SettingsViewModel.kt # settings-saved + HDC health-check events

app/src/test/java/com/clawmate/app/log/
├── SensitiveLogRedactorTest.kt        # redaction patterns (no raw secret retained)
├── RingBufferInAppLogRepositoryTest.kt# retention/eviction, scoped & full clear, min-level, redact-on-insert, truncation
└── LogFilterTest.kt                   # source/level/query, case-insensitive, tag, limit, ordering
(mod) app/src/test/java/com/clawmate/app/settings/SettingsSectionTest.kt  # seven sections + badge "L"
```

```
app/src/main/java/com/clawmate/app/gallery/ocr/
├── AndroidOcrEngine.kt      # Real ML Kit Chinese OCR adapter (ContentResolver → bounded Bitmap → recognition)
├── OcrBitmapDecode.kt       # Pure bounded-decode planning (inSampleSize + memory-budget guard)
└── OcrMapping.kt            # Pure result mapping (real text vs empty / typed failure / no-fake-output)

app/src/main/java/com/clawmate/app/gallery/
├── (mod) CapabilityEngines.kt        # Typed OcrResult / OcrErrorKind; suspend OcrEngine.recognize
├── (mod) GalleryComponent.kt         # ocr(context) now returns the real androidOcrEngine adapter
├── (mod) data/GalleryDao.kt / GalleryRepository.kt / DefaultGalleryRepository.kt  # updateOcr
├── (mod) model/GalleryModels.kt      # ANALYSIS_STATE_ANALYZED = "analyzed"
├── (mod) ui/GalleryViewModel.kt      # analyzeOcr + OcrViewState + detail OCR state
├── (mod) ui/GalleryDetailScreen.kt   # Analyze OCR section (load/success/empty/error/model states)
├── (mod) ui/GalleryScreen.kt         # wiring onAnalyzeOcr
└── (mod) ui/GalleryDetailLabels.kt   # honest "已分析（本地 OCR）" label

(modified) app/build.gradle.kts, gradle/libs.versions.toml  # ML Kit text-recognition-chinese dependency
app/src/test/java/com/clawmate/app/gallery/ocr/            # OcrMappingTest (12) + OcrBitmapDecodeTest (8)
app/src/test/java/com/clawmate/app/gallery/ui/OcrAnalysisViewModelTest.kt  # 6 tests
app/src/test/java/com/clawmate/app/gallery/data/GalleryRepositoryEditDeleteTest.kt  # +updateOcr test
```

## Layout — features added in Phase 12

```
app/src/main/java/com/clawmate/app/profile/avatar/
├── domain/
│   ├── AvatarDomain.kt            # Typed port of datamanager/avatar/AvatarTypes.ets
│   │                              #   (profile snapshot, domain snapshot, consent, state,
│   │                              #    version VM, 4 persona domains, completeness, error text)
│   └── AvatarGenerationGate.kt    # Honest NOT_CONFIGURED / CONFIGURED_BUT_NO_PROVIDER boundary
├── data/
│   ├── ProfileAvatarRepository.kt          # persistence boundary
│   ├── DataStoreProfileAvatarRepository.kt # app-private DataStore (JSON, tolerant decode)
│   └── FakeProfileAvatarRepository.kt      # deterministic in-memory fake (tests)
└── ui/
    ├── ProfileAvatarViewModel.kt  # draft editing + save validation + honest gate wiring
    ├── ProfileAvatarScreen.kt     # editable local profile + generation status card + snackbar
    └── ProfileAvatarHost.kt       # self-contained entry (DataStore repo + settings repo)

(mod) app/src/main/java/com/clawmate/app/home/ui/HomeScreen.kt  # avatar card → ProfileAvatarHost
app/src/test/java/com/clawmate/app/profile/avatar/              # AvatarDomainTest + ProfileAvatarViewModelTest
```

## Layout — features added in Phase 13

```
app/src/main/java/com/clawmate/app/wechat/
└── domain/
    ├── WechatAutoSummaryTypes.kt    # Pure port of WechatAutoSummaryTypes.ets (keys/defaults/modes,
    │                                #   request/response/message/conversation types, bounded normalizers,
    │                                #   validateWechatAutoSummaryRequest)
    └── WechatCollectionGate.kt      # Typed PC/HDC collection boundary: BLOCKED / NOT_CONFIGURED from the
                                     #   Phase-8 verified bridge; collect() never dispatches or fakes a summary

(mod) app/src/main/java/com/clawmate/app/settings/domain/SettingsKeys.kt    # WECHAT_* keys (+ ALL_KEYS)
(mod) app/src/main/java/com/clawmate/app/settings/domain/AppSettings.kt     # wechatAutoSummary* / wechatCollection* fields
(mod) app/src/main/java/com/clawmate/app/settings/domain/SettingsSection.kt # sixth WechatAutoSummary section
(mod) app/src/main/java/com/clawmate/app/settings/data/SettingsCodec.kt     # encode/decode the WeChat fields
(mod) app/src/main/java/com/clawmate/app/settings/ui/SettingsViewModel.kt   # setters + wechatCollectionGate()/summary request
(mod) app/src/main/java/com/clawmate/app/settings/ui/SettingsScreen.kt      # section dispatch
(new) app/src/main/java/com/clawmate/app/settings/ui/WechatSettingsSection.kt # real section UI + live boundary card

app/src/test/java/com/clawmate/app/wechat/  # WechatAutoSummaryTypesTest (15) + WechatSettingsCodecTest (4) + WechatCollectionGateTest (4)
(mod) app/src/test/java/com/clawmate/app/settings/SettingsSectionTest.kt    # six sections + badge "W"
```

## Layout — features added in Phase 10

```
app/src/main/java/com/clawmate/app/localmodel/
├── LocalModelTypes.kt        # Typed status/error domain + normalization + labels
├── LocalModelPathValidator.kt# App-private model-dir path validation (no traversal/deletion)
├── LocalModelInventory.kt    # App-private inventory scan (config.json + weight file) via probe seam
├── LocalModelCapability.kt   # Honest boundary: READY only on real native load success
├── LocalModelNative.kt       # JNI binding to the native stub (typed results, no fake output)
├── LocalModelViewModel.kt    # ViewModel backing the Settings local-model panel
└── LocalModelPanel.kt        # Settings panel: status + inventory + explicit 加载/测试

app/src/main/cpp/
├── CMakeLists.txt            # Android CMake build of the native stub
└── local_model_jni.cpp       # JNI stub: status()/nativeLoad() return typed UNSUPPORTED

(modified) app/build.gradle.kts                     # externalNativeBuild cmake + ndkVersion 25.2.9519653 + abiFilters
(modified) settings/ui/SettingsSections.kt          # LocalModelPanel wired into the 模型与智能体 section
app/src/test/java/com/clawmate/app/localmodel/      # 5 deterministric test classes (38 tests)
```

## Layout — features added in Phase 9

```
app/src/main/java/com/clawmate/app/gallery/geocode/
├── LocationModels.kt            # ReverseGeocodeOutcome / ReverseGeocodeErrorKind / GalleryLocationInfo
├── AddressFormatter.kt          # Pure single-line place formatter (device-free, deterministic)
├── AndroidGeocoderClient.kt     # Platform android.location.Geocoder client + pure mapping (AddressFields)
└── AndroidReverseGeocoder.kt    # Explicit injected ReverseGeocoder capability

app/src/main/java/com/clawmate/app/gallery/ui/
├── GalleryDetailScreen.kt       # Item detail flow (URI/date/EXIF/state/tags/summary/membership + edit/delete + location)
└── GalleryDetailLabels.kt       # Pure deterministic label mapping

(modified) GalleryViewModel.kt / GalleryScreen.kt / GalleryComponent.kt / CapabilityEngines.kt
(modified) GalleryModels.kt / GalleryEntities.kt / GalleryDao.kt / GalleryMappers.kt
(modified) GalleryRepository.kt / DefaultGalleryRepository.kt / gallery database v1→v2 migration

app/src/test/java/com/clawmate/app/gallery/...      (geocode + ui + data edit/delete tests, extended capability test)
```

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

Prereqs: JDK 17+, Android SDK (compileSdk 34), **Android NDK (the local-model
native stub pins `ndkVersion = "25.2.9519653"`) + Google CMake**, Gradle 8.9 (a
wrapper can be generated with `gradle wrapper`).

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
                                        # Phase 9: geocoder mapping/error mapping, detail labels,
                                        # edit/delete persistence, capability states, group recomputation
                                       # Phase 10: local-model state normalization, path validation,
                                       # inventory scanning, no-fake-output (missing lib/model) mapping
                                       # Phase 11: OCR result mapping (real text/empty/typed failure),
                                       # bounded-bitmap decode planning, Analyze-action persistence
./gradlew :app:connectedAndroidTest     # instrumented ASR/TTS + ML Kit OCR (device/emulator; not run in this worktree)
```

In this worktree the Phase-2 through Phase-11 builds and unit tests were actually
executed with a local JDK 17 / Android SDK (platform 34) / **Android NDK
25.2.9519653 + CMake 3.22.1** / Gradle 8.9 — commands and results (including the
Phase-11 commands and exit codes) are recorded in `MIGRATION_STATUS.md` §6 and
`DSH_PHASE_RESULT.md`. `connectedAndroidTest` was not run because no
emulator/device is available (the instrumented voice tests are documented as
unavailable without a device; the platform Geocoder reverse lookup runs on-device
only and is not faked in unit tests). Device/native local-model smoke tests are
similarly unavailable here.

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

The **local model** capability performs **no network and no model load on app
launch**; the Settings 模型与智能体 section scans app-private `filesDir` and the
"加载/测试" action is explicit. Model files must live under `filesDir/model` /
`filesDir/modelscope` and are validated by an app-private path checker (no
arbitrary file/dir deletion). Because MNN-for-Android and the OHOS N-API/HiAI
runtime are not vendored, the native stub returns typed
NOT_CONFIGURED / UNSUPPORTED — the app never fabricates a loaded model or
output.
