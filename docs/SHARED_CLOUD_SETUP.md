# 共享版自定义云聊天

共享 ArkTS 前端已有 OpenAI Chat Completions 兼容请求、SSE、取消和本地会话保存。
接入兼容中转使用现有设置，不需要把地址或密钥打进 APK。

## 手机设置（所有构建均可用）

1. 我的 → 模型与智能体 → 云端 Planner/Decider。
2. Planner 服务填提供方 HTTPS 基址（可包含路径和 `/v1`），Model 填模型列表中的精确 ID，API Key 填提供方密钥。
3. 返回再进入确认保存；按页面完成云服务授权及隐私同意。
4. 测试 Planner，再到聊天选择云端、发送短消息。回复结束后重新进入会话检查保存。

客户端会为没有 `/v1` 的基址追加 `/v1/chat/completions`；已经以
`/chat/completions` 结束的地址原样使用。不要用网页展示名代替模型 ID。
文字聊天成功不代表视觉、工具调用、语音或分身服务已通过验收。

## 已连接调试手机配置工具

在忽略目录 `.arkui-x-validation/` 下创建 `cloud.local.json`，内容结构：

```json
{"baseUrl":"https://your-provider.example/prefix/v1","model":"provider-model-id","apiKey":"your-private-key"}
```

```powershell
python scripts/configure-shared-cloud.py --adb '<adb路径>' --serial '<adb devices中的序列号>' --config '.arkui-x-validation/cloud.local.json'
```

要求已安装可 `run-as` 的 `com.clawmate.shared` 调试包，且已启动生成设置文件。
工具停止应用、只更新 Planner 三项字段、原子替换后读回核对。旧设置备份仅留在
应用私有目录 `files/preference/shared_frontend_settings.cloud-backup`，备份也可能含密钥。
工具不会替用户同意隐私声明或开启云授权；重新启动后按页面操作。
如需回退，停止应用后在该包的 `run-as` 环境将备份复制回原设置文件，再重启。
不要将真实 JSON、设置文件、请求日志或备份加入 Git。

## 本轮验证边界

2026-11-02：用户提供的中转在桌面 HTTPS 实测模型列表和 Chat Completions，
DeepSeek 普通响应为 HTTP 200，正文返回合成测试标记；GLM SSE 为 HTTP 200，
包含思考/正文增量。模型 ID 取自服务列表。没有发送私人聊天、图片或数据库。
自动审批拦截了保存本地配置并写入手机/启动应用的命令，未说明具体原因。
因此不宣称工具已在真机执行成功，也不宣称 App 端联网或长期稳定性通过。
