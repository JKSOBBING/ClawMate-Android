import { UIContext } from '@kit.ArkUI';
import { PRIVACY_DECLARATION } from '../components/PrivacyConsentDialog';
import { AiServiceAccess, HUAWEI_ACCOUNT_SESSION_KEY } from '../utils/AiServiceAccess';
import { PrivacyConsent } from '../utils/PrivacyConsent';
import { showToastSafely } from '../utils/PromptSafe';
import { SettingsStore } from './SettingsStore';

export class AndroidPrivacyPolicy {
  static open(ui: UIContext): void {
    const allowed = AiServiceAccess.isAllowed() && PrivacyConsent.isAccepted();
    ui.showAlertDialog({
      title: '隐私政策',
      message: PRIVACY_DECLARATION,
      autoCancel: true,
      primaryButton: { value: '关闭', action: () => {} },
      secondaryButton: {
        value: allowed ? '撤回云端处理授权' : '同意云端处理',
        action: () => { AndroidPrivacyPolicy.setAllowed(!allowed); }
      }
    });
  }

  private static async setAllowed(allowed: boolean): Promise<void> {
    try {
      if (!allowed) { AppStorage.setOrCreate(HUAWEI_ACCOUNT_SESSION_KEY, false); }
      try {
        await PrivacyConsent.choose(allowed);
        AppStorage.setOrCreate(HUAWEI_ACCOUNT_SESSION_KEY, allowed);
      } finally {
        SettingsStore.save();
      }
      showToastSafely(allowed ? '已同意云端处理' : '已撤回云端处理授权');
    } catch (error) {
      AppStorage.setOrCreate(HUAWEI_ACCOUNT_SESSION_KEY, false);
      showToastSafely('隐私选择保存失败，请重试');
    }
  }
}
