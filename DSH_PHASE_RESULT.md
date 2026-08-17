# Phase 25A + 25B result: wire the WeChat collection gate to the typed adapter

## Scope

Phase 25B replaces the old always-blocked `WechatCollectionGate` boundary with a
safe, explicit, user-triggered call that uses the `WorkflowDeviceAdapter.wechatCollect`
action added in Phase 25A (`wechat_collect`).

`WechatCollectionGate` now wraps the actual `WorkflowDeviceAdapter` (defaulting to
`DisabledWorkflowDeviceAdapter`) plus the `summaryEnabled` opt-in. Its `status`
derives from the health-verified adapter (`isConfigured`) and the opt-in:
`BLOCKED` (no configured bridge), `NOT_CONFIGURED` (auto-summary off),
`AVAILABLE` (configured + enabled). A new `suspend fun collect(request)` performs a
bridge call **only** when every gate passes:

1. strict validation (`validateWechatAutoSummaryRequest`),
2. `summaryEnabled` opt-in,
3. a health-verified, control-ready adapter.

Failing any gate makes **zero** bridge calls and returns an honest typed
`WechatCollectionResult.Blocked` / `NotConfigured`. When allowed, `collect`
invokes `wechatCollect` and maps `HdcActionResult.Success` / `BridgeError` /
`TransportError` / `Unsupported` to typed `WechatCollectionResult.Success` /
`Error` / `TransportError` / `Unsupported` reusing the
`WechatAutoSummaryResponse` status/message vocabulary. A summary is never
fabricated: `Success` (status `ok`) is produced only on a real `ok` bridge
envelope, and conversation/contact/runId fields are left null. Messages surfaced
to the caller are secret-redacted (`SensitiveLogRedactor`) and bounded.

Changed files:

- app/src/main/java/com/clawmate/app/wechat/domain/WechatCollectionGate.kt
- app/src/main/java/com/clawmate/app/settings/ui/SettingsViewModel.kt
- app/src/main/java/com/clawmate/app/settings/ui/WechatSettingsSection.kt
- app/src/test/java/com/clawmate/app/wechat/WechatCollectionGateTest.kt
- MIGRATION_STATUS.md

## Verification

Command (focused tests, single Gradle worker):

```
./gradlew --no-daemon --max-workers=1 :app:testDebugUnitTest \
  --tests com.clawmate.app.wechat.WechatCollectionGateTest \
  --tests com.clawmate.app.task.hdc.WorkflowDeviceAdapterTest --console=plain
```

Result: BUILD SUCCESSFUL; the focused WeChat gate test suite (no-call gates,
valid single call, fixed success/error/transport/unsupported mapping,
secret-redaction/bounding and cancellation propagation) plus the existing
Phase-25A `WorkflowDeviceAdapterTest` all pass, 0 failures, 0 errors. A full
application build was intentionally not run (Phase 25 tasks: focused tests only).

The safety diff scan found no newly introduced scheduling, background work,
arbitrary-command API, or UI redesign. `wechatCollect` still sends only the fixed
`wechat_collect` action; no `run_cmd` / adb / hdc / shell is ever sent.

## Limitations

- The active/default-status UI card is updated to reflect the new `AVAILABLE`
  state and the explicit-call posture; collection itself remains a programmatic
  `collect` API (the Settings card carries no new collect button) — no UI
  redesign was added.
- Auto-summary still runs on the PC-side Python/HDC service; Android only
  surfaces a truthful status/result through the adapter and never fabricates a
  summary.
- The rich summary fields (`conversations`, `contactsCollected`, `runId`) are not
  parsed from the bridge on this slice; a `Success` carries only the bound
  `status`/`message`, leaving those nullable fields null (honest, not inferred).
- No scheduling / background polling / periodic collection was introduced.

## Merge

Merge the phase commit into the Android target only after review with `git merge --ff-only PHASE_COMMIT`.
