import UIAbility from '@ohos.app.ability.UIAbility';
import window from '@ohos.window';

// 交大邮箱 jAccount 回调（clawmate://jaccount?code=...&state=...）由 Android 宿主
// SharedActivity 直接交给本机桥处理（Manifest 声明意图过滤器），因此这里不需要 onNewWant。
export default class EntryAbility extends UIAbility {
  onWindowStageCreate(windowStage: window.WindowStage): void {
    windowStage.loadContent('pages/Index');
  }
}
