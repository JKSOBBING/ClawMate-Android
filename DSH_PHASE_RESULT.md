# DSH_PHASE_RESULT — ClawMate Android, Phase 10 (Local model capability boundary)

## Worktree

- Path: `/opt/deepseek-harness/clawmate-phase10-local-model`
- Branch: `master`
- Source of truth (reference only, not modified): `/root/mobiinfra-oh`
- This delivery was **built and tested in this worktree** (JDK 17.0.19 OpenJDK,
  Android SDK platform 34 / build-tools 34.0.0 at
  `/opt/deepseek-harness/android-sdk`, **Android NDK 25.2.9519653 + Google CMake
  3.22.1 installed there via the SDK's `sdkmanager`**, Gradle 8.9 at
  `/opt/deepseek-harness/gradle-dist/gradle-8.9`).
- Build state used: `GRADLE_USER_HOME=/opt/deepseek-harness/.gradle-user-home`,
  `ANDROID_USER_HOME=/opt/deepseek-harness/.android-user-home` (discarded, never
  committed). Nothing was written to `/root/ClawMate-Android`; `/root/mobiinfra-oh`
  was not modified. All Phase 1–9 code is preserved.
- Working policy: serial only, single worker, no parallel Gradle processes, no
  servers/emulators. The NDK/cmake install was a single `sdkmanager` invocation
  (one compatible NDK, no model binaries, no parallel installs).

## Commits

First commit (feature slice):
- Message: `Phase 10 local model capability boundary`
- Hash: **`936436752f78c9222ddcb03d8133c8594f879d9d`** (`93643675`) on `master`,
  created in this worktree.

The result-file update (this file, with the feature hash filled in) is then
committed as the final docs commit — both the feature commit and the result-file
commit are present in the branch history.

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
`app/build/outputs/apk/debug/app-debug.apk` (~19 MB) and packaged the truthful
native stub `libclawmate_local_model.so` for **arm64-v8a, armeabi-v7a, x86_64**
(`externalNativeBuild` via `app/src/main/cpp`). `:app:testDebugUnitTest` ran
**380 tests, 0 failures, 0 errors** (342 prior Phases 1–9 + **38 new Phase-10
local-model tests**). `:app:connectedAndroidTest` was **not run** — no
device/emulator is available in this worktree.

## Scope delivered (Phase 10)

1. **Typed local-model domain** (`localmodel/LocalModelTypes.kt`): typed
   lifecycle states **NOT_CONFIGURED / UNSUPPORTED / LOADING / READY / ERROR**
   with typed error kinds (INVALID_PATH, MISSING_CONFIG, MISSING_MODEL_FILE,
   NOT_READY, NATIVE_LIBRARY_MISSING, LOAD_FAILED) and deterministic labels.
   Normalization never reports READY from disk state alone.

2. **App-private path validation** (`localmodel/LocalModelPathValidator.kt`):
   model dirs must live under `filesDir/model` (legacy) or
   `filesDir/modelscope/{owner}__{repo}` (ModelScope); `..`/`.`/escape/
   non-whitelisted bases and the `filesDir` root itself are rejected before any
   load. No arbitrary file/dir deletion path is ever offered.

3. **Deterministic inventory scanner** (`localmodel/LocalModelInventory.kt`),
   porting `Index.ets modelLoadableStatus / hasModelWeightFile /
   isModelWeightFile`: a model dir is loadable when it has `config.json` + a
   weight file (`.mnn`/`.om`/`.safetensors`/`.weight`), scanned through an
   injectable `ModelFolderProbe` seam so tests are device-free.

4. **Honest capability boundary** (`localmodel/LocalModelCapability.kt`): the
   explicit `loadAndTest(modelId)` action validates the path, requires a real
   complete model dir, requires a supported native adapter, and then performs
   the native load — reaching **READY only on a genuine native Success**.
   Missing MNN/LLM on Android always maps to UNSUPPORTED; missing config/weight
   maps to typed ERROR. No fake completion.

5. **JNI/CMake native adapter (truthful stub)** (`app/src/main/cpp/`
   `CMakeLists.txt` + `local_model_jni.cpp`, built by AGP `externalNativeBuild
   cmake`, `ndkVersion = "25.2.9519653"`): exposes `nativeStatus`,
   `nativeLoad`, `nativeUnload`, `nativeSelfCheck`. Because MNN-for-Android and
   the OHOS N-API/HiAI runtime are **absent** (and not vendored/copied), the
   stub returns typed `UNSUPPORTED` / `NATIVE_LIBRARY_MISSING` results and
   documents exactly what a real adapter needs (per-ABI `libMNN` + an
   `Llm`-equivalent loader). The `.so` is verified packaged into the APK.

6. **Settings integration** (`localmodel/LocalModelViewModel.kt` +
   `LocalModelPanel.kt`, wired into `settings/ui/SettingsSections.kt` under the
   模型与智能体 section): shows the typed status, discovered inventory, and an
   explicit **"加载/测试 <模型>"** action per loadable model; READY only after a
   real load success. App launch performs no network and no model load. Cloud
   chat path unchanged.

7. **Deterministic tests (38 new, §Verification summary)**: state
   normalization, path validation, inventory scanning, no-fake-output
   (missing-library / missing-config / missing-weight / invalid-path /
   native-success-only-READY) and native result-token parsing.
   Device/native smoke tests documented unavailable (no emulator, no on-device
   MNN runtime).

## Files changed (Phase 10)

New (Kotlin domain + UI):
- `app/src/main/java/com/clawmate/app/localmodel/LocalModelTypes.kt`
- `app/src/main/java/com/clawmate/app/localmodel/LocalModelPathValidator.kt`
- `app/src/main/java/com/clawmate/app/localmodel/LocalModelInventory.kt`
- `app/src/main/java/com/clawmate/app/localmodel/LocalModelCapability.kt`
- `app/src/main/java/com/clawmate/app/localmodel/LocalModelNative.kt`
- `app/src/main/java/com/clawmate/app/localmodel/LocalModelViewModel.kt`
- `app/src/main/java/com/clawmate/app/localmodel/LocalModelPanel.kt`

New (native/JNI stub):
- `app/src/main/cpp/CMakeLists.txt`
- `app/src/main/cpp/local_model_jni.cpp`

New (tests):
- `app/src/test/java/com/clawmate/app/localmodel/LocalModelTypesTest.kt`
- `app/src/test/java/com/clawmate/app/localmodel/LocalModelPathValidatorTest.kt`
- `app/src/test/java/com/clawmate/app/localmodel/LocalModelInventoryTest.kt`
- `app/src/test/java/com/clawmate/app/localmodel/LocalModelCapabilityTest.kt`
- `app/src/test/java/com/clawmate/app/localmodel/LocalModelNativeTest.kt`

Modified:
- `app/build.gradle.kts` (externalNativeBuild cmake, ndkVersion 25.2.9519653,
  abiFilters)
- `app/src/main/java/com/clawmate/app/settings/ui/SettingsSections.kt`
  (LocalModelPanel wired into the 模型与智能体 section)

## Verification summary

- Baseline (before changes): `:app:assembleDebug :app:testDebugUnitTest` →
  **BUILD SUCCESSFUL exit 0**, 342 tests green.
- After Phase 10: `:app:assembleDebug :app:testDebugUnitTest` → **BUILD
  SUCCESSFUL exit 0**, 380 tests / 0 failures / 0 errors.
- The native JNI/CMake stub compiled and packaged for all three ABIs
  (`:app:externalNativeBuildDebug` and `:app:assembleDebug` both succeeded).
- Device-dependent paths are not exercised here: no emulator for
  `connectedAndroidTest`; a real on-device MNN model load cannot run without
  MNN-for-Android and a device, so the native adapter is a documented truthful
  stub and no fake model output is produced.

## Known gaps / notes

- **MNN-for-Android absent:** a real on-device local model requires linking a
  per-ABI MNN Android prebuilt library plus an `Llm`-equivalent C++ loader
  (the OHOS N-API/HiAI runtime cannot be reused on Android). The capability
  boundary and native stub are in place; the adapter honestly reports
  NOT_CONFIGURED / UNSUPPORTED until MNN is provided.
- Model files stay app-private (`filesDir/model`, `filesDir/modelscope`) and
  are never committed; the inventory/path checks prevent arbitrary deletion.
- `connectedAndroidTest` and an on-device load smoke test are documented as
  unavailable (no emulator/device, no MNN runtime).
