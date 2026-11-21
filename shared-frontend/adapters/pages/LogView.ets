import { AppHeader } from '../components/AppHeader';
import { router } from '@kit.ArkUI';
import Bridge from '@arkui-x.bridge';

interface MikaRunSummary {
  at: number;
  kind: string;
  app: string;
  label: string;
  goal: string;
  status: string;
  steps: number;
  pages: number;
  items: number;
  note: string;
}

interface MikaRunHistory {
  v: number;
  records: MikaRunSummary[];
}

interface MikaRecordSummary {
  id: string;
  goal: string;
  model: string;
  startedAt: number;
  endedAt: number | null;
  outcome: string;
  steps: number;
  answer: string;
}

interface MikaRecordList {
  v: number;
  records: MikaRecordSummary[];
}

interface MikaStepEvent {
  atMs: number;
  type: string;
  step: number;
  text: string;
}

interface MikaStepList {
  v: number;
  id: string;
  events: MikaStepEvent[];
}

interface ExportResult {
  ok: boolean;
  error?: string;
  chars?: number;
}

@Entry
@Component
struct LogView {
  @StorageProp('cloudAgentLogs') cloudLogs: string = '';
  @StorageProp('galleryLogs') galleryLogs: string = '';
  @State private mikaHistory: string = '正在读取…';

  @State private mikaRecords: string = '';
  @State private mikaSteps: string = '';
  @State private latestRunId: string = '';
  onPageShow(): void {
    void this.loadMikaHistory();
    void this.loadMikaRecords();
  }

  private async loadMikaHistory(): Promise<void> {
    try {
      const bridge = Bridge.createBridge('ClawMatePlatform');
      const raw = await bridge.callMethod('mikaRunHistory', '{"limit":20}') as string;
      const history = JSON.parse(raw) as MikaRunHistory;
      if (history.v !== 1 || !Array.isArray(history.records)) {
        throw new Error('运行记录接口版本不兼容');
      }
      this.mikaHistory = history.records.length > 0
        ? history.records.map((record: MikaRunSummary): string => this.formatMikaRun(record)).join('\n\n')
        : '暂无 Mika 运行记录';
    } catch (_) {
      this.mikaHistory = '读取 Mika 运行记录失败，请返回后重新打开';
    }
  }

  private async loadMikaRecords(): Promise<void> {
    try {
      const bridge = Bridge.createBridge('ClawMatePlatform');
      const raw = await bridge.callMethod('mikaRunRecords', '{"limit":10}') as string;
      const data = JSON.parse(raw) as MikaRecordList;
      if (data.v !== 1 || !Array.isArray(data.records)) { throw new Error('运行记录接口版本不兼容'); }
      this.mikaRecords = data.records.length > 0
        ? data.records.map((record: MikaRecordSummary): string => this.formatMikaRecord(record)).join('\n\n')
        : '暂无完整运行记录';
      if (data.records.length > 0) { await this.loadMikaSteps(data.records[0].id); }
      this.latestRunId = data.records.length > 0 ? data.records[0].id : '';
    } catch (_) {
      this.mikaRecords = '读取完整运行记录失败，请返回后重新打开';
    }
  }

  private async loadMikaSteps(id: string): Promise<void> {
    try {
      const bridge = Bridge.createBridge('ClawMatePlatform');
      const raw = await bridge.callMethod('mikaRunEvents', JSON.stringify({ id: id })) as string;
      const data = JSON.parse(raw) as MikaStepList;
      if (data.v !== 1 || !Array.isArray(data.events)) { throw new Error('运行步骤接口版本不兼容'); }
      this.mikaSteps = data.events.length > 0
        ? data.events.map((event: MikaStepEvent): string => this.formatMikaStep(event)).join('\n')
        : '这次运行没有记录到可展示的步骤';
    } catch (_) {
      this.mikaSteps = '读取运行步骤失败';
    }
  }

  private async exportLatestRun(): Promise<void> {
    if (this.latestRunId.length === 0) { this.mikaSteps = '还没有可导出的运行记录'; return; }
    try {
      const bridge = Bridge.createBridge('ClawMatePlatform');
      const raw = await bridge.callMethod('mikaRunExport', JSON.stringify({ id: this.latestRunId })) as string;
      const result = JSON.parse(raw) as ExportResult;
      if (result.ok !== true) { this.mikaSteps = result.error ?? '导出失败'; }
    } catch (error) {
      this.mikaSteps = (error as Error).message;
    }
  }

  private formatMikaStep(event: MikaStepEvent): string {
    const when = new Date(event.atMs).toLocaleTimeString();
    return `${when} · ${event.text}`;
  }

  private formatMikaRecord(record: MikaRecordSummary): string {
    const when = new Date(record.startedAt).toLocaleString();
    const head = [when, record.outcome.length > 0 ? record.outcome : '未结束', `${record.steps} 步`].join(' · ');
    const lines = [head];
    if (record.goal.length > 0) { lines.push(record.goal); }
    if (record.answer.length > 0) { lines.push(record.answer); }
    return lines.join('\n');
  }

  private formatMikaRun(record: MikaRunSummary): string {
    const type = record.kind === 'collect' ? '采集' : '任务';
    const target = record.label || record.app;
    const count = record.kind === 'collect' ? `${record.pages} 页 · ${record.items} 条` : `${record.steps} 步`;
    const lines = [`${new Date(record.at).toLocaleString()} · ${type}`,
      [target, record.status, count].filter((value: string): boolean => value.length > 0).join(' · ')];
    if (record.goal) { lines.push(record.goal); }
    if (record.note) { lines.push(record.note); }
    return lines.join('\n');
  }

  build() {
    Column() {
      AppHeader({ title: '运行日志', showBackButton: true, onBackClick: (): void => { router.back(); } })
      Scroll() {
        Column({ space: 12 }) {
          Button('Mika 记忆').onClick(() => { Bridge.createBridge('ClawMatePlatform').callMethod('mikaMemorySettings').catch(() => {}); })
          Text('Mika 最近运行').fontSize(16).fontWeight(FontWeight.Medium).width('100%')
          Text(this.mikaHistory).fontSize(13).width('100%')
          Text('Mika 完整运行记录（按时间倒序）').fontSize(16).fontWeight(FontWeight.Medium).width('100%')
          Text(this.mikaRecords).fontSize(13).copyOption(CopyOptions.InApp).width('100%')
          Text('最近一次运行的步骤').fontSize(16).fontWeight(FontWeight.Medium).width('100%')
          Text(this.mikaSteps).fontSize(12).copyOption(CopyOptions.InApp).width('100%')
          Button('导出最近一次运行').enabled(this.latestRunId.length > 0).onClick(() => { this.exportLatestRun(); })
          Text('本次运行日志').fontSize(16).fontWeight(FontWeight.Medium).width('100%')
          Text(this.cloudLogs.length + this.galleryLogs.length > 0 ? this.cloudLogs + '\n' + this.galleryLogs : '暂无本次运行日志；Debug 默认关闭。MNN 原生日志尚未接入。')
            .fontSize(13).copyOption(CopyOptions.InApp).width('100%')
        }.padding(20).width('100%')
      }.layoutWeight(1)
    }.width('100%').height('100%')
  }
}
