# Android 共用能力

`:app`（Compose）和 `:shared-app`（ArkUI-X）共用的实际 Kotlin 实现。
依赖方向为宿主→能力库；不依赖 Compose、ArkUI-X、任一宿主的 Room/DataStore 或云配置。

- 本地 ML Kit 中文 OCR、受限解码、路径验证与后台任务取消。
- 系统 TTS、ASR 和转录合并规则，包含真实回调及权限/不可用状态。
- 图片尺寸/方向修正、去元数据 JPEG 和私有图片文件约束。
- 工作流模型、JSON解析/校验、有界等待/条件/循环/echo/summary 执行器及受限 HDC协议。
- 原纯日志模型/可选文件写入接口。共享宿主不向执行器提供文件写入目录，只接收结果，
  由 ArkTS 提交自己的运行记录和成功 summary；旧宿主保留原文件写入方式。

为兼容现有调用者保留公开包名；源码只存在此库，不表示依赖 `:app`。
原77项相关测试随实现迁入，当前库104项；另有依赖 app 资源/领域的测试保留在 app。

```powershell
./gradlew.bat :android-capabilities:testDebugUnitTest :app:testDebugUnitTest :shared-app:assembleDebug
```

单元测试不能替代真机识别、录音或执行证据。完整职责和数据归属见
[共享架构](../docs/SHARED_ARCHITECTURE.md)，实际命令和结果见迁移日志。
