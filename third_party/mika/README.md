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

## 中文

本目录以 Mika 0.1.45（versionCode 46）的 `f881d83f` 为基线。`core/src/main` 保存 Kotlin/JVM 源码和资源，Gradle 工程接入 ClawMate 的依赖版本目录。模块保留上游的 `LICENSE`、`NOTICE` 和第三方声明。[上游版本](https://github.com/wangzan101/Mika/tree/f881d83f)

### 构建与手机任务

构建使用 Kotlin 1.9.24、coroutines 1.8.1 和 serialization 1.6.3。`LocalPortScan.kt`、`AppTools.kt`、`RequestConfirmation.kt`、`DeviceCode.kt` 和 `XaiAuth.kt` 内标有兼容调整。`TaskTools.kt` 与 `RunAssembler.kt` 支持按宿主限定可用能力，默认组装沿用上游。`assembly/ClawMateCollection.kt` 提供 `ToolDispatcher.collectPages`，任务页可直接调用 `phone_collect` 并在本地采集当前页及后续页面。

`Tap.kt` 在描述屏幕后使用上游 `Locator` 定位坐标。描述若唯一匹配屏幕文字，就采用树中的位置；其他情况沿用现有视觉核对。这样可以检查目标一致性和画面的时效性。宿主把工作流目的和后续节点类型传入 `AgentLoop` 与 `Verifier`，步骤文字继续决定路线和成功条件。GUI 步骤前的单次点击须经既有屏幕验证。`TreeQuality` 对混合文字的大叶节点请求设备 OCR。`VisibleText` 保留这些区域内文字的位置，同时排除独立定位和敏感文字。`Locator` 将唯一匹配的 OCR 文字绑定到当前帧哈希和有时效的视觉引用。`ScreenJson` 及差异包含选中状态。跳转前的点击回执记录控件名或实际观察到的文字。

本地配对的显示助手通过 `AdbConnection.openWireless` 连接回环地址，并在接受 ADB 连接前要求 TLS。

### 同步与宿主适配

- 2026-11-17 从 `eb10b34` 更新至 `9fd0be4`，涉及 `core/src/main` 下 18 个文件，资源保持一致。上游更新保留了五项兼容调整和能力限制。
- 从 `9fd0be4` 更新至 `f881d83f`，整合 10 个上游提交，涉及 13 个主源码文件（增加 313 行、删除 45 行）。新增 `AppPacePlan.kt`（快、标准、拟人节奏）和 `SkillFollow.kt`（仅在任务使用技能后记录结果）。更新涉及 `TaskTools.kt`、`PhoneCapability.kt`、`AppDwell.kt`、`AppPacing.kt`、`OperatingPace.kt`、`ChildRuns.kt`、`SpawnAgent.kt`、`GuideCatalog.kt`、`RunContracts.kt` 和 `SkillDrafter.kt`，包括节奏上限和并行任务批次。冲突仅涉及 `TaskTools.kt` 的 `allowedToolNames`，已重新应用。当时与上游的核对结果为 7 个本地适配文件（增加 96 行、删除 27 行）。
- 2026-11-18，`platform/mika/memory` 移植了 `EncryptedMemoryFile`、`TaskMemoryFile`、`KeyCipher` 和 `MemorySettings`，调整了包名。云端文字对话通过受限原生会话调用原记忆工具，话语来自用户输入。手机任务直接使用 `AgentLoop.memory`、`UserUtterances` 和任务记忆工具。`MemoryExtractor` 先暂存再发布，管理设置变化后拒绝过期写入。此次移植范围留待后续的部分包括画像与个人技能学习、跨轮确认引用和原版逐结果记忆卡。
- 安卓宿主的 `MikaRouteStore` 将 `FileRouteStore` 接到按目录管理的单例。`MikaRouteLearning` 在结果交付后连接 `RouteRecorder`、`OutcomeJudge` 和 `LearnGate`。`MikaPhoneRun` 使用上游知识、`RouteMatcher` 和 `IdleScroll`。`MikaFieldText` 和输入路径沿用 `ScreenReader.fieldText` / `typeAndVerify`，并适配指定显示屏的事件与条件等待。当时另列的范围包括剪贴板与不可见编辑器回退、多应用所有权、接管、应用副本和滚轮。
- `FileProfileBackend`、`PersonalSkillFiles` 和 `RunLearning` 来自同一固定安卓版本，调整了包名、导入及中文界面资源。画像和技能工具、起草及确认沿用上游算法。`MikaConversationHistory` 转换宿主保存的对话，`MikaMemoryRuntime` 提供 Android 存储、生命周期与后台学习。技能文本保存在应用私有存储，画像通过现有 Android Keystore 加密。
- 屏幕任务启用上游 `AnswerReview` 和文字 `Verifier`。当屏幕上出现所报数值时，宿主还核对数值与对象的对应关系。`CapsulePreview.kt` 来自固定的上游 Android 实现。
