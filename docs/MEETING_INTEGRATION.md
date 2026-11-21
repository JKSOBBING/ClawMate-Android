# 会议 SDK · Meeting SDK

## 中文

「汇总」里的录音分析使用会议 SDK 做转写、说话人分段和纪要整理。模型权重由用户在设备上准备。APK 的源码构建已包含 SDK 的 Kotlin 服务、Python 后端以及 ARM64 Python、语音和推理运行库。

- `meeting/android/meeting-sdk/src/main/java/` 提供录音和会议服务、客户端和协议。
- `src/main/python/`（模块内）保存后端及网页资源。Gradle 调用 `meeting/package_backend.py` 将它们和宿主的 `clawmate_engine.py` 打包为 `app.imy`，并生成 Chaquopy 使用的资源摘要。
- 模块的 `libs/`、`src/main/assets/chaquopy/` 和 `src/main/jniLibs/arm64-v8a/` 保存对应运行库。原生复数数学库从 `third_party/android-complex-math/` 重建。原生库的许可和来源在模块的 `src/main/assets/notices/meeting/`，Python 依赖的许可保留在各发行包内。LAME 4.0、mpg123 1.33.7 和 libsndfile 1.2.2 的对应源码、校验值与 Termux 构建配方在 [codec-sources](../meeting/codec-sources/)。
- 会议模块基线为 `93b58adc901268a7eed052ce6b849e9be870060f`。维护者可以用 `-PmeetingSdkRoot=<root>` 或 `MEETING_SDK_ROOT` 测试兼容模块，普通构建直接使用仓库中的版本。

从仓库根目录运行 `./gradlew :meeting-sdk:testDebugUnitTest` 可验证 SDK 测试。完整构建还检查 APK 中的 Python 后端、运行库摘要、ABI 及 16 KB 对齐。[完整 APK 构建](../shared-frontend/README.md)

## English

Recording analysis under **Summary** uses the meeting SDK for transcription, speaker segmentation and meeting notes. Models are provisioned on the device. This checkout supplies the Kotlin services, Python backend, and ARM64 Python, speech and inference runtimes needed to compile the APK.

- `meeting/android/meeting-sdk/src/main/java/` contains the recording and meeting services, client and protocol.
- The module’s `src/main/python/` contains the backend and web resources. Gradle invokes `meeting/package_backend.py` to package them with the host’s `clawmate_engine.py` as `app.imy` and generate Chaquopy’s asset digests.
- The module’s `libs/`, `src/main/assets/chaquopy/` and `src/main/jniLibs/arm64-v8a/` contain runtime dependencies. The complex math library is rebuilt from `third_party/android-complex-math/`. Native notices and source references are under `src/main/assets/notices/meeting/`. Python distribution notices remain inside their packages. The corresponding LAME 4.0, mpg123 1.33.7 and libsndfile 1.2.2 source archives, checksums and Termux recipes are in [codec-sources](../meeting/codec-sources/).
- The SDK baseline is `93b58adc901268a7eed052ce6b849e9be870060f`. Maintainers may override the bundled SDK using `-PmeetingSdkRoot=<root>` or `MEETING_SDK_ROOT`. A normal build uses the copy in this checkout.

Run `./gradlew :meeting-sdk:testDebugUnitTest` at the repository root to run SDK tests. A full build also checks the packaged Python backend, runtime asset digests, ABI and 16 KB alignment. [Build the complete APK](../shared-frontend/README.md)
