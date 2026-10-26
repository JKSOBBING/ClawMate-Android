# ClawMate 共享前端

当前 Android 宿主是 `../shared-app/`，包名 `com.clawmate.shared`，显示名称
“ClawMate 共享前端”。`../app/` 原 Compose 产品保留；本阶段不替换其包名，不搬动其私有数据。

## 源码与职责

以鸿蒙提交 `cd69b68bf16b9ed352be5073939eb24d042de583` 为唯一参考版本。
`app/Index.ets` 负责安卓应用组装、导航和服务连接，页面与绝大部分业务代码直接从原源码生成。
`adapters/` 只提供平台接口或明确的能力限制；`prepare.mjs` 保存少量可审查的源码适配。
没有修改鸿蒙源项目，也没有用截图或 Compose 重新描绘这些页面。

SH-03 生成工程包含 130 个 ArkTS 文件：81 个与锁定上游逐字一致、32 个经适配、17 个为新平台代码。
这是本次编译输入的统计，不是整个鸿蒙产品的复用率。

- `upstream-lock.json` 与 `../experiments/arkui-x/ui-upstream-lock.json` 锁定全部输入，准备时逐文件检查 Git blob。
- 编译输入在 `.arkui-x-validation/reference/`；生成工程在 `.arkui-x-validation/application/project/`。
- SDK、运行库、APK、截图和测试产生的数据均忽略。原端带疑似凭据的两个默认配置文件没有导入，
  由生成的空配置替代；服务地址、模型名、密钥均由用户填写。
- Kotlin 宿主提供 Android 系统栏、焦点、键盘、剪贴板、浏览器及本地 OCR/TTS 桥接，
  OCR 和 TTS 的实际引擎与旧版共用 `:android-capabilities`，不依赖某手机厂商 SDK。
  当前调试包仅包含 arm64，最低 Android API 26；已在 OPPO PKB110 / Android 16 / OPlus V16.1.0 检查。

## 功能映射

| 功能 | 共享来源 | 当前连接与边界 |
| --- | --- | --- |
| 启动、四页引导 | FirstLaunchOnboarding / OnboardingTypes | 原始页面；完成版本实际持久化，更新与重启不重复引导 |
| 主导航、主题 | MainTabConfig / FloatingMainTabs / AppTheme / FrostedPage | 原五入口及导航样式，悬浮胶囊尺寸取锁定源码；原颜色资源跟随系统主题。Android 基础模糊近似 HDS，系统栏由宿主管理 |
| 首页、提醒、推荐、关怀 | HomePage / HomeTaskSuggestion / HomeEmotionalSupport | 原页面；真实数据管理器；建议/关怀接原提示词与响应解析，结果缓存到本机；真实模型未验收 |
| 聊天、会话、消息 | ChatPage / ChatMessageList / ChatSessionDrawer / ChatSessionStorage | 流式客户端、深度思考参数、文件保存、新建/重命名/置顶/删除；原 UTF-8/取消修正沿用；没有接入自动工具执行 |
| 上下文预算 | ChatContextBudget | 使用原阈值计算；达到预算会阻止继续发送并提示新建会话，保留历史。自动摘要尚未接入，设置页明确说明保留轮数暂未生效 |
| 汇总、事件详情 | CollectionPage / CollectionGroupDetailPage | 读取真实索引，支持原详情与分页；同步调用原数据管线，空数据不伪造同步时间 |
| 图库、筛选、检索、问答 | GalleryPage / GalleryStorage / GallerySearchService / GalleryAnalyzer | 原页面与存储/云协议；系统选图可打开和取消。OCR 经 URI 桥接到共用本地引擎，失败明确返回；未上传私人照片或做真实模型分析；HDC 批量读图受限 |
| 数据管理 | DataManagerFacade / DataManagerStorage / IndexService / ConsolidationService | 完整原始数据管理代码，使用 Android 上的 ArkUI-X 文件与加密接口；未导入旧应用私有数据 |
| 任务与工作流 | TaskPage / WorkflowStorage / WorkflowFileViewer | 图编辑、JSON、场景、联系人上下文、保存、复制、删除、文件预览/清理沿用原代码；测试实际保存与复制后删除了测试记录。执行器尚未接入，开关只保存任务选择 |
| 设置与服务 | SettingsCenterPage / SettingsCenterControls | 原分组和表单；私有偏好持久化，写失败保留可重试状态；空服务显示“未配置”；用户主动测试模型时才请求配置的服务 |
| 账号与隐私 | PrivacyConsent / PrivacyConsentDialog | 安卓授权指使用自配云服务，不代表华为账号登录；声明按实际行为改写并单独版本化；拒绝/撤回会阻断请求，小艺 A2A 明确不可用 |
| 数字分身 | HomePage / AvatarService / SeedreamAvatarProvider | 原生成/历史/参数快照界面及文生图服务连接；真实生成待验收。照片参考上传、人物前景分割未验收/未接入，不伪造成功 |
| 语音、本地模型 | 原朗读控件 / 共用 Android TTS | 原消息朗读/暂停/重播连接本机 TTS，使用实际初始化和播放回调；没有伪造音频缓存。ASR、聊天拍照/图片附件、MNN/HiAI/OM 尚未接入 |
| HDC/GUI、Python、通知 | 平台接口与能力说明页 | 未执行跨应用操作或后台任务；时间可以保存，但提醒始终不显示已注册 |
| 桌面端、诊断、日志 | Android 平台页 / 原工作流查看页 | 用户自行填写 HTTPS 桌面下载地址；原生诊断受限；运行日志只展示实际内存日志 |

## 构建

复用前一验证阶段已准备的 API 20 工具链及官方空模板。
详细 SDK 下载锁见 `../experiments/arkui-x/toolchain-lock.json`，初次环境准备见该目录 README。
当前 prepare 需要忽略目录中的 `.arkui-x-validation/project` 空模板/资源，以及锁定的只读源文件；
它不是一个从网络自动安装 SDK、下载私有源仓库的安装器。

从仓库根目录运行，路径只作为本机参数，不写入应用配置：

```powershell
./shared-frontend/build.ps1 -JavaHome '<JDK 17>' -AndroidSdk '<Android SDK>' -Offline
```

流程：验证源文件哈希 → 生成共享 ArkTS 工程 → Hvigor assembleHap → 导出匹配 SDK 运行库
→ Gradle Android assembleDebug。任一步失败立即停止，不使用上一次 APK 冒充新构建。

统一构建的产物：`shared-app/build/outputs/apk/debug/shared-app-debug.apk`。
SH-01 独立工程的历史文件名为 `ClawMateShared-debug.apk`，不要用旧产物代替本轮构建。
调试包使用全量 SDK 插件定位兼容性，尚未进行发布签名、依赖裁剪、大小/性能或发布 SDK 等级验收。

## 检查与实际证据

使用 Node 24 从仓库根运行：

```powershell
node shared-frontend/verify.mjs
node shared-frontend/verify-native.mjs
node experiments/arkui-x/verify-contracts.mjs --source-root .arkui-x-validation/reference
./gradlew.bat :android-capabilities:testDebugUnitTest :app:testDebugUnitTest
```

第一条检查输入锁、九个路由、设置读写/重启语义、失败后的保存重试和上下文预算边界；
第二条检查跨运行时协议、取消及源导航样式；第三条重跑七个原始模块的八项主机契约。
Gradle 检查共用能力和 Compose 基线。主机检查不代替 ArkTS 编译或 Android 真机检查。

设备检查包括：四页引导与完成状态、五个主入口、图库路由及授权拦截、系统选图打开/取消、
授权同意/撤回、设置写入后进程重启恢复、工作流图新建/保存/复制/删除/文件预览、
未保存编辑的返回确认、文件数量实时刷新、会话新建/置顶/重命名/重启恢复/删除。
只操作独立共享包；测试任务、会话和 `.invalid` 测试下载地址已清理，授权恢复关闭。

有实际日志后才记录构建/运行成功。SH-01 的历史结果见 `verification-results.json`；
SH-03 的页面、架构和真机检查见 `verification-integration.json`。
完整日志、截图和 XML 见忽略目录 `.arkui-x-validation/application/`。

SH-04 已补齐最终真机检查：3 项原生测试通过，识别临时文字图中的中文，验证原生取消/路径限制，
并收到系统 TTS 真实开始和停止回调。另从原聊天按钮实际验证朗读、重播和暂停；测试消息已清理。
修复 Android/ArkUI 重复键盘避让，复测键盘开关、首页弹层返回和日夜间画面；系统主题已恢复。
完整图库云分析、私人照片与真实模型服务仍未验收。动态主题切换时的无障碍检查边界见页面对齐文档。

两版职责与数据归属见 `../docs/SHARED_ARCHITECTURE.md`，页面对齐边界见 `../docs/FRONTEND_PARITY.md`。

## 继续跟随上游

保持上游只读。新版本需更新锁定提交与文件哈希，重新审查平台适配、确认完整依赖闭包，
然后执行 prepare、主机检查、构建和真机回归。不要通过关闭编译检查或直接改参考输入解决漂移。
Android 厂商差异只应进入平台接口；其他系统应实现自己的接口并单独构建/验收。

这轮完成共享前端的应用组装和主要本地交互；真实云服务/图片服务、自动摘要、原生集成与旧数据迁移
仍有明确边界。当前只有一台 Android 手机的证据，不宣称小米等多品牌或 iOS 已验收。
