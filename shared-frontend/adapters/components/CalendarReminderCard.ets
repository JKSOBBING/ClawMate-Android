import { router } from '@kit.ArkUI';
import Bridge from '@arkui-x.bridge';
import { AppTheme } from '../utils/AppTheme';

interface QuickItem { id: string; title: string; body: string; location: string; start: number; end: number; allDay: boolean; source: string; }
interface QuickSnapshot {
  items: QuickItem[];
  syncedAt: number;
  memorySyncedAt: number;
  windowStart: number;
  windowEnd: number;
  systemEnabled: boolean;
  holidayEnabled: boolean;
  systemGranted: boolean;
  selectedCount: number;
}

interface CalendarStatus { calendarEnabled: boolean; holidayEnabled: boolean; granted: boolean; selected: number; }

const SYSTEM_SOURCE: string = '系统日历';
const HOLIDAY_SOURCE: string = '常用节假日';
const WEEKDAYS: string[] = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'];

// 首页“为你提醒”里的日历卡片：显示从手机系统日历读取的日程与内置常用节假日，
// 只做展示和跳转，不自动把内容发给云端模型。
@Component
export struct CalendarReminderCard {
  @State items: QuickItem[] = [];
  @State status: string = '';
  @State loading: boolean = false;
  @State syncedAt: number = 0;
  @State summary: string = '';
  private alive: boolean = false;
  private epoch: number = 0;
  private lastRefresh: number = 0;
  private refreshing: boolean = false;

  aboutToAppear(): void { this.alive = true; this.refresh(); }
  aboutToDisappear(): void { this.alive = false; this.epoch++; }
  onPageShow(): void { this.refreshIfStale(); }
  onPageHide(): void { this.epoch++; }

  aboutToReuse(): void { this.refreshIfStale(); }

  /** 前台恢复时刷新，但 30 秒内不重复读取，避免频繁访问系统日历。 */
  async refreshIfStale(): Promise<void> {
    if (this.refreshing) { return; }
    if (Date.now() - this.lastRefresh < 30000 && this.items.length > 0) { return; }
    await this.refresh();
  }

  async refresh(): Promise<void> {
    if (this.refreshing) { return; }
    this.refreshing = true; let generation = ++this.epoch;
    try {
      // 先只读确认来源开关与系统日历授权，避免未开启时发起不必要的系统查询。
      let rawStatus = await Bridge.createBridge('ClawMatePlatform').callMethod('sourcesStart', 'calendarStatus', '{}') as string;
      let statusText = await this.awaitJob(rawStatus, generation);
      if (statusText === null) { return; }
      let status = JSON.parse(statusText) as CalendarStatus;
      if (!status.calendarEnabled && !status.holidayEnabled) {
        this.items = []; this.status = '未开启日历来源，点“去设置”打开系统日历或常用节假日。';
        this.loading = false; this.lastRefresh = Date.now(); return;
      }
      this.loading = true;
      let raw = await Bridge.createBridge('ClawMatePlatform').callMethod('sourcesStart', 'calendar', '{"quick":true}') as string;
      let snapshot = await this.awaitJob(raw, generation);
      if (snapshot === null) { return; }
      this.apply(JSON.parse(snapshot) as QuickSnapshot);
    } catch (e) {
      if (generation === this.epoch) { this.status = (e as Error).message; }
    } finally {
      if (generation === this.epoch) { this.loading = false; this.lastRefresh = Date.now(); }
      this.refreshing = false;
    }
  }

  /** 轮询一次来源作业并返回结果文本；被新一轮刷新取代时返回 null。 */
  private async awaitJob(jobId: string, generation: number): Promise<string | null> {
    let deadline = Date.now() + 15000;
    while (generation === this.epoch && Date.now() < deadline) {
      let poll = JSON.parse(await Bridge.createBridge('ClawMatePlatform').callMethod('sourcesPoll', jobId) as string) as Record<string, string>;
      if (poll['state'] === 'done') {
        let result = JSON.parse(poll['value']) as Record<string, Object>;
        if (result['ok'] !== true) { throw new Error(String(result['error'] || '读取失败')); }
        return String(result['value']);
      }
      if (poll['state'] !== 'running') { throw new Error('读取失败'); }
      await new Promise<void>((resolve): void => { setTimeout(resolve, 200); });
    }
    if (generation === this.epoch) { throw new Error('读取超时'); }
    return null;
  }

  private apply(snapshot: QuickSnapshot): void {
    this.loading = false;
    this.syncedAt = snapshot.memorySyncedAt || 0;
    let today = new Date(); today.setHours(0, 0, 0, 0);
    let start = today.getTime();
    let system = snapshot.items.filter((item: QuickItem): boolean => item.source !== 'holiday' && item.end >= start);
    system.sort((a: QuickItem, b: QuickItem): number => a.start - b.start);
    let holidays = snapshot.items.filter((item: QuickItem): boolean => item.source === 'holiday' && item.end >= start);
    holidays.sort((a: QuickItem, b: QuickItem): number => a.start - b.start);
    this.items = system.slice(0, 4).concat(holidays.slice(0, 2));
    this.items = this.items.slice(0, 6);
    this.status = this.describe(snapshot, system.length);
  }

  private describe(snapshot: QuickSnapshot, systemCount: number): string {
    if (!snapshot.systemEnabled && !snapshot.holidayEnabled) { return '未开启日历来源，点“去设置”打开系统日历或常用节假日。'; }
    if (snapshot.systemEnabled && !snapshot.systemGranted) { return '尚未获得系统日历权限，点“去设置”授权后再读取。'; }
    if (snapshot.systemEnabled && snapshot.selectedCount === 0) { return systemCount === 0 ? '系统日历尚未选择来源；节假日仍会显示。' : ''; }
    return '';
  }

  private dayLabel(item: QuickItem): string {
    let date = new Date(item.start);
    let today = new Date(); today.setHours(0, 0, 0, 0);
    let target = new Date(item.start); target.setHours(0, 0, 0, 0);
    let days = Math.round((target.getTime() - today.getTime()) / 86400000);
    let prefix = days === 0 ? '今天' : days === 1 ? '明天' : days === 2 ? '后天' : WEEKDAYS[date.getDay()];
    let md = (date.getMonth() + 1) + '月' + date.getDate() + '日';
    let time = item.allDay ? '全天' : this.hhmm(item.start) + (item.end > item.start ? '–' + this.hhmm(item.end) : '');
    return prefix + ' ' + md + ' · ' + time;
  }

  private hhmm(value: number): string {
    let date = new Date(value);
    return (date.getHours() < 10 ? '0' : '') + date.getHours() + ':' + (date.getMinutes() < 10 ? '0' : '') + date.getMinutes();
  }

  private openPage(): void { router.pushUrl({ url: 'pages/settings/CalendarSourcesPage', params: { kind: 'calendar' } }); }

  @Builder Tag(label: string, holiday: boolean) {
    Text(label).fontSize(10).fontColor(AppTheme.text(holiday ? '#B4690E' : '#4F68FF'))
      .padding({ left: 6, right: 6, top: 2, bottom: 2 })
      .backgroundColor(AppTheme.background(holiday ? '#FDF3E3' : '#EEF2FF')).borderRadius(6)
  }

  @Builder ReminderRow(item: QuickItem) {
    Row({ space: 10 }) {
      Column() {
        Text(this.dayLabel(item)).fontSize(11).fontColor(AppTheme.text('#7C879A')).maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
        Row({ space: 6 }) {
          this.Tag(item.source === 'holiday' ? HOLIDAY_SOURCE : SYSTEM_SOURCE, item.source === 'holiday')
          Text(item.title).fontSize(14).fontWeight(FontWeight.Medium).fontColor(AppTheme.text('#161B28'))
            .layoutWeight(1).maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
        }.width('100%').margin({ top: 3 })
        if (item.location.length > 0) {
          Text(item.location).fontSize(11).fontColor(AppTheme.text('#8A91A1')).maxLines(1).width('100%')
            .textOverflow({ overflow: TextOverflow.Ellipsis })
        }
      }.alignItems(HorizontalAlign.Start).layoutWeight(1)
    }.width('100%').padding({ top: 10, bottom: 10, left: 4, right: 4 })
    .onClick(() => { this.openPage(); })
  }

  build() {
    Column() {
      if (this.items.length > 0) {
        ForEach(this.items, (item: QuickItem, index: number) => {
          if (index > 0) { Divider().strokeWidth(1).color(AppTheme.border('#E9ECF3')).margin({ left: 4 }) }
          this.ReminderRow(item)
        }, (item: QuickItem): string => item.id)
      } else {
        Row({ space: 10 }) {
          Text(this.loading ? '正在读取日历…' : (this.status.length > 0 ? this.status : '近期没有日历安排或节假日。'))
            .fontSize(13).lineHeight(20).fontColor(AppTheme.text('#53617A')).layoutWeight(1)
          if (!this.loading) {
            Button('去设置', { type: ButtonType.Normal }).fontSize(13).height(32).padding({ left: 10, right: 10 })
              .borderRadius(8).fontColor(AppTheme.text('#4F68FF')).backgroundColor(AppTheme.background('#EEF2FF'))
              .onClick(() => { this.openPage(); })
          }
        }.width('100%').padding({ left: 4, right: 4, top: 8, bottom: 8 })
      }
      Row({ space: 8 }) {
        Text(this.syncedAt > 0 ? '日历记忆同步于 ' + new Date(this.syncedAt).toLocaleString() : '尚未同步日历记忆')
          .fontSize(11).fontColor(AppTheme.text('#9AA2B1')).layoutWeight(1).maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
        Text(this.loading ? '读取中' : '刷新').fontSize(12).fontColor(AppTheme.text(this.loading ? '#A0A8B6' : '#4F68FF'))
          .padding({ left: 8, right: 4, top: 4, bottom: 4 })
          .onClick(() => { if (!this.loading) { this.refresh(); } })
        Text('去设置').fontSize(12).fontColor(AppTheme.text('#4F68FF')).padding({ left: 4, right: 4, top: 4, bottom: 4 })
          .onClick(() => { this.openPage(); })
      }.width('100%').padding({ top: 2, bottom: 2 })
    }
    .width('100%')
    .padding({ left: 14, right: 14, top: 6, bottom: 10 })
    .backgroundColor(AppTheme.background('#FFFFFF'))
    .borderRadius(18)
    .clip(true)
    .onVisibleAreaChange([0.0], (visible: boolean): void => { if (visible) { this.refreshIfStale(); } })
  }
}
