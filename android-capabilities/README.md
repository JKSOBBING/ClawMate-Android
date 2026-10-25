# Android capabilities

`:app`（Compose）和 `:shared-app`（ArkUI-X）共用的 Android 能力库。
依赖方向只允许应用 → 能力库，能力库不依赖 Compose、ArkUI-X、应用数据库或云配置。

已抽取旧版的真实 TTS 适配器、本地 OCR 引擎、OCR 结果类型、映射和解码边界。
为了兼容现有调用者，暂时保留原公开 Kotlin 包名；这些类的源码只存在于此模块。
Compose 的 `AndroidOcrEngine` 仅把自身照片模型转为 URI，再转接能力结果。
共享前端必须经过 Android 桥接调用，添加工程依赖本身不代表已打通功能。

根目录可运行：

```powershell
./gradlew.bat :android-capabilities:testDebugUnitTest :app:assembleDebug :shared-app:assembleDebug
```

OCR 单元测试从原模块迁入，覆盖真实/空/失败结果映射和内存解码边界，不替代真机识别。
整体职责与数据写入归属见 `../docs/SHARED_ARCHITECTURE.md`。
