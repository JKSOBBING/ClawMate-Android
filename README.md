# ClawMate Android

ClawMate Android 是 [ClawMate HarmonyOS](https://github.com/doulujiyao12/mobiinfra-oh)
的 Android 迁移版本。HarmonyOS 工程位于 `/home/yydh/mobiinfra-oh`，仅作为只读的
产品、数据契约和交互参考；Android 实现只修改本仓库。

项目目标不是复刻截图，而是在 Android 平台保留真实的数据流、用户入口和能力边界。
不支持或未配置的能力会显示 `NOT_CONFIGURED`、`DISABLED` 或 `BLOCKED`，不会用假数据
冒充成功。

## 当前入口

应用使用 Compose 五 Tab 外壳：

| Tab | 当前内容 |
| --- | --- |
| 首页 | 首次启动引导、头像/资料、资料完整度、推荐和本地状态 |
| 汇总 | 图库导入、图库分析、集合、月度洞察、照片详情和本地 OCR |
| 聊天 | 会话持久化、云端流式回复、附件、工具状态、语音输入输出和错误状态 |
| 任务 | Workflow 浏览、场景分组、启停状态、批量执行和结果状态 |
| 设置 | 模型与智能体、隐私、数据采集、任务自动化、存储、日志和可选集成 |

## 能力边界

已接入 Android 的能力：

- Room：图库、集合、聊天会话和任务状态持久化。
- DataStore：设置、首次启动状态和本地资料持久化。
- OpenAI-compatible HTTP/SSE：云端聊天和部分图库云端能力。
- Android Photo Picker、MediaStore、EXIF、Geocoder 和 ML Kit 中文 OCR。
- Android SpeechRecognizer 和 TextToSpeech，权限和不可用状态会显式呈现。
- Workflow JSON 解析、状态机、取消、超时和受控执行器。
- JNI/CMake 本地模型适配层。当前只提供诚实的能力边界，未内置 MNN 推理运行时。

当前不会伪装成已支持的能力：

- HarmonyOS HiAI/N-API 和 MNN 本地推理。
- PC 侧 HDC、GUI 自动化和 Python 服务，除非显式配置并通过健康检查。
- 跨应用无障碍操作、任意 shell/adb/hdc 命令执行。
- 鸿蒙专属的数字人生成、云端 MiMO 语音和部分 WeChat 自动采集流程。

详细的源代码映射、阶段记录和剩余差异见 [MIGRATION_STATUS.md](MIGRATION_STATUS.md)。

## 构建环境

| 工具 | 版本或路径 |
| --- | --- |
| Java | OpenJDK 17 |
| Android SDK | `/home/yydh/Android/Sdk`，platform 34 |
| Android NDK | `25.2.9519653` |
| CMake | `3.22.1` |
| Gradle | Wrapper，Gradle 8.9 |
| Gradle 缓存 | `/home/yydh/.gradle` |

首次构建前确认 Android SDK、NDK 和 CMake 已安装。Gradle wrapper/daemon 和缓存会写入
仓库外目录，必要时使用有权限的 shell 执行构建。

## 构建与测试

在仓库根目录执行：

```bash
GRADLE_USER_HOME=/home/yydh/.gradle \
ANDROID_HOME=/home/yydh/Android/Sdk \
ANDROID_SDK_ROOT=/home/yydh/Android/Sdk \
ANDROID_SDK_HOME=/tmp/clawmate-android-home \
./gradlew :app:assembleDebug --console=plain
```

运行 JVM 单元测试：

```bash
GRADLE_USER_HOME=/home/yydh/.gradle \
ANDROID_HOME=/home/yydh/Android/Sdk \
ANDROID_SDK_ROOT=/home/yydh/Android/Sdk \
ANDROID_SDK_HOME=/tmp/clawmate-android-home \
./gradlew :app:testDebugUnitTest --console=plain
```

Debug APK 输出位置：

```text
app/build/outputs/apk/debug/app-debug.apk
```

连接 Android 设备后安装：

```bash
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell monkey -p com.clawmate.app 1
```

真机检查可以使用：

```bash
android screen capture -o /tmp/clawmate-screen.png
android layout -o /tmp/clawmate-layout.json
adb logcat
```

安装、截图和运行检查必须以实际命令输出为准，不要仅根据构建成功推断运行时正常。

## 云端模型配置

应用默认不携带 endpoint、API key 或本地数据库。配置模板在：

```text
app/src/main/res/raw/config_example.properties
```

本地调试时复制为被 Git 忽略的文件并填写实际值：

```bash
cp app/src/main/res/raw/config_example.properties \
   app/src/main/res/raw/local_config.properties
```

至少需要配置：

```properties
cloudPlannerBaseUrl=
cloudPlannerApiKey=
cloudPlannerModel=
```

API key、token、数据库、签名文件和设备状态禁止提交。没有配置云端模型时，聊天页会显示
未配置状态，不会发送请求或生成假的回复。也可以直接在应用的“设置中心 > 模型与智能体”
中编辑配置；敏感字段会掩码显示。

## Android 主题约定

共享视觉 token 位于：

```text
app/src/main/java/com/clawmate/app/ui/theme/DesignTokens.kt
```

主要页面统一使用浅色背景、白色卡片和蓝紫色强调色。设置页与 HarmonyOS 源实现保持以下
关键尺寸：

- 页面背景：`#F4F8FF`
- 设置主操作色：`#4F68FF`
- 紫色强调色：`#765BFF`
- 主文字：`#11182F`
- 卡片圆角：`18dp`
- 控件圆角：`12dp`
- 设置输入框高度：`42dp`
- 设置按钮高度：`40dp`

新增页面应优先复用 `ClawMateDesign` 和现有 Compose 组件，不要在页面中重新定义一套
颜色、圆角或间距。

## 目录结构

```text
app/src/main/java/com/clawmate/app/
├── chat/          会话、流式客户端、Agent、语音
├── gallery/       图库、集合、OCR、月度洞察、云端适配器
├── home/          首页、推荐、引导和资料
├── localmodel/    本地模型扫描与 JNI 能力边界
├── profile/       资料、头像和分享
├── settings/      设置仓库、配置和设置页面
├── task/          Workflow、HDC 适配器和执行器
├── log/           应用内日志查看器
└── ui/            Tab 外壳、公共页头、主题和导航
```

## 开发约定

- 使用 Kotlin 和 Jetpack Compose；新页面优先复用现有主题与组件。
- 网络请求必须有超时、取消、错误映射和显式配置入口。
- 外部输入、模型输出和导入数据都按不可信数据处理。
- 每个有意义的阶段保持小提交，并在 `MIGRATION_STATUS.md` 增加简短记录。
- 不修改 `/home/yydh/mobiinfra-oh`，不提交生成目录、IDE 状态或本地凭据。

## 许可证与上游参考

本仓库是 ClawMate Android 迁移目标。HarmonyOS 上游项目仅用于参考：

<https://github.com/doulujiyao12/mobiinfra-oh>
