# ClawMate Android Migration

## Scope

This repository is the Android migration target for the HarmonyOS application at
`~/mobiinfra-oh` (`/home/yydh/mobiinfra-oh`). Upstream source repository:
https://github.com/doulujiyao12/mobiinfra-oh. The HarmonyOS source is
reference-only: do not modify files under `~/mobiinfra-oh`.

The target must be a real Android application, not a screenshot mock or a
minimal demo. Preserve the user-visible product flows and data contracts where
the Android platform can support them.

## Working rules

- Approved workspaces are /home/yydh/ClawMate-Android and the current Windows
  repository C:\Users\Askeladd\Documents\ChatGPT\ClawMate_Android. The user
  authorized Windows work while the remote host was unavailable.
- Follow the user's accepted shared-frontend strategy: reuse ArkTS/ArkUI with
  ArkUI-X, and keep Android-specific adapters in Kotlin. The shared Android host
  is shared-app/ and the source preparation/overlays are shared-frontend/.
  Preserve app/ (the existing Compose product) as a regression baseline until a
  production switch and data migration have been separately verified.
- Keep commits small and reviewable. Every meaningful phase must end with a
  commit and a short note in MIGRATION_STATUS.md.
- Never claim a build, test, or runtime check passed unless the command actually
  ran and its output supports that claim.
- Do not copy secrets, signing keys, tokens, local databases, or device-specific
  state from the HarmonyOS project.
- Do not delete or overwrite target files without checking git diff first.
- Do not change the HarmonyOS source project.
- Keep generated build output, local SDK paths, and IDE state out of Git.

## Required first phase

Before implementing broad UI:

1. Inventory the source tree and identify the source of truth for each major
   feature.
2. Create a feature mapping in MIGRATION_STATUS.md covering:
   onboarding, home/recommendations, chat and agent loop, gallery/collection,
   data manager, workflows/tasks, settings, voice/avatar, local model, cloud
   model, HDC/GUI automation, Python bridge, and native C++/MNN.
3. Record which Android implementation strategy will be used for each feature.
4. Create a compiling Android skeleton with application id, navigation shell,
   theme, resources, and test source sets.
5. Add a .gitignore before importing generated files or dependencies.

## Product requirements

The migration should preserve these flows in priority order:

1. App launch, onboarding, home, navigation, and settings.
2. Chat sessions, streaming model replies, tool/agent status, persistence, and
   error states.
3. Gallery analysis, collections, recommendations, and local data storage.
4. Workflow/task browsing, execution state, and result display.
5. Cloud model configuration and local model capability boundaries.
6. Optional integrations: voice, digital avatar, HDC/GUI automation, Python
   services, and native inference.

For platform-specific features that cannot be ported directly, implement a
clear Android adapter or a controlled disabled state with an actionable error.
Do not silently remove a feature or pretend that a mock response is real.

## Networking and security

- Keep endpoints and credentials out of source control.
- Use Android-compatible HTTP/WebSocket clients and structured serialization.
- Preserve streaming, cancellation, retries, timeouts, and error handling where
  the source has them.
- Put environment-specific endpoints in a local configuration mechanism and
  provide a safe example configuration.
- Treat imported data and model output as untrusted input.

## Environment notes

- `adb` is available (`/usr/bin/adb`); a real phone may be attached. Use it to
  install the debug APK and inspect the live app (logcat, screenshots, UI dumps)
  when a device is connected.
- Google's `android` CLI skill is installed at `~/.codex/skills/android-cli/`
  (project creation, device/emulator interaction, UI layout inspection,
  Android doc lookup). Consult its `references/` for device-interaction details.

## Build environment

For the Windows shared frontend, see shared-frontend/README.md and build.ps1.
Use the locked SDK pair and pass local JDK/Android SDK paths as parameters; keep
generated SDKs, upstream inputs and runtime libraries in the ignored workspace.
Do not replace the original application ID or copy its private data as part of
ordinary frontend checks. The Linux command below remains for the Compose baseline.

The Android build environment lives at `~/Android/Sdk` on the DSH host. Gradle
operations may need elevated permissions because the wrapper and daemon write
outside the repository. Use the persistent Gradle cache at
`/home/yydh/.gradle`; do not create a project-local cache or commit generated
Gradle state. The Android SDK and Gradle cache paths are environment-specific
and must not be copied into application source or configuration files.

The verified build command is:

```bash
cd /home/yydh/ClawMate-Android && \
GRADLE_USER_HOME=/home/yydh/.gradle \
ANDROID_HOME=/home/yydh/Android/Sdk \
ANDROID_SDK_ROOT=/home/yydh/Android/Sdk \
ANDROID_SDK_HOME=/tmp/clawmate-android-home \
./gradlew :app:assembleDebug
```

If the sandbox rejects wrapper/cache access, rerun the same command with
elevated permissions after confirming the target paths.

### SDK components (verified working)

| Component | Location |
|-----------|----------|
| **Java** | `/usr/bin/java` — OpenJDK 17.0.18 |
| **Android SDK** | `~/Android/Sdk/platforms/android-34` |
| **Build tools** | `~/Android/Sdk/build-tools/34.0.0`, `35.0.0` |
| **NDK** | `~/Android/Sdk/ndk/` |
| **CMake** | `~/Android/Sdk/cmake/` |
| **Gradle Wrapper** | `/home/yydh/ClawMate-Android/gradlew` — Gradle 8.9 |
| **Gradle Cache** | `/home/yydh/.gradle/wrapper/dists/gradle-8.9-bin/` |


## Verification

At each phase run the strongest checks available. At minimum use Gradle
configuration/build checks when the Android SDK is available, Kotlin/static
checks where available, and unit tests for storage, parsing, routing, and
workflow state. If the remote machine lacks Java, Android SDK, or Gradle,
record that blocker and still run repository-level checks that do not need
those tools.

Before asking for review, report:

- changed files and feature coverage;
- commands run and their actual results;
- known gaps and platform blockers;
- the commit hash for the phase.

## Collaboration protocol

The migration is reviewed in phases. Do not start a large unrelated refactor
while a previous phase has failing checks. Prefer an explicit TODO or adapter
over speculative infrastructure. Keep the source-to-target mapping current as
implementation decisions change.
