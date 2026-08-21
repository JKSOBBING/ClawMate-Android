# ClawMate Android Debug APK Export

## Scope

Export and verify a debug APK from the current Android migration baseline. This is an artifact/export task, not a feature implementation task.

- Target repository: `/root/ClawMate-Android`
- HarmonyOS reference: `/root/mobiinfra-oh` (read-only; do not modify)
- Baseline branch: `main`
- Expected baseline commit: `bb5f735`
- Output directory: `/root/exports/ClawMate-Android-debug-<timestamp>/`
- Required result file: `/root/ClawMate-Android/export.result.md`

## Required procedure

1. Confirm the repository is on `main` at the expected baseline, or record the actual commit if it differs.
2. Confirm JDK, Android SDK, Build Tools, and Gradle versions.
3. Use one serial Gradle process with `--no-daemon --max-workers=1`.
4. Run `:app:testDebugUnitTest` and record the actual result.
5. Run `:app:assembleDebug` and record the actual result.
6. Copy the resulting `app/build/outputs/apk/debug/app-debug.apk` into the timestamped output directory.
7. Compute and record the APK size and SHA256.
8. Do not modify application source code, do not create release signing material, and do not commit build outputs, `local.properties`, caches, or credentials.

## Result contract

Write `/root/ClawMate-Android/export.result.md` with:

- exact commands and exit codes;
- test totals and failures;
- APK absolute path, byte size, and SHA256;
- current Git commit and clean/dirty status;
- toolchain versions;
- whether source files changed;
- device-install/runtime validation status and remaining limitations.

If any step fails, stop before claiming success and record the failure, relevant logs, and the smallest next action. At the end, record one reusable lesson for the DSH collaboration Skill if this task reveals a new workflow or resource issue.
