import Bridge from '@arkui-x.bridge';
import { CloudChatCompletionOptions, CloudChatToolDefinition } from '../utils/CloudModelClient';
import { NativeCapabilities } from './NativeCapabilities';

interface MemoryBegin { id: string; context: string; tools: CloudChatToolDefinition[]; }
interface MemoryPoll { state: string; status: string; message: string; }

/** Native Mika tools retain the user quote checks; model arguments never become user utterances. */
export class MikaMemory {
  id: string = '';
  context: string = '';
  tools: CloudChatToolDefinition[] = [];
  allowAuto: boolean = true;
  static async begin(goal: string, conversation: string): Promise<MikaMemory> {
    let result = JSON.parse(await Bridge.createBridge('ClawMatePlatform').callMethod('mikaMemoryBeginContext', goal, conversation) as string) as MemoryBegin;
    let turn = new MikaMemory(); turn.id = result.id; turn.context = result.context; turn.tools = result.tools;
    return turn;
  }
  has(name: string): boolean { return this.tools.some((t: CloudChatToolDefinition): boolean => t.function.name === name); }
  async call(name: string, args: string): Promise<string> {
    return await Bridge.createBridge('ClawMatePlatform').callMethod('mikaMemoryCall', this.id, name, args) as string;
  }
  async finish(options: CloudChatCompletionOptions, active: () => boolean, trace: (text: string) => void): Promise<void> {
    if (!this.allowAuto || !active() || this.tools.length === 0) { return; }
    const bridge = Bridge.createBridge('ClawMatePlatform');
    let job = '';
    try {
      job = await bridge.callMethod('mikaMemoryFinish', this.id, options.baseUrl, options.model || '', options.apiKey || '') as string;
      const deadline = Date.now() + 45000;
      while (active() && Date.now() < deadline) {
        const status = JSON.parse(await bridge.callMethod('mikaPhonePoll', job) as string) as MemoryPoll;
        if (status.state === 'done') {
          if (active() && status.message) { trace(status.message); }
          return;
        }
        if (status.state !== 'running') { break; }
        await NativeCapabilities.wait();
      }
      if (active()) { trace('记忆整理未完成，可在 Mika 记忆中核对。'); }
    } catch (ignored) { if (active()) { trace('记忆整理暂不可用，聊天回复已保留。'); } }
    finally { if (job) { bridge.callMethod('mikaPhoneCancel', job).catch(() => {}); } }
  }
  close(): void { if (this.id) { Bridge.createBridge('ClawMatePlatform').callMethod('mikaMemoryClose', this.id).catch(() => {}); } }
}
