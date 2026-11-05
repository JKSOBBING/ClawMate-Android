# 邮箱授权注册与接入指南

更新：2026-11-03。目标 Android 共享宿主包名为 `com.clawmate.shared`。
本文供项目所有者配置 Google Cloud / Microsoft Entra；无需把密码、令牌或签名私钥发到聊天。
注册需要使用你拥有或被授予管理权限的服务商账户，代码无法替你取得这些管理权限。

## 当前接入方式

| 邮箱 | 应用内入口 | 前置条件 |
| --- | --- | --- |
| Gmail / Google Workspace | Gmail（Google授权） | Google Play服务、网络可达、Android OAuth注册、账户允许所请求范围 |
| Outlook / Hotmail / 国际版 Microsoft 365 | Outlook / Hotmail / Microsoft 365 | Entra公共客户端、IMAP委托权限、邮箱开启IMAP、租户允许设备码流程 |
| QQ/Foxmail邮箱、网易163/126/yeah、腾讯/阿里企业邮箱、iCloud、Yahoo、交大标准邮箱 | 相应预设或自定义IMAP | TLS收信服务和服务商允许的客户端凭据 |

预设只是帮助填写连接参数，不等于所有提供商已经实测通过。Foxmail软件本身不是邮箱服务商。
交大365与标准交大邮箱分开管理；不能由域名推断IMAP开放。世纪互联Microsoft 365、
Proton/Tuta专用接入、POP3、Exchange专有协议及STARTTLS尚未接入。
自定义入口支持使用系统信任证书的IMAP over TLS，默认993，不允许忽略证书错误。

## Google Cloud：注册 Gmail Android 授权

1. 使用项目所有者账户进入 Google Cloud Console，创建或选定项目。
2. 在 Google Auth Platform 的 Branding 填应用名称、支持邮箱、隐私政策和应用主页；
   Audience 选择适合目标用户的类型。开发期保留 Testing，并添加实际参与验收的测试账户。
3. 在 Clients 新建 **Android** OAuth 客户端。包名填 `com.clawmate.shared`，
   SHA-1 填当前安装包所用签名证书的指纹。请用本机工具查询，不复制私钥：

   ```powershell
   .\gradlew.bat :shared-app:signingReport --console=plain
   ```

   先按项目构建说明配置 JDK/Android SDK。Debug、Release和商店重签名证书可能不同，
   应分别登记需要验收/发布的签名；更换证书后旧登记不会自动生效。
4. 在 Data Access 添加 `https://mail.google.com/`。IMAP OAuth使用此范围；
   它比本应用只读收信的行为更宽，不能在同意页宣传成服务端只读授权。
5. 安装签名匹配的包，在“我的 → 数据采集 → 日历与邮箱”选择“Gmail（Google授权）”，
   输入完整Google邮箱地址，点击“授权邮箱 / 重新授权”，完成Google系统窗口。
   无须在本项目填写Google客户端密钥，也无须部署令牌交换后端。
6. 授权完成后点“读取近三天邮件”。只有真实读取完成并显示同步时间才算邮箱接入验收完成。

公开发布前，按控制台对受限范围、品牌、域名、隐私政策及验证的要求办理。
测试用户可用不代表应用已经通过公开发布验证。当前AI查询会在用户确认后向所配置模型传输有限邮件内容，
发布审核须一并说明此用途并核对受限数据传输的安全评估要求。Workspace管理员仍可限制第三方访问。

排错：先核对包名/签名、测试账户、Play服务和网络。设备没有可用Play服务时，
可使用“Gmail（应用专用密码）”，但仅限账户开启两步验证且允许生成该密码的情况。
不应把普通Google登录密码填入客户端凭据。

依据：[Android授权与客户端注册](https://developer.android.com/identity/authorization)、
[Gmail IMAP OAuth](https://developers.google.com/workspace/gmail/imap/imap-smtp)、
[Gmail权限范围](https://developers.google.com/workspace/gmail/api/auth/scopes)、
[应用专用密码](https://support.google.com/accounts/answer/185833?hl=zh-Hans)。

## Microsoft Entra：注册 Outlook / Microsoft 365

1. 进入 Microsoft Entra 管理中心 → App registrations → New registration。
   若同时面向个人Outlook与不同组织，选择支持任意组织目录及个人Microsoft账户的账户类型；
   仅内部部署时可选择单租户，并在应用里填写该租户ID。
2. 记录 **Application (client) ID**。这是公开标识符，不能填写对象ID、客户端密钥或邮箱密码。
3. 在 Authentication 的高级设置中启用公共客户端流程（Allow public client flows）。
   本实现使用设备码流程，不要求重定向URI或客户端密钥。
4. 在 API permissions 中为 **Office 365 Exchange Online** 添加委托权限
   `IMAP.AccessAsUser.All`。授权请求使用完整范围
   `https://outlook.office.com/IMAP.AccessAsUser.All`；不要误配为Microsoft Graph的Mail.Read。
   租户需要管理员同意时，由管理员按组织策略处理。
5. 确认实际邮箱已开启IMAP。组织的条件访问或身份策略可能禁用设备码流程，
   这种情况应由管理员评估，不要求用户关闭组织安全策略。
6. 在应用选择Outlook预设，填写邮箱、客户端ID、租户。默认 `common` 同时面向组织与个人，
   也接受 `consumers`、`organizations` 或租户GUID，须与注册账户类型匹配。
7. 点“授权邮箱 / 重新授权”，页面显示本次设备码。点“打开Microsoft授权网页”，
   在官方网页输入该码并登录与应用所填邮箱对应的账户。返回应用等到“OAuth授权完成”，
   再点“读取近三天邮件”。请勿把设备码发给他人。

错误处理：取消或拒绝后重新发起；设备码过期则申请新码；网络轮询失败会间隔重试，
服务器要求slow_down时延长间隔。授权成功而IMAP失败时，检查邮箱地址、IMAP开关、
委托权限及管理员策略。OAuth成功本身不能证明邮箱访问已经获得批准。

依据：[Microsoft应用注册](https://learn.microsoft.com/en-us/entra/identity-platform/quickstart-register-app)、
[设备码流程](https://learn.microsoft.com/en-us/entra/identity-platform/v2-oauth2-device-code)、
[IMAP OAuth及权限](https://learn.microsoft.com/en-us/exchange/client-developer/legacy-protocols/how-to-authenticate-an-imap-pop-smtp-application-by-using-oauth/)、
[Outlook IMAP设置](https://support.microsoft.com/en-gb/outlook/pop-imap-and-smtp-settings-for-outlook-com?nochrome=true)。

## 存储、会话与撤销

- Google和Microsoft访问令牌只存Android进程内存，不回传ArkTS、不写日志或磁盘。
  当前不请求离线刷新令牌；进程重启或令牌过期后重新授权。Google系统可能复用既有同意。
- Microsoft客户端ID和租户、邮箱地址与服务器保存在本机配置中；这些不是令牌。
  授权码模式仍用Android Keystore加密保存凭据。每次仅配置一个邮箱。
- 开始新授权或保存连接配置会清除旧邮件副本；取消授权不恢复旧副本。
  仅去浏览器/Google窗口完成授权不会主动中断本次流程；从来源页面退出会取消待授权任务。
- “清除邮件和授权码”删除本机来源副本、配置与内存令牌，不会删除服务器邮件，
  也不等于撤销服务商侧的应用同意。完整撤销需到Google第三方应用管理或Microsoft账户/组织应用管理处理。
- 邮件正文保存在应用私有目录；仅在每次确认后，有限记录才会发往当前配置的聊天模型。
  服务商OAuth同意与向AI发送内容的确认是两个独立步骤。

## 交大邮箱：默认用 jAccount 账号 + 密码（2026-11-05）

学校对安卓客户端的官方说明就是「填写交大邮箱邮箱地址和密码」，因此**推荐并默认**使用预设
「上海交大邮箱」：

- 账号填 `abc`（会自动归一化成 `abc@sjtu.edu.cn`）或直接填完整地址；
- 密码填 jAccount 密码；
- 服务器固定 `mail.sjtu.edu.cn:993`（IMAP over TLS），走标准 `LOGIN`；
- 密码用 Android Keystore（AES-GCM）加密保存在本机，不通过桥接返回，也不写日志；
- 不需要申请 jAccount 接口、不需要客户端ID/密钥/回调网址。

实机验证（2026-11-05）：保存后本机记录为
`user=clawmtest@sjtu.edu.cn`、`host=mail.sjtu.edu.cn`、`port=993`，密码字段为加密串；
用测试账号读取时服务器在 TLS 握手后返回 `* OK IMAP4rev1 proxy server ready`，
`LOGIN` 得到明确的 `A1 NO LOGIN failed`——说明账号密码方式链路可用，
失败原因是测试账号不存在。真实 jAccount 账号的收信验收仍需你本人完成。

边界：如果学校以后关闭密码登录、要求统一身份认证，应用会明确报“读取失败”，
不会回退成别的登录方式。

## 交大邮箱 jAccount 授权（高级选项，2026-11-04 新增）

交大邮箱的传统接入方式是 IMAP + jAccount 账号密码/客户端授权码（学校说明：
[交大邮箱](https://net.sjtu.edu.cn/wlfw/dzyj.htm)，IMAP `mail.sjtu.edu.cn:993`）。本次新增的是
**jAccount OAuth2 授权码**登录方式，它要求应用先加入 jAccount 认证体系：

1. 用 jAccount 登录「交我办」PC 版（my.sjtu.edu.cn）→ 信息服务 → 其他 → **jAccount接口申请**，
   由部门负责人审批，网络信息中心 3 个工作日内通过交我办消息反馈结果。
2. 获批后网络信息中心分配 **clientId 与一段密钥**（密钥由你本人保管，不写进代码或仓库），
   并要求在申请表单中填写**应用服务网址**——OAuth2 服务器会校验该网址与申请时一致。
3. 在应用内到「汇总 → 邮箱分析 → 邮箱类型：上海交大邮箱（jAccount授权）」填写：
   - **jAccount账号**：`abc` 或 `abc@sjtu.edu.cn`
   - **jAccount客户端ID**：第 2 步分配的 clientId
   - **jAccount回调网址**：申请时登记的服务网址（HTTPS），或本应用的 Deep Link `clawmate://jaccount`
     （Deep Link 同样要在申请表中登记；两者都通过校验，填其它值会被明确拒绝）
   - **jAccount密钥**：第 2 步分配的密钥，只用于本次换取令牌，**不会保存**
4. 点「启动jAccount授权 / 重新授权」→「打开授权网页」，在浏览器完成 jAccount 登录；
   浏览器回到回调网址（Deep Link 会直接拉起应用）后，应用在本机后台用授权码换取访问令牌。
5. 回到页面点「检查授权状态」，显示授权完成后即可「读取近三天邮件」。

登录协议：拿到访问令牌后用 **RFC 7628 OAUTHBEARER**（`AUTHENTICATE OAUTHBEARER`）登录
`mail.sjtu.edu.cn:993`。如果学校服务器没有为邮箱开放该 SASL 机制，应用会明确报出认证失败，
**不会**退回成密码登录，也不会假装读取成功。

边界：授权令牌只保存在进程内（应用重启或过期后需要重新授权）；clientSecret 只在内存中短暂停留；
回调 state 每次授权随机生成并校验，不匹配直接拒绝；回调网址必须是 HTTPS。

上述流程基于学校公开说明与 `https://jaccount.sjtu.edu.cn/oauth2/.well-known/openid-configuration`
公开的授权/令牌端点编写，**尚未用真实 jAccount 应用注册与校园账号完成端到端验收**。

## 验收清单

- [ ] Google注册匹配Debug签名；测试账户完成授权、拒绝、重新授权及进程重启场景。
- [ ] Microsoft个人与组织账户分别完成设备码、拒绝、到期、IMAP禁用及重新授权场景。
- [ ] 用测试邮箱核对近三天正文、编码、附件排除、重复邮件与只读状态。
- [ ] 交大邮箱分别完成「jAccount 授权」与「客户端授权码」两条路径的真实收信验收；
      并确认学校服务器是否支持 OAUTHBEARER。
- [ ] 聊天查询只发送确认范围，引用来源/同步时间；拒绝后不发送，清除后无旧副本。
- [ ] 发布注册、范围审核、多厂商设备和无障碍体验。

代码和受控检查的结果另记于 `PERSONAL_SOURCES_MIGRATION.md`；本表的真实服务验收项不能用构建成功勾选。

其他服务商设置参考：[QQ授权码](https://help.mail.qq.com/detail/106/985)、
[阿里邮箱服务器](https://help.aliyun.com/zh/document_detail/36576.html)、
[iCloud](https://support.apple.com/en-ie/102525)、[Yahoo](https://uk.help.yahoo.com/kb/SLN3697.html)、
[交大标准邮箱](https://net.sjtu.edu.cn/wlfw/dzyj.htm)。网易与企业邮箱的客户端开关、
账号写法和专用服务器请再核对各自账号设置，尤其是VIP、信创和单位自建实例。
