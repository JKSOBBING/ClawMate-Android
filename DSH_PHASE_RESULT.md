# Phase 21 result: config-driven Gallery cloud adapter (D1 remote embedding + D2 AI month summary) behind explicit settings; reuses OpenAI-compatible client/config (no new credentials, no silent upload); BUILD SUCCESSFUL; 602 tests, 0 failures, 0 errors.
# Phase 20 result: Android overlay capability gate and explicit SYSTEM_ALERT_WINDOW opt-in; BUILD SUCCESSFUL; 578 tests, 0 failures, 0 errors; no silent overlay.
# Phase 19 result: controlled Android accessibility boundary for AutoAgent; BUILD SUCCESSFUL; 557 tests, 0 failures, 0 errors; no arbitrary GUI execution.
# Phase 18 result: local avatar asset storage/version history and real image picker boundary; full test run BUILD SUCCESSFUL; 540 tests, 0 failures, 0 errors; commit follows.
# Phase 17 result: A4 profile-share import/merge; compile and unit-test verification completed; 521 tests, 0 failures, 0 errors; commit follows.
# Phase 16 result: A1 profile-to-Home pipeline; :app:testDebugUnitTest --rerun-tasks BUILD SUCCESSFUL; 502 tests, 0 failures, 0 errors; commit follows.
# DSH_PHASE_RESULT — ClawMate Android, Phase 15 (Gallery monthly insights: pure bucketing / tag-count / LLM-input port + real Loading/Empty insights panel)

## Worktree

- Path: `/opt/deepseek-harness/clawmate-phase15-gallery-insights`
- Branch: `phase15-gallery-monthly-insights`
- Source of truth (reference only, not modified): `/root/mobiinfra-oh`
  (`entry/src/main/ets/utils/gallery/GalleryMonthlyInsights.ets`,
  `GalleryTypes.ets`, `pages/gallery/GalleryPage.ets`).
- Built and tested in this worktree (JDK 17.0.19 OpenJDK, Android SDK platform
  34 / build-tools 34.0.0 at `/opt/deepseek-harness/android-sdk`, Gradle 8.9 at
  `/opt/deepseek-harness/gradle-dist/gradle-8.9`).
- Build state used: `GRADLE_USER_HOME=/opt/deepseek-harness/.gradle-user-home`,
  `ANDROID_USER_HOME=/opt/deepseek-harness/.android-user-home`, and the same
  Gradle 8.9 release binary used in Phases 2–14 (the wrapper distribution URL is
  unreachable here). `/root/mobiinfra-oh` was not modified. All Phase 1–14 code
  is preserved.
- Working policy: serial only, single worker, no parallel Gradle processes, no
  servers/emulators, one Gradle build at a time. No media scan, no shell / adb /
  hdc, no cloud calls, no fake text.

## Commit

The Phase-15 changes are committed on branch `phase15-gallery-monthly-insights`.

## Commit hash

- `Phase 15 gallery monthly insights` →
  **2c82f402e20e066fd650e786f6cd21e8e664d77a**.

## Scope delivered (Phase 15)

1. **Pure port** of Harmony `GalleryMonthlyInsights.ets`
   (`gallery/insights/GalleryMonthlyInsights.kt`, row D2 that was previously
   **Portable-pending**): calendar-month bucketing from `date` (falling back to
   `createdAt`, then an honest `0000-00` / 时间未知 group), per-month tag counts
   (sorted desc, stable ties exactly as the .ets stable sort), a deterministic
   local summary, up to 6 representative items, and a `buildLlmInput` text block
   capped at 80 samples with the exact "月份/总数/标签分布/本地初步摘要/图片摘要清单"
   header contract. Works **only** over the real Room-backed `GalleryPhotoItem`s
   already stored (Phases 3/9/11). No media scan, no fabricated insight, no
   cloud call — `buildLlmInput` only string-formats the items passed in.
2. **Observable derivation** (`GalleryRepository.observeMonthlyInsights()`): a
   pure `map` over `observeItems()` in `DefaultGalleryRepository`, so insights
   stay in sync with stored items automatically and always reflect only what is
   really in Room.
3. **Real Loading / Empty / Error / Loaded insights state in the existing
   Gallery UI** (`GalleryScreen` + new `ui/insights/MonthlyInsightsPanel.kt` +
   `GalleryInsightsViewModel`): a 月度洞察 card shows an honest Loading spinner
   while derivation is pending, an honest Empty state when there are no stored
   items, month chips + per-month totals, deterministic category-count
   bars/chips, the local summary, up to 6 representative items (tap to open the
   existing detail panel via `vm.selectItem`), and a truthful NOT_CONFIGURED
   boundary for the cloud AI monthly summary / vector embedding
   (`gallery/insights/GalleryMonthlyInsightCloudState.kt`) — nothing is ever
   fabricated.
4. **Deterministic unit tests** (`app/src/test/java/com/clawmate/app/gallery/
   insights/` `GalleryMonthlyInsightsTest`, `ui/insights/GalleryInsightsViewModelTest`,
   plus a repository-derivation test in `GalleryRepositoryEditDeleteTest`):
   bucketing (date / createdAt fallback / unknown), newest-month-first and
   within-month newest-first ordering, tie-order stability, tag cleaning/dedup,
   category fallback, the 6-representative cap, the 80-line LLM-input cap +
   determinism + the "无" empty block, the ViewModel Loading→Loaded/Empty/Error
   state machine, and repository-item-driven derivation.

## Command executed (actual, in this worktree)

```
ANDROID_HOME=/opt/deepseek-harness/android-sdk \
ANDROID_SDK_ROOT=/opt/deepseek-harness/android-sdk \
GRADLE_USER_HOME=/opt/deepseek-harness/.gradle-user-home \
ANDROID_USER_HOME=/opt/deepseek-harness/.android-user-home \
/opt/deepseek-harness/gradle-dist/gradle-8.9/bin/gradle \
    --no-daemon --max-workers=1 :app:assembleDebug :app:testDebugUnitTest
```

Result: **BUILD SUCCESSFUL** (exit code `0`).
- `:app:assembleDebug` produced `app/build/outputs/apk/debug/app-debug.apk`
  including the new `gallery/insights` and `gallery/ui/insights` slices plus all
  prior Phase 1–14 code.
- `:app:testDebugUnitTest` ran fresh in this worktree: **490 tests, 0 failures,
  0 errors** (472 prior Phases 1–14 + **18 new Phase-15 tests**:
  `GalleryMonthlyInsightsTest` 14, `GalleryInsightsViewModelTest` 3,
  `observeMonthlyInsightsDerivesFromStoredItems` 1).
- `:app:connectedAndroidTest` was **not run** — no device/emulator is available
  in this worktree.

## Files changed (Phase 15)

New:
- `app/src/main/java/com/clawmate/app/gallery/insights/GalleryMonthlyInsights.kt`
- `app/src/main/java/com/clawmate/app/gallery/insights/GalleryMonthlyInsight.kt`
- `app/src/main/java/com/clawmate/app/gallery/insights/GalleryMonthlyInsightCloudState.kt`
- `app/src/main/java/com/clawmate/app/gallery/ui/insights/GalleryInsightsViewModel.kt`
- `app/src/main/java/com/clawmate/app/gallery/ui/insights/MonthlyInsightsPanel.kt`
- `app/src/test/java/com/clawmate/app/gallery/insights/GalleryMonthlyInsightsTest.kt`
- `app/src/test/java/com/clawmate/app/gallery/ui/insights/GalleryInsightsViewModelTest.kt`

Modified:
- `app/src/main/java/com/clawmate/app/gallery/data/GalleryRepository.kt`
  (`observeMonthlyInsights()`)
- `app/src/main/java/com/clawmate/app/gallery/data/DefaultGalleryRepository.kt`
  (pure `map` over `observeItems()`)
- `app/src/main/java/com/clawmate/app/gallery/ui/GalleryScreen.kt` (wire the
  月度洞察 panel + a `MonthlyInsightsPanel` import)
- `app/src/test/java/com/clawmate/app/gallery/data/GalleryRepositoryEditDeleteTest.kt`
  (new derivation test)
- `app/src/test/java/com/clawmate/app/gallery/ui/GalleryViewModelEditDeleteTest.kt`
- `app/src/test/java/com/clawmate/app/gallery/ui/OcrAnalysisViewModelTest.kt`
- `README.md`, `MIGRATION_STATUS.md`, this `DSH_PHASE_RESULT.md`

## Verification summary

- `:app:assembleDebug :app:testDebugUnitTest` → **BUILD SUCCESSFUL**, exit `0`.
- Unit tests re-ran in this worktree → **490 tests, 0 failures, 0 errors**.
- No fake text / no cloud call / no media scan: insights are derived purely from
  stored Room items; the cloud AI summary and embedding are explicit
  NOT_CONFIGURED boundaries.
- No secrets / large files in the source tree (gitignore already excludes
  `*.jks/*.keystore/*.p12`, `local.properties`, build output, APKs).
- `:app:connectedAndroidTest` not run (no device/emulator).

## Known gaps / notes (recorded honestly)

- **Cloud AI monthly summary + vector embedding are NOT_CONFIGURED.** Harmony can
  optionally generate an AI month summary through a configured cloud LLM and
  store embeddings. Neither credential/endpoint nor client is wired on Android
  (rows D1/D12), so the panel renders an explicit 未配置 notice and never fakes a
  summary. The deterministic local insight (`GalleryMonthlyInsights`) is
  independent of this gate and always derives from stored analysis data.
- **No gallery auto-scan / memory sync / embedding** (rows D3/D1) were touched in
  this phase; those remain documented defer/boundary states.
- The phase delivers the pure port, its unit tests, and the real
  Loading/Empty/Error/Loaded insights panel in the existing Gallery UI — nothing
  outside that scope was changed.

---

# DSH_PHASE_RESULT — ClawMate Android, Phase 21 (config-driven Gallery cloud adapter: D1 remote embedding + D2 AI month summary)

## Worktree

- Delivery worktree: `/opt/deepseek-harness/clawmate-phase21-gallery-cloud`, cloned from
  `/root/ClawMate-Android` at `master` (bd3ed3a, Phase 20), branch `phase21-gallery-cloud`.
  Source of truth (read-only, not modified): `/root/mobiinfra-oh`
  (`entry/src/main/ets/utils/gallery/GalleryEmbeddingService.ets`,
  `GalleryMonthlyInsights.ets`, `utils/CloudModelClient.ets`).
- Built and tested in this worktree (JDK 17, Android SDK platform 34 / build-tools
  34.0.0 at `/opt/deepseek-harness/android-sdk`, Gradle 8.9 at
  `/opt/deepseek-harness/gradle-dist/gradle-8.9`). Build state:
  `GRADLE_USER_HOME=/opt/deepseek-harness/.gradle-user-home`,
  `ANDROID_USER_HOME=/opt/deepseek-harness/.android-user-home`.
- Working policy: serial only, `--max-workers=1`, one Gradle build at a time, no
  servers/emulators, no parallel jobs, no media scan, no adb/hdc, no cloud calls
  during tests, no fabricated text/vectors.

## Commit hash

Phase 21 changes committed on branch `phase21-gallery-cloud` in the delivery
worktree at commit **8631cd4**. (This commit also carries the updated DSH
result.)

## Scope delivered (Phase 21)

1. **Config-driven adversarial adapter** (`gallery/cloud/GalleryCloudAdapter.kt` +
   `GalleryCloudTypes.kt`) with honest NOT_CONFIGURED / UNSUPPORTED / READY gates.
   A remote-embedding config is READY only when the existing gallery embedding
   mode is `remote` **and** base URL + API key + model are present; otherwise
   NOT_CONFIGURED (remote incomplete) or UNSUPPORTED (`huawei_local`, no Android
   equivalent). The AI month summary is READY only behind the new explicit
   `galleryAiSummaryEnabled` opt-in **and** a fully-configured Planner endpoint /
   model / key — no new credentials are added.
2. **Remote text embedding** (`gallery/cloud/GalleryEmbeddingClient.kt`): OpenAI-
   compatible `/v1/embeddings` OkHttp client, port of `CloudModelClient.createEmbedding`
   / `normalizeEmbeddingUrl` / `GalleryEmbeddingService.embedTextRemote`. Bounds the
   request input to 512 chars, the response body (512 KiB) and the vector dimension
   (4096), redacts secrets from every error, returns a `GalleryEmbeddingResult`, and
   raises redacted `GalleryCloudException`s (HTTP / IO / MALFORMED / OVER_BOUNDS /
   EMPTY / EMPTY_INPUT). Reuses the existing gallery embedding settings — no new
   credentials.
3. **AI month summary** (`gallery/cloud/GalleryMonthlySummaryClient.kt`): **reuses
   the existing OpenAI-compatible `ChatCompletionsClient`** (via the `ChatClient`
   contract) with the exact Harmony `summarizeGalleryMonth` prompt, bounded response,
   redacted errors, JSON parsing into `GalleryMonthSummaryResult` (tolerant raw-text
   fallback as the source does), EMPTY when a reply has no title/summary/suggestion.
   Uses the Planner endpoint/model/key.
4. **Explicit opt-in setting** `gallery_ai_summary_enabled` (`galleryAiSummaryEnabled`)
   persisted via `SettingsCodec`, exposed in Settings 数据采集 → 月度 AI 总结, plus the
   reusable embedding settings already present. No inferring credentials from
   subscriptions.
5. **Wiring into Gallery insights behind settings**: `GalleryInsightsViewModel` now
   accepts an injectable suspend adapter provider (default `{ null }` → honest
   NOT_CONFIGURED); the 月度洞察 `Loaded` states reflect the real gate; explicit
   `生成/刷新总结` + `生成向量` buttons invoke the cloud **only from a user tap**
   (success → READY, failure → redacted ERROR). The deterministic local insight path
   (`GalleryMonthlyInsights`) always remains and never uploads.
6. **Device-free tests** (24 new): `gallery/cloud/GalleryCloudAdapterTest` (8),
   `GalleryEmbeddingClientTest` (6, MockWebServer), `GalleryMonthlySummaryClientTest`
   (6), `ui/insights/GalleryInsightsCloudViewModelTest` (3),
   `SettingsCodecTest` (1 new round-trip). Cover config gating, no-call behaviour,
   prompt/input + response bounds, parsing, secret redaction and settings round-trip.

## Command executed (actual, in this worktree)

```
ANDROID_HOME=/opt/deepseek-harness/android-sdk \
ANDROID_SDK_ROOT=/opt/deepseek-harness/android-sdk \
GRADLE_USER_HOME=/opt/deepseek-harness/.gradle-user-home \
ANDROID_USER_HOME=/opt/deepseek-harness/.android-user-home \
/opt/deepseek-harness/gradle-dist/gradle-8.9/bin/gradle \
    --no-daemon --max-workers=1 :app:assembleDebug :app:testDebugUnitTest
```

Result: **BUILD SUCCESSFUL** (exit code `0`).
- `:app:assembleDebug` produced `app/build/outputs/apk/debug/app-debug.apk` including
  the new `gallery/cloud` slice plus all prior Phase 1–20 code.
- `:app:testDebugUnitTest` ran fresh → **602 tests, 0 failures, 0 errors**
  (578 prior phases + **24 new Phase-21 tests**).
- `:app:connectedAndroidTest` not run (no device/emulator).

## Files changed (Phase 21)

New:
- `app/src/main/java/com/clawmate/app/gallery/cloud/GalleryCloudTypes.kt`
- `app/src/main/java/com/clawmate/app/gallery/cloud/GalleryEmbeddingClient.kt`
- `app/src/main/java/com/clawmate/app/gallery/cloud/GalleryMonthlySummaryClient.kt`
- `app/src/main/java/com/clawmate/app/gallery/cloud/GalleryCloudAdapter.kt`
- `app/src/test/java/com/clawmate/app/gallery/cloud/GalleryCloudAdapterTest.kt`
- `app/src/test/java/com/clawmate/app/gallery/cloud/GalleryEmbeddingClientTest.kt`
- `app/src/test/java/com/clawmate/app/gallery/cloud/GalleryMonthlySummaryClientTest.kt`
- `app/src/test/java/com/clawmate/app/gallery/ui/insights/GalleryInsightsCloudViewModelTest.kt`

Modified:
- `gallery/insights/GalleryMonthlyInsightCloudState.kt` (add `Error(message)` state)
- `gallery/ui/insights/GalleryInsightsViewModel.kt` (adapter provider + explicit actions)
- `gallery/ui/insights/MonthlyInsightsPanel.kt` (gate states + explicit buttons)
- `gallery/ui/GalleryScreen.kt` (wire provider + actions)
- `settings/domain/SettingsKeys.kt`, `AppSettings.kt`, `SettingsCodec.kt`,
  `settings/ui/SettingsViewModel.kt`, `SettingsSections.kt` (new opt-in key)
- `gallery/GalleryComponent.kt` (cloudAdapterProvider / buildCloudAdapter / OkHttp)
- `settings/SettingsCodecTest.kt` (round-trip for the new key)
- `README.md`, `MIGRATION_STATUS.md`, this `DSH_PHASE_RESULT.md`

## Verification summary

- `:app:assembleDebug :app:testDebugUnitTest` → **BUILD SUCCESSFUL**, exit `0`.
- 602 tests, 0 failures, 0 errors.
- No silent upload: adapter gates are pure; summaries/embeddings fire only on an
  explicit button tap with full config. No new credentials; `huawei_local` is
  UNSUPPORTED. Secrets redacted from all errors.
- `:app:connectedAndroidTest` not run (no device/emulator).

## Known gaps / notes (recorded honestly)

- The cloud summary/embedding executors are real network adapters but were not
  exercised against a live provider in this worktree (no credentials / no network).
  Their wire/parse/error/bound behaviour is covered by MockWebServer/fake-client
  unit tests. READY means "configured and would call", not "live call succeeded".
- No gallery auto-scan / memory-sync (row D3) is touched; those stay deferred.
