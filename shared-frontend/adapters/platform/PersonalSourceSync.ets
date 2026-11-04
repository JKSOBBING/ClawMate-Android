import { PersonalSources, SourceSnapshot, SourceItem, MailConfiguration } from './PersonalSources';
import { MailImapClient } from '../utils/mail/MailImapClient';
import { defaultMailSettings, emptyMailSnapshot, mailDayBounds, MailItem } from '../utils/mail/MailTypes';

// The page and summary entry use the same bounded, cancellable read and atomic commit.
export class PersonalSourceSync {
  private reader: MailImapClient | null = null;
  private running: boolean = false;
  cancel(): void { if (this.running) { this.reader?.close(); PersonalSources.cancel(); } }
  async run(kind: string, progress: (message: string) => void): Promise<SourceSnapshot> {
    if (PersonalSources.busy) { throw new Error('已有来源操作正在进行，请稍候'); }
    PersonalSources.busy = true; this.running = true; let generation = PersonalSources.epoch;
    try {
      let options = await PersonalSources.options();
      if (generation !== PersonalSources.epoch) { throw new Error('读取已取消'); }
      let next = PersonalSources.empty();
      if (kind === 'calendar') {
        if (!options.calendarEnabled || options.selected.length === 0) { throw new Error('请先在日历分析中开启接入并选择日历来源'); }
        progress('正在读取所选日历…');
        next = JSON.parse(await PersonalSources.operation('calendar', '{}')) as SourceSnapshot;
      } else {
        let config = JSON.parse(await PersonalSources.call('sourcesSettings')) as MailConfiguration;
        if (generation !== PersonalSources.epoch) { throw new Error('读取已取消'); }
        if (!config.username) { throw new Error('请先在邮箱分析中保存账号或完成授权'); }
        let settings = defaultMailSettings(); settings.host = config.host; settings.port = config.port;
        settings.username = config.username; settings.maxMessages = options.maxMessages;
        this.reader = new MailImapClient();
        let result = await this.reader.readToday(settings, 'stored-in-android-keystore', 'local', emptyMailSnapshot(), Date.now(), 3,
          (p): void => { progress(p.message); });
        let bounds = mailDayBounds(Date.now(), 3);
        let items = result.items.map((m: MailItem): SourceItem => { return { id: m.id, title: m.subject,
          body: m.body + (m.bodyError ? '\n正文提示：' + m.bodyError : ''), start: m.receivedAt, end: m.receivedAt,
          location: m.from, allDay: false, timeZone: '', calendarId: 0 }; });
        next = { items: items, sources: [], selected: [], syncedAt: Date.now(), windowStart: bounds[0], windowEnd: bounds[1], partial: result.partial };
      }
      if (generation !== PersonalSources.epoch) { throw new Error('读取已取消'); }
      await PersonalSources.call('sourcesWrite', kind, JSON.stringify(next));
      return next;
    } finally { this.reader = null; this.running = false; PersonalSources.busy = false; }
  }
}
