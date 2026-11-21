import Bridge from '@arkui-x.bridge';
import { promptAction } from '@kit.ArkUI';
import { WorkflowCancellation } from '../utils/WorkflowCancellation';

interface AccessState { connected: boolean; supported: boolean; helperAvailable: boolean; }

export async function ensureCollectionAccess(cancellation: WorkflowCancellation, virtual: boolean): Promise<void> {
  const bridge = Bridge.createBridge('ClawMatePlatform');
  while (true) {
    cancellation.throwIfCancelled();
    const state = JSON.parse(await bridge.callMethod('guiControlStatus') as string) as AccessState;
    if (!state.supported) { throw new Error('界面采集需要 Android 14 或更高版本'); }
    const access = !state.connected;
    const pair = virtual && !state.helperAvailable;
    if (!access && !pair) { return; }
    const message = (access ? '无障碍服务用于读取本次采集页面和执行手势。请在系统设置中开启 ClawMate 界面控制。' : '') +
      (access && pair ? '\n\n' : '') + (pair ? '无线调试用于连接本机虚拟屏。已有配对可直接重连，首次配对需要输入系统显示的配对码。' : '');
    const buttons: promptAction.Button[] = [{ text: '取消任务', color: $r('app.color.ui_text_secondary') }];
    if (access) { buttons.push({ text: '开启无障碍', color: $r('app.color.ui_accent_blue') }); }
    if (pair) { buttons.push({ text: '连接无线助手', color: $r('app.color.ui_accent_blue') }); }
    const choice = await promptAction.showDialog({ title: '开启采集所需服务', message: message, buttons: buttons })
      .catch((): promptAction.ShowDialogSuccessResponse => { return { index: -1 }; });
    if (choice.index <= 0) { cancellation.cancel(); cancellation.throwIfCancelled(); }
    cancellation.throwIfCancelled();
    const openingAccess = access && choice.index === 1;
    const ticket = await bridge.callMethod(openingAccess ? 'guiTaskAccessibility' : 'guiWirelessAutoSetup') as string;
    const deadline = Date.now() + 300000;
    while (true) {
      cancellation.throwIfCancelled();
      if (Date.now() > deadline) { throw new Error('设置已超时，请回到任务页重试'); }
      await new Promise<void>((resolve): void => { setTimeout(resolve, 350); });
      const result = await bridge.callMethod(openingAccess ? 'guiTaskAccessibilityResult' : 'guiWirelessSetupResult', ticket) as string;
      if (result === 'waiting') { continue; }
      if (result !== 'ready') { cancellation.cancel(); cancellation.throwIfCancelled(); }
      break;
    }
  }
}
