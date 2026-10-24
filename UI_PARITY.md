# 鸿蒙 6 前端对齐验收表

## 对照基准

- 用户选择：鸿蒙 6 / 较新系统的悬浮胶囊底栏。
- 源码：`doulujiyao12/mobiinfra-oh` / `clawmate_dev` / `cd69b68bf16b9ed352be5073939eb24d042de583`。
- 参考路径前缀：`entry/src/main/ets/`；资源来自 `entry/src/main/resources/`。
- Android 为真实应用，继续使用原有数据和回调。空数据、未配置、错误不能当成成功结果。
- 本阶段优先前端；不连接新后端、不执行 MNN 推理、不提交上游 PR。

## 验收含义

“源码对齐”表示布局参数、素材和状态逻辑已根据该提交实现；“手机验证”只表示 Android 真机实际观察结果。只有取得同状态、同尺寸的鸿蒙截图/录屏并比较后，才能宣称视觉或动画完全一致。目前没有鸿蒙运行截图/录屏，不能把代码检查称为像素级验收。

鸿蒙 HDS 的 `EXQUISITE/IMMERSIVE` 材质、渐变模糊与系统动画曲线是系统实现，应用源码不含其全部参数。Android 12+ 使用 GPU RenderNode/RenderEffect 对页面背景模糊，前景控件保持清晰；旧 Android 保留半透明表面。两者的系统字体栅格化、阴影和原生弹窗仍需逐项对照。

## 页面及状态清单

| 范围 | 源码依据 | 必须核对的内容 | 当前状态 |
|---|---|---|---|
| 共用颜色 | `utils/AppTheme.ets`、base/dark `color.json` | 文字、背景、边框、8 类强调色、系统深色切换 | 已导入资源和颜色映射；验证中 |
| 顶栏 | `components/AppHeader.ets` | 56 高、居中标题 20/28、左右 20、头像 40/34、返回/操作/PC 指示器 | 已实现；验证中 |
| 毛玻璃页面 | `components/FrostedPage.ets` | 固定顶栏、滚动穿透、24 模糊、32 渐隐、安全区 | Android 适配；HDS 材质待实机对照 |
| 悬浮底栏 | `pages/main/FloatingMainTabs.ets`、`MainTabConfig.ets` | 64 高、左右 20、底部 12、88 内容避让、五项图标/选中状态 | 已实现；系统选择动画为 Android 近似，待对照 |
| 首页概览 | `pages/home/HomePage.ets:159` | 原始背景、待处理数、完整度、四领域、头像入口、宽屏分支 | 已重建；验证中 |
| 为你提醒 | `pages/home/HomePage.ets:1099` | 连续列表、空态猫图、过滤、分页、详情覆盖层 | 源码布局及真实回调已接入，真机通过；系统面板动效待对照 |
| 猜你喜欢 | `pages/home/HomePage.ets:1755` | 三列任务建议、刷新按钮、加载/错误、任务详情 | 已重建并通过真机测试；未接入执行能力明确报错 |
| 帮你解闷 | `pages/home/HomeComfortSection.ets` | 话题列表、空态、全屏聊天覆盖层 | 已重建并通过真机测试；真实后端响应未验收 |
| 汇总 | `pages/collection/CollectionPage.ets` | 下划线标签、足迹概览、时间线、分页、两行入口 | 源码布局已重建并通过真机测试；详情内部继续对齐 |
| 聊天 | `pages/chat/ChatPage.ets`、`ChatMessageList.ets`、`ChatSessionDrawer.ets` | 空态、模式标签、消息、工具状态、底部输入、键盘、会话抽屉 | 欢迎/输入/抽屉/气泡已重建并验证；深度思考参数与精细工具模块尚未接入 |
| 任务 | `pages/task/TaskPage.ets` | 执行概览、设备控制、提醒、任务列表、配置/编辑/文件管理 | 执行页及配置入口已重建并验证；图编辑/新建/完整文件管理继续对齐 |
| 我的 | `pages/settings/SettingsCenterPage.ets` | 账号、Debug、桌面/HDC 入口、五组设置、详情表单 | 主列表、表单尺寸及保存交互已重建并验证；HMS/HDC 为明确受限适配 |
| 图库及详情 | `pages/gallery/`、`pages/collection/CollectionGroupDetailPage.ets` | 分类、分析状态、图像预览、分组详情、返回 | 主界面与图片/事件详情弹层已重建并真机验证；索引缺少来源图时明确提示，月度云结果继续对齐 |
| 引导与隐私 | `pages/onboarding/`、相关隐私页 | 分页、选择、按钮、许可和确认弹窗 | 首次四页引导已对齐并真机验证；隐私/许可二级面板继续对齐 |
| 数字分身 | `pages/chat/DigitalAvatarView.ets`、首页分身覆盖层 | 呼吸 760ms、按压/阴影、生成/历史/版本 | 聊天猫素材、脉冲和点击反馈已实现并验证；个人分身管理页继续对齐 |

## 素材来源

本次新增 `home_overview_background*.png`、`home_category_icons.png`、`home_explorer_mascot.png` 原样取自上述参考提交；`tab_mine.xml` 为同名 SVG 路径的 VectorDrawable 转换。分类图标沿用源码 362px 图集切片和视觉中心偏移。没有读取或复制源项目配置、凭据或设备数据。

## 真机检查记录

构建与手机检查结果完成后写入此处及 `MIGRATION_STATUS.md`，不预填通过。

### UI-01 实际检查（2026-10-24）

- dev APK 构建通过（最后一次 3m43s），测试 APK 构建通过；已安装到 PKB110。
- `HarmonyPageTest` 修复后两次通过，滚动前后标题 bounds 一致，绘制可恢复空闲。修复前反复重绘导致测试未完成，已主动停止，未计为通过。
- 手机检查：首页、汇总、“我的”导航和顶栏正常；小字号行高已按实际 XML 核查纠正。
- 证据：外部验证目录中的 `ui-shell-home-3.png/xml`、`ui-shell-mine-3.png/xml` 和 `ui-shell-device-test-3.log`。
- 此阶段不代表全部页面或像素级视觉验收通过。

### UI-02 实际检查（2026-10-24）

首页主内容已切换为“为你提醒”“猜你喜欢”“帮你解闷”的源码布局；完成过滤、真实回调、建议溯源和陪伴覆盖层已接入。完整单元回归 1315/1315 通过，首页 9 项真机测试通过，之后与汇总一起重建的 11 项测试也通过。证据为 `ui-home-sections.png/xml`、`ui-home-scrolled.png/xml`、`ui-home-comfort-cover.png/xml`、`ui-home-device-tests-2.log`。执行能力未接入时显示错误；原生面板材质/转场仍待鸿蒙运行画面对照。

### UI-03 实际检查（2026-10-24）

汇总标签、概览、时间线、5 组分页和两行入口已按源码重建；入口可直接进入数据采集设置。8 项针对性单元测试和含首页在内的 11 项真机测试通过。`ui-summary-layout.png/xml` 与 `ui-summary-data-collection-route.png/xml` 已检查。微信采集仍为明确的未接入状态，分组详情/图库内部页面及 HDS 默认材质继续对齐。

### UI-04 实际检查（2026-10-24）

我的列表和设置控件已对齐源码尺寸/颜色，真实保存竞态已修复。103 项设置/日志单元测试通过；最终构建和并发保存回归通过；5 项真机测试通过。证据：ui-settings-build-3.log、ui-settings-device-tests-4.log、ui-settings-mine-verified.png/xml、ui-settings-collection-verified.png/xml。HMS、小艺、手机 HDC 为受限适配，尚未完成原生页视觉验收。

### UI-05 实际检查（2026-10-24）

聊天欢迎、输入、抽屉和消息气泡已按源码重建；83 项针对性单元测试通过，聊天 6 项真机测试与 4 项 Markdown/复制回归通过。真机已检查键盘/日夜主题/草稿卡片/抽屉，头像点击像素变化和固定容器通过。最终构建与 UI-06 一起验证。深度思考参数、下发任务、原生媒体和工具细分模块仍有差异；未做真实模型、相机实拍或 ASR 服务验收。

### UI-06 实际检查（2026-10-24）

任务执行页、配置导航、分场景任务卡和 JSON 真实入口已重建；3 项针对性真机测试通过。证据：ui-task-chat-build.log、ui-task-chat-device-tests.log、ui-task-execution.png/xml、ui-task-config.png/xml。配置新建面板/图编辑/上下文变量/完整文件管理尚未完成，控制方式和采集提醒有明确 Android 能力限制；未运行真实任务。

### UI-07 实际检查（2026-10-24）

图库主界面、日期过滤、分类回退、问答/检索布局和月度本机概览已重建。完整单元回归 1323 项通过；12 项真机回归通过，其中图库 3 项。证据：ui-gallery-build-2.log、ui-gallery-device-tests.log、ui-gallery-main.png/xml。运行中无计数时显示不确定进度，未把本机入库称为模型分析；详情弹层和月度云结果的细节继续迁移。

### UI-08 实际检查（2026-10-25）

图片和分组事件详情弹层、正文滚动、关闭及原图片管理入口已重建。早期滚动语义失败已修复；最终 ui-avatar-details-device-tests.log 共 13 项通过，其中详情 2 项。已检查 ui-gallery-detail-fixture.png、ui-indexed-detail-fixture.png，平台默认淡入造成的中间帧叠影已消除。没有来源图片/模型证据的数据不补造；仍需鸿蒙同状态运行画面对照。

### UI-09 实际检查（2026-10-25）

四页引导的原始插图、横向滑动、页码/圆点和按钮已重建；素材名映射错误已修复。最终 13 项真机回归含引导 2 项及问答选择器回归 2 项。测试使用内存引导仓库，没有改动用户的完成状态；源码默认 Swiper 动画待双端录屏验收。
