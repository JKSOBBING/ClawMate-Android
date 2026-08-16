# DSH_PHASE_RESULT — ClawMate Android, Phase 5 (Settings / Configuration vertical slice)

## Worktree

- Path: `/opt/deepseek-harness/clawmate-phase5-settings`
- Branch: `master`
- Source of truth (reference only, not modified): `/root/mobiinfra-oh`
- This delivery was **built and tested in this worktree** (JDK 17.0.19 OpenJDK,
  Android SDK platform 34 / build-tools 34.0.0 at `/opt/deepseek-harness/android-sdk`,
  Gradle 8.9 at `/opt/deepseek-harness/gradle-dist/gradle-8.9`).
- Build state used: `GRADLE_USER_HOME=/opt/deepseek-harness/.gradle-user-home`,
  `ANDROID_USER_HOME=/opt/deepseek-harness/.android-user-home` (discarded, never
  committed). Nothing was written to `/root/ClawMate-Android`; `/root/mobiinfra-oh`
  was not modified.

## Commit

First commit (feature slice):
- Message: `Phase 5 settings configuration vertical slice`
- Hash: **`4a9b285`** on `master`, created in this worktree. (This ships the full
  Settings/Configuration slice.)

The result-file update (this file, with the feature hash filled in) was then
committed as the final docs commit — both the feature commit and the
result-file commit are present in the branch history below.

## Scope delivered (Phase 5)

1. **Android Settings domain** — `settings/domain/`:
   - `SettingsSection.kt`: `SettingsSectionId` (5 sections), the ordered section
     descriptors (`SETTINGS_SECTIONS` 个人与隐私 · 数据采集 · 模型与智能体 ·
     任务与自动化 · 存储与关于), `normalize`/`isSelected` (mirrors
     `SettingsCenterTypes.ets`).
   - `SettingsKeys.kt`: full typed key inventory + `SENSITIVE_KEYS` subset.
   - `AppSettings.kt`: typed snapshot plus `…Configured` / `hdcBridgeConfigured`
     connectivity facts that only become true when a real endpoint+secret exist.
   - `SettingsNormalizers.kt`: deterministic URL / model / bounded int / double /
     boolean / embedding-mode normalizers (total functions, never throw).
   - `SensitiveValueUtils.kt`: port of `SensitiveValueUtils.ets` +
     `maskSensitiveSettingValue` / `restoreMaskedSensitiveSettingDefault`; the
     mask marker is a display-only transform.
   - **No real HarmonyOS default values were copied** — every committed default
     is empty/safe; only the git-ignored `local_config.properties` (runtime)
     carries real endpoint values.

2. **Repository** — `settings/data/`:
   - `SettingsRepository` interface; `DataStoreSettingsRepository`
     (DataStore Preferences, all values stored normalized as strings).
   - `SettingsCodec`: encode/decode round-trips + `sanitizeForWrite` that strips
     any masked value from the persisted map — **a masked display string is never
     written back as a literal secret**. Connectivity flags are never persisted.
   - `FakeSettingsRepository` (deterministic, same codec guarantees) used by
     tests/previews.

3. **Integration with existing components (safe)** — `settings/config/EndpointBridge.kt`
   + `config/EndpointConfig.applyCloudEndpoint`:
   - Editing cloud planner/decider base URL, model, API key in Settings updates
     the runtime `EndpointConfig` that `ChatServerConfig` / the chat cloud client
     read; a masked value is never pushed into runtime config.
   - The gallery capability config is treated as user-editable keys (base URL /
     API key / model / OCR / embedding / map) surfaced in the Data-Collection
     section; the gallery engines still report `CapabilityState.NotConfigured`
     until a real endpoint is configured — nothing claims connected.
   - Workflow/agent toggles and HDC bridge state are editable/displayed; HDC / PC
     backend are shown NOT_CONFIGURED / DISABLED unless truly configured.

4. **Real Compose Settings UI** — `settings/ui/`:
   - `SettingsScreen`: section list ↔ detail navigation, loading / error / saved /
     save-busy states, save/reset bar; app version + storage summary.
   - `SettingsSections`: the five section composables with grouped cards
     (`SettingsGroupCard`), text fields, `SettingToggle` switches, sensitive
     `SensitiveSettingInput` with reveal/hide editing, `SettingsActionRow`
     buttons and `SettingsOutputPanel`.
   - `SettingsViewModel`: working draft, section selection, sensitive reveal
     (only one active), typed field setters, save (normalizes, sanitizes, pushes
     to runtime) and reset-to-defaults.
   - Missing / unconfigured endpoints surface as NOT_CONFIGURED or DISABLED; the
     storage/about section shows app version/code and an app-private storage
     summary. Destructive cleanup actions are explicit disabled buttons that do
     **not** delete broad directories or user data.

5. **Deterministic unit tests** — 34 new tests (see §Test count) covering
   normalization, masking/restore, persistence round-trips, section selection,
   and safe disabled/unconfigured states.

## Commands run & exit codes

Environment: `ANDROID_HOME=/opt/deepseek-harness/android-sdk`,
`ANDROID_SDK_ROOT=/opt/deepseek-harness/android-sdk`,
`GRADLE_USER_HOME=/opt/deepseek-harness/.gradle-user-home`,
`ANDROID_USER_HOME=/opt/deepseek-harness/.android-user-home`.
Gradle binary: `/opt/deepseek-harness/gradle-dist/gradle-8.9/bin/gradle`.

| # | Command | Result |
| --- | --- | --- |
| 1 | `gradle --no-daemon --max-workers=1 :app:compileDebugKotlin` | **SUCCESS** (exit 0) — during development |
| 2 | `gradle --no-daemon --max-workers=1 :app:testDebugUnitTest --tests 'com.clawmate.app.settings.*'` | **SUCCESS** (exit 0) — 34 Phase-5 settings tests green |
| 3 | `gradle --no-daemon --max-workers=1 :app:assembleDebug :app:testDebugUnitTest` | **SUCCESS** (exit code `0`) — full build + all 163 JVM unit tests |
| 4 | `gradle --no-daemon --max-workers=1 :app:connectedAndroidTest` | **not run** — no emulator/device available (same as Phases 1–4) |

## Test count

- Total JVM unit tests green: **163** (129 prior Phases 1–4 + **34 new Phase-5
  Settings tests**); **0 failures / 0 errors** across `:app:testDebugUnitTest`.
- New Phase-5 tests by area:
  - `SettingsNormalizersTest` (8) — URL/model trim, compression threshold +
    recent-rounds bounds, positive concurrency clamp, boolean parse, embedding
    mode enum, double bounds.
  - `SensitiveValueUtilsTest` (6) — blank never masked, mask-marker detection,
    deterministic length-preserving mask, restore-exact-default, unknown-masked →
    empty, non-masked passthrough.
  - `SettingsCodecTest` (4) — encode/decode round-trip, empty map → safe defaults,
    corrupt-value degradation (total), `sanitizeForWrite` drops masked secrets.
  - `SettingsRepositoryTest` (5) — save/snapshot round-trip + normalization,
    flow emission, masked-secret never persisted, safe unconfigured defaults,
    connectivity flags never persisted.
  - `SettingsSectionTest` (5) — five sections order/content, normalize/isSelected,
    titles/subtitles, section badges.
  - `SafeDisabledStateTest` (6) — configured flags require URL+secret, HDC URL
    rule, EndpointBridge never pushes a masked key into runtime config, plain
    typed secret applied, `EndpointConfig.applyCloudEndpoint` mask guard.

## Changed files (this phase)

```
app/src/main/java/com/clawmate/app/config/EndpointConfig.kt                 (modified — applyCloudEndpoint, mask guard)
app/src/main/java/com/clawmate/app/ui/ClawMateRoot.kt                       (modified — route 设置 tab to settings.ui.SettingsScreen)
app/src/main/java/com/clawmate/app/ui/settings/SettingsScreen.kt            (modified — Phase-1 placeholder marked non-routed reference)
app/src/main/java/com/clawmate/app/settings/SettingsComponent.kt            (new)
app/src/main/java/com/clawmate/app/settings/config/EndpointBridge.kt        (new)
app/src/main/java/com/clawmate/app/settings/domain/SettingsSection.kt       (new)
app/src/main/java/com/clawmate/app/settings/domain/SettingsKeys.kt          (new)
app/src/main/java/com/clawmate/app/settings/domain/AppSettings.kt           (new)
app/src/main/java/com/clawmate/app/settings/domain/SettingsNormalizers.kt   (new)
app/src/main/java/com/clawmate/app/settings/domain/SensitiveValueUtils.kt   (new)
app/src/main/java/com/clawmate/app/settings/data/SettingsRepository.kt      (new)
app/src/main/java/com/clawmate/app/settings/data/SettingsCodec.kt           (new)
app/src/main/java/com/clawmate/app/settings/data/DataStoreSettingsRepository.kt (new)
app/src/main/java/com/clawmate/app/settings/data/FakeSettingsRepository.kt  (new)
app/src/main/java/com/clawmate/app/settings/ui/SettingsScreen.kt            (new)
app/src/main/java/com/clawmate/app/settings/ui/SettingsSections.kt          (new)
app/src/main/java/com/clawmate/app/settings/ui/SettingsControls.kt          (new)
app/src/main/java/com/clawmate/app/settings/ui/SettingsTheme.kt             (new)
app/src/main/java/com/clawmate/app/settings/ui/SettingsViewModel.kt         (new)
app/src/main/java/com/clawmate/app/settings/ui/SettingsViewModelFactory.kt  (new)
app/src/main/java/com/clawmate/app/settings/ui/SettingsUiState.kt           (new)
app/src/test/java/com/clawmate/app/settings/*Test.kt                         (6 new test classes, 34 tests)
README.md                                                                   (modified)
MIGRATION_STATUS.md                                                         (modified)
DSH_PHASE_RESULT.md                                                         (rewritten — this file)
```

## Limitations & platform blockers

- **Connectivity is never fabricated.** Cloud Planner/Decider, gallery vision,
  Embedding, HDC bridge and the PC backend are reported NOT_CONFIGURED / DISABLED
  until a real URL + secret + live check proves otherwise. There is no fake
  "connected" status, no gratuitous `Runtime.exec`, no shell/adb/hdc invocation
  and no silent directory deletion.
- **Sensitive masking is display-only.** A masked value (bullet marker) is
  recognized, never persisted verbatim (codec `sanitizeForWrite`) and never
  pushed into runtime config (EndpointBridge guard). Only a user-typed plaintext
  secret is stored, and only while the user is actively editing the revealed
  field.
- **Destructive cleanups are gated.** The 清理操作 card renders explicit disabled
  buttons with an explanatory note; no cleanup deletes broad directories or user
  data. The Phase 5 slice intentionally does not wire file deletion.
- **HDC / GUI automation and model download remain out of scope** — HDC bridge
  state is editable/config-visible but the actual bridge/auto-pairing is not
  implemented (surfaced as DISABLED), consistent with the Phase-4 blocker.
- **connectedAndroidTest** not run (no device/emulator); `assembleDebug` and all
  163 JVM unit tests pass.
- **Some benign Kotlin warnings remain** (a few unused params in new composable
  scaffolding) — non-fatal, no compilation errors.

## Next phase

1. **Cloud/PC bridge adapter for GUI automation** — the source drives real
   devices over HDC via a PC-side Python service. The Android equivalent is an
   HTTP/adb/UIAutomator/Shizuku adapter wired behind the `WorkflowRunner` and the
   HDC settings card, so `gui_task`/`gui_action`/HDC steps could become
   executable and the HDC "已连接" state could become truthful — with the current
   NOT_CONFIGURED/DISABLED states as the honest fallback.
2. **Voice / digital avatar** and **local MNN model** vertical slices (rows 8–9
   in MIGRATION_STATUS) — the settings fields for avatar/TTS already exist; wire
   the real clients and per-section enable/disable.
3. Optionally: on-device OCR/cloud-vision analytics once an endpoint + engine
   are configured, gated by the gallery settings this phase introduced.
