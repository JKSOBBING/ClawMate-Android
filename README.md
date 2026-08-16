# ClawMate Android — Gallery / Collection vertical slice (Phase 3)

This is the Android migration target for the ClawMate HarmonyOS app (source of
truth: `/root/mobiinfra-oh`, reference-only). Phase 1 shipped the skeleton
(5-tab Compose shell, secrets-free endpoint config); **Phase 2** added the chat /
cloud-model vertical slice. **Phase 3** adds the gallery / collection vertical
slice wired into the 汇总 tab: real Android Photo Picker import, Room persistence
of photo items / collection groups / searchable metadata, deterministic heuristic
grouping + weighted search, and honest NOT_CONFIGURED states for OCR / cloud
vision / reverse geocoding.

## Layout — features added in Phase 3 (gallery / collection)

```
app/src/main/java/com/clawmate/app/gallery/
├── model/GalleryModels.kt        # GalleryPhotoItem, CollectionGroup, GalleryExifMetadata,
│                                 #   stable content-URI -> id mapping
├── photo/PhotoPickerAdapter.kt   # System Photo Picker (PickMultipleVisualMedia, no broad storage
│                                 #   permission) + ContentResolver/ExifInterface metadata reader;
│                                 #   typed PickResult (Picked/Cancelled/Unavailable)
├── collection/CollectionGroupingService.kt  # deterministic heuristic grouping (port)
├── search/GallerySearchService.kt           # weighted full-text scoring (port)
├── analysis/CapabilityEngines.kt   # OCR / cloud vision / reverse geocoding as explicit
│                                   #   interface + typed NOT_CONFIGURED/UNSUPPORTED states
├── data/
│   ├── GalleryEntities.kt          # Room entities: items, collection groups, FTS4 search table
│   ├── GalleryDao.kt
│   ├── GalleryMappers.kt           # deterministic domain <-> entity (JSON literals for lists/EXIF)
│   ├── GalleryDatabase.kt          # Room DB, explicit migrations, schema export
│   ├── GalleryRepository.kt        # repository boundary
│   └── DefaultGalleryRepository.kt # Room-backed repo; dedupe-by-URI, group refresh, search
├── GalleryComponent.kt             # lightweight manual DI
└── ui/
    ├── GalleryViewModel.kt         # loading/loaded/error state + capability summary
    └── GalleryScreen.kt            # picker action, item list, empty/loading/error, groups, search
```

The 汇总 tab in `ui/ClawMateRoot.kt` now routes to `GalleryScreen`.

## No fake analysis

The gallery slice captures real photos, stores them, groups and searches their real
metadata. OCR, cloud vision and reverse geocoding are **not** implemented: they sit
behind explicit `CapabilityEngines` interfaces that report `NOT_CONFIGURED`, and the
screen says so. No fake OCR/vision text is ever produced.

> The sections below describe the Phase-1 skeleton and Phase-2 chat slice.

## Layout — features added in Phase 2

```
app/src/main/java/com/clawmate/app/chat/
├── model/ChatModels.kt            # ChatMessage/ChatSession roles, kinds, title/preview rules
├── cloud/
│   ├── OpenAiModels.kt            # OpenAI /v1/chat/completions wire models (kotlinx.serialization)
│   ├── ChatClient.kt              # ChatClient interface (completion + completionStream)
│   ├── ChatCompletionsClient.kt   # OkHttp client: non-streaming, SSE streaming, cancellation,
│   │                              #                          timeouts, HTTP + malformed-event errors
│   ├── SseParser.kt               # Deterministic SSE framing + chunk mapping
│   ├── ChatServerConfig.kt        # Secrets-free config + /v1/chat/completions URL normalisation
│   └── ChatClientTypes.kt         # ChatResult/ChatException error model
├── data/
│   ├── ChatDatabase.kt            # Room DB (@Entity ChatSession/ChatMessage, ChatDao)
│   ├── ChatMappers.kt             # domain <-> entity mappers + session-title derivation
│   ├── ChatRepository.kt          # ChatRepository interface + ChatSendEvent
│   └── DefaultChatRepository.kt   # Room + client-backed repository (no fake replies)
├── ChatComponent.kt               # Lightweight manual DI for the chat feature
└── ui/
    ├── ChatViewModel.kt           # Real ViewModel: send, history, incremental streaming,
    │                              #                       loading/empty/error/missing-config states
    ├── ChatViewModelFactory.kt
    └── ChatScreen.kt              # Compose chat UI (LazyColumn history, input, states)
```

The Phase-1 `ui/FeatureScreens.kt` chat placeholder was replaced by the real
`ChatScreen`; the tab shell (`ui/ClawMateRoot.kt`) now routes the 聊天 tab to it.

## How the pieces connect

1. `EndpointConfig` loads a git-ignored `raw/local_config.properties`
   (`cloudPlannerBaseUrl`, `cloudPlannerApiKey`, `cloudPlannerModel`).
2. `ChatServerConfig` normalises the base URL to `/v1/chat/completions`.
3. `ChatCompletionsClient` posts with OkHttp; the streaming path decodes SSE via
   `SseEventParser`/`ChatStreamChunkParser` and surfaces a cancellable `Flow`.
4. `DefaultChatRepository` persists the user message, streams the reply, and
   persists the final assistant message. Missing-config is a typed
   `ChatException(CONFIG)`; no assistant message is ever stored unless the model
   actually produced one.
5. `ChatViewModel` drives the screen state; `ChatScreen` renders history,
   incremental deltas, empty, loading, error, and missing-config states.

## Build & verify

Prereqs: JDK 17+, Android SDK (compileSdk 34), Gradle 8.9 (a wrapper can be
generated with `gradle wrapper`).

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest        # SSE parsing, mapping, errors, repository tests
./gradlew :app:connectedAndroidTest     # instrumented (device/emulator)
```

In this worktree the Phase-2 and Phase-3 builds and unit tests were actually
executed with a local JDK 17 / Android SDK (platform 34) / Gradle 8.9 —
commands and results are recorded in `MIGRATION_STATUS.md` §6 and
`DSH_PHASE_RESULT.md`. The Phase-3 run used `GRADLE_USER_HOME` /
`ANDROID_USER_HOME` pointed outside the read-only `/root` (see
`MIGRATION_STATUS.md` §0).

## No secrets

Endpoints/keys are not in this tree. Copy
`app/src/main/res/raw/config_example.properties` to
`app/src/main/res/raw/local_config.properties` (git-ignored) and fill in real
values (`cloudPlannerBaseUrl` / `cloudPlannerApiKey` / `cloudPlannerModel`).
With no configuration the chat tab shows a "尚未配置云端模型" state instead of
faking a reply.
