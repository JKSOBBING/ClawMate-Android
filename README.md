# ClawMate Android

ClawMate 可以把手机上的资料整理成本地记录，在首页查看提醒，也可以连接自己配置的模型服务和桌面 Codex。

## 中文

### 使用入口

- **首页**可以查看提醒、开始对话和使用已连接的助手。
- **任务**提供页面采集工作流。按页面提示开启无线调试配对和无障碍服务后，即可执行相应任务。
- **悬浮窗**可以拖到屏幕两边。单击圆形图标展开提醒，点击提醒进入首页，双击圆形图标采集当前可读取的页面。开关在「我的」里。
- **汇总**用于查看采集记录和分析资料。「我的」提供模型、智能体和调试设置。

### 源码和构建

安卓宿主在 `shared-app/`，ArkUI-X 界面与平台适配在 `shared-frontend/`。安卓原生能力在 `android-capabilities/`，本地推理接口在 `mobiinfer/`，第三方源码和随附许可在 `third_party/`。

当前候选版本为 `1.1.0-rc.1`。Release 包名为 `com.clawmate.app`，Debug 包名为 `com.clawmate.shared`。仓库包含会议 SDK、Python 后端及 ARM64 运行库。装好工具链后即可从本仓库构建完整 APK，脚本会下载并校验固定版本的公共前端输入。[前端与 APK 构建](shared-frontend/README.md) · [会议 SDK 接入](docs/MEETING_INTEGRATION.md)

开发时，真机截图、采集导出、测试报告和临时配置可以放在 Git 忽略的 `local-only/`。构建工具会将生成工程和 APK 写入各自的忽略目录。


## English

ClawMate turns information from your Android phone into local records, shows reminders on the home screen, and connects to a model service or desktop Codex instance you configure.

- **Home** displays reminders and opens chat with your connected assistant.
- **Tasks** runs page collection workflows. Follow the prompts to pair wireless debugging and enable the accessibility service when required.
- **Floating icon** snaps to either screen edge. Tap the circle to expand reminders, tap a reminder to open Home, or double-tap the circle to collect a readable page. The on/off switch is under **Profile**.
- **Summary** shows collected records and analysis. **Profile** contains model, agent, and debugging settings.

The Android host lives in `shared-app/`, the ArkUI-X frontend and adapters in `shared-frontend/`, native Android capabilities in `android-capabilities/`, and local inference in `mobiinfer/`. The meeting SDK, its Python backend, and its ARM64 runtime are in `meeting/`. Other vendored sources and notices are under `third_party/`.

The release candidate is `1.1.0-rc.1`. Release builds use `com.clawmate.app` and debug builds use `com.clawmate.shared`. With the toolchain installed, the build script fetches and verifies the pinned public frontend inputs and produces a complete APK from this repository. [Build the APK](shared-frontend/README.md) · [Meeting SDK](docs/MEETING_INTEGRATION.md)

Keep device screenshots, personal exports, reports, and temporary configuration in the ignored `local-only/` directory. Generated projects and APKs also go to ignored directories.
