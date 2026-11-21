# 会议 SDK 接入

「汇总」里的录音分析通过 Android 会议 SDK 完成转写、说话人分段和纪要整理。用户可以导入本地录音，也可以授权麦克风进行录制。

完整 APK 构建依赖单独分发的会议 SDK。向 SDK 维护者取得包含 `android/meeting-sdk/build.gradle.kts` 或 `build.gradle` 的源码目录，以及该版本对应的 Python 运行时依赖。当前适配的 SDK 基线提交是 `93b58adc901268a7eed052ce6b849e9be870060f`。

Windows 构建脚本通过 `-MeetingSdkRoot` 接收 SDK 根目录。直接使用 Gradle 时可设置环境变量 `MEETING_SDK_ROOT` 或传入 `-PmeetingSdkRoot=<目录>`。Gradle 会引用 `:meeting-sdk` 并从固定源码构建其原生数学库。运行时模型由用户在设备上准备。

SDK 准备好后，按[应用构建步骤](../shared-frontend/README.md)生成 APK。
