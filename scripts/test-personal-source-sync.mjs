import fs from 'node:fs';
import assert from 'node:assert/strict';
import { stripTypeScriptTypes } from 'node:module';
import { runInNewContext } from 'node:vm';
const read = path => fs.readFileSync(new URL('../shared-frontend/adapters/' + path, import.meta.url), 'utf8');
const compile = source => stripTypeScriptTypes(source.replace(/^import .*;\r?\n/gm, '')).replace(/^export /gm, '');
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
await assert.rejects(service.run('calendar',()=>{}), /选择日历/);
assert.equal(writes,1); assert.equal(Sources.busy,false);
console.log('PASS shared sync commits only complete reads, honours stored limit, cancellation, source selection and busy ownership');
