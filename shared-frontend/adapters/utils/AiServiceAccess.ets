// Android cloud authorization is explicit permission to use user-configured
// credentials. It is not a Huawei account login or a fabricated identity.
export const HUAWEI_ACCOUNT_SESSION_KEY: string = 'androidCloudAccessGranted';
export const AI_LOGIN_REQUIRED_MESSAGE: string = '请到“我的 → 个人与隐私 → 隐私政策”，点“同意云端处理”后再试。';
export function aiServiceLoginAllowed(requireLogin: boolean, active: boolean | undefined): boolean {
  return !requireLogin || active === true;
}
export class AiServiceAccess {
  static isAllowed(): boolean { return AppStorage.get<boolean>(HUAWEI_ACCOUNT_SESSION_KEY) === true; }
  static requireAllowed(): void { if (!AiServiceAccess.isAllowed()) { throw new Error(AI_LOGIN_REQUIRED_MESSAGE); } }
}
