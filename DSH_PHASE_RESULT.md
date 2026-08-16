# DSH_PHASE_RESULT — ClawMate Android, Phase 6 (Home / Onboarding vertical slice)

## Worktree

- Path: `/opt/deepseek-harness/clawmate-phase6-home`
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
- Message: `Phase 6 home onboarding vertical slice`
- Hash: **`f51f27c`** on `master`, created in this worktree. (Ships the
  full Home + Onboarding slice.)

The result-file update (this file, with the feature hash filled in) is then
committed as the final docs commit — both the feature commit and the
result-file commit are present in the branch history.

## Scope delivered (Phase 6)

1. **Android home domain models** — `home/domain/`:
   - `HomeDomain.kt` — the four persona pillars (吃/穿/住/行) as
     `HomeDomainViewModel` with source colors, an honest `暂无数据` empty default,
     and `homeProfileCompleteness` (port of `homeProfileCompleteness`).
   - `HomeRecommendation.kt` — `HomeRecommendationViewModel` + the minimal
     persisted `HomeRecommendationState` + per-domain accent‑color mapping
     (port of `HomeRecommendationState` / `homeRecommendations` colors). Nothing
     invents recommendation content.
   - `HomeProfile.kt` — `HomeProfileViewModel` aggregate (`updatedLabel`,
     `completeness`, `totalEvents`, `domains`, `recommendations`, `empty`,
     honest `avatar`) plus `HomeUiState` (Loading / Ready / Error).
   - `RecommendationListLogic.kt` — pure pagination/filter/completion reductions
     (4-per-page, 未完成/全部, pending-first ordering, page clamping) mirroring
     `HomePage.ets`.
   - `AvatarStatus.kt` — the 5 source statuses (`empty/current/stale/generating/
     failed`) plus an honest `AvatarSection.notConfigured` (DISABLED). Avatar
     generation/import on Android is **NOT configured** — never fabricated.
   - `OnboardingTypes.kt` — pure onboarding contract from
     `OnboardingTypes.ets`: `FIRST_LAUNCH_ONBOARDING_VERSION`
     (`2026-07-core-preview-usage-guide`), the 4 slides (3 capability previews +
     usage-guide), the 3 usage-guide items, `shouldShowFirstLaunchOnboarding`,
     last-page/button-text/clamp and a `skip` target helper.

2. **App-private / DataStore persistence** — `home/data/`:
   - `OnboardingRepository` + `DataStoreOnboardingRepository` (app-private
     `clawmate_home` Preferences; fresh install → not completed; no network).
   - `HomeRecRepository` + `DataStoreHomeRecRepository` (app-private
     `clawmate_home_recs` Preferences; per-id `completed` flags). Only flags are
     persisted — never content, endpoints, keys, or personal data. `applyFlags`
     colours supplied recommendations without inventing them.
   - Deterministic fakes (`FakeOnboardingRepository`, `FakeHomeRecRepository`)
     and a `HomeProfileSource` seam whose current implementation
     (`EmptyHomeProfileSource`) returns the honest empty profile until the
     data-manager pipeline lands.

3. **Real Compose first-launch onboarding** — `home/ui/onboarding/`:
   - `FirstLaunchOnboardingScreen` renders the three capability preview slides
     (ClawMate / 数字分身 / 自动执行) + the usage-guide page, with 跳过 / 下一页 /
     进入首页 actions and animated progress dots (port of
     `FirstLaunchOnboarding.ets`).
   - `OnboardingViewModel` (+ factory) drives next/skip/finish and persists the
     completion version through `OnboardingRepository`.
   - The app shell (`ui/ClawMateRoot.kt`) gates the 5-tab root behind
     `shouldShowFirstLaunchOnboarding(storedVersion)` and swaps to the tabs once
     the version is persisted. Onboarding works on a configuration-free fresh
     install and requires no network.

4. **Real Compose Home** — `home/ui/HomeScreen.kt` + `HomeViewModel` (+ factory):
   - Honest avatar section (NOT_CONFIGURED / DISABLED; never a fabricated image
     or success label), completeness bar, and the four persona pillars.
   - Recommendation list with completion toggles (persisted per-id), 未完成/全部
     filter, 4-per-page pagination, and expandable card behaviour.
   - Profile loading / empty / error states surfaced. Recommendation content is
     loaded via `HomeProfileSource` (empty profile until a real data source).
   - The obsolete Phase-1 `ui/home/HomeScreen.kt` placeholder was removed; the
     Home tab route now points at `home/ui/HomeScreen.kt`. Chat / Summary /
     Task / Settings routes are preserved unchanged.

5. **Deterministic unit tests** — 36 new tests (see §Test count) covering
   onboarding version/section navigation, persistence round-trips (onboarding +
   recommendation completion), recommendation completion/pagination, empty and
   disabled / not-configured guarantees, and stable model mapping.

## Commands run & exit codes

Environment: `ANDROID_HOME=/opt/deepseek-harness/android-sdk`,
`ANDROID_SDK_ROOT=/opt/deepseek-harness/android-sdk`,
`GRADLE_USER_HOME=/opt/deepseek-harness/.gradle-user-home`,
`ANDROID_USER_HOME=/opt/deepseek-harness/.android-user-home`.
Gradle binary: `/opt/deepseek-harness/gradle-dist/gradle-8.9/bin/gradle`.

| # | Command | Result |
| --- | --- | --- |
| 1 | `gradle --no-daemon --max-workers=1 :app:compileDebugKotlin` | **SUCCESS** (exit 0) — during development |
| 2 | `gradle --no-daemon --max-workers=1 :app:testDebugUnitTest --tests 'com.clawmate.app.home.*'` | **SUCCESS** (exit 0) — 36 Phase-6 Home/Onboarding tests green |
| 3 | `gradle --no-daemon --max-workers=1 :app:assembleDebug :app:testDebugUnitTest` | **SUCCESS** (exit code `0`) — full build + all 199 JVM unit tests |
| 4 | `gradle --no-daemon --max-workers=1 :app:connectedAndroidTest` | **not run** — no emulator/device available (same as Phases 1–5) |

Note: an intermediate development run of `:app:assembleDebug` produced a temp
DSH file-session error because a suspend call (`storedVersion`) was initially
made outside a coroutine in one test; that was fixed (wrapped in `runTest` +
`advanceUntilIdle`) and is not present in the final green run. The final
`assembleDebug` + `testDebugUnitTest` combination above is the authoritative
result.

## Test count

- Total JVM unit tests green: **199** (163 prior Phases 1–5 + **36 new Phase-6
  Home/Onboarding tests**); **0 failures / 0 errors** across
  `:app:testDebugUnitTest`.
- New Phase-6 tests by area:
  - `OnboardingTypesTest` (7) — 4 slides (3 capability + usage guide), versioned
    completion key matches the source, `shouldShowFirstLaunchOnboarding`,
    last-page detection with clamping, button text, index bounds.
  - `OnboardingViewModelTest` (5) — starts on first slide, next/finish
    behaviour, skip jumps to the usage-guide (last) page,
    version-persistence on finish, fresh-install not-completed.
  - `HomePersistenceRoundTripTest` (6) — onboarding fresh-install + version
    round-trip via the store flow, recommendation flags start empty and
    round-trip, blank-id rejection, `applyFlags` colours without inventing.
  - `RecommendationListLogicTest` (6) — empty list, pending-only filter,
    show-all ordering (pending first), 4-per-page pagination + clamping, and
    completion grouping when showing all.
  - `HomeViewModelTest` (4) — empty profile ready state, persisted flags
    applied on load, toggle persists through the repo, loader-failure error
    state.
  - `HomeModelMappingTest` (8) — avatar NOT_CONFIGURED/disabled guarantees
    (never a fabricated image/label), status-token parsing, four default empty
    pillars, completeness counting, recommendation-colour mapping with
    fallback, empty-profile honesty.

## Changed files (this phase)

```
app/src/main/java/com/clawmate/app/ui/ClawMateRoot.kt                  (modified — onboarding gate before the 5-tab root; Home tab → home/ui)
app/src/main/java/com/clawmate/app/ui/home/HomeScreen.kt               (removed — obsolete Phase-1 placeholder)
app/src/main/java/com/clawmate/app/home/domain/AvatarStatus.kt         (new)
app/src/main/java/com/clawmate/app/home/domain/HomeDomain.kt           (new)
app/src/main/java/com/clawmate/app/home/domain/HomeRecommendation.kt   (new)
app/src/main/java/com/clawmate/app/home/domain/HomeProfile.kt          (new)
app/src/main/java/com/clawmate/app/home/domain/OnboardingTypes.kt      (new)
app/src/main/java/com/clawmate/app/home/domain/RecommendationListLogic.kt (new)
app/src/main/java/com/clawmate/app/home/data/OnboardingRepository.kt   (new)
app/src/main/java/com/clawmate/app/home/data/DataStoreOnboardingRepository.kt (new)
app/src/main/java/com/clawmate/app/home/data/FakeOnboardingRepository.kt (new)
app/src/main/java/com/clawmate/app/home/data/HomeRecRepository.kt      (new)
app/src/main/java/com/clawmate/app/home/data/DataStoreHomeRecRepository.kt (new)
app/src/main/java/com/clawmate/app/home/data/FakeHomeRecRepository.kt  (new)
app/src/main/java/com/clawmate/app/home/data/HomeProfileSource.kt      (new)
app/src/main/java/com/clawmate/app/home/ui/HomeScreen.kt               (new — real Compose Home)
app/src/main/java/com/clawmate/app/home/ui/HomeViewModel.kt            (new)
app/src/main/java/com/clawmate/app/home/ui/HomeViewModelFactory.kt     (new)
app/src/main/java/com/clawmate/app/home/ui/onboarding/FirstLaunchOnboardingScreen.kt (new)
app/src/main/java/com/clawmate/app/home/ui/onboarding/OnboardingViewModel.kt (new)
app/src/main/java/com/clawmate/app/home/ui/onboarding/OnboardingViewModelFactory.kt (new)
app/src/test/java/com/clawmate/app/home/*Test.kt                        (6 new test classes, 36 tests)
README.md                                                              (modified)
MIGRATION_STATUS.md                                                    (modified)
DSH_PHASE_RESULT.md                                                    (rewritten — this file)
```

## Limitations & platform blockers

- **No successful data is invented.** The home profile, pillars, completeness,
  and avatar section report the honest empty / NOT_CONFIGURED / DISABLED states
  because no real Android data-generator or avatar engine exists yet.
  Recommendation *content* requires the upstream data-manager pipeline (a later
  phase); the UI and completion-persistence (`HomeRecRepository`) are real and
  fully testable, but the list is physically empty until a real source feeds it.
- **Avatar generation/import is explicitly NOT configured / DISABLED.** There is
  no Android avatar implementation yet, so the home avatar section renders a
  disabled "未启用（未配置）" action and never fabricates an image or a success
  label. `AvatarStatus.DISABLED` + `AvatarSection.notConfigured` are used, and
  tests assert this guarantee.
- **Onboarding & home need no network.** The gate is driven purely by an
  app-private DataStore version key; a configuration-free fresh install shows
  onboarding once and then the 5-tab root, with no endpoint or permission
  requirement.
- **connectedAndroidTest** not run (no device/emulator). `assembleDebug` and all
  **199** JVM unit tests pass in this worktree.
- **Benign Kotlin warnings remain** (a handful of unused params in pre-existing
  Phase-3/4 test scaffolding and a couple of unused symbols in new Compose
  scaffolding) — non-fatal, no compilation errors. New composables are
  intentionally minimal and deterministic; no lint task is configured.

## Next phase

1. **Data-manager vertical slice** — port `DataManagerFacade`/`IndexService`/
   `HomeRecommendationService` behind the `HomeProfileSource` seam so the Home
   pillars, completeness, total-events and recommendation list become populated
   from real (imported) data instead of the honest empty profile, feeding the
   existing completion-toggling and persistence plumbing.
2. **Digital avatar (real generator)** once a provider is configured — replace
   the honest DISABLED avatar section with a real image-generation client gated
   by the existing Settings avatar keys, keeping NOT_CONFIGURED as the fallback.
3. Optionally: **onboarding a/b or re-onboarding** by bumping
   `FIRST_LAUNCH_ONBOARDING_VERSION` and re-running the guide for users on an
   older version.
