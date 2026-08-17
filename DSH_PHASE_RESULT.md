# Phase 26 result: explicit live agent status overlay

DSH added a real status-only overlay renderer fed by the existing ChatViewModel agent lifecycle. The renderer is enabled only after explicit user opt-in and observed SYSTEM_ALERT_WINDOW permission.

Implemented:
- bounded and control-character-sanitized OverlayStateRegistry status (160 chars)
- Chat running, tool, completed and error state publication
- explicit Settings start and stop action, never automatic on launch or recomposition
- status-only TYPE_APPLICATION_OVERLAY surface refreshed while service is alive
- FLAG_NOT_TOUCHABLE; no screenshots, edge-flow shader, GUI actions, shell, adb, hdc or commands

Focused verification: BUILD SUCCESSFUL; 37 tests, 0 failures, 0 errors.
Tests: OverlayGateTest 14, OverlayServiceStartPolicyTest 8, OverlayStateRegistryAgentStatusTest 10, SettingsOverlayServiceControlTest 5.

Known limitations: Harmony edge-flow shader and agent GUI controls remain unsupported. The overlay only displays bounded coarse status and selected tool name.

Target merge: git merge --ff-only PHASE_COMMIT.
