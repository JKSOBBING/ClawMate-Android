# DSH_PHASE_RESULT — ClawMate Android, Phase 9 (Gallery detail & local analysis)

## Worktree

- Path: `/opt/deepseek-harness/clawmate-phase9-gallery-analysis`
- Branch: `master`
- Source of truth (reference only, not modified): `/root/mobiinfra-oh`
- This delivery was **built and tested in this worktree** (JDK 17.0.19 OpenJDK,
  Android SDK platform 34 / build-tools 34.0.0 at `/opt/deepseek-harness/android-sdk`,
  Gradle 8.9 at `/opt/deepseek-harness/gradle-dist/gradle-8.9`).
- Build state used: `GRADLE_USER_HOME=/opt/deepseek-harness/.gradle-user-home`,
  `ANDROID_USER_HOME=/opt/deepseek-harness/.android-user-home` (discarded, never
  committed). Nothing was written to `/root/ClawMate-Android`; `/root/mobiinfra-oh`
  was not modified. All Phase 1–8 code is preserved.
- Working policy: serial only, single worker, no parallel Gradle processes, no
  servers/emulators.

## Commits

First commit (feature slice):
- Message: `Phase 9 gallery detail analysis`
- Hash: **`522c967`** (`522c9675fc0fef46564e855f6bf51164ccb26beb`) on `master`,
  created in this worktree.

The result-file update (this file, with the feature hash filled in) is
then committed as the final docs commit — both the feature commit and the
result-file commit are present in the branch history.

## Command executed (actual, in this worktree)

```
ANDROID_HOME=/opt/deepseek-harness/android-sdk \
ANDROID_SDK_ROOT=/opt/deepseek-harness/android-sdk \
GRADLE_USER_HOME=/opt/deepseek-harness/.gradle-user-home \
ANDROID_USER_HOME=/opt/deepseek-harness/.android-user-home \
/opt/deepseek-harness/gradle-dist/gradle-8.9/bin/gradle \
    --no-daemon --max-workers=1 :app:assembleDebug :app:testDebugUnitTest
```

Result: **BUILD SUCCESSFUL — exit code 0**. `:app:assembleDebug` produced
`app/build/outputs/apk/debug/app-debug.apk` (~18.3 MB); `:app:testDebugUnitTest`
ran **342 tests, 0 failures, 0 errors** (303 prior Phases 1–8 + **39 new
Phase-9 gallery tests**). `:app:connectedAndroidTest` was **not run** — no
device/emulator is available in this worktree.

## Scope delivered (Phase 9)

1. **Real gallery item detail flow** (`gallery/ui/GalleryDetailScreen.kt`),
   opened from the existing gallery list by tapping a photo row. Shows only
   real data — content URI, archive date, EXIF capture time / device /
   dimensions, analysis state, tags / summary and collection membership — with
   no fabricated values. Deterministic label mapping in
   `gallery/ui/GalleryDetailLabels.kt`.

2. **Explicit safe edit operations** (`GalleryRepository` /
   `DefaultGalleryRepository` / `GalleryDao`): `updateTitle`, `updateSummary`,
   `updateTags`, `updateLocation`, and **delete-by-item** (`deleteItem(id)`)
   with a UI confirmation `AlertDialog`. Edits are persisted to Room; delete
   removes only the single item row and recomputes collection groups. No broad
   files are deleted and Photo-Picker URI grants are never revoked; a deleted /
   inaccessible item is surfaced honestly.

3. **Android reverse geocoding** (`gallery/geocode/`):
   - `AndroidGeocoderClient` wraps the platform `android.location.Geocoder`
     (no AMap/web key, no network endpoint).
   - Typed outcomes and errors (`ReverseGeocodeOutcome` /
     `ReverseGeocodeErrorKind` UNAVAILABLE / PERMISSION / LOOKUP_FAILED / IO /
     NOT_CONFIGURED) with an honest single-line `AddressFormatter` and pure,
     device-free `AndroidGeocoderMapping` (separated from the platform call so
     the decision logic is unit-testable).
   - Wired as an **explicit injected capability** via
     `GalleryComponent.reverseGeocoder(context)` → `AndroidReverseGeocoder`
     (the default NOT_CONFIGURED engine remains for tests/injection).
   - The detail panel exposes 解析/重试 with typed failure text and **never
     claims a location when a lookup fails**; a resolved place is persisted to
     the new `address` / `locationProvider` item columns.

4. **OCR engine boundary**: ML Kit `text-recognition-chinese` (16.0.x) is
   resolvable on Google Maven, but it is the download-on-device (Play-Services)
   model variant and there is no emulator/device here to verify real recognized
   text — so it is **not** vendored. OCR remains the explicit `OcrEngine`
   NOT_CONFIGURED boundary with a typed `Unavailable` message; cloud vision
   remains NOT_CONFIGURED. Documented in `CapabilityEngines.kt` and
   `MIGRATION_STATUS.md`. No OCR output is ever invented.

5. **Capability UI reflects actual states**: `CapabilitySummary` now tracks
   `hasUnconfigured` / `hasUnsupported`; `GalleryScreen` renders Ready /
   NotConfigured / Unsupported per-capability text, and the detail panel shows
   retry/unsupported messages (e.g. geocoder unavailable → typed error + 重试).

6. **Storage / schema**: `GalleryItemEntity` gains `address` / `locationProvider`
   / `analysisState`; `GalleryDatabase` bumped **v1 → v2** with an explicit,
   non-destructive `ALTER TABLE ADD COLUMN` migration; the Room v2 schema JSON
   is exported and committed. Photo-Picker URI-grant import and the existing
   search/grouping services are preserved; no broad storage permissions added.

7. **Deterministic tests (39 new, §Verification summary)**: detail mapping,
   edit/delete persistence, geocoder result/error mapping, capability states
   and group recomputation. No live geocoder/device tests are included.

## Files changed (Phase 9)

New:
- `app/src/main/java/com/clawmate/app/gallery/geocode/LocationModels.kt`
- `app/src/main/java/com/clawmate/app/gallery/geocode/AddressFormatter.kt`
- `app/src/main/java/com/clawmate/app/gallery/geocode/AndroidGeocoderClient.kt`
- `app/src/main/java/com/clawmate/app/gallery/geocode/AndroidReverseGeocoder.kt`
- `app/src/main/java/com/clawmate/app/gallery/ui/GalleryDetailScreen.kt`
- `app/src/main/java/com/clawmate/app/gallery/ui/GalleryDetailLabels.kt`
- `app/schemas/com.clawmate.app.gallery.data.GalleryDatabase/2.json`
- `app/src/test/java/com/clawmate/app/gallery/geocode/AndroidGeocoderMappingTest.kt`
- `app/src/test/java/com/clawmate/app/gallery/geocode/AddressFormatterTest.kt`
- `app/src/test/java/com/clawmate/app/gallery/data/GalleryRepositoryEditDeleteTest.kt`
- `app/src/test/java/com/clawmate/app/gallery/ui/GalleryViewModelEditDeleteTest.kt`
- `app/src/test/java/com/clawmate/app/gallery/ui/GalleryDetailLabelsTest.kt`

Modified:
- `app/src/main/java/com/clawmate/app/gallery/GalleryComponent.kt`
- `app/src/main/java/com/clawmate/app/gallery/analysis/CapabilityEngines.kt`
- `app/src/main/java/com/clawmate/app/gallery/data/GalleryDao.kt`
- `app/src/main/java/com/clawmate/app/gallery/data/GalleryDatabase.kt` (v1→v2)
- `app/src/main/java/com/clawmate/app/gallery/data/GalleryEntities.kt`
- `app/src/main/java/com/clawmate/app/gallery/data/GalleryMappers.kt`
- `app/src/main/java/com/clawmate/app/gallery/data/GalleryRepository.kt`
- `app/src/main/java/com/clawmate/app/gallery/model/GalleryModels.kt`
- `app/src/main/java/com/clawmate/app/gallery/ui/GalleryScreen.kt`
- `app/src/main/java/com/clawmate/app/gallery/ui/GalleryViewModel.kt`
- `app/src/test/java/com/clawmate/app/gallery/analysis/CapabilityEnginesTest.kt`

## Verification summary

- Baseline (before changes): `:app:assembleDebug :app:testDebugUnitTest` →
  **BUILD SUCCESSFUL exit 0**, 303 tests green.
- After Phase 9: `:app:assembleDebug :app:testDebugUnitTest` → **BUILD
  SUCCESSFUL exit 0**, 342 tests / 0 failures / 0 errors.
- Device-dependent paths are not exercised here: no emulator for
  `connectedAndroidTest`; the platform Geocoder reverse lookup and OCR model
  download run on-device only and are deliberately not faked in unit tests.

## Known gaps / notes

- OCR stays NOT_CONFIGURED (documented): wiring ML Kit now without a device to
  verify real recognized text would risk fabricated output.
- Reverse geocoding quality/latency depends on the device's platform Geocoder.
- The Android Geocoder reverse lookup of a provided coordinate needs no location
  permission; the `PERMISSION` error kind is modelled for future keyed providers
  and is never falsely raised by the Android adapter.
