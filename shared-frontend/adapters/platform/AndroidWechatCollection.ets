import Bridge from '@arkui-x.bridge';
import { http } from '@kit.NetworkKit';
import { WorkflowJsonMap } from '../utils/WorkflowTypes';
import { DataManagerStorage } from '../datamanager/DataManagerStorage';
import { nowIsoLocal } from '../datamanager/DataManagerUtils';
import { PhoneWechatAutoSummaryClient, WechatPhoneDevice } from '../utils/wechat/PhoneWechatAutoSummaryClient';
import { WechatAutoSummaryConversation, WechatAutoSummaryMessage, WechatAutoSummaryRequest, WechatAutoSummaryResponse, validateWechatAutoSummaryRequest } from '../utils/wechat/WechatAutoSummaryTypes';
import { WechatAutoSummaryStorage, wechatAutoSummaryDailyEntries, renderWechatAutoSummaryMarkdown } from '../utils/wechat/WechatAutoSummaryStorage';
import { NativeCapabilities } from './NativeCapabilities';

interface StepPoll { state: string; result: string; }
interface StepResult { text?: string; error?: string; }
interface WechatBridgeRequest { action: string; payload: WechatAutoSummaryRequest; }

export class AndroidWechatCollection implements WechatPhoneDevice {
  private token: string = 'wechat-' + Date.now() + '-' + Math.floor(Math.random() * 1000000000);
  private stopped: boolean = false;
  private pc: http.HttpRequest | null = null;
  private date: string = nowIsoLocal().slice(0, 10);
  saved: number = 0;

  static lastSummary(filesDir: string): string {
    const storage = new DataManagerStorage();
    return storage.readText(storage.join(filesDir, 'workflows', 'wechat-collection', 'latest-summary.txt'), '');
  }

  private check(): void { if (this.stopped) { throw new Error('微信采集已停止'); } }
  async stop(): Promise<void> {
    this.stopped = true;
    this.pc?.destroy(); this.pc = null;
    await Bridge.createBridge('ClawMatePlatform').callMethod('wechatStop', this.token);
  }

  private async step(payload: WorkflowJsonMap): Promise<string> {
    this.check();
    const bridge = Bridge.createBridge('ClawMatePlatform');
    const id = await bridge.callMethod('wechatStepStart', this.token, JSON.stringify(payload)) as string;
    const deadline = Date.now() + 25000;
    while (Date.now() < deadline) {
      this.check();
      const poll = JSON.parse(await bridge.callMethod('wechatStepPoll', id) as string) as StepPoll;
      this.check();
      if (poll.state === 'done') {
        const response = JSON.parse(poll.result) as StepResult;
        if (response.error) { throw new Error(response.error); }
        return response.text || '';
      }
      if (poll.state !== 'running') { throw new Error('微信窗口操作已中断'); }
      await NativeCapabilities.wait();
    }
    throw new Error('等待微信窗口超时，请回到聊天首页后重试');
  }
  private command(action: string): WorkflowJsonMap {
    const payload: WorkflowJsonMap = {}; payload['action'] = action; return payload;
  }
  async openWechat(): Promise<void> { await this.step(this.command('open')); }
  async dump(): Promise<string> { return await this.step(this.command('dump')); }
  async action(payload: WorkflowJsonMap): Promise<void> {
    if (payload['action'] === 'wait') {
      const until = Date.now() + Number(payload['seconds']) * 1000;
      while (Date.now() < until) { this.check(); await NativeCapabilities.wait(); }
    } else { await this.step(payload); }
  }

  private async collectPc(base: string, request: WechatAutoSummaryRequest): Promise<WechatAutoSummaryResponse> {
    if (!/^https:\/\/[^@/?#\s]+(?:\/[^?#\s]*)?$/.test(base) && !/^http:\/\/127\.0\.0\.1:\d+$/.test(base)) {
      throw new Error('请配置电脑 HDC 桥的 HTTPS 地址，或切换到手机自己控制');
    }
    const body: WechatBridgeRequest = { action: 'wechat_collect', payload: request };
    const client = http.createHttp(); this.pc = client;
    try {
      const response = await client.request(base.replace(/\/+$/, '') + '/api/workflow', {
        method: http.RequestMethod.POST, header: { 'Content-Type': 'application/json' }, extraData: JSON.stringify(body),
        connectTimeout: 8000, readTimeout: 300000, expectDataType: http.HttpDataType.STRING
      });
      this.check();
      if (response.responseCode < 200 || response.responseCode >= 300) { throw new Error('电脑采集服务返回 HTTP ' + response.responseCode); }
      const raw = response.result as string;
      if (!raw || raw.length > 12 * 1024 * 1024) { throw new Error('电脑采集响应为空或超出大小上限'); }
      const result = JSON.parse(raw) as WechatAutoSummaryResponse;
      if (result.status === 'ok') {
        const conversations = result.conversations || [];
        const limit = request.mode === 'target_contact' ? 1 : request.max_contacts;
        if ((result.days !== undefined && result.days !== request.days) || (result.mode && result.mode !== request.mode) ||
          !Array.isArray(conversations) || !conversations.length || conversations.length > limit || conversations.some((item: WechatAutoSummaryConversation): boolean =>
          !item || typeof item.title !== 'string' || !item.title.trim() || !Array.isArray(item.messages) ||
          item.messages.some((message: WechatAutoSummaryMessage): boolean => !message || typeof message.kind !== 'string' ||
            (message.text !== null && typeof message.text !== 'string')) ||
          (request.mode === 'target_contact' && item.title.trim() !== request.target_contact.trim()))) {
          throw new Error('电脑返回的会话与本次采集范围不一致');
        }
        // Derive entries from the validated conversations, even if a bridge also supplies free-form logs.
        result.daily_log_entries = [];
      }
      return result;
    } finally { if (this.pc === client) { this.pc = null; client.destroy(); } }
  }

  async collect(filesDir: string, request: WechatAutoSummaryRequest, pcMode: boolean, base: string,
    progress: (message: string) => void): Promise<void> {
    this.check();
    const error = validateWechatAutoSummaryRequest(request);
    if (error) { throw new Error(error); }
    const rawStore = new WechatAutoSummaryStorage(filesDir);
    const storage = new DataManagerStorage();
    const save = (response: WechatAutoSummaryResponse): void => {
      // Use our own run ID for file paths. A remote response is untrusted data.
      rawStore.writeRawArtifact(this.token, 'wechat_collect.json', JSON.stringify(response));
      rawStore.writeRawArtifact(this.token, 'wechat_collect.md', renderWechatAutoSummaryMarkdown(response));
      const entries = wechatAutoSummaryDailyEntries(response);
      let count = 0;
      for (let i = 0; i < entries.length; i++) {
        const conversation = response.conversations?.[i];
        const entry = entries[i].trim();
        if (!entry) { continue; }
        const source = conversation?.title?.trim() || entry.split('\n')[0];
        const key = 'contact-' + storage.sha256Text(source);
        const path = storage.join(filesDir, 'workflows', 'daily-log', this.date, 'com.tencent.mm__wechat_auto_summary__' + key + '.md');
        // Interrupted reads keep their raw checkpoint; never replace a previous daily record with a partial one.
        if (response.status === 'ok' || !storage.exists(path)) {
          rawStore.writeDailyLog(key, request.mode, request.days, this.date, [entry]);
        }
        count++;
      }
      this.saved = count;
    };
    const result = pcMode ? await this.collectPc(base, request) : await new PhoneWechatAutoSummaryClient(
      this, rawStore, (): void => {}, progress, (): void => { this.check(); }, save).collect(request);
    this.check();
    if (result.status !== 'ok') { throw new Error(result.message || '微信采集未完成'); }
    save(result);
    if (!this.saved) { throw new Error('本次未取得可入库的微信会话记录'); }
    const lines: string[] = [this.date + ' · ' + this.saved + ' 个会话 · 请求最近 ' + request.days + ' 天'];
    for (const conversation of result.conversations || []) {
      const messages = conversation.messages.filter((m: WechatAutoSummaryMessage): boolean => m.kind !== 'time' && !!m.text);
      const latest = messages[messages.length - 1];
      lines.push(conversation.title + ' · ' + messages.length + ' 条消息' +
        (latest?.text ? '\n最近一条：' + latest.text.slice(0, 140) : '') +
        (conversation.collection_stop_reason ? '\n' + conversation.collection_stop_reason : ''));
    }
    storage.writeTextAtomic(storage.join(filesDir, 'workflows', 'wechat-collection', 'latest-summary.txt'), lines.join('\n\n'));
  }
}
