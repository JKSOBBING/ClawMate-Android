# 构建 Android 应用

`shared-app` 将 ArkUI-X 界面、安卓原生能力和会议 SDK 打包为 ARM64 APK。

## 准备构建环境

- 安装 Git、Python 3、JDK 17、Android SDK Platform 36、Build Tools 36.0.0、NDK 25.2.9519653 和 CMake 3.22.1。
- ArkTS 编译使用 Node 20.19.5、OpenHarmony 6.0.0.47、ArkUI-X 6.0.0.103、Hvigor 6.22.7 和 ArkUI-X 构建插件 4.22.7。SDK 根目录应包含 `20/`，Hvigor 安装根目录应包含 `node_modules/@ohos/`。下载地址和版本见[工具链清单](../experiments/arkui-x/toolchain-lock.json)。
- 准备[会议 SDK](../docs/MEETING_INTEGRATION.md)。首次构建还会获取 Gradle 依赖和由 SHA-256 固定的 MobiInfer 源码。

## 准备界面源码

在本仓库根目录运行以下命令。导出脚本按清单为每个文件读取指定的 Git 提交，并核对源码和资源的 blob 哈希。已克隆上游时，可直接把路径传给脚本。

```sh
git clone --no-checkout https://github.com/doulujiyao12/mobiinfra-oh.git ../mobiinfra-oh
node scripts/export-frontend-inputs.mjs ../mobiinfra-oh
```

## Windows 构建

将占位路径替换为本机路径。从仓库根目录运行以下 PowerShell 命令。

```powershell
./shared-frontend/build.ps1 -JavaHome '<JDK 17>' -AndroidSdk '<Android SDK>' `
  -MeetingSdkRoot '<会议 SDK 源码根目录>' -SourceRoot '.arkui-x-validation/upstream-inputs' `
  -ArkuiXSdk '<ArkUI-X SDK 根目录>' -OpenHarmonySdk '<OpenHarmony SDK 根目录>' `
  -BuildTools '<Hvigor 安装根目录>' -BuildNode '<Node 20 的 node.exe>' -BuildMode release
```

脚本依次编译 ArkTS、打包运行库和 APK，并检查原生库及 ZIP 的 16 KB 对齐。生成的未签名 APK 位于 `shared-app/build/outputs/apk/release/shared-app-release-unsigned.apk`。开发调试可改用 `-BuildMode debug`，安装包位于 `shared-app/build/outputs/apk/debug/shared-app-debug.apk`。

## Linux 构建入口

在 Bash 中运行 `scripts/arkui-env.sh --check` 检查环境，并设置 `MEETING_SDK_ROOT`。从仓库根目录运行 `scripts/build-shared.sh --upstream .arkui-x-validation/upstream-inputs --node "<Node 20 路径>" --release`。

前端主机回归使用 Node 24，例如 `node shared-frontend/verify-bootstrap.mjs`。安卓能力单元测试可用 `./gradlew :android-capabilities:testDebugUnitTest` 运行。
