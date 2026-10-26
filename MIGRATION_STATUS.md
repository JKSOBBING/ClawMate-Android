# ClawMate Android 迁移状态与日志

更新：2026-10-26。迁移进度、阶段与验证统一记录于此；架构见[共享架构](docs/SHARED_ARCHITECTURE.md)，页面规范见[前端对齐](docs/FRONTEND_PARITY.md)，后续工作见[待办](MIGRATION_BACKLOG.md)。

## 当前版本

- 主前端：ArkTS/ArkUI + ArkUI-X；Android 宿主 `shared-app`，包名 `com.clawmate.shared`，版本 `0.3-integrated`。
- 根 Gradle 同时管理共享宿主、Compose 基线 `app` 与共用 Kotlin 库 `android-capabilities`；原正式包和私有数据保留，共享版不双写或自动导入旧数据。
- 鸿蒙只读参考：`doulujiyao12/mobiinfra-oh` / `clawmate_dev` / `cd69b68bf16b9ed352be5073939eb24d042de583`。135个 ArkTS 编译输入中78个不变、35个适配、22个自编；不是全产品复用率。
- 交付分支：`codex/android-migration`。功能整合提交 `53816d8`，未切换正式包名或合并 main。

## 功能覆盖

“接通”指共享版已有入口、调用和结果处理；不代表真实云服务质量或所有平台已验收。

| 范围 | 共享版实现与边界 |
| --- | --- |
| 引导、导航、主题、设置 | 原四页引导、五入口、页面/资源与私有偏好；Android适配系统栏、键盘和返回，HDS材质近似 |
| 首页、建议、陪伴 | 原提示词、来源证据、详情和缓存；`Index`连接模型协议，自由GUI/Qwen执行受限 |
| 聊天、会话、Markdown | 原会话持久化、思考/流式回复、格式渲染、复制、取消和错误；自动摘要未接入 |
| 记忆查询 F04 | `SharedAgent`→`MemoryQuery`→原索引；只读query_events，最多3次查询、有界JSON结果，不执行任意模型工具 |
| 图片聊天 F05 | 原附件面板→系统相册/相机→逐次确认→共用图片准备→多模态SSE；最多4张去元数据JPEG，仅本回合上传，历史图片保存在会话私有目录 |
| 图库、汇总、集合 F07–F09 | 原分析/检索/问答/照片引用、12类记忆、详情和分页；本地OCR共用，完整私人图库与真实视觉服务待验收 |
| 数据管理、长期记忆 F03 | `MemorySyncPage`→`DataManagerFacade`/`ChatMemorySync`→日志/索引；近7天范围确认、保存的模型名、进度/取消、检查点去重及无网络索引重建 |
| 工作流 F10 | 原图/JSON、上下文、新建/保存/复制/删除和文件查看；`AndroidWorkflow`调用共用执行器，支持等待/条件/有限循环/echo/summary、单任务/批量停止，限60秒/200步；真实结果写入run_summary.json，成功summary进入日志 |
| 语音、数字分身 | 原控件接共用系统ASR/TTS，按需申请权限；原分身生成/历史/版本和文生图协议；真人识别、实拍和实际生成效果待验收，照片参考生成仍受限 |
| 云模型与授权 | Android兼容HTTP/SSE、超时/取消/授权撤回；端点、模型和凭据本地配置，图片与聊天记忆另作范围确认 |
| 本地模型、C++/MNN | 保留配置和明确能力边界；MNN/HiAI/OM推理及模型下载未接入 |
| HDC/GUI、Python | 共用库保留受限协议；共享执行器默认阻断GUI/HDC，Python/微信自动采集等仍需外部服务适配 |

## 关键阶段

| 阶段 / 提交 | 主要结果 |
| --- | --- |
| 早期Compose基线 `7f76afb` | Android外壳、Room/DataStore、流式聊天/智能体、图库/OCR、画像/分享、月度洞察、日志和受限任务/平台接口；NFC、受限无障碍、JobScheduler等旧版专项不算共享版已接入 |
| 2026-10-23，`04c7c0a`～`e21430d` | Windows构建；聊天记忆/事件查询、Markdown复制、首页建议/陪伴、图片聊天、单图云分析、引用问答和JSON编辑；修复取消、最新配置及入库/分析文案 |
| 2026-10-24～25，UI-01～12，至`cc23078` | Compose鸿蒙页面对齐，补齐图编辑、新建保存、详情及文件预览；当时1340项单元、27项设备测试通过 |
| 2026-10-25，`9ff69dd`、`86317b3` | ArkUI-X源码/协议/工具链验证；锁定OpenHarmony API20与ArkUI-X API20，修复UTF-8分块及取消 |
| 2026-10-26，SH-01～04，`ff68bde`～`0678fdf` | 共享应用、根工程整合、OCR/TTS桥接、原导航样式、主题/返回；真机修复重复键盘避让及深色手势区域 |
| SH-05～06，`7cb292a`、`53816d8` | 抽取同一份图片/ASR/工作流引擎与测试；补齐共享图片聊天、记忆工具/同步、任务运行/停止和结果查看，修复模型名、非流式取消及本地结果来源标记 |
| SH-07～08，`e89f6e7`及后续文档提交 | 统一交付记录、历史摘要、功能审计与验证结果；删除重复日志，采用项目阶段表述，应用实现不变 |

## 最近一次功能验证（2026-10-26）

| 检查 | 实际结果 |
| --- | --- |
| 完整JVM回归 | Compose 1243 + 共用库104 = **1347项通过**，零失败/错误/跳过；77项随实现迁移 |
| 主机检查 | 设置/路由6 + 桥接7 + 源契约8 + 整合12 = **33项通过** |
| 构建 | `build.ps1`完成ArkTS/HAP、匹配运行库导出及Android APK；两版测试包构建成功 |
| OPPO PKB110 / Android16 | **共享原生6项、Compose 34项通过**；实际中文OCR、TTS开始/停止、JPEG重编码、summary、GUI阻断和取消 |
| 共享界面 | 实际任务success、30秒等待停止后cancelled、运行文件列表/原文、记忆入口/空索引提示；此前已检查引导、会话/工作流持久化、主题、键盘和返回 |

受控模型/合成图片测试不等于真实服务效果；Compose设备测试不替代共享页面验收。文档整理阶段未重跑应用构建或设备测试。
精确命令、APK及18份证据哈希见[验证报告](shared-frontend/verification-reconciliation.json)；原始日志/截图/XML保存在忽略目录 `.arkui-x-validation/application/`。

修正记录：离线依赖、ArkTS类型、Windows换行和测试运行器问题均在修复后通过；曾发现任务停止不可达、运行文件名不匹配及错误AI标记并修正。真机坐标误触默认任务时执行器返回blocked、未做外部GUI操作；已恢复验证前状态，清理2个合成任务/6份运行记录及合成日志，保留原12个任务。

## 剩余边界与追溯

真实云服务/视觉/分身、真人ASR、相机实拍、多厂商Android/iOS、TalkBack及鸿蒙同状态像素/动画仍未验收。
GUI规划、HDC/Python/微信采集、通知、MiMO、MNN/HiAI、照片参考分身、自动摘要和正式包/旧数据迁移仍有待办；动态主题下UIAutomator节点刷新边界保留。

历史全文保留在Git提交 `e89f6e7` 及其之前的版本；例如 `git show e89f6e7:docs/archive/MIGRATION_STATUS.en.md`。后续迁移日志只更新本文，其他文档维护设计、构建方法或待办。

## SH-09：本机会议 SDK 集成（2026-10-26）

分支 `codex/meeting-sdk-integration`。从 `on-device-meeting-notes` 源码引入 Android Library；
共享“汇总”新增会议入口。ArkTS 页面接入会议/音频/任务/版本、离线转写与本地纪要、模型组状态、
前台录音命令、正文编辑、回听和系统文件导入导出。Kotlin 只负责平台接口；会议后端独占会议数据，
ArkTS 在用户主动保存后独占写入 ClawMate 的记录/索引/收据。确定 export_id 文件名保证重复导入幂等，
来源版本/hash 和原交接包保留。源码依赖及复测方法见 [会议集成说明](docs/MEETING_INTEGRATION.md)。

验证记录：主机原33项及新增副本导入5项通过；Compose1243 + 共用库104项 JVM 回归通过；
`build.ps1` 实际完成 ArkTS/HAP、运行库导出及共享 APK 构建。
APK 校验确认同一包中 ArkUI-X/Python/ONNX、当前后端/网页、Kotlin桥接与服务，以及 SDK 原生库哈希一致。
公开中文音频已在 OPPO PKB110 / Android16 共享包完成真实转写。
会议专项设备测试1项通过：实际渲染页面保存副本、来源收据、重复导入去重与 Activity 销毁后 SDK
仍可使用。修复刷新后旧标题/模型/任务行复用和 SDK 复用过期 HTTP 连接；写请求不自动重试。
当前 SDK15项 JVM 测试在宿主 Kotlin1.9.24 工具链通过。
共享原生能力6项设备回归通过，包括中文OCR、JPEG处理、TTS回调、有界任务和取消。
本次共享调试 APK SHA256：`c3a43127fc5d27dc9457767bda4b94faa4047190c6c47dce1e697dd21dbdbd4e`。

本阶段只使用公开模型权重和测试音频，未复制原会议私有数据库；不改变 Compose 正式包。
长时间锁屏录音、来电/音频焦点、多厂商及16KB设备、正式签名与包体仍需专项验收。

## SH-10：共享前端维护门禁与发布边界（2026-10-26）

继续沿用共享ArkTS与Kotlin适配；本轮开发集中在构建可靠性，没有新增业务入口。
两层prepare的源码替换改为命中校验，单次替换遇到歧义即失败；重建overlay时清理旧生成文件。
新增准备输入/输出和编译运行库SHA256绑定，Android preBuild拒绝陈旧源码或运行库。
根Gradle允许Compose基线在未配置会议SDK时独立构建；共享宿主缺依赖时明确报错。
新增包体/ELF审计，包含Chaquopy嵌套Python扩展；原鸿蒙源码、正式包和私有数据未改动。

实测：原38项主机契约、9项维护检查、4项ELF审计器测试通过；两次真实prepare摘要一致，
注入的旧引导页overlay被清除。缺SDK和陈旧运行库的两次Gradle负向检查均按预期失败。
`build.ps1`实际完成ArkTS/HAP、运行库导出及共享APK构建；无会议SDK的Compose APK独立构建成功。
强制重跑JVM：Compose1243 + 共用库104 + 会议SDK15 = **1362项通过**，无失败/错误/跳过。
最终APK在OPPO PKB110 / Android16安装成功，共享原生6项与真实会议页面专项1项通过，
包括中文OCR/TTS、图片/工作流取消、公开中文音频转写、副本保存/去重及Activity销毁后SDK可用。
本次公开样例副本依测试设计保留供检查，源会议移入可恢复回收站；未启动麦克风。

当前全插件ARM64调试APK为**298.15 MiB**，SHA256：
`45d45ce522299c47a9289dbfdf08a64fd49c0c8601183573deffc8a874efdd61`。
ZIP对齐检查通过；325个ELF中324个满足静态16 KB对齐，会议SDK的
`libandroid-complex-math.so`为4 KB，发布门禁`--require-16kb`实际返回1。
107个按名称及SHA256确认的ArkUI-X原生库未发现此项失败；不能把会议依赖问题归因于ArkUI-X。
手机页大小4096，未做真实16 KB运行验证。

三次进程冷启动系统TotalTime为763/746/740 ms，文件缓存未清理；不代表首屏可交互时间。
最后一次启动约3秒后单次PSS为345229 KiB（337.14 MiB），仅作调试基线，不代表release稳态性能。
当前结论：路线适合继续开发，长期维护与正式替换仍须真实升级演练、16 KB依赖修复、包体/内存优化、
TalkBack、多厂商、真实服务及旧数据迁移验收。详见[维护评估](docs/SHARED_FRONTEND_MAINTENANCE.md)与
[精确证据](shared-frontend/verification-maintenance.json)。Android CLI skill在当前Windows环境未安装，
设备检查实际使用现有Android SDK的adb；本轮未依赖远程主机。
