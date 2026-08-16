# Phase 25A result: typed WeChat collection bridge action

## Scope

DSH added a typed wechatCollect operation to the Android workflow device adapter. The operation sends only the fixed wechat_collect action through the existing HDC bridge. It maps WechatAutoSummaryRequest to the confirmed snake_case Harmony and Python bridge payload and does not expose shell, adb, hdc, run_cmd, or arbitrary command execution.

Changed files:

- app/src/main/java/com/clawmate/app/task/hdc/WorkflowDeviceAdapter.kt
- app/src/main/java/com/clawmate/app/task/hdc/BridgeBackedWorkflowDeviceAdapter.kt
- app/src/test/java/com/clawmate/app/task/hdc/WorkflowDeviceAdapterTest.kt

## Verification

Command: gradle --no-daemon --max-workers=1 :app:testDebugUnitTest --tests com.clawmate.app.task.hdc.WorkflowDeviceAdapterTest --console=plain

Result: BUILD SUCCESSFUL; 16 tests, 0 failures, 0 errors.

The safety diff scan found no newly introduced process execution or arbitrary command path. Existing mentions of adb, hdc, and run_cmd are defensive documentation and tests only.

## Limitations

This phase only adds the adapter action and tests. WechatCollectionGate and the Android UI/user-triggered collection flow remain to be wired in the next phase. No full application build was required for this slice.

## Merge

Merge the phase commit into the Android target only after review with git merge --ff-only PHASE_COMMIT.
