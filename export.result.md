# ClawMate Android Debug APK Export

## Result

Debug unit tests and debug APK assembly completed successfully from baseline commit `bb5f735098308d1f64245ffe2610640193418a07`.

- APK: `/root/exports/ClawMate-Android-debug-20260819-131650/app-debug.apk`
- Size: `64147083` bytes
- SHA256: `131b2a3aabaade911a3844a897f5435e5357fa22ffe78bbd5c48d8327124271a`

## Commands and Exit Codes

| Command | Exit code | Result |
|---|---:|---|
| `git status --short --branch && git rev-parse HEAD && git log -1 --oneline` | 0 | `main`, expected commit `bb5f735`; pre-existing untracked `EXPORT_TASK.md` |
| `java -version` | 0 | OpenJDK 17.0.19 |
| `./gradlew --version` | 0 | Gradle 8.9 |
| `./gradlew --no-daemon --max-workers=1 :app:testDebugUnitTest :app:assembleDebug` | 0 | Tests and APK assembly succeeded in one serial Gradle process |
| `cp app/build/outputs/apk/debug/app-debug.apk /root/exports/ClawMate-Android-debug-20260819-131650/app-debug.apk` | 0 | APK exported |
| `sha256sum /root/exports/ClawMate-Android-debug-20260819-131650/app-debug.apk` | 0 | Digest recorded above |
| `adb devices 2>&1 || true` | 0 (underlying `adb` status 127) | `adb` is not installed; no device validation possible |

## Unit Tests

Task: `:app:testDebugUnitTest`

- Tests: `1118`
- Skipped: `0`
- Failures: `0`
- Errors: `0`

## Toolchain

- JDK: OpenJDK `17.0.19` (`/usr/lib/jvm/java-17-openjdk-amd64`)
- Gradle: `8.9`
- Kotlin: `1.9.23`
- Android SDK: `/opt/deepseek-harness/android-sdk` via ignored `local.properties`
- Android SDK command-line tools: `12.0`
- Compile SDK: Android API `34`, revision `3`
- Build Tools: `34.0.0`
- CMake: `3.22.1`
- NDK: `25.2.9519653`

## Repository State

- Branch: `main`
- Commit: `bb5f735098308d1f64245ffe2610640193418a07`
- Git status: dirty only from pre-existing untracked `EXPORT_TASK.md`; no tracked files were modified by this export.
- Application source files changed: no.
- Build outputs, `local.properties`, caches, and credentials were not committed.

## Validation and Limitations

Device install/runtime validation was not performed because `adb` is unavailable and no device/emulator is connected. The APK is a debug artifact built with the project debug signing configuration; no release signing material was created. Unit-test and assembly logs contained compiler warnings only, with no task failures.

## Reusable DSH Lesson

For Android export jobs, check ignored `local.properties` before declaring the SDK unavailable: this workspace supplied the SDK path there even though `ANDROID_HOME` and `ANDROID_SDK_ROOT` were unset. Check `adb` separately because a successful APK build does not imply install/runtime validation is possible.
