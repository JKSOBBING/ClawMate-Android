export interface MailProvider { name: string; host: string; mode: string; hint: string; }
// Presets are configuration aids, not a claim that each service/account has passed acceptance.
export const MAIL_PROVIDERS: MailProvider[] = [
  { name: 'QQ / Foxmail邮箱', host: 'imap.qq.com', mode: 'password', hint: '在QQ邮箱网页开启IMAP并生成客户端授权码。Foxmail客户端中的其他邮箱应选择其实际服务商。' },
  { name: '网易163', host: 'imap.163.com', mode: 'password', hint: '在网页版开启IMAP并生成客户端授权码；已支持IMAP客户端ID声明。' },
  { name: '网易126', host: 'imap.126.com', mode: 'password', hint: '开启IMAP并使用客户端授权码；以账号设置显示的服务器为准。' },
  { name: '网易yeah.net', host: 'imap.yeah.net', mode: 'password', hint: '开启IMAP并使用客户端授权码；VIP邮箱请使用自定义服务器。' },
  { name: '腾讯企业邮箱', host: 'imap.exmail.qq.com', mode: 'password', hint: '需管理员允许IMAP；按企业要求使用客户端凭据，信创版服务器请自定义。' },
  { name: '阿里企业邮箱', host: 'imap.qiye.aliyun.com', mode: 'password', hint: '填写完整邮箱地址，按管理员要求开启IMAP及客户端访问。' },
  { name: 'Gmail（Google授权）', host: 'imap.gmail.com', mode: 'google', hint: '需要Google Play服务及应用包名/SHA-1注册；输入完整Google邮箱地址后授权。' },
  { name: 'Outlook / Hotmail / Microsoft 365', host: 'outlook.office365.com', mode: 'microsoft', hint: '填写Entra应用客户端ID并使用设备码授权；账户或管理员须允许IMAP。中国区世纪互联不在此预设范围内。' },
  { name: 'iCloud', host: 'imap.mail.me.com', mode: 'password', hint: '使用Apple账户生成的App专用密码；账号按Apple邮箱设置填写。' },
  { name: 'Yahoo', host: 'imap.mail.yahoo.com', mode: 'password', hint: '使用完整邮箱地址和应用专用密码，账户需允许第三方客户端。' },
  { name: '上海交大邮箱（jAccount授权）', host: 'mail.sjtu.edu.cn', mode: 'jaccount', hint: '需先在交我办申请jAccount接口获取clientId与密钥，并登记回调网址；授权成功后以OAUTHBEARER登录交大邮箱。' },
  { name: '上海交大邮箱（客户端授权码）', host: 'mail.sjtu.edu.cn', mode: 'password', hint: '按学校说明填写jAccount账号及邮箱凭据。交大365邮箱单独管理，不能假定已开放IMAP。' },
  { name: 'Gmail（应用专用密码）', host: 'imap.gmail.com', mode: 'password', hint: '仅适用于已开启两步验证且允许应用专用密码的账户。' },
  { name: '其他 / 自定义IMAP', host: '', mode: 'password', hint: '输入服务商提供的IMAP TLS服务器和客户端凭据；不支持明文、STARTTLS、POP3或Exchange专有协议。Proton/Tuta等需专用桥接或API的服务未直接接入。' }
];
