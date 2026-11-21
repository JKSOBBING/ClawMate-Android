# Mika core

Source baseline: [wangzan101/Mika at f881d83f](https://github.com/wangzan101/Mika/tree/f881d83f) (0.1.45 / versionCode 46).
Upstream `main` is tracked as-is: the vendored tree is the upstream tree at that commit plus the
ClawMate adaptation files listed below.

`core/src/main` is the pinned Kotlin/JVM module source and resources. The Gradle
project file is adapted to the ClawMate dependency catalog. `LICENSE`, `NOTICE`
and the upstream third-party notices are included in the module resources.

The ClawMate build uses Kotlin 1.9.24, coroutines 1.8.1 and serialization 1.6.3.
Compatibility edits are marked in the source of `LocalPortScan.kt`,
`AppTools.kt`, `RequestConfirmation.kt`, `DeviceCode.kt` and `XaiAuth.kt`.
ClawMate also adds an optional per-host capability allowlist in
`TaskTools.kt` and `RunAssembler.kt`. The default retains the upstream assembly.
`assembly/ClawMateCollection.kt` adds `ToolDispatcher.collectPages`, which runs
the upstream `phone_collect` tool without a model so the task page can collect
the current screen and the following pages locally.

`uistate/tools/Tap.kt` routes coordinate taps following `describe` through the
upstream `Locator` before dispatch. A unique exact or quoted on-screen label
uses its tree match; other descriptions use the existing visual corroboration.
This host adaptation prevents free-form description coordinates from bypassing
the same target and stale-frame checks used by `find_visually`.

The Android workflow host supplies the bounded workflow purpose and next sibling type to the model context
in `AgentLoop` and the verifier request in `Verifier`. The step text still keys
routes and success criteria. For explicit tap-only navigation preceding a separate GUI
step, the existing screen verifier checks the destination before another action
can take the workflow past it. `TreeQuality` requests the existing on-device OCR
for large leaves with merged text. `VisibleText` keeps the positions of captions
inside those regions while still excluding independently located and secret text.
`Locator` can register a unique matching OCR caption against the current frame hash
using the same expiring visual references as other image-based taps. `ScreenJson`
also includes selected states in its flat control list and subsequent diffs.
The tap receipt retains the resolved control name or observed visual caption from
before navigation, so verification can still identify the control after the new
page replaces the previous snapshot.

The Android host uses `AdbConnection.openWireless` for its locally paired display helper. This entry connects to loopback and requires TLS before it accepts the ADB connection.

Upgrade history:

Memory host ports (2026-11-18): `platform/mika/memory` copies upstream
`EncryptedMemoryFile`, `TaskMemoryFile`, `KeyCipher` and `MemorySettings`, with
package-only adaptations. Shared cloud text chat invokes the original memory
tools through a bounded native session whose utterances come from the user input.
Phone runs use `AgentLoop.memory`, `UserUtterances` and task-memory tools directly.
Extraction reuses `MemoryExtractor`, stages changes before publication and rejects
stale writes after management changes. Profile/personal-skill learning, cross-turn
confirmation references and original per-result memory cards remain separate gaps.

Android host ports (2026-11-18): `MikaRouteStore` copies upstream `FileRouteStore`
with a directory-based singleton instead of an Application interface. `MikaRouteLearning`
connects `RouteRecorder`, `OutcomeJudge` and `LearnGate` after result delivery.
`MikaPhoneRun` now supplies upstream knowledge, `RouteMatcher` and `IdleScroll`.
`MikaFieldText` and the input path follow `ScreenReader.fieldText` / `typeAndVerify`;
the Android port supplies display-scoped events and condition waits. These are host
adaptations, not replacements for the vendored execution loop. Clipboard/unseen-editor
fallback, multi-app ownership, takeover, app-copy and wheel support are separate gaps.

- `eb10b34` → `9fd0be4` (2026-11-17): 18 files under `core/src/main`, resources
  unchanged. The five compatibility edits and the allowlist above were not
  touched upstream, so they carry over unchanged. See `docs/MIKA_UPSTREAM_DIFF.md`.
- `9fd0be4` → `f881d83f` (2026-11-17): 10 upstream commits, 13 files under
  `core/src/main` (+313/−45), resources unchanged. New files `cap/phone/AppPacePlan.kt`,
  `cap/phone/AppTempo.kt` (per-app operating pace: 快/标准/像人) and
  `skills/SkillFollow.kt` (record a skill outcome only when this run actually followed it).
  Modified: `TaskTools.kt` (appPace → appTempo + appCapped), `PhoneCapability.kt`,
  `AppDwell.kt`, `AppPacing.kt`, `OperatingPace.kt`, `ChildRuns.kt`, `SpawnAgent.kt`
  (parallel `tasks` batch for sub-agents), `GuideCatalog.kt`, `RunContracts.kt`, `SkillDrafter.kt`.
  Only `TaskTools.kt` overlapped a ClawMate edit (the `allowedToolNames` parameter); it was
  re-applied on top of the upstream file. The other six local adaptation files were untouched
  upstream and carried over unchanged. Verified afterwards: the vendored tree differs from
  upstream `f881d83f` in exactly those seven files (+96/−27).

Profile/skill host ports (2026-11-18): `FileProfileBackend`,
`PersonalSkillFiles` and `RunLearning` are copied from the same pinned Android
app source. Adaptations are package/import names and Chinese UI resource strings.
The core profile, skill tools, drafting, outcome and confirmation algorithms are
unchanged. `MikaConversationHistory` adapts this host’s saved chat pairs;
`MikaMemoryRuntime` supplies Android storage, lifecycle and background learning.
Skills retain upstream plaintext files in app-private storage; profile entries
use the existing Android Keystore cipher.

ClawMate opts screen tasks into the upstream AnswerReview and text Verifier.
The host option in AgentLoop/AnswerReview also checks the association between a
reported value and its subject when the literal text is present on the screen.
CapsulePreview.kt is adapted from the pinned Android capsule implementation.
