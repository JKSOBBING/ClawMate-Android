# 构建 Android 应用 · Build the Android app

## 构建环境 · Prerequisites

安装 Git、Python 3、JDK 17、Android SDK Platform 36、Build Tools 36.0.0、NDK 25.2.9519653 和 CMake 3.22.1。ArkTS 需要 Node 20.19.5、OpenHarmony 6.0.0.47、ArkUI-X 6.0.0.103、Hvigor 6.22.7 和 ArkUI-X 构建插件 4.22.7。两个 SDK 根目录均需包含 `20/`，Hvigor 工具根目录需包含 `node_modules/@ohos/`。[工具链清单](../experiments/arkui-x/toolchain-lock.json)

Install Git, Python 3, JDK 17, Android SDK Platform 36, Build Tools 36.0.0, NDK 25.2.9519653 and CMake 3.22.1. ArkTS needs Node 20.19.5, OpenHarmony 6.0.0.47, ArkUI-X 6.0.0.103, Hvigor 6.22.7 and the ArkUI-X build plugin 4.22.7. Each SDK root contains `20/`, and the Hvigor tools root contains `node_modules/@ohos/`. [Toolchain lock](../experiments/arkui-x/toolchain-lock.json)

## 单仓构建 · Build from one checkout

会议 SDK、Python 后端和 ARM64 运行库随源码提供。首次构建联网获取锁定的公共前端输入、Gradle 依赖和 MobiInfer 源码，并核对前端 Git blob 和 MobiInfer 压缩包的哈希。模型权重在设备上按需准备。[会议 SDK](../docs/MEETING_INTEGRATION.md)

The checkout contains the meeting SDK, Python backend and ARM64 runtime. The first build downloads pinned public frontend inputs, Gradle dependencies and MobiInfer source. It verifies the frontend Git blob IDs and the MobiInfer archive hash. Models are provisioned separately on the device. [Meeting SDK](../docs/MEETING_INTEGRATION.md)

```sh
git clone https://github.com/JKSOBBING/clawmate-android.git
cd clawmate-android
```

### Windows

在 PowerShell 中将占位符替换为本机工具路径。脚本编译前端、构建完整 APK，并检查原生库与 ZIP 的 16 KB 对齐。

Replace the placeholders with your tool paths in PowerShell. The script compiles the frontend, builds the complete APK and checks native libraries and ZIP alignment for 16 KB pages.

```powershell
./shared-frontend/build.ps1 -JavaHome '<JDK 17>' -AndroidSdk '<Android SDK>' `
  -ArkuiXSdk '<ArkUI-X SDK>' -OpenHarmonySdk '<OpenHarmony SDK>' `
  -BuildTools '<Hvigor tools>' -BuildNode '<Node 20/node.exe>' -BuildMode release
```

### Linux

在 Bash 中设置以下环境变量后运行命令。In Bash, set `JAVA_HOME`, `ANDROID_HOME`, `ARKUIX_SDK_HOME`, `OpenHarmony_HOME` and `ARKUI_BUILD_TOOLS`, then run the commands below.

```sh
scripts/arkui-env.sh --check
scripts/build-shared.sh --node '<path to Node 20>' --release
```

## 产物与测试 · Outputs and tests

| 用途 / Purpose | 命令或路径 / Command or path |
| --- | --- |
| Release APK（未签名 / unsigned） | `shared-app/build/outputs/apk/release/shared-app-release-unsigned.apk` |
| Debug APK | `shared-app/build/outputs/apk/debug/shared-app-debug.apk` |
| Android 与会议单测 / Android and meeting tests | `./gradlew :android-capabilities:testDebugUnitTest :meeting-sdk:testDebugUnitTest` |
| 前端检查 / Frontend checks (Node 24) | `node shared-frontend/verify-bootstrap.mjs` |

开发调试时，Windows 选择 `-BuildMode debug`，Linux 省略 `--release`。默认会议模块在 `meeting/android/meeting-sdk/`。维护者已有上游源码时，可运行 `node scripts/export-frontend-inputs.mjs <上游仓库>`，或给构建脚本传入已校验的输入目录。

For a development build, use `-BuildMode debug` on Windows or omit `--release` on Linux. The bundled module is in `meeting/android/meeting-sdk/`. Maintainers can run `node scripts/export-frontend-inputs.mjs <upstream checkout>` or supply a directory of verified frontend inputs to the build script.
