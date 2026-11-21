import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';
import { stripTypeScriptTypes } from 'node:module';
import { runInNewContext } from 'node:vm';

const here = path.dirname(fileURLToPath(import.meta.url));
const repo = path.resolve(here, '..');
const project = path.join(repo, '.arkui-x-validation/application/project');
const read = file => fs.readFileSync(file, 'utf8');
let passed = 0;
const check = (name, task) => { task(); console.log('PASS ' + name); passed++; };
check('all upstream inputs match their pinned Git blobs', () => {
  const locks = ['experiments/arkui-x/ui-upstream-lock.json', 'shared-frontend/upstream-lock.json'];
  for (const lockFile of locks) {
    const lock = JSON.parse(read(path.join(repo, lockFile)));
    for (const file of lock.files) {
      const bytes = fs.readFileSync(path.join(repo, '.arkui-x-validation/reference', file.path));
      const hash = createHash('sha1').update(`blob ${bytes.length}\0`).update(bytes).digest('hex');
      assert.equal(hash, file.gitBlob, file.path);
    }
  }
});
// 路由数跟随 main_pages.json：新增独立页面（如日历/邮箱分析）后不需要改检查脚本。
check('every declared route exists and was included in staging', () => {
  const routes = JSON.parse(read(path.join(project, 'entry/src/main/resources/base/profile/main_pages.json'))).src;
  const manifest = JSON.parse(read(path.join(project, 'staged-sources.json')));
  for (const route of routes) {
    assert.ok(fs.existsSync(path.join(project, 'entry/src/main/ets', route + '.ets')), route);
    assert.ok(manifest.files.some(file => file.path === route + '.ets'), route);
  }
});

check('personal privacy keeps the upstream cards and opens the Android privacy policy', () => {
  const settingsPath = 'entry/src/main/ets/pages/settings/SettingsCenterPage.ets';
  const upstream = read(path.join(repo, '.arkui-x-validation/reference', settingsPath));
  const generated = read(path.join(project, settingsPath));
  const section = source => source.match(/  @Builder PrivacySection\(\) \{[^]*?\n  \}/)[0];
  assert.equal(section(generated), section(upstream));
  assert.ok(generated.includes('AndroidPrivacyPolicy.open(this.getUIContext())'));
});

// Exercise the buttons through the same handler the generated settings page opens.
const policySource = read(path.join(here, 'adapters/platform/AndroidPrivacyPolicy.ets'))
  .replace(/^import .*;\r?\n/gm, '').replace('export class', 'class');
let granted = false, accepted = false, saved = false, dialog;
const policy = runInNewContext(stripTypeScriptTypes(policySource) + '\nAndroidPrivacyPolicy;', {
  AiServiceAccess: { isAllowed: () => granted }, PRIVACY_DECLARATION: 'policy',
  PrivacyConsent: { isAccepted: () => accepted, choose: async value => { accepted = value; } },
  HUAWEI_ACCOUNT_SESSION_KEY: 'androidCloudAccessGranted',
  AppStorage: { setOrCreate: (key, value) => { granted = value; } },
  SettingsStore: { save: () => { saved = granted; } }, showToastSafely() {}
});
const ui = { showAlertDialog: value => { dialog = value; } };
for (const initial of [false, true]) {
  accepted = initial; granted = initial; saved = initial;
  policy.open(ui);
  dialog.primaryButton.action();
  assert.equal(granted, initial);
  assert.equal(accepted, initial);
  policy.open(ui);
  assert.equal(dialog.secondaryButton.value, initial ? '撤回云端处理授权' : '同意云端处理');
  dialog.secondaryButton.action();
  await new Promise(resolve => setImmediate(resolve));
  check(initial ? 'revocation clears both stored choices' : 'explicit consent enables cloud processing', () => {
    assert.equal(accepted, !initial);
    assert.equal(granted, !initial);
    assert.equal(saved, !initial);
  });
}

// Execute only the authored settings repository, with in-memory platform ports.
// This checks persistence semantics, not ArkData or Android compatibility.
const keysSource = read(path.join(project, 'entry/src/main/ets/platform/SettingsKeys.ets'));
const keys = Object.fromEntries([...keysSource.matchAll(/export const (\w+): string\[\] = (\[[^\n]*\]);/g)]
  .map(match => [match[1], JSON.parse(match[2])]));
const stored = new Map();
const memory = new Map();
let flushes = 0;
let failFlush = false;
const platformStore = {
  hasSync: key => stored.has(key), getSync: (key, fallback) => stored.get(key) ?? fallback,
  putSync: (key, value) => stored.set(key, value),
  flushSync: () => { flushes++; if (failFlush) throw new Error('disk unavailable'); }
};
const source = read(path.join(here, 'adapters/platform/SettingsStore.ets'))
  .replace(/^import .*;\r?\n/gm, '').replace('export class SettingsStore', 'class SettingsStore');
const SettingsStore = runInNewContext(stripTypeScriptTypes(source) + '\nSettingsStore;', {
  ...keys, preferences: { getPreferencesSync: () => platformStore },
  AppStorage: { get: key => memory.get(key), setOrCreate: (key, value) => memory.set(key, value) },
  setTimeout, clearTimeout, showToastSafely: () => {}
});
check('legacy settings fill an empty host once and preserve its current permissions and service settings', () => {
  assert.equal(SettingsStore.needsLegacyImport({}), true);
  SettingsStore.importLegacy({}, JSON.stringify({ cloudPlannerModel: 'legacy', autoConfirm: 'false',
    androidCloudAccessGranted: 'true', unknown: 'discard' }));
  assert.equal(stored.get('cloudPlannerModel'), 'legacy');
  assert.equal(stored.get('autoConfirm'), false);
  assert.equal(stored.has('androidCloudAccessGranted'), false);
  assert.equal(stored.has('unknown'), false);
  assert.equal(SettingsStore.needsLegacyImport({}), false);
  stored.clear();
  stored.set('cloudPlannerBaseUrl', 'https://existing.invalid');
  assert.equal(SettingsStore.needsLegacyImport({}), false);
  stored.clear();
});
check('restore preserves defaults and excludes unknown preference keys', () => {
  memory.set('cloudPlannerModel', 'default-model');
  stored.set('cloudPlannerBaseUrl', 'https://fixture.invalid');
  stored.set('androidCloudAccessGranted', true);
  stored.set('unrecognized-key', 'must-not-import');
  SettingsStore.initialize({});
  assert.equal(memory.get('cloudPlannerModel'), 'default-model');
  assert.equal(memory.get('cloudPlannerBaseUrl'), 'https://fixture.invalid');
  assert.equal(memory.get('androidCloudAccessGranted'), true);
  assert.equal(memory.has('unrecognized-key'), false);
});
check('edited strings and authorization flags survive a repository reload', () => {
  memory.set('cloudPlannerModel', 'unit-test-model');
  memory.set('androidCloudAccessGranted', false);
  SettingsStore.save();
  memory.clear(); SettingsStore.initialize({});
  assert.equal(memory.get('cloudPlannerModel'), 'unit-test-model');
  assert.equal(memory.get('androidCloudAccessGranted'), false);
});
check('failed writes stay retryable instead of reporting an unchanged saved state', () => {
  memory.set('cloudPlannerModel', 'retry-model'); failFlush = true;
  assert.throws(() => SettingsStore.save(), /disk unavailable/);
  const failedFlushes = flushes;
  failFlush = false; SettingsStore.save();
  assert.equal(flushes, failedFlushes + 1);
  memory.clear(); SettingsStore.initialize({});
  assert.equal(memory.get('cloudPlannerModel'), 'retry-model');
});
const budgetSource = read(path.join(repo, '.arkui-x-validation/reference/entry/src/main/ets/pages/chat/ChatContextBudget.ets'))
  .replace(/^import\s+[^;]*;\s*/gm, '');
const budgetJs = stripTypeScriptTypes(budgetSource).replace(/^export /gm, '');
const budget = runInNewContext(budgetJs + '\n({buildChatContextBudgetSettings, shouldCompressCloudContext});');
check('context limits normalize saved settings and distinguish short from oversized histories', () => {
  const settings = budget.buildChatContextBudgetSettings('999', '999');
  assert.equal(settings.thresholdPercent, 95);
  assert.equal(settings.keepRecentTurns, 20);
  assert.equal(budget.shouldCompressCloudContext([{ role: 'user', content: 'hello' }], settings), false);
  const long = [{ role: 'user', content: '中文上下文'.repeat(200000) }];
  assert.equal(budget.shouldCompressCloudContext(long, settings), true);
  assert.equal(long.length, 1);
  assert.equal(long[0].content.length, '中文上下文'.length * 200000);
});
console.log(JSON.stringify({ passed, scope: 'Host persistence contracts and source/route checks only' }));
