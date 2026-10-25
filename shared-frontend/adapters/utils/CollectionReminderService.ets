import { common } from '@kit.AbilityKit';
import { preferences } from '@kit.ArkData';
import { CollectionReminderState, CollectionReminderIntent } from './CollectionReminderPolicy';
export const COLLECTION_REMINDER_VERSION_KEY: string = 'collectionReminderVersion';
export const COLLECTION_REMINDER_READY_KEY: string = 'collectionReminderReady';
export const COLLECTION_REMINDER_REQUEST_KEY: string = 'collectionReminderRequest';
export class CollectionReminderService {
  private static store: preferences.Preferences | null = null;
  static initialize(context: common.UIAbilityContext): void {
    CollectionReminderService.store = preferences.getPreferencesSync(context, { name: 'android_reminder_preferences' });
    AppStorage.setOrCreate(COLLECTION_REMINDER_READY_KEY, false);
  }
  static load(): CollectionReminderState {
    let state = new CollectionReminderState();
    let store = CollectionReminderService.store;
    if (store !== null) {
      state.hour = Number(store.getSync('hour', 21)); state.minute = Number(store.getSync('minute', 0));
    }
    state.errorText = '安卓通知调度尚未接入；可保存时间，尚不会发送提醒';
    return state;
  }
  static async setEnabled(enabled: boolean): Promise<void> {
    if (enabled) { throw new Error('安卓通知调度尚未接入，提醒未启用'); }
  }
  static async setTime(hour: number, minute: number): Promise<void> {
    let store = CollectionReminderService.store;
    if (store === null || !Number.isInteger(hour) || !Number.isInteger(minute) || hour < 0 || hour > 23 || minute < 0 || minute > 59) {
      throw new Error('无效时间或存储未就绪');
    }
    store.putSync('hour', hour); store.putSync('minute', minute); store.flushSync();
    AppStorage.setOrCreate(COLLECTION_REMINDER_VERSION_KEY, Date.now());
  }
  static pendingIntent(request: string): CollectionReminderIntent | null { return null; }
  static clearRequest(request: string): void { AppStorage.setOrCreate(COLLECTION_REMINDER_REQUEST_KEY, ''); }
  static claim(request: string): boolean { return false; }
  static async recordSuccessfulCollection(): Promise<void> { throw new Error('安卓提醒执行回调尚未接入'); }
}
