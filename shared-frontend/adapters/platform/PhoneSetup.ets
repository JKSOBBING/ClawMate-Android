export interface PhoneSetupGuide {
  issue: string;
  button: string;
  method: string;
  steps: string;
}

const guides: PhoneSetupGuide[] = [
  { issue: 'accessibility', button: '打开无障碍设置', method: 'guiControlSettings',
    steps: '点下方按钮，确认后进入系统「无障碍 → 已下载的应用」。选择当前安装包的「ClawMate 界面控制」并开启。若开关已开但服务仍未连接，可关闭后重新开启。' },
  { issue: 'pair', button: '自动配对', method: 'guiWirelessAutoSetup',
    steps: '在系统无线调试中显示配对码，在手机授权弹窗填入六位码。连接成功后会自动回到本聊天，继续这条任务。' },
  { issue: 'reconnect', button: '自动连接助手', method: 'guiWirelessAutoSetup',
    steps: '本机已保存配对。开启系统无线调试并确认连接后，会自动回到本聊天，继续这条任务。' },
  { issue: 'appAccess', button: '打开应用访问', method: 'mikaAppAccessSettings',
    steps: '在「Mika 应用访问」中核对本次要操作的应用。如同意此次访问，可调整该应用的规则，再返回聊天。' },
  { issue: 'calendar', button: '打开应用权限', method: 'calendarAppSettings',
    steps: '在本应用的系统页面打开「权限 → 日历」，允许日历访问，然后返回聊天。' }
];

export function phoneSetupGuide(issue?: string): PhoneSetupGuide | undefined {
  return guides.find((guide: PhoneSetupGuide): boolean => guide.issue === issue);
}
