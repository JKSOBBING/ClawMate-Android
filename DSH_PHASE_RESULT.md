# DSH_PHASE_RESULT — ClawMate Android, Phase 13 (WeChat auto-summary settings + normalization/validation + typed PC/HDC collection boundary)

## Worktree

- Path: `/opt/deepseek-harness/clawmate-phase13-wechat`
- Branch: `phase13-wechat-settings`
- Source of truth (reference only, not modified): `/root/mobiinfra-oh`
- This delivery was **built and tested in this worktree** (JDK 17.0.19 OpenJDK,
  Android SDK platform 34 / build-tools 34.0.0 at
  `/opt/deepseek-harness/android-sdk`, Gradle 8.9 at
  `/opt/deepseek-harness/gradle-dist/gradle-8.9`).
- Build state used: `GRADLE_USER_HOME=/opt/deepseek-harness/.gradle-user-home`,
  `ANDROID_USER_HOME=/opt/deepseek-harness/.android-user-home` (both discarded,
  never committed), and the exact same Gradle 8.9 release binary used in Phases
  2–12 (the wrapper distribution URL is unreachable here). Nothing was written
  to `/root/ClawMate-Android`; `/root/mobiinfra-oh` was not modified. All Phase
  1–12 code is preserved.
- Working policy: serial only, single worker, no parallel Gradle processes, no
  servers/emulators, one Gradle build at a time.

## Commit

The Phase-13 feature changes and documentation are ready to commit as `Phase 13
WeChat settings`, but this worktree cannot create the commit itself (see
"Commit hash" below — the git metadata lives under
`/root/ClawMate-Android/.git`, which the sandbox denies writing to and no
approval channel is available). All changes are staged-ready in this working
tree.

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
  (regenerated fresh in this worktree) including the new `wechat/` slice and the
  existing ML Kit bundled OCR + local-model native stub.
- `:app:testDebugUnitTest` ran to completion: **448 tests, 0 failures,
  0 errors** (425 prior Phases 1–12 + **23 new Phase-13 WeChat tests**).
- `:app:connectedAndroidTest` was **not run** — no device/emulator is available
  in this worktree.

## Scope delivered (Phase 13)

1. **Pure port of the WeChat auto-summary settings/validation types**
   (`wechat/domain/WechatAutoSummaryTypes.kt`) from
   `utils/wechat/WechatAutoSummaryTypes.ets`: the settings keys/defaults, the
   `recent_contacts`/`target_contact` modes, the request/response/message/
   conversation/time-range data types aligned to the PC bridge snake_case
   contract, the bounded normalizers (`days` 1..90, `max_contacts` 1..50,
   `swipe_speed` 0..20000, `history_swipe_ratio` 0.1..0.95, `stable_swipes`
   1..10, `max_history_swipes` 1..300, `wait` 0..10, mode) and the
   `validateWechatAutoSummaryRequest` validator (empty when valid; user-
   presentable Chinese message otherwise). Every function is a pure total
   function.
2. **Real Settings center section (微信自动汇总)** — a sixth section wired into the
   existing five-section shell. Fields (`wechatAutoSummaryEnabled` and the
   bounded numerics + mode + target contact) are exposed through the existing
   DataStore-backed `SettingsRepository`/`SettingsCodec` (`SettingsKeys.WECHAT_*`,
   added to `AppSettings` and `SettingsCodec.encode/decode`), normalized on
   change, with honest loading/saved/error states. No secrets among the WeChat
   keys, so no additional masking entry is needed.
3. **Typed PC/HDC collection boundary** (`wechat/domain/WechatCollectionGate.kt`):
   derives a truthful `BLOCKED`/`NOT_CONFIGURED` state from the Phase-8 verified
   bridge (`TaskComponent.currentAdapter().isConfigured`). It **never drives
   WeChat, never sends arbitrary commands, and `collect()` never fabricates a
   summary** — the Android `WorkflowDeviceAdapter` exposes no safe
   `wechat_collect` action, so even a health-verified generic bridge stays
   `BLOCKED`. The Settings section renders this status live with an explanatory
   note.
4. **Deterministic unit tests** (`app/src/test/java/com/clawmate/app/wechat/`):
   `WechatAutoSummaryTypesTest` (15), `WechatSettingsCodecTest` (4),
   `WechatCollectionGateTest` (4) — 23 tests covering normalizer bounds,
   validator, codec round-trips/degradation, repository persistence and the
   honest boundary. `SettingsSectionTest` extended for the sixth section + "W"
   badge.
5. **No Harmony-only binaries / secrets**: no MNN/HiAI `.so` copied; the
   Phase-10 `libclawmate_local_model.so` stub is unchanged; no secrets or large
   binaries added (source tree scanned).

## Files changed (Phase 13)

New:
- `app/src/main/java/com/clawmate/app/wechat/domain/WechatAutoSummaryTypes.kt`
- `app/src/main/java/com/clawmate/app/wechat/domain/WechatCollectionGate.kt`
- `app/src/main/java/com/clawmate/app/settings/ui/WechatSettingsSection.kt`
- `app/src/test/java/com/clawmate/app/wechat/WechatAutoSummaryTypesTest.kt`
- `app/src/test/java/com/clawmate/app/wechat/WechatSettingsCodecTest.kt`
- `app/src/test/java/com/clawmate/app/wechat/WechatCollectionGateTest.kt`

Modified:
- `app/src/main/java/com/clawmate/app/settings/domain/SettingsKeys.kt` (WECHAT_* keys + ALL_KEYS)
- `app/src/main/java/com/clawmate/app/settings/domain/AppSettings.kt` (wechatAutoSummary*/wechatCollection* fields)
- `app/src/main/java/com/clawmate/app/settings/domain/SettingsSection.kt` (sixth WechatAutoSummary section)
- `app/src/main/java/com/clawmate/app/settings/data/SettingsCodec.kt` (encode/decode the WeChat fields)
- `app/src/main/java/com/clawmate/app/settings/ui/SettingsViewModel.kt` (setters + wechatCollectionGate()/wechatSummaryRequestOrNull())
- `app/src/main/java/com/clawmate/app/settings/ui/SettingsScreen.kt` (section dispatch)
- `app/src/test/java/com/clawmate/app/settings/SettingsSectionTest.kt` (six sections + badge "W")
- `README.md`, `MIGRATION_STATUS.md`, this `DSH_PHASE_RESULT.md`

## Verification summary

- `:app:assembleDebug :app:testDebugUnitTest` → **BUILD SUCCESSFUL**, exit `0`.
- Fresh test execution in this worktree → **448 tests, 0 failures, 0 errors**.
- `:app:connectedAndroidTest` not run (no device/emulator).

## Known gaps / notes (recorded honestly)

- **WeChat auto-summary collection is not implemented on Android** — it is a
  PC-side Python/HDC GUI-automation service (`wechat_collect`). The typed
  `WechatCollectionGate` reports an honest BLOCKED / NOT_CONFIGURED state and
  never runs collection or fabricates a summary. Real collection remains a
  documented remaining gap (Phase-12 inventory E2).
- **No dynamic WeChat conversation screen / summary rendering** — Phase 13 is
  the settings/normalization/validation slice only; summary storage/rendering
  (Harmony `WechatAutoSummaryStorage.ets`) remains portable-pending.
- **Local profile → Home recommendation pipeline** (Phase 12 profile slice) is
  unchanged and still deferred; not in Phase-13 scope.

## Commit hash

- `Phase 13 WeChat settings` → **NOT COMMITTED** — see "Commit" above (sandbox
  blocks writes to the external `/root/ClawMate-Android/.git/` repo metadata;
  no approval channel is available). Record the hash here once committed by a
  writable environment.
