# Phase 23 result: real serial opt-in Android boundary for Harmony GalleryAutoScanner/GalleryMemorySync/background gallery behaviour; typed NOT_CONFIGURED/BLOCKED/READY/RUNNING/SUCCESS/PARTIAL/ERROR states; explicit user setting + runtime media permission gate; bounded/cancellable/idempotent coordinator; cloud memory-sync calls ONLY the explicitly configured GalleryCloudAdapter and never sends when unconfigured; Photo Picker + local insights preserved; Settings control/status; production fix: MediaStoreGalleryMediaSource builds a valid content URI from the row _ID without requiring the scoped-storage-omittable filesystem DATA column; BUILD SUCCESSFUL; 649 tests, 0 failures, 0 errors.

---

# DSH_PHASE_RESULT — ClawMate Android, Phase 23 (serial opt-in Gallery background scan/sync coordinator + scoped-storage-safe MediaStore content-URI fix)

## Worktree

- Delivery worktree: `/opt/deepseek-harness/clawmate-phase23-gallery-background`, a git
  worktree on the current target HEAD (`1b482c4`, Phase 22). Target `/root/ClawMate-Android`
  was **not modified**.
- Source of truth (read-only, not modified): `/root/mobiinfra-oh`
  (`entry/src/main/ets/utils/gallery/GalleryAutoScanner.ets`,
  `GalleryMemorySync.ets`, `GalleryStorage.ets`, `GalleryTypes.ets`).
- Built and tested in this worktree (JDK 17, Android SDK platform 34 / build-tools 34.0.0
  at `/opt/deepseek-harness/android-sdk`, Gradle 8.9 at
  `/opt/deepseek-harness/gradle-dist/gradle-8.9`). Build state:
  `GRADLE_USER_HOME=/opt/deepseek-harness/.gradle-user-home`,
  `ANDROID_USER_HOME=/opt/deepseek-harness/.android-user-home`.
- Working policy: serial only, `--max-workers=1`, one Gradle build at a time, no
  servers/emulators, no parallel jobs, no media scan, no adb/hdc, no cloud calls during
  tests, no fabricated success.

## Commit strategy

This session delivered a clean phase worktree at target HEAD `1b482c4` (Phase 22). The
Phase-23 changes are committed in this delivery worktree at commit
**2578897** (branch `phase23-gallery-background`) on top of `1b482c4`. To keep a clean
commit without writing to the target's own `.git` (sandbox-denied, the same class of
blocker documented for Phases 12/22), this worktree is a **standalone repo** rooted at
`/opt/deepseek-harness/clawmate-phase23-gallery-background` whose parent is the target
Phase-22 commit `1b482c4` and which alternates to the target's object store
(`/root/ClawMate-Android/.git/objects`). To integrate onto the target, re-create the
worktree and cherry-pick `2578897` (or use this worktree's branch). The Phase-23 diff is
exactly the files listed below.

## Scope delivered (Phase 23)

1. **Typed honest state model** (`gallery/background/GalleryBackgroundState.kt`):
   `GallerySyncState` = `NOT_CONFIGURED` / `BLOCKED` / `READY` / `RUNNING` / `SUCCESS` /
   `PARTIAL` / `ERROR`, `GallerySyncRunSummary`, and `GalleryCloudSyncStatus`
   (`RUN` / `SKIPPED_NOT_CONFIGURED` / `FAILED`). This is the single honest vocabulary for
   the Settings status, coordinator results and tests.
2. **Pure bounds + scheduling gate** (`gallery/background/GallerySyncPolicy.kt`):
   scan-limit cap (`MAX_ITEMS_PER_RUN` = 60), batch cap (`MAX_ITEMS_PER_BATCH` = 20),
   work-duration cap (`MAX_WORK_DURATION_MS` = 60 s), coalescing floor
   (`MIN_RUN_INTERVAL_MS` = 10 min), `clampScanLimit`, `batchSizes`, and a pure
   `decideRun` scheduling gate returning `GallerySchedulingDecision`
   (NotConfigured / Blocked / Coalesced / Run). Device-free, deterministic.
3. **Runtime permission + explicit-setting gate** (`gallery/background/GalleryMediaPermission.kt`):
   `GalleryMediaPermission` contract, `GallerySyncGate`, and `GallerySyncGateStatus`
   (NOT_CONFIGURED / BLOCKED / READY). A background scan requires BOTH the user's explicit
   `gallery_background_sync_enabled` setting AND a runtime photo/media-read grant; otherwise
   nothing is scanned.
4. **Media abstraction + Android boundary** (`gallery/background/GalleryMediaSource.kt`,
   `GalleryMediaSource`/`GalleryPhotoInput`; `AndroidGalleryMediaBoundary.kt`):
   `AndroidGalleryMediaPermission` (ContextCompat read of `READ_MEDIA_IMAGES` on 33+ /
   `READ_EXTERNAL_STORAGE` below), `MediaStoreGalleryMediaSource` (newest-first, refuses to
   query without a grant — defense in depth), and `FakeGalleryMediaPermission` for host tests.
5. **Serial coordinator** (`gallery/background/GalleryBackgroundCoordinator.kt`): the realised
   Harmony `GalleryAutoScanner` + `GalleryMemorySync` boundary on the existing coroutine +
   Room stack — NO new WorkManager / JobScheduler / Service dependency. Single-flight
   `Mutex.tryLock`, bounded serial batches, cooperative `cancelActive()`, wall-clock deadline,
   Room URI-unique upsert (idempotent; re-runs never duplicate), and a cloud memory-sync step
   that calls **only** the explicitly configured `GalleryCloudAdapter.generateMonthlySummary`
   on a bounded aggregate of actually-stored content — with no configured adapter or no
   meaningful stored content it sends **no bytes** (`SKIPPED_NOT_CONFIGURED`) and never
   invents embeddings/AI summaries.
6. **Settings wiring**: `galleryBackgroundSyncEnabled` (default `false`) in `AppSettings`,
   `GALLERY_BACKGROUND_SYNC_ENABLED` in `SettingsKeys.ALL_KEYS`, `SettingsCodec` encode/decode,
   `SettingsViewModel.setGalleryBackgroundSyncEnabled`, and a 后台图库同步 card in 数据采集
   (toggle + live gate status NOT_CONFIGURED / BLOCKED / READY + honest note).
7. **Manifest**: declares `READ_MEDIA_IMAGES` (33+) / `READ_EXTERNAL_STORAGE` (≤32) **only** as
   the honest grant-observation prerequisite; nothing requests or auto-uses them.
8. **Preserved boundaries**: the Photo Picker least-privilege per-URI flow and the
   deterministic local `GalleryMonthlyInsights` derivation are untouched by this boundary.

## Command executed (actual, in this worktree)

```
ANDROID_HOME=/opt/deepseek-harness/android-sdk \
ANDROID_SDK_ROOT=/opt/deepseek-harness/android-sdk \
GRADLE_USER_HOME=/opt/deepseek-harness/.gradle-user-home \
ANDROID_USER_HOME=/opt/deepseek-harness/.android-user-home \
/opt/deepseek-harness/gradle-dist/gradle-8.9/bin/gradle \
    --no-daemon --max-workers=1 :app:assembleDebug :app:testDebugUnitTest
```

Final result (with the production URI fix applied): **BUILD SUCCESSFUL** (exit code `0`).
- `:app:assembleDebug` produced `app/build/outputs/apk/debug/app-debug.apk` including the fixed
  `gallery/background/` slice plus all prior Phase 1–22 code.
- `:app:testDebugUnitTest` ran fresh in this worktree: **649 tests, 0 failures, 0 errors**
  (623 prior phases + **21 Phase-23 tests** from the original delivery — `GalleryBackgroundCoordinatorTest` 13,
  `GallerySyncPolicyTest` 7, `SettingsCodecTest` +1 opt-in round-trip — + **5 `MediaStoreUriBuilderTest`**
  device-free tests added for the fix).

Evidence: aggregated from `app/build/test-results/testDebugUnitTest/*.xml`
(`tests=649 failures=0 errors=0`).

## Files changed (Phase 23)

New:
- `app/src/main/java/com/clawmate/app/gallery/background/GalleryBackgroundState.kt`
- `app/src/main/java/com/clawmate/app/gallery/background/GallerySyncPolicy.kt`
- `app/src/main/java/com/clawmate/app/gallery/background/GalleryMediaPermission.kt`
- `app/src/main/java/com/clawmate/app/gallery/background/GalleryMediaSource.kt`
- `app/src/main/java/com/clawmate/app/gallery/background/GalleryBackgroundCoordinator.kt`
- `app/src/main/java/com/clawmate/app/gallery/background/AndroidGalleryMediaBoundary.kt`
- `app/src/test/java/com/clawmate/app/gallery/background/GalleryBackgroundCoordinatorTest.kt`
- `app/src/test/java/com/clawmate/app/gallery/background/GallerySyncPolicyTest.kt`
- `app/src/test/java/com/clawmate/app/gallery/background/MediaStoreUriBuilderTest.kt`

Modified (this production fix):
- `app/src/main/AndroidManifest.xml` (declares READ_MEDIA_IMAGES / READ_EXTERNAL_STORAGE as the grant prerequisite only)
- `app/src/main/java/com/clawmate/app/settings/domain/AppSettings.kt` (+ galleryBackgroundSyncEnabled)
- `app/src/main/java/com/clawmate/app/settings/domain/SettingsKeys.kt` (+ GALLERY_BACKGROUND_SYNC_ENABLED)
- `app/src/main/java/com/clawmate/app/settings/data/SettingsCodec.kt` (encode/decode of the opt-in)
- `app/src/main/java/com/clawmate/app/settings/ui/SettingsViewModel.kt` (+ setGalleryBackgroundSyncEnabled)
- `app/src/main/java/com/clawmate/app/settings/ui/SettingsSections.kt` (后台图库同步 card + live gate status)
- `app/src/test/java/com/clawmate/app/settings/SettingsCodecTest.kt` (+ opt-in round-trip test)

Docs (this phase): `README.md`, `MIGRATION_STATUS.md` (row D3 + Phase 23 section), `DSH_PHASE_RESULT.md`.

## Verification detail (device-free tests)

- `GalleryBackgroundCoordinatorTest` (13): scheduling gate (not-opted-in → NOT_CONFIGURED,
  no media touched), permission gate (opted-in-but-no-grant → BLOCKED, no media touched),
  ready-run bounded scan (scan-limit respected, bounded batch size), repeated-run
  idempotency (no duplicates by URI), cancellation → honest PARTIAL, cloud no-call when no
  adapter (`SKIPPED_NOT_CONFIGURED`), cloud no-call when adapter unconfigured, cloud no-call
  when nothing meaningful stored, configured-adapter RUN once (real `GalleryCloudAdapter` +
  fake `ChatClient` → summary path fires once), failing-cloud → PARTIAL, and single-flight
  refusal of a concurrent second run.
- `GallerySyncPolicyTest` (7): scheduling gate (NOT_CONFIGURED / BLOCKED / READY-fresh /
  READY-later), coalescing window, scan-limit clamp, batch cap, honest gate status labels.
- `SettingsCodecTest` (1 new): `gallery_background_sync_enabled` round-trips and defaults
  false; corrupt value degrades to off.

## Production bug fix (delivery review): scoped-storage-safe content-URI construction

**Bug:** `MediaStoreGalleryMediaSource.scanNewest` projected `MediaStore.Images.Media.DATA` and
`?: continue`d any row whose filesystem path was null. On modern Android scoped storage the
`DATA` path is frequently **omitted/null** for a valid image, so a completely usable row was
silently skipped and its content URI never surfaced. It also referenced `dateCol` (not `dataCol`)
in the DATA check, so the guard was wrong as well.

**Fix (in this worktree, committed as described below):**
- The query no longer requests `MediaStore.Images.Media.DATA` at all. The projection is now only
  `_ID` + `DATE_ADDED`.
- Every row yields a content URI purely from its `_ID` via a new pure, device-free helper
  `MediaStoreUriBuilder.contentUriFor(baseUri, id)` (`content://media/external/images/media/<id>`),
  with defensive blank/blank-base and non-numeric-id guards (returns `""`, which the source skips,
  instead of fabricating a URI). No filesystem `DATA` path is read, written, or relied upon.
- Defensive column handling is kept: `_ID` via `getColumnIndexOrThrow`, `DATE_ADDED` via a
  `getColumnIndex` check that falls back to epoch-0 (empty date string) when absent.
- Permission gate and bounded-scan behaviour (`limit <= 0` short-circuit, no-scan-without-grant,
  stop-at-limit) are unchanged and preserved.

**Device-free coverage:** `MediaStoreUriBuilderTest` (5 tests) asserts the _ID-only URI is derived
correctly, trailing-slash robustness on the base, and the defensive refusals (blank id, blank base,
non-numeric / traversal-like ids).

## Known gaps / platform blockers

- The Harmony periodic full-library auto-run loop is intentionally exposed as an
  **explicit-run** (a never-auto-scheduled serial coordinator), not a background timer, to
  honour "never silently scan"; this is documented as the D3 gap.
- This worktree's commit cannot be written into `/root/ClawMate-Android/.git` (sandbox denial,
  consistent with Phases 12/22); integrating onto the target requires re-creating the worktree
  (or cherry-picking) from this clean commit as documented in the "Commit" section.

## Git log (actual)

```
ed6327b fix(phase23): MediaStoreGalleryMediaSource builds content URI from _ID without requiring DATA
2578897 Phase 23 add serial opt-in Gallery background scan/sync coordinator (D3)
```

The clean Phase-23 delivery commit is **2578897** (branch `phase23-gallery-background`, parent
`1b482c4` = Phase 22). The delivery-review **production fix** is the second commit (hash recorded
at commit time above). No fabricated success, no network/cloud calls during tests, no media
scan/copy during the build, no servers/emulators.
