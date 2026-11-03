# 日历、邮箱与聊天问答接入

2026-11-03。用户已将日历/邮箱优先于原后续顺序，并要求覆盖主流邮箱。

## 源码与边界

邮件类型和MIME/IMAP解析复用鸿蒙 `4e86f6250c7739d0ea922e2379eeedad2d35d450` 的
`entry/src/main/ets/utils/mail/MailTypes.ets`、`MailImapCodec.ets`；MailImapClient保留原只读查询、
近三天、UIDVALIDITY、候选上限、正文选择与部分结果逻辑，仅把TLS连接替换为Android桥。
这些文件作为适配输入纳入Git；主上游锁不整体升级。
CalendarMemoryPolicy的过去90天/未来30天、实例读取与安排非完成事实语义由CalendarContract适配。

入口：我的→数据采集→日历与邮箱。Kotlin通用能力位于android-capabilities/platform/sources，
PlatformBridge仅接权限、作业和数据端口。页面使用ArkTS，保留Compose基线。

- 日历显式选来源、系统READ_CALENDAR授权、只读重复实例；权限撤回后不提供快照。
- 邮箱只读EXAMINE/BODY.PEEK、TLS主机名和系统证书验证；客户端凭据Keystore AES-GCM加密，
  不回传ArkTS。暂每次配置一个账号；保存配置清旧副本。邮件正文原样保存在私有目录、不进入画像。
- query_calendar/query_mail为独立工具，共享本轮3次工具预算；日期/关键词过滤、最多10项、正文500字，
  逐次确认当前模型，拒绝、撤权、取消不继续发送。返回同步时间、覆盖窗、部分/截断信息。
- 手动AI分析复用相同授权/有界数据。不会伪装为全量画像生成、后台同步、日历写回或邮件发送。
- 本阶段不包括鸿蒙日历完成标记、纳入画像开关、首页一周弹窗、邮件逐封关注偏好缓存；这些仍是完整功能对齐待办。

## 验证

首段完整ArkTS→APK构建通过。最终这一段APK SHA256：
`2d268cd4ad2555585669b4fe8a8f92ff24a3d30731a9b94aa5bffb6abce0a711`。
3项IMAP JVM检查通过；来源查询/模型工具回传受控检查通过；既有集成12项、源码/路由6项、原生桥7项通过。
连接手机已安装APK；PersonalSourcesDeviceTest 1项通过，使用隔离目录与合成凭据验证Keystore、
原子快照、清除及未授权分支，没有读取私人日历或登录真实邮箱。
手册生成与--check通过16章/7图，新增页暂无审核截图。

此前手动hvigor运行因工作目录、未设置环境变量失败；使用项目build.ps1成功。
instrumentation首次构建被来源摘要漂移正确拦截，重建共享前端后通过。
手册编辑遇系统Python编码、章ID及外链校验问题，改用可用Python与本地手册约定后通过。
未执行会议专项，构建脚本原有SDK打包完整性检查不属于会议功能测试。

待验收：真实日历实例/时区/重复例外、QQ/Gmail/交大及其他提供商、模型回答质量、页面无障碍与生命周期。
邮箱扩展与OAuth注册/接入作为接续阶段，不能把IMAP通用实现当作所有邮箱均已支持。

## 官方服务依据

- [Gmail IMAP](https://developers.google.com/workspace/gmail/imap/imap-smtp)、[应用专用密码](https://support.google.com/accounts/answer/185833?hl=zh-Hans)
- [交大标准邮箱](https://net.sjtu.edu.cn/wlfw/dzyj.htm)：mail.sjtu.edu.cn:993；365邮箱是另一服务。
- [Microsoft OAuth IMAP](https://learn.microsoft.com/en-us/exchange/client-developer/legacy-protocols/how-to-authenticate-an-imap-pop-smtp-application-by-using-oauth/)
- [iCloud](https://support.apple.com/en-ie/102525)、[Yahoo](https://uk.help.yahoo.com/kb/SLN3697.html)
