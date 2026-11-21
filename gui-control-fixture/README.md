# GUI control device fixture

Disposable, test-only Android APK with a native counter/scroll surface and a
platform `Instrumentation` actor. It has no host app dependency, network
permission, credentials, or private data. Uninstall after device verification.

The actor instruments itself so it cannot restart the real accessibility
service's host process. It opens the host's native accessibility settings entry,
chooses this exact fixture package, and checks real window capture, screenshot
coordinate mapping, confirmed taps, refused/confirmed swipes, and leaving the
allowed app. It never selects another application or exports screenshots.

Build using the same project Android SDK/JDK setup:

```text
gradlew :gui-control-fixture:assembleDebug
adb install -r -t gui-control-fixture/build/outputs/apk/debug/gui-control-fixture-debug.apk
```

The live scenario requires explicit human authorization before temporarily
enabling ClawMate's accessibility service. Snapshot and restore the original
permission configuration in a `finally` block, including on failure/timeout.
Run with the service already authorized:

```text
adb shell am instrument -w -r -e guiControlGranted true com.clawmate.gui.fixture/com.clawmate.shared.GuiControlExternalRunner
```

Success is `scenarioPassed=true` plus the `guiControlFixture` status marker. This
is one SDK scenario, not a JUnit result. The actor itself does not change system
permissions. Normal migration regression excludes this explicit opt-in scenario.

To verify collection on a test installation with empty collection history, add
`-e guiCollectionFixture true` before the instrumentation component. The actor
checks that cancellation leaves history empty, a confirmed save includes visible
fixture text, editable and offscreen values are omitted, and deleting the saved
record leaves history empty. Success emits `scenarioPassed=true` and the
`guiCollectionFixture` status marker.

If the device vendor presents an installation CAPTCHA, the human must complete
it on the phone. The actor and build scripts do not solve or bypass that gate.
Vendor accessibility safety prompts may also require physical confirmation on
the phone after the separate temporary-permission authorization. Complete those
before the live scenario; the actor does not confirm OEM safety dialogs. Keep
the original grant snapshot until the scenario ends, and restore it even when a
prompt blocks the test. A service connection alone is not a gesture test pass.

## 中文

这是用于真机测试的独立 Android APK。它提供原生计数器、可滚动页面和运行在自身进程中的 `Instrumentation`，不依赖宿主应用和网络权限。测试结束后可以卸载。

测试从宿主的无障碍设置进入，选择测试应用，检查真实窗口截图、坐标映射、确认后的点击、拒绝或确认滑动，以及切出目标应用后的处理。构建和安装命令如下。

```sh
./gradlew :gui-control-fixture:assembleDebug
adb install -r -t gui-control-fixture/build/outputs/apk/debug/gui-control-fixture-debug.apk
```

仅在用户明确授权的实验机上启用 ClawMate 无障碍服务。临时授权前记录原始配置，在 `finally` 中恢复，超时和失败时也要恢复。服务已经授权后运行以下命令。

```sh
adb shell am instrument -w -r -e guiControlGranted true com.clawmate.gui.fixture/com.clawmate.shared.GuiControlExternalRunner
```

通过时会同时输出 `scenarioPassed=true` 和 `guiControlFixture`。这是单独运行的设备场景。要验证采集，请使用采集历史为空的测试安装，在组件名前加入 `-e guiCollectionFixture true`。测试逐项检查取消后历史为空、确认后能保存可见文字、编辑框和屏幕外的值会被排除，以及删除记录后历史恢复为空。通过标记为 `scenarioPassed=true` 和 `guiCollectionFixture`。

设备厂商的安装验证码和无障碍确认需要在手机上手动完成。保留原始授权快照直至场景结束，并以真实手势检查及结果标记判定通过。
