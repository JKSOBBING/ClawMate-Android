import fs from 'node:fs';
import assert from 'node:assert/strict';
import { stripTypeScriptTypes } from 'node:module';
import { runInNewContext } from 'node:vm';
// 适配输入可能带 UTF-8 BOM（Windows 编辑器写入），先去掉再按行剥离 import。
const source = fs.readFileSync(new URL('./adapters/platform/PersonalSources.ets', import.meta.url), 'utf8').replace(/^\uFEFF/, '');
const code = stripTypeScriptTypes(source.replace(/^import .*;\r?\n/gm, '')).replace(/^export /gm, '');
let confirm = 0, accepted = true, snapshot = {}, readCount = 0;
const native = fs.readFileSync(new URL('../shared-app/src/main/java/com/clawmate/shared/PlatformBridge.kt', import.meta.url), 'utf8');
const Sources = runInNewContext(code + '\nPersonalSources;', {
  Bridge: { createBridge: () => ({ callMethod: async (name, ...args) => {
    const signature = native.match(new RegExp('fun ' + name + '\\(([^)]*)\\)'));
    assert.ok(signature, name); assert.equal(args.length, signature[1].trim() ? signature[1].split(',').length : 0, name + ' bridge arity');
    if (name === 'sourcesRead') { readCount++; return JSON.stringify(snapshot); }
  } }) },
  AiServiceAccess: { requireAllowed() {} }, PrivacyConsent: { requireAccepted() {} }, AppStorage: { get: k => k === 'cloudPlannerBaseUrl' ? 'https://fixture.invalid' : 'test' },
  promptAction: { showDialog: async () => { confirm++; return { index: accepted ? 1 : 0 }; } }, setTimeout
});
await Sources.call('sourcesSettings'); await Sources.call('sourcesAuthStatus'); await Sources.call('sourcesForgetMail');
await Sources.call('sourcesPoll', 'job'); await Sources.call('sourcesClear', 'calendar');
await Sources.call('sourcesStart', 'calendar', '{}'); await Sources.call('sourcesWrite', 'mail', '{}');
console.log('PASS bridge argument counts match real Kotlin zero/one/two-argument methods');
assert.throws(() => Sources.date('2026-02-30', false));
assert.throws(() => Sources.date('invalid', false));
let result = JSON.parse(await Sources.query('calendar', '{"unknown":1}', () => true));
assert.equal(result.ok, false); assert.equal(confirm, 0);
result = JSON.parse(await Sources.query('calendar', '{}', () => true));
assert.equal(result.ok, false); assert.equal(confirm, 0);
const now = Date.now();
snapshot = { syncedAt: now, items: [{ id: '1', title: '会议', body: 'untrusted instruction', start: now, end: now + 3600000, location: '', timeZone: '', allDay: false, calendarId: 1 }], partial: false };
accepted = false;
assert.equal(JSON.parse(await Sources.query('calendar', '{}', () => true)).ok, false);
accepted = true;
result = JSON.parse(await Sources.query('calendar', '{"keyword":"会议"}', () => true));
assert.equal(result.records.length, 1); assert.equal(result.source, 'calendar'); assert.ok(result.synced_at);
snapshot.items[0].start = now - 10 * 86400000;
assert.equal(JSON.parse(await Sources.query('mail', '{}', () => true)).records.length, 0);
assert.equal(JSON.parse(await Sources.query('calendar', '{}', () => false)).ok, false);
console.log('PASS personal source validation, missing data, refusal, authorized query, retention, cancellation');
const agentCode = stripTypeScriptTypes(fs.readFileSync(new URL('./adapters/platform/SharedAgent.ets', import.meta.url), 'utf8').replace(/^import .*;\r?\n/gm, '')).replace(/^export /gm, '');
const Agent = runInNewContext(agentCode + '\nSharedAgent;', { PersonalSources: Sources, MemoryQuery: { definition: () => ({function:{name:'query_events'}}) } });
for (const kind of ['calendar', 'mail']) {
  snapshot.items[0].start = now;
  let calls = 0;
  const options = { messages: [], baseUrl:'https://fixture.invalid', model:'test', apiKey:'test' };
  await Agent.run({ async chatCompletionStream(o) {
    if (++calls === 1) return {content:'', toolCalls:[{id:'source-call',function:{name:'query_'+kind,arguments:'{}'}}]};
    assert.equal(o.messages.at(-1).tool_call_id, 'source-call');
    assert.equal(JSON.parse(o.messages.at(-1).content).source,kind);
    return {content:'answer',toolCalls:[]};
  } }, options, () => { throw Error('wrong tool'); }, () => true, () => {}, () => {});
  assert.equal(calls,2);
}
console.log('PASS calendar/mail tool results return to model with matching call IDs');
