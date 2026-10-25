# ArkUI-X 复用验证

目标：跟随鸿蒙版本维护 Android，尽量共享真实源代码，并保留其他系统的适配边界。
本目录是独立实验，不替换现有 Compose 应用，不修改鸿蒙参考仓库。

## 可重复检查

上游版本及只读输入文件的 Git blob 哈希见 `upstream-lock.json`。
从仓库根目录运行（主机契约检查使用带 `stripTypeScriptTypes` 的 Node 24）：

```powershell
node experiments/arkui-x/verify-contracts.mjs --source-root <鸿蒙只读源码目录>
node experiments/arkui-x/audit.mjs --source-root <鸿蒙只读源码目录>
```

也可使用通过 GitHub 连接生成的、不含配置值的导入元数据重放静态审计：

```powershell
node experiments/arkui-x/audit.mjs --inventory .arkui-x-validation/source-inventory.json
```

检查在内存中擦除 TypeScript 类型并包装导出，不改写算法。每个输入文件先验证
Git blob 哈希，版本漂移会失败。主机检查通过不能证明 ArkTS 编译或 Android 运行通过。
`audit-summary.json` 是保守的导入依赖图，不区分仅用于类型的普通导入，也不覆盖隐式
框架全局变量；文件数量不能当作代码复用率或待重写量。

## 2026-10-25 第一阶段结果

- 源版本：`cd69b68bf16b9ed352be5073939eb24d042de583`。
- 扫描全部 197 个生产 `.ets` 文件：90 个直接导入平台模块，107 个未直接导入；
  38 个包含 UI 装饰器。保守首页依赖闭包为 61 个文件，聊天页为 34 个，云模型客户端为 14 个。
- 7 个原始纯逻辑模块通过 8 项主机检查，覆盖流式展示、会话切换、持久化节流、
  首页网格、取消、记忆分类、引导与 JSON 文本规范化。
- 已下载 ArkUI-X Windows SDK 6.0.2.118 / API 22，官方 SHA-256 校验通过：
  `b72867f9658a72f0d2fa4d7775344978cab2cc52450e06ccd35995ad0e0e600f`。
- Android SDK、JDK 17 和 PKB110 真机可用；ArkTS 编译链还在准备。
- 旧版官方 `ohsdkmgr` 实际只列出 API 7–9，不能用它宣称已安装 API 22。
- Windows 工作目录已获用户明确授权。两个已有远程 SSH 连接均超时。

## 已识别的适配边界

| 源端依赖 | 验证方向 |
| --- | --- |
| `HomePage.ets` 默认值依赖 `DataManagerFacade` | 将展示模型与数据工厂解耦，避免页面拉入整套存储/模型服务 |
| `FrostedPage.ets`、主导航的 `@kit.UIDesignKit` | 验证标准 ArkUI 外壳；运行时 `canIUse` 不等于编译时可忽略不支持的导入 |
| `ChatPage.ets` 的 `@kit.IMEKit` | 将输入法收起等操作放进平台接口 |
| `CloudModelClient.ets`、`PrivacyConsent.ets` | 保留源端 SSE 解析、取消和隐私撤回；逐方法验证 HTTP 插件 |
| `AppLogger` → `DebugSettings` → `libentry.so` | 拆出日志/诊断边界，不能因为原生日志依赖迫使云聊天链接 MNN |
| 华为账号、CoreVision OCR/主体分割、提醒、跨应用控制 | 核对具体 API，缺口通过原生适配或明确不可用状态处理 |

SDK、参考输入、下载包、构建产物、截图和详细日志位于被忽略的
`.arkui-x-validation/`。未复制源端疑似凭据配置、签名材料或用户数据。
没有实际编译/运行输出前，不将 Android 页面、SSE 或桥接标记为通过。

官方依据：[SDK 6.0.2 发布说明](https://github.com/arkui-x/docs/blob/master/zh-cn/release-notes/ArkUI-X-v6.0.2-release.md)、
[跨平台 API](https://github.com/arkui-x/docs/blob/master/zh-cn/application-dev/reference/apis/README.md)、
[平台桥接](https://github.com/arkui-x/docs/blob/master/zh-cn/application-dev/quick-start/platform-bridge-introduction.md)。
