import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { stripTypeScriptTypes } from 'node:module';
import { runInNewContext } from 'node:vm';

// Use the compiled project's ArkTS inputs, including the pinned Harmony collector.
const staged = new URL('../.arkui-x-validation/application/project/entry/src/main/ets/', import.meta.url);
const read = name => readFileSync(new URL(name, staged), 'utf8');
const moduleSource = name => read(name).replace(/^import\s[\s\S]*?;\s*$/gm, '').replace(/^export /gm, '');
const index = read('pages/Index.ets');
assert.match(read('components/HomeTaskExecutionSheet.ets'), /在手机上执行/);
assert.match(read('pages/chat/ChatMessageList.ets'), /message\.id\.startsWith\('phone-trace-'\)/);
function method(name, next) {
  const from = index.indexOf(name), until = index.indexOf(next, from + name.length);
  assert.ok(from >= 0 && until > from, name);
  return index.slice(from, until);
}
const methods = [
  method('  private cancelChat()', '  private ensureGeneration('),
  method('  private async handlePhoneSetup(', '  private async submitPhoneTask('),
  method('  private async submitPhoneTask(', '  private async runMikaPhoneTask('),
  method('  private async delegateHomeNote(', '  private openSecretaryJob('),
  method('  private openSecretaryJob(', '  @Builder private Home(')
].join('\n');
const cloudCallback = index.match(/onSubmitCloudTask: (async \(\): Promise<void> => \{[\s\S]*?\n      \}),/)[1];
const phoneSetupGuide = runInNewContext(stripTypeScriptTypes(moduleSource('platform/PhoneSetup.ets') + '\nphoneSetupGuide'));
const opened = [];
let wirelessReply = 'cancelled';
const page = runInNewContext(stripTypeScriptTypes('new class { ' + methods + '\n click = ' + cloudCallback + '; }'), {
  phoneSetupGuide, setTimeout: callback => queueMicrotask(callback),
  Bridge: { createBridge: () => ({ callMethod: async name => {
    if (name === 'guiWirelessSetupResult') return wirelessReply;
    opened.push(name); return name === 'guiWirelessAutoSetup' ? 'fixture-ticket' : true;
  } }) },
  Date: { now: () => 1800000000000 },
  CHAT_MODE_CLOUD_AGENT: 'cloud', MainTabIndex: { Chat: 2 }, AppStorage: { get() { return 'phone'; } },
  showToastSafely() {}, AiServiceAccess: { requireAllowed() {} }, PrivacyConsent: { requireAccepted() {} },
  createChatMessage: (id, role, kind, content) => ({ id, role, kind, content, trace: [] })
});
let session = 0, called = [], note = {}, nextStatus = 'success';
Object.assign(page, {
  generation: 0, tab: 2, messages: [], pendingImages: [], cloudInput: '打开计算器', activeRecord: null,
  stopAsr() {}, persistChat() {}, scheduleChatSave() {}, async authorizeCloud() {}, cloudOptions() { return { baseUrl: 'https://fixture.invalid', model: 'fixture' }; },
  ensureGeneration(g) { if (g !== this.generation) throw Error('取消'); },
  createSession(mode) { this.messages = []; this.mode = mode; this.sessionId = String(++session); this.activeRecord = { id: this.sessionId }; },
  selectSession(id) { this.sessionId = id; },
  secretaryDesk: {
    start(key) { if (note[key]) return false; note[key] = {}; return true; },
    update(key, state, message, id) { Object.assign(note[key], { state, message }); if (id) note[key].sessionId = id; }
  },
  async runMikaPhoneTask(goal, opts, active, method, trace) {
    active(); called.push([goal, method]); trace('已打开指定应用');
    return JSON.stringify({ status: nextStatus, message: 'fixture-receipt' });
  }
});
await page.click();
assert.deepEqual(called, [['打开计算器', 'mikaPhoneStart']]);
assert.equal(page.cloudInput, '');
assert.match(page.messages.find(m => m.kind === 'task_result').content, /完成.*fixture-receipt/);
assert.deepEqual([...page.messages.find(m => m.kind === 'task_trace').trace], ['已打开指定应用']);
const blocked = page.messages.at(-1);
for (const [issue, expected] of [['accessibility', 'guiControlSettings'], ['pair', 'guiWirelessAutoSetup'],
  ['reconnect', 'guiWirelessAutoSetup'], ['calendar', 'calendarAppSettings'], ['appAccess', 'mikaAppAccessSettings']]) {
  blocked.setupIssue = issue;
  await page.handlePhoneSetup(blocked.id, false);
  assert.equal(opened.at(-1), expected);
}
blocked.setupIssue = 'arbitraryModelMethod';
await page.handlePhoneSetup(blocked.id, false);
assert.equal(opened.length, 5);
blocked.setupIssue = 'pair'; page.cloudInput = '保留草稿';
await page.handlePhoneSetup(blocked.id, true); assert.equal(called.length, 1);
page.cloudInput = '';
await page.handlePhoneSetup(blocked.id, true); assert.equal(called.length, 2);
await page.handlePhoneSetup(blocked.id, true); assert.equal(called.length, 2);
assert.equal(called.at(-1)[0], '打开计算器');
const original = page.messages.at(-1);
original.setupIssue = 'pair';
const count = page.messages.length;
wirelessReply = 'disconnected';
await page.handlePhoneSetup(original.id, false);
assert.equal(called.length, 2);
wirelessReply = 'ready';
await Promise.all([page.handlePhoneSetup(original.id, false), page.handlePhoneSetup(original.id, false)]);
assert.equal(called.length, 3);
assert.equal(page.messages.length, count);
assert.equal(page.messages.at(-1).id, original.id);
assert.equal(page.messages.at(-1).setupIssue, undefined);
assert.match(page.messages.at(-1).content, /任务完成/);
await page.handlePhoneSetup(original.id, false);
assert.equal(called.length, 3);
console.log('PASS wireless cancellation, disconnection, single resume and original chat receipt');
for (const [status, expected] of [['success','finished'],['cancelled','cancelled'],['blocked','failed'],['unverified','review']]) {
  nextStatus = status;
  const assignment = { task: '核对便签 ' + status, route: 'phone' };
  await page.delegateHomeNote({ key: status, title: '便签' }, assignment, false);
  await page.delegateHomeNote({ key: status, title: '便签' }, assignment, false);
  assert.equal(called.filter(([goal]) => goal === assignment.task).length, 1);
  assert.equal(note[status].state, expected); assert.equal(note[status].sessionId, page.sessionId);
  const id = page.sessionId; page.sessionId = 'different';
  page.openSecretaryJob({ route: 'phone', sessionId: id }); assert.equal(page.sessionId, id);
}
let entered, finish;
const running = new Promise(resolve => { entered = resolve; });
const nativeResult = new Promise(resolve => { finish = resolve; });
page.runMikaPhoneTask = async () => { entered(); return await nativeResult; };
page.client = { cancelActiveRequests() {} }; page.recordRuntime = () => {}; page.mikaRunId = '';
const pending = page.submitPhoneTask('取消正在执行的任务');
await running;
assert.equal((await page.submitPhoneTask('重复点击')).status, 'blocked');
page.cancelChat();
finish(JSON.stringify({ status: 'success', message: '迟到的完成回执' }));
assert.equal((await pending).status, 'cancelled');
assert.match(page.messages.at(-1).content, /已停止/);
assert.doesNotMatch(page.messages.at(-1).content, /迟到/);
const previousCalls = called.length;
page.cloudInput = '保留草稿'; page.authorizeCloud = async () => { throw Error('拒绝授权'); };
await page.click(); assert.equal(page.cloudInput, '保留草稿'); assert.equal(called.length, previousCalls);
console.log('PASS chat button, task trace, approval refusal, original note goal, four terminal states, duplicate submission and reopening');

// Both cloud routes must retain the local action even when the model cannot finish its reply.
const sendMessage = method('  private async sendMessage(', '  private async handlePhoneSetup(');
for (const explicit of [true, false]) {
  let modelCalls = 0;
  const receipt = { status: 'blocked', message: '虚拟屏助手未连接。', setupIssue: 'pair' };
  const turn = runInNewContext(stripTypeScriptTypes('new class { ' + sendMessage + ' }'), {
    phoneSetupGuide, CHAT_MODE_CLOUD_AGENT: 'cloud', DEFAULT_SHARED_AGENT_BUDGETS: {},
    nowIsoLocal: () => '2026-11-20', AppStorage: { get() {} }, AiServiceAccess: { requireAllowed() {} },
    PrivacyConsent: { requireAccepted() {} }, buildChatContextBudgetSettings: () => ({}), shouldCompressCloudContext: () => false,
    createChatMessage: (id, role, kind, content) => ({ id, role, kind, content, trace: [] }),
    ChatContext: { prepare: async () => ({ result: { messages: [{ role: 'user', content: 'history-marker' }] } }),
      capture() {}, apply() {} }, MikaMemory: { begin: async () => ({ close() {}, finish() {} }) },
    Bridge: { createBridge: () => ({ callMethod: async () => explicit }) },
    SharedAgent: { run: async (_client, _options, _query, _active, _delta, _trace, _codex, _budgets, _collections, phone) => {
      const payload = JSON.parse(await phone('mikaPhoneStart')); modelCalls++;
      assert.equal(payload.guidance, phoneSetupGuide('pair').steps);
      throw Error('fixture offline');
    } }, ChatImages: { discard() {} }, showToastSafely() {}
  });
  const options = { baseUrl: 'https://fixture.invalid', model: 'fixture', apiKey: '', messages: [] };
  Object.assign(turn, { generation: 0, mode: 'cloud', activeRecord: { mode: 'cloud' }, cloudInput: 'open calculator',
    messages: [], pendingImages: [], stopAsr() {}, authorizeCloud() {}, ensureGeneration() {}, cloudOptions: () => options,
    clearPendingImages() {}, persistChat() {}, codexDelegation() {}, runMikaPhoneTask: async () => JSON.stringify(receipt),
    client: { async chatCompletionStream(request) {
      modelCalls++;
      assert.equal(request.tools, undefined);
      assert.ok(!JSON.stringify(request.messages).includes('history-marker'));
      assert.ok(request.messages[1].content.includes(phoneSetupGuide('pair').steps));
      throw Error('fixture offline');
    } }
  });
  await turn.sendMessage();
  assert.equal(modelCalls, 1);
  assert.equal(turn.messages.at(-1).setupIssue, 'pair');
  assert.equal(turn.messages.at(-1).content, receipt.message);
  assert.match(turn.messages.at(-1).trace.at(-1), /fixture offline/);
}
console.log('PASS both cloud routes retain the actionable setup receipt after a provider failure');

const files = new Map();
class Storage {
  join(...parts) { return parts.join('/'); }
  exists(key) { return files.has(key); }
  readText(key, fallback) { return files.get(key) ?? fallback; }
  writeTextAtomic(key, value) { files.set(key, value); }
  sha256Text(value) { return createHash('sha256').update(value).digest('hex'); }
}
const units = ['utils/wechat/WechatAutoSummaryTypes.ets','utils/wechat/WechatUiParser.ets','utils/wechat/WechatAutoSummaryStorage.ets',
  'utils/wechat/PhoneWechatAutoSummaryClient.ets','platform/AndroidWechatCollection.ets'];
let remote;
const Collection = runInNewContext(stripTypeScriptTypes(units.map(moduleSource).join('\n') + '\nAndroidWechatCollection'), {
  ArkJSON: JSON, stringifyJsonCompat: JSON.stringify, DataManagerStorage: Storage, nowIsoLocal: () => '2026-11-19',
  http: { RequestMethod: { POST: 'POST' }, HttpDataType: { STRING: 'string' }, createHttp: () => ({
    async request(url, options) {
      assert.equal(url, 'https://fixture.invalid/api/workflow');
      assert.deepEqual(JSON.parse(options.extraData), { action: 'wechat_collect', payload: request });
      return { responseCode: 200, result: JSON.stringify(remote) };
    }, destroy() {}
  }) },
  Bridge: { createBridge: () => ({ async callMethod(name) { assert.equal(name, 'wechatStop'); } }) }
});
function node(type, bounds, text = '', children = []) {
  return { attributes: { type, bounds, text, bundleName: 'com.tencent.mm' }, children };
}
const title = text => node('Text','[380,90][640,145]',text);
const row = (top, text) => node('ListItem',`[0,${top}][1000,${top+110}]`,'',[node('Text',`[100,${top+5}][400,${top+90}]`,text)]);
const home = node('Group','[0,0][1000,2000]','',[title('微信'),node('Text','[0,1800][200,1900]','通讯录'),
  node('Text','[300,1800][500,1900]','发现'),node('List','[0,220][1000,1600]','',[row(240,'合成验收会话')])]);
const chat = node('Group','[0,0][1000,2000]','',[title('合成验收会话'),node('List','[0,220][1000,1650]','',[row(350,'验收暗号 QA-731')]),
  node('TextInput','[120,1800][700,1950]')]);
const request = { mode: 'target_contact', days: 2, max_contacts: 1, target_contact: '合成验收会话', max_list_swipes: 4,
  swipe_speed: 2000, history_swipe_ratio: 0.4, stable_swipes: 1, max_history_swipes: 4, wait: 0 };
function fixture(stopEarly = false, wrongTitle = false) {
  const run = new Collection(), stages = [];
  let current = home;
  run.openWechat = async () => {};
  run.dump = async () => JSON.stringify(current);
  run.action = async action => {
    if (action.action === 'click') {
      current = structuredClone(chat);
      if (wrongTitle) current.children[0].attributes.text = '其他会话';
    }
    if (stopEarly && action.action === 'swipe_with_coords') await run.stop();
  };
  return { run, stages, exec: (req = request) => run.collect('/fixture', req, false, '', s => stages.push(s)) };
}
for (const mode of ['target_contact','recent_contacts']) {
  const f = fixture(); await f.exec({ ...request, mode });
  assert.equal(f.run.saved, 1); assert.ok(f.stages.some(s => s.includes('翻阅')));
}
const daily = [...files.keys()].filter(k => k.includes('/daily-log/'));
assert.equal(daily.length, 1); assert.match(files.get(daily[0]), /合成验收会话/); assert.match(files.get(daily[0]), /QA-731/);
const saved = files.get(daily[0]);
await assert.rejects(fixture(true).exec(), /停止/);
await assert.rejects(fixture(false,true).exec(), /目标聊天页/);
assert.equal(files.get(daily[0]), saved);
const summary = Collection.lastSummary('/fixture');
assert.match(summary, /合成验收会话/); assert.match(summary, /QA-731/);
const remoteRun = () => new Collection().collect('/remote', request, true, 'https://fixture.invalid', () => {});
const sample = { title: request.target_contact, messages: [{ kind: 'message', text: '回环验收' }] };
remote = { status: 'ok', days: 2, conversations: [sample], daily_log_entries: ['不属于已确认会话的数据'] };
await remoteRun();
const remoteFile = [...files.keys()].find(key => key.startsWith('/remote/') && key.includes('/daily-log/'));
assert.match(files.get(remoteFile), /回环验收/); assert.doesNotMatch(files.get(remoteFile), /不属于/);
for (const invalid of [{ ...sample, title: '其他会话' }, { ...sample, messages: [null] }]) {
  remote = { status: 'ok', conversations: [invalid] };
  await assert.rejects(remoteRun(), /采集范围/);
}
assert.match(Collection.lastSummary('/remote'), /回环验收/);
assert.match(index, /onRunWechatAutoSummary: [^\n]*this\.runWechat\(request\)/);
assert.match(index, /onStopWechatAutoSummary: [^\n]*this\.stopWechat\(\)/);
console.log('PASS Harmony target and recent-contact collection, history scrolling, local daily log, idempotent retry, wrong contact, stop and prior-record preservation');
