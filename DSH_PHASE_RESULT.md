# DSH_PHASE_RESULT — ClawMate Android, Phase 11 (Real on-device OCR)

## Worktree

- Path: `/opt/deepseek-harness/clawmate-phase11-ocr`
- Branch: `master`
- Source of truth (reference only, not modified): `/root/mobiinfra-oh`
- This delivery was **built and tested in this worktree** (JDK 17.0.19 OpenJDK,
  Android SDK platform 34 / build-tools 34.0.0 at
  `/opt/deepseek-harness/android-sdk`, Android NDK 25.2.9519653 + Google CMake
  3.22.1 installed there via the SDK's `sdkmanager`, Gradle 8.9 at
  `/opt/deepseek-harness/gradle-dist/gradle-8.9`).
- Build state used: `GRADLE_USER_HOME=/opt/deepseek-harness/.gradle-user-home`,
  `ANDROID_USER_HOME=/opt/deepseek-harness/.android-user-home` (both discarded,
  never committed). Nothing was written to `/root/ClawMate-Android`;
  `/root/mobiinfra-oh` was not modified. All Phase 1–10 code is preserved.
- Working policy: serial only, single worker, no parallel Gradle processes, no
  servers/emulators, one Gradle build at a time.
- Note: the Gradle wrapper distribution URL (`services.gradle.org`) is **not
  reachable from this worktree** (download times out). The build therefore ran
  the same **Gradle 8.9** release binary directly from
  `/opt/deepseek-harness/gradle-dist/gradle-8.9/bin/gradle` (the same binary the
  Phase 10 build used), with the same environment variables. This is recorded
  exactly under "Command executed".

## Commit

The Phase-11 feature changes plus this documentation are committed together as a
single feature commit:
- Message: `Phase 11 local OCR`
- Hash: recorded at the bottom of this file after the commit (this file is part
  of that same commit, so the hash is the commit that contains it).

## Command executed (actual, in this worktree)

```
ANDROID_HOME=/opt/deepseek-harness/android-sdk \
ANDROID_SDK_ROOT=/opt/deepseek-harness/android-sdk \
GRADLE_USER_HOME=/opt/deepseek-harness/.gradle-user-home \
ANDROID_USER_HOME=/opt/deepseek-harness/.android-user-home \
/opt/deepseek-harness/gradle-dist/gradle-8.9/bin/gradle \
    --no-daemon --max-workers=1 :app:assembleDebug :app:testDebugUnitTest
```

Result: **BUILD SUCCESSFUL — exit code 0**.
- `:app:assembleDebug` produced `app/build/outputs/apk/debug/app-debug.apk`
  (~52.5 MB, regenerated fresh in this worktree). The APK packages the **bundled
  ML Kit Chinese text-recognition** (`com.google.mlkit:text-recognition-chinese:
  16.0.1`, resolved on Google Maven): `libmlkit_google_ocr_pipeline.so` for
  arm64-v8a / armeabi-v7a / x86_64 plus the bundled Chinese (`Hani_ctc`) and
  Latin (`Latn_ctc`) OCR model assets and the truthful local-model JNI stub
  (`libclawmate_local_model.so`, three ABIs).
- `:app:testDebugUnitTest` was re-run to completion in this worktree: **407
  tests, 0 failures, 0 errors** (380 prior Phases 1–10 + **27 new Phase-11 OCR
  tests**), across 61 test class result files.
- `:app:connectedAndroidTest` was **not run** — no device/emulator is available
  in this worktree; ML Kit's runtime and any on-device model handling require a
  real device.

## ML Kit dependency resolution (verified)

`com.google.mlkit:text-recognition-chinese:16.0.1` (and its
`com.google.mlkit:text-recognition` and `com.google.mlkit:vision-common`
dependencies and the bundled `com.google.android.gms:play-services-*` base)
resolved on Google Maven. The resolved AAR/artifacts were confirmed present in
`GRADLE_USER_HOME/caches/modules-2`, and the final APK was inspected to contain
the bundled OCR native pipeline + Chinese/Latin model assets described above.

## Scope delivered (Phase 11)

1. **Real on-device Chinese OCR adapter** (`gallery/ocr/AndroidOcrEngine.kt`),
   replacing the Phase-9 `NOT_CONFIGURED` OCR boundary: opens the picked
   `content://` photo URI through `ContentResolver` (picker URI grant — no broad
   storage permission), decodes a bounded-size `Bitmap` (64 MB pixel budget),
   runs ML Kit `task-recognition-chinese` on a background dispatcher, maps every
   outcome to typed `OcrResult` states, and closes the recognizer/bitmap/stream.

2. **Typed result taxonomy** (`CapabilityEngines.kt`): `OcrResult` is now
   `Text / Empty / Cancelled / ModelDownload / Failed(kind) / Unavailable` with a
   stable `OcrErrorKind` enum (UNKNOWN, URI_ACCESS, DECODE, MLKIT_ENGINE,
   UNAVAILABLE, CANCELLED). Only `Text` carries real recognized text; every
   other state is an honest non-text result. `OcrEngine.recognize` is now a
   suspend function.

3. **Device-free pure mapping** (`gallery/ocr/OcrMapping.kt`): concentrated
   decision logic (real-text vs honest-empty, failure classification,
   model-download state, no-fake-output guarantee) that is deterministic and
   unit-tested without a device.

4. **Bounded-bitmap decode planning** (`gallery/ocr/OcrBitmapDecode.kt`):
   deterministic power-of-two `inSampleSize` downscale to ≤2048 px side and an
   honest memory-budget refusal (no blind OOM).

5. **User-triggered Analyze OCR** in the gallery detail panel
   (`GalleryDetailScreen` + `GalleryViewModel.analyzeOcr`): explicit action with
   typed loading/success/empty/error/model-download/unavailable states. Only real
   recognized text (or a genuine empty) + the `analyzed` status is persisted via
   Room (`GalleryDao.updateOcr` / `GalleryRepository.updateOcr`). Failures,
   model-download, and unavailable persist nothing and never fabricate text.

6. **Wiring**: `GalleryComponent.ocr(context)` now returns the real adapter
   (`androidOcrEngine`); cloud vision stays NOT_CONFIGURED and reverse geocoding
   is unchanged.

## Files changed (Phase 11)

New:
- `app/src/main/java/com/clawmate/app/gallery/ocr/AndroidOcrEngine.kt`
- `app/src/main/java/com/clawmate/app/gallery/ocr/OcrBitmapDecode.kt`
- `app/src/main/java/com/clawmate/app/gallery/ocr/OcrMapping.kt`
- `app/src/test/java/com/clawmate/app/gallery/ocr/OcrBitmapDecodeTest.kt`
- `app/src/test/java/com/clawmate/app/gallery/ocr/OcrMappingTest.kt`
- `app/src/test/java/com/clawmate/app/gallery/ui/OcrAnalysisViewModelTest.kt`

Modified:
- `app/build.gradle.kts` (added `libs.mlkit.text.recognition.chinese`)
- `gradle/libs.versions.toml` (ML Kit version + dependency coordinates)
- `GalleryComponent.kt`, `CapabilityEngines.kt` (typed `OcrResult`/`OcrErrorKind`,
  suspend `recognize`, real adapter wiring)
- `GalleryDAO.kt`, `GalleryRepository.kt`, `DefaultGalleryRepository.kt`
  (`updateOcr`)
- `GalleryModels.kt` (`ANALYSIS_STATE_ANALYZED = "analyzed"`)
- `GalleryViewModel.kt` (`analyzeOcr` + `OcrViewState` + detail OCR state)
- `GalleryDetailScreen.kt` (Analyze OCR section), `GalleryScreen.kt` (wiring),
  `GalleryDetailLabels.kt` (honest "已分析（本地 OCR）" label)
- `README.md`, `MIGRATION_STATUS.md` (this phase's docs)

## Verification summary

- ML Kit `com.google.mlkit:text-recognition-chinese:16.0.1` resolves on Google
  Maven (verified in the module cache; bundled model/native libs confirmed in the
  final APK).
- `:app:assembleDebug :app:testDebugUnitTest` → **BUILD SUCCESSFUL exit 0**.
- Fresh test execution in this worktree → **407 tests, 0 failures, 0 errors**
  (incl. the 27 new Phase-11 OCR tests).
- `:app:connectedAndroidTest` not run (no device/emulator).

## Known gaps / notes

- **Live OCR output requires a real device**: ML Kit Chinese text recognition is
  wired, bundled, and unit-tested (deterministic mapping), but real recognized
  text from an actual photo and any on-device model handling can only be
  exercised on a device/emulator, which this worktree lacks — documented, not
  claimed as verified on-device.
- Cloud vision (MiniCPM-V) remains NOT_CONFIGURED (no endpoint/key configured).
- No broad storage permission, no network endpoint/API key, no background scan of
  all media — only explicit user-triggered analysis of a selected photo.

## Commit hash

- `Phase 11 local OCR` → **`3b5f5407d64a10bcaecd1e6803f8b96a44b16607`**
