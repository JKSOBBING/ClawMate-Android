import Bridge from '@arkui-x.bridge';
import { AppTheme } from '../utils/AppTheme';
import { showToastSafely } from '../utils/PromptSafe';

@Component
export struct FloatingNotesSwitch {
  @State private floatingEnabled: boolean = false;
  @State private ready: boolean = false;
  @State private saving: boolean = false;
  private revision: number = 0;

  aboutToAppear(): void { this.refresh(); }

  private async refresh(): Promise<void> {
    if (this.saving) { return; }
    const revision = ++this.revision;
    try {
      const value = await Bridge.createBridge('ClawMatePlatform').callMethod('guiFloatingEnabled') as boolean;
      if (revision === this.revision) { this.floatingEnabled = value; this.ready = true; }
    } catch (error) { showToastSafely('暂时无法读取悬浮窗设置'); }
  }

  private async change(value: boolean): Promise<void> {
    if (!this.ready || this.saving || value === this.floatingEnabled) { return; }
    this.revision++;
    this.saving = true;
    this.floatingEnabled = value;
    try {
      this.floatingEnabled = await Bridge.createBridge('ClawMatePlatform').callMethod('guiSetFloatingEnabled', value) as boolean;
    } catch (error) { this.floatingEnabled = !value; showToastSafely('悬浮窗设置未能保存'); }
    finally { this.saving = false; }
  }

  build() {
    Row({ space: 14 }) {
      Image($r('app.media.settings_automation')).width(24).height(24).objectFit(ImageFit.Contain)
      Text('后台悬浮窗').fontSize(17).fontColor($r('app.color.ui_text_primary')).layoutWeight(1)
      Toggle({ type: ToggleType.Switch, isOn: this.floatingEnabled }).accessibilityText('后台悬浮窗')
        .enabled(this.ready && !this.saving).selectedColor(AppTheme.background('#4F68FF'))
        .onChange((value: boolean): void => { this.change(value); })
    }.width('100%').constraintSize({ minHeight: 64 }).padding({ left: 18, right: 18, top: 12, bottom: 12 })
    .onVisibleAreaChange([0.0], (visible: boolean): void => { if (visible) { this.refresh(); } })
  }
}
