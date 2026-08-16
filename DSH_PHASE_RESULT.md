# DSH_PHASE_RESULT — ClawMate Android, Phase 14 (in-app Log viewer: bounded ring buffer + AppLogger port + Compose Log screen + redaction)

## Worktree

- Path: `/opt/deepseek-harness/clawmate-phase14-logviewer`
- Branch: `phase14-log-viewer`
- Source of truth (reference only, not modified): `/root/mobiinfra-oh`
- Built and tested in this worktree (JDK 17.0.19 OpenJDK, Android SDK platform
  34 / build-tools 34.0.0 at `/opt/deepseek-harness/android-sdk`, Gradle 8.9 at
  `/opt/deepseek-harness/gradle-dist/gradle-8.9`).
- Build state used: `GRADLE_USER_HOME=/opt/deepseek-harness/.gradle-user-home`,
  `ANDROID_USER_HOME=/opt/deepseek-harness/.android-user-home` (both discarded,
  never committed), and the same Gradle 8.9 release binary used in Phases 2–13
  (the wrapper distribution URL is unreachable here). `/root/mobiinfra-oh` was
  not modified. All Phase 1–13 code is preserved.
- Working policy: serial only, single worker, no parallel Gradle processes, no
  servers/emulators, one Gradle build at a time. No logcat/`adb`/`hdc` is read —
  the Log viewer surfaces only real in-app records.

## Commit

The Phase-14 changes are committed on branch `phase14-log-viewer`.

## Commit hash

- `Phase 14 in-app log viewer (bounded ring buffer + AppLogger + Log screen)` →
  **38938d552966ee0e4012ae2d5b5f173109db79a6**.

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
  including the new `log/` slice and the existing bundled ML Kit OCR +
  local-model native stub.
- `:app:testDebugUnitTest` was re-run fresh in this worktree
  (`:app:cleanTestDebugUnitTest :app:testDebugUnitTest`) so the unit tests
  actually executed, not just replayed from cache: **472 tests, 0 failures,
  0 errors** (448 prior Phases 1–13 + **24 new Phase-14 log tests**:
  `SensitiveLogRedactorTest` 10, `RingBufferInAppLogRepositoryTest` 7,
  `LogFilterTest` 7; the `SettingsSectionTest` section/badge assertions were
  extended within its existing test methods).
- `:app:connectedAndroidTest` was **not run** — no device/emulator is available
  in this worktree.

## Scope delivered (Phase 14)

1. **Portable in-app Log viewer** based on the HarmonyOS
   `entry/src/main/ets/pages/LogView.ets`, `utils/AppLogger.ets` and
   `utils/CloudLogOutput.ets` (Phase-12 inventory row C2,
   previously Portable-pending).
2. **Bounded ring buffer** (`log/data/RingBufferInAppLogRepository.kt`):
   in-memory, capacity-bounded (`capacity` 512 default, 4096 max) FIFO ring
   buffer with minimal-level retention gate, message-length truncation, and a
   `StateFlow` projecting the newest-first list for Compose. Only **real events
   written by actual app code paths** are stored; nothing is fabricated.
3. **Actual event wiring** (real code runs only): app launch
   (`ClawMateApplication`), settings saved + HDC health-check result
   (`SettingsViewModel`), chat send / complete / error (`ChatViewModel`),
   gallery OCR run/empty/model-download/failed (`GalleryViewModel`). Events that
   never occur (e.g. an unconfigured cloud call) never appear.
4. **Sensitive-value redaction** (`log/domain/SensitiveLogRedactor.kt`): applied
   **on insert** so a raw secret can never be stored, rendered, or copied from
   the viewer — `Authorization`/Bearer/Basic, `api_key`, `sk-*`, GitHub
   `ghp_`/`ghs_` tokens, AWS credentials, and generic
   `password=`/`secret=`/`token=` assignments.
5. **Compose Log screen** (`log/ui/LogScreen.kt` + `LogViewModel`) reachable from
   an existing Settings entry (new 运行日志 section,
   `SettingsSectionId.RuntimeLogs`, badge "L"): source tabs (应用 / 云端 Agent /
   图库分析 / 任务自动化), severity + free-text filters (pure `LogFilter`),
   Refresh, auto-refresh ticker, auto-scroll, copy visible (already redacted)
   text, clear-current-source / clear-all, live count/capacity footer, and an
   **honest empty state** that never shows a fabricated record.
6. **Deterministic unit tests** (`app/src/test/java/com/clawmate/app/log/` +
   extended `SettingsSectionTest`): redactor patterns / determinism,
   ring-buffer retention & eviction / scoped & full clear / min-level /
   redact-on-insert / truncation, and pure source/level/query/tag/limit/ordering
   filtering.

## Files changed (Phase 14)

New:
- `app/src/main/java/com/clawmate/app/log/AppLogger.kt`
- `app/src/main/java/com/clawmate/app/log/LogComponent.kt`
- `app/src/main/java/com/clawmate/app/log/domain/InAppLogTypes.kt`
- `app/src/main/java/com/clawmate/app/log/domain/LogFilter.kt`
- `app/src/main/java/com/clawmate/app/log/domain/SensitiveLogRedactor.kt`
- `app/src/main/java/com/clawmate/app/log/data/RingBufferInAppLogRepository.kt`
- `app/src/main/java/com/clawmate/app/log/ui/LogScreen.kt`
- `app/src/main/java/com/clawmate/app/log/ui/LogViewModel.kt`
- `app/src/main/java/com/clawmate/app/log/ui/LogViewModelFactory.kt`
- `app/src/test/java/com/clawmate/app/log/SensitiveLogRedactorTest.kt`
- `app/src/test/java/com/clawmate/app/log/RingBufferInAppLogRepositoryTest.kt`
- `app/src/test/java/com/clawmate/app/log/LogFilterTest.kt`

Modified:
- `app/src/main/java/com/clawmate/app/ClawMateApplication.kt` (init LogComponent +
  real "App" launch event)
- `app/src/main/java/com/clawmate/app/chat/ui/ChatViewModel.kt` (cloud-agent
  events)
- `app/src/main/java/com/clawmate/app/gallery/ui/GalleryViewModel.kt` (gallery
  events)
- `app/src/main/java/com/clawmate/app/settings/domain/SettingsSection.kt`
  (seventh 运行日志 section, badge "L")
- `app/src/main/java/com/clawmate/app/settings/ui/SettingsScreen.kt` (route the
  section to `LogScreen`)
- `app/src/main/java/com/clawmate/app/settings/ui/SettingsViewModel.kt`
  (settings-saved + HDC events)
- `app/src/test/java/com/clawmate/app/settings/SettingsSectionTest.kt`
  (seven sections + badge "L")
- `README.md`, `MIGRATION_STATUS.md`, this `DSH_PHASE_RESULT.md`

## Verification summary

- `:app:assembleDebug :app:testDebugUnitTest` → **BUILD SUCCESSFUL**, exit `0`.
- Unit test re-run in this worktree (`cleanTestDebugUnitTest` then test) →
  **472 tests, 0 failures, 0 errors**.
- No secrets / large files in the source tree (scan: none; gitignore already
  excludes `*.jks/*.keystore/*.p12`, `local.properties`, build output, APKs).
- `:app:connectedAndroidTest` not run (no device/emulator).

## Known gaps / notes (recorded honestly)

- **No logcat / native C++ / AppStorage log capture.** Harmony `LogView.ets`
  reads `libentry.so` `getLogs()/clearLogs()` and `AppStorage` cloud/gallery log
  strings. Neither has an Android equivalent this app owns, so the Android
  viewer surfaces the real wired in-app sources (application / cloud-Agent /
  gallery / workflow) and the empty state stays truthful — it never claims
  events that did not happen. Nothing is read from logcat/`adb`/`hdc`.
- **In-memory only (no disk persistence).** The ring buffer is deliberately a
  process-lifetime in-memory buffer (app-private, bounded, redacted) so a raw
  secret can never touch storage; it is not persisted across app restarts.
- **Only the wired code paths emit events.** Other modules still use `Log.i`
  directly; a broader migration to the in-app `AppLogger` across all modules is
  a natural follow-up, not part of Phase 14.
