# 鸿蒙 → Android 迁移待办清单

鸿蒙参考仓库：`doulujiyao12/mobiinfra-oh`，分支 `clawmate_dev`，提交
`cd69b68bf16b9ed352be5073939eb24d042de583`，源码只读。
Android 基线：`7f76afb`；迁移分支：`codex/android-migration`。
Android 仓库已包含大量现有实现；下表记录代码对比发现的差异及后续范围，并不表示所有功能都从零开始。
详细证据、提交及实际验证结果见[中文更新日志](MIGRATION_STATUS.md)。

| 编号 | 功能差异 / 下一步 | 鸿蒙参考代码 | 当前状态 |
|---|---|---|---|
| F01 | 可执行的首页任务建议 | `pages/home/HomePage.ets`、`HomeTaskSuggestion.ets` | 已完成生成、证据/详情、可编辑复制及受支持工作流导航；自由 GUI/Qwen 执行仍待 P01/P06 适配 |
| F02 | 情绪陪伴入口与提示词 | `pages/home/HomePage.ets`、`HomeEmotionalSupport.ets`、`HomeComfortChatStore.ets` | 已完成主动闲聊、有证据的话题及本应用聊天适配；单元测试/构建及 4 项设备测试通过；真实模型质量和采集来源扩展未验证 |
| F03 | 聊天 → 每日日志的长期记忆同步 | `datamanager/ChatMemorySync.ets`、`ChatMemoryExtraction.ets` | 已实现每次确认及独立本地索引刷新；单元测试/构建及 4 项功能设备测试通过；真实服务及已配置账号验证待办 |
| F04 | `query_events` 个人记忆智能体工具 | `datamanager/DataManagerQueryEventsTool.ets` | 已实现；单元测试/构建及 2 项真机查询测试通过 |
| F05 | 图片附件发送到支持视觉的云模型 | `pages/chat/ChatAttachmentPolicy.ets`、`pages/Index.ets` | 已实现确认后的当前回合多模态 SSE、私有图片历史/缩略图和无损数据库迁移；单元测试/构建及 4 项设备检查通过；每次最多发送 4 张图片，真实服务仍待验证 |
| F06 | Markdown、代码、安全链接与完整消息复制 | `components/MarkdownView.ets`、`pages/chat/ChatMessageList.ets` | 已实现；单元测试/构建及 4 项原生渲染/整条剪贴板设备测试通过 |
| F07 | 图库云端视觉分析 | `utils/gallery/GalleryAnalyzer.ets` | 已完成单图显式上传确认、真实 HTTP、预览及事务保存；单元测试/构建及 3 项设备测试通过；批量编排和真实模型验证待办 |
| F08 | 图库问答 | `pages/gallery/GalleryPage.ets`、`utils/gallery/GallerySearchService.ets` | 已实现确认后的有界候选文字问答、照片引用校验及原生详情导航；单元测试/构建及 2 项设备测试通过；真实云端回答质量未验证 |
| F09 | 图库 → 每日日志 → 索引的统一刷新 | `utils/gallery/GalleryMemorySync.ets`、`datamanager/DataManagerFacade.ets` | 已补齐全部 12 个记忆汇总类别及旧类别映射；图库/全局统一刷新编排待办 |
| F10 | 工作流配置查看、JSON 编辑、创建/删除及图编辑 | `pages/task/TaskPage.ets`、`WorkflowFileViewer.ets` | 查看器见 `b930746`，JSON 编辑已完成；创建/删除及图编辑界面待办 |
| F11 | 工作流 `vlm_qa` 云工具 | `utils/WorkflowRunner.ets` | 待办 |
| F12 | 数字分身图生图与版本选择 | `datamanager/avatar/AvatarService.ets` | 待办；已有文生图 |
| F13 | 真实 MiMO 流式语音客户端 | `utils/voice/MimoVoiceClient.ets` | 待办；已有系统 ASR/TTS |
| F14 | 采集提醒通知 | `utils/CollectionReminderPolicy.ets`、`CollectionReminderService.ets` | 待办；与整合任务的周期调度不同 |
| F15 | 图库扫描暂停/恢复状态持久化 | `pages/gallery/GalleryPage.ets` | 待办；已有运行/取消 |
| F16 | 版本化隐私同意及撤销 | `utils/PrivacyConsent.ets`、`pages/Index.ets` | 待办 |

平台专项后续工作：实现明确的 Android 适配器，或显示可操作的不可用状态，不能模拟成功。

| 编号 | 范围 | 当前 Android 能力边界 |
|---|---|---|
| P01 | 模型驱动的 GUI/截图循环 | 无障碍执行目前只允许应用白名单内的语义操作 |
| P02 | PC 配套服务/路由 | 需要配置和适配配套服务 |
| P03 | 微信富采集结果持久化 | 桥接目前只映射状态/消息 |
| P04 | 浮动控制与可视状态 | 已有状态悬浮层；暂停/恢复/停止控件待办 |
| P05 | 分身分割与聊天中的自定义分身 | 聊天目前使用默认图像资源 |
| P06 | 小艺 A2A、华为账号、ShareKit | 需决定鸿蒙服务在 Android 上的实现方式 |

原生 C++/MNN 实际推理按项目要求继续暂缓，能力状态必须如实显示。
不能将截图中的中转地址、凭据或密钥写入版本控制。真实云端检查需要配置凭据；本地 HTTP 协议测试与真实服务验证分开记录。

每完成一个小功能，记录实际构建/测试、手机验证、状态说明并单独提交。
保留手机原应用及其数据，使用独立开发包验证。只在用户最终审阅确认后创建 PR。
