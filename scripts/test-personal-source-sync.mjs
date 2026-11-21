import fs from 'node:fs';
import assert from 'node:assert/strict';
import { stripTypeScriptTypes } from 'node:module';
import { runInNewContext } from 'node:vm';
const read = path => fs.readFileSync(new URL('../shared-frontend/adapters/' + path, import.meta.url), 'utf8');
const compile = source => stripTypeScriptTypes(source.replace(/^\uFEFF/, '').replace(/^import .*;\r?\n/gm, '')).replace(/^export /gm, '');
const util = {
  Base64Helper: class { decodeSync(value) { return new Uint8Array(Buffer.from(value, 'base64')); } },
  TextDecoder: class { constructor(name) { this.decoder = new TextDecoder(name); } decodeToString(bytes) { return this.decoder.decode(bytes); } }
};
const now = new Date(); now.setHours(12, 0, 0, 0);
const stamp = `${now.getUTCDate()}-${['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec'][now.getUTCMonth()]}-${now.getUTCFullYear()} ${String(now.getUTCHours()).padStart(2,'0')}:00:00 +0000`;
let bodies = 0, fail = false, writes = 0, cancelAfterRead = false, cancelDuringSettings = false, opens = 0;
const options = { calendarEnabled: true, historyDays: 30, selected: [1], maxMessages: 20 };
const config = { host: 'imap.example.invalid', port: 993, username: 'fixture@example.invalid' };
const Sources = {
  busy: false, epoch: 0,
  async cancel() { this.epoch++; },
  async options() { return options; },
  empty() { return { items: [], sources: [], selected: [], syncedAt: 0 }; },
  async call(name) { if (name === 'sourcesSettings') { if (cancelDuringSettings) this.epoch++; return JSON.stringify(config); } if (name === 'sourcesWrite') writes++; },
  async operation(name, command) {
    if (name === 'mailOpen') { opens++; if (fail) throw Error('connection fixture failed'); return '{}'; }
    if (name === 'calendar') { if (cancelAfterRead) this.epoch++; return JSON.stringify({items: [], selected:[1], sources:[], syncedAt:1}); }
    if (command === '@LOGIN') return '[]';
    if (command === 'CAPABILITY') return JSON.stringify(['* CAPABILITY IMAP4rev1']);
    if (command.startsWith('EXAMINE')) return JSON.stringify(['* OK [UIDVALIDITY 123]']);
    if (command.startsWith('UID SEARCH')) return JSON.stringify(['* SEARCH ' + Array.from({length:21},(_,i)=>i+1).join(' ')]);
    if (command.includes('BODYSTRUCTURE')) return JSON.stringify(Array.from({length:21},(_,i)=> {
      const uid = i+1, header = `From: fixture@example.invalid\r\nSubject: fixture ${uid}\r\n`;
      return `* ${uid} FETCH (UID ${uid} INTERNALDATE "${stamp}" BODYSTRUCTURE ("TEXT" "PLAIN" ("CHARSET" "UTF-8") NIL NIL "7BIT" 12 1) BODY[HEADER.FIELDS (FROM SUBJECT DATE)] {${header.length}}\r\n${header})`;
    }));
    const uid = Number(command.match(/^UID FETCH (\d+)/)?.[1]); assert.ok(uid); bodies++;
    return JSON.stringify([`* ${uid} FETCH (UID ${uid} BODY[1]<0> {12}\r\nfixture body)`]);
  }
};
const modules = ['utils/mail/MailTypes.ets','utils/mail/MailImapCodec.ets','utils/mail/MailImapClient.ets','platform/PersonalSourceSync.ets'].map(read).map(compile).join('\n');
const { MailImapClient, PersonalSourceSync, defaultMailSettings, emptyMailSnapshot } = runInNewContext(modules + '\n({MailImapClient,PersonalSourceSync,defaultMailSettings,emptyMailSnapshot});', {
  PersonalSources: Sources, util, AppLogger: { info() {} }, Bridge: { createBridge: () => ({callMethod: async () => true}) }
});
for (const limit of [20, 50, 100]) {
  bodies = 0;
  const settings = {...defaultMailSettings(),...config,maxMessages:limit};
  const result = await new MailImapClient().readToday(settings,'fixture','local',emptyMailSnapshot(),now.getTime(),3);
  assert.equal(result.items.length, Math.min(limit,21)); assert.equal(bodies, Math.min(limit,21));
  assert.equal(result.partial, limit === 20); assert.ok(result.items.every(item => item.body === 'fixture body'));
}
console.log('PASS real IMAP reader daily cap 20/50/100, bounded body fetch and partial coverage');
const service = new PersonalSourceSync();
const beforeDisabled = opens;
config.mailEnabled = false;
await assert.rejects(service.run('mail', () => {}), /开启邮件提醒/);
assert.equal(opens, beforeDisabled);
config.mailEnabled = true;
const saved = await service.run('mail', () => {});
assert.equal(saved.items.length,20); assert.equal(writes,1); assert.equal(Sources.busy,false);
fail = true;
await assert.rejects(service.run('mail',()=>{}), /connection fixture failed/);
assert.equal(writes,1); assert.equal(Sources.busy,false);
fail = false; cancelDuringSettings = true; const previousOpens = opens;
await assert.rejects(service.run('mail',()=>{}), /取消/);
assert.equal(opens,previousOpens); assert.equal(writes,1); assert.equal(Sources.busy,false);
cancelDuringSettings = false; cancelAfterRead = true;
await assert.rejects(service.run('calendar',()=>{}), /取消/);
assert.equal(writes,1); assert.equal(Sources.busy,false);
Sources.busy = true;
await assert.rejects(service.run('mail',()=>{}), /正在进行/);
assert.equal(Sources.busy,true); Sources.busy = false;
options.selected = [];
await assert.rejects(service.run('calendar',()=>{}), /开启读取手机系统日历/);
assert.equal(writes,1); assert.equal(Sources.busy,false);
console.log('PASS shared sync commits only complete reads, honours stored limit, cancellation, source selection and busy ownership');
const page = read('pages/settings/MailSourcesPage.ets');
const pageLogic = page.slice(page.indexOf('struct MailSourcesPage'), page.indexOf('  @Builder'))
  .replace('struct MailSourcesPage', 'class MailSourcesPage').replace(/@State /g, '') + '}';
const providers = runInNewContext(compile(read('platform/MailProviders.ets')) + '\nMAIL_PROVIDERS');
assert.deepEqual(Array.from(providers.filter(p => p.host === 'mail.sjtu.edu.cn'), p => p.mode), ['sjtu_imap', 'jaccount']);
for (const host of ['imap.exmail.qq.com', 'imap.qiye.aliyun.com', 'imap.mail.yahoo.com']) {
  assert.equal(providers.some(p => p.host === host), false, host);
}

let savedConfig = { ...config, mode: 'password', host: 'imap.mail.yahoo.com' };
let retainedSnapshot = { items: [{id: 'fixture'}], syncedAt: 123 };
let savedPreferences;
let authCalls = [];
const { MailSourcesPage } = runInNewContext(stripTypeScriptTypes(pageLogic) + '\n({MailSourcesPage})', {
  MAIL_PROVIDERS: providers, Scroller: class {}, PersonalSourceSync: class {}, CloudModelClient: class {},
  PersonalSources: {
    busy: false, empty: () => ({ items: [], syncedAt: 0 }), snapshot: async () => retainedSnapshot,
    options: async () => ({ maxMessages: 50 }),
    async call(name, kind, raw) {
      if (name === 'sourcesSettings') return JSON.stringify(savedConfig);
      if (name === 'sourcesSaveOptions') {
        assert.equal(kind, 'mail');
        savedPreferences = JSON.parse(raw);
        return '{"ok":true}';
      }
      if (name === 'sourcesAuthOpen') { authCalls.push(name); return true; }
      assert.equal(name, 'sourcesAuthStatus');
      savedConfig = { host: 'mail.sjtu.edu.cn', mode: 'jaccount', port: 993, username: 'fixture@sjtu.edu.cn' };
      return '{"state":"done","message":"授权完成","code":"","uri":""}';
    }
  },
  Bridge: { createBridge: () => ({ async callMethod(name, account, client, redirect) {
    authCalls.push(name);
    assert.equal(name, 'sourcesJAccountStart');
    assert.equal(account, 'fixture');
    assert.equal(client, 'fixture-client');
    assert.equal(redirect, 'clawmate://jaccount');
    return '{"ok":true}';
  } }) }
});
const view = new MailSourcesPage();
for (const host of ['imap.exmail.qq.com', 'imap.qiye.aliyun.com', 'imap.mail.yahoo.com']) {
  savedConfig.host = host; savedConfig.port = 1993;
  await view.reload();
  assert.equal(providers[view.providerIndex].host, '');
  assert.equal(view.host, host);
  assert.equal(view.port, '1993');
  await view.save(false);
  assert.equal(view.status, '邮箱设置已保存');
  assert.equal(view.mail.syncedAt, 123);
}
savedPreferences = undefined;
view.chooseProvider(providers.findIndex(p => p.mode === 'jaccount'));
view.username = 'fixture'; view.clientId = 'fixture-client'; view.jaccountSecret = 'synthetic-secret';
view.mailEnabled = true; view.aiAllowed = true; view.interested = '会议'; view.ignored = '促销';
await view.save(true);
assert.match(view.status, /授权/);
assert.equal(savedPreferences, undefined);
assert.notEqual(view.snapshotAccount, view.accountKey());
retainedSnapshot = { items: [], syncedAt: 0 };
await view.authorize();
assert.deepEqual(authCalls, ['sourcesJAccountStart', 'sourcesAuthOpen']);
assert.equal(view.username, 'fixture@sjtu.edu.cn');
assert.equal(view.jaccountSecret, ''); assert.equal(view.busy, false);
assert.equal(view.mail.syncedAt, 0);
retainedSnapshot = { items: [{id: 'same-mailbox'}], syncedAt: 456 };
view.username = 'fixture'; view.jaccountSecret = 'synthetic-secret';
await view.authorize();
assert.equal(view.mail, retainedSnapshot);
assert.equal(view.snapshotAccount, view.accountKey());
assert.deepEqual(savedPreferences, {
  maxMessages: 50, mailEnabled: true, mailAiAllowed: true, mailInterested: '会议', mailIgnored: '促销'
});
console.log('PASS removed presets preserve custom IMAP accounts, SJTU uses browser jAccount, and filter preferences persist');
