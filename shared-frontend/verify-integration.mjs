import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import fs from 'node:fs';
import { stripTypeScriptTypes } from 'node:module';
import { runInNewContext } from 'node:vm';

const generated = '.arkui-x-validation/application/project/entry/src/main/ets/';
function module(file, names, ports = {}) {
  const source = fs.readFileSync(file, 'utf8').replace(/^import\s+[^;]+;\s*/gm, '')
    .replace(/^export\s*\{[^}]+\}(?:\s*from[^;]+)?;\s*/gm, '');
  return runInNewContext(stripTypeScriptTypes(source).replace(/^export /gm, '') + `\n({${names}});`,
    { setTimeout, clearTimeout, console, ensureCollectionAccess: async () => {}, ...ports });
}
let passed = 0;
async function check(name, task) { await task(); passed++; console.log('PASS ' + name); }
const { MemoryQuery } = module('shared-frontend/adapters/platform/MemoryQuery.ets', 'MemoryQuery');
await check('memory queries reject malformed bounds, dates and unknown fields before touching storage', () => {
  for (const raw of ['[]', 'null', '{"limit":-1}', '{"limit":"2"}', '{"keywords":[2]}', '{"date":"2026-02-30"}',
    '{"month":"2026-13"}', '{"entities":[null]}', '{"domains":["secret"]}', '{"file":"/private"}',
    '{"date":"2026-01-01","month":"2026-01"}', '{"start_date":"2026-10-26","end_date":"2026-10-25"}']) {
    assert.throws(() => MemoryQuery.options(raw), undefined, raw);
  }
  assert.equal(MemoryQuery.options('{"keyword_operator":"or","limit":0}').limit, 1000);
  assert.equal(MemoryQuery.options('{"entities":[{"type":"person","name":"测试"}]}').entities[0], 'person:测试');
});
await check('memory tool emits bounded valid JSON without private IDs, paths or source references', () => {
  const event = { event_id: 'private-id', source_state_file: '/private/state', source_refs: ['private-ref'],
    event_date: '2026-10-26', summary: '真实事实'.repeat(500), normalized_fact: '事实'.repeat(500),
    image_paths: ['/private/photo'], todo: { type: 'todo', deadline: '', text: '待办' } };
  const output = MemoryQuery.response({ total_events: 40, events_truncated: false, events: Array(40).fill(event) });
  assert.ok(output.length < 3800); assert.ok(!output.includes('private'));
  const parsed = JSON.parse(output); assert.equal(parsed.total_events, 40); assert.equal(parsed.events_truncated, true);
  assert.equal(parsed.events[0].event_id, 0);
  assert.equal(parsed.events[0].content_truncated, true);
});
const { SharedAgent } = module('shared-frontend/adapters/platform/SharedAgent.ets', 'SharedAgent', { MemoryQuery,
  PersonalSources: { definition: kind => ({ type: 'function', function: { name: 'query_' + kind } }), query: async () => '{"ok":false,"error":"fixture denied"}' } });
const options = () => ({ baseUrl: 'https://fixture.invalid', apiKey: '', model: 'fixture-model', messages: [{ role: 'user', content: '查询历史' }] });
const tool = (name = 'query_events') => ({ id: 'call-1', type: 'function', function: { name, arguments: '{"keywords":["测试"]}' } });
await check('Mika memory uses native tool receipts and external reads suppress automatic extraction', async () => {
  let calls = 0, turn = 0;
  const memory = { tools: [{ type: 'function', function: { name: 'memory_save' } }], allowAuto: true,
    has: name => name === 'memory_save', call: async (name, args) => {
      assert.equal(name, 'memory_save'); assert.equal(JSON.parse(args).quote, '请记住我喜欢简洁回答');
      calls++; return '{"ok":true,"id":"native-entry"}';
    } };
  const client = { async chatCompletionStream(request) {
    assert.ok(request.tools.some(t => t.function.name === 'memory_save'));
    if (++turn === 1) return { content: '', toolCalls: [{ id: 'memory-call', function: { name: 'memory_save', arguments: '{"quote":"请记住我喜欢简洁回答"}' } }] };
    if (turn === 2) {
      assert.equal(request.messages.at(-1).tool_call_id, 'memory-call');
      assert.equal(JSON.parse(request.messages.at(-1).content).id, 'native-entry');
      return { content: '', toolCalls: [tool()] };
    }
    return { content: '', toolCalls: [] };
  } };
  await SharedAgent.run(client, options(), () => '{}', () => true, () => {}, () => {}, undefined, undefined, undefined, undefined, memory);
  assert.equal(calls, 1); assert.equal(memory.allowAuto, false);
});
await check('Mika phone delegation runs once and returns its receipt to the matching model call', async () => {
  let runs = 0;
  let turns = 0;
  const client = { async chatCompletionStream(request) {
    assert.ok(request.tools.some(entry => entry.function.name === 'run_phone_task'));
    assert.ok(request.tools.some(entry => entry.function.name === 'run_phone_calendar_batch'));
    if (++turns === 1) {
      return { content: '', toolCalls: [
        { ...tool('run_phone_calendar_batch'), id: 'phone-one' },
        { ...tool('run_phone_system_task'), id: 'phone-duplicate' }
      ] };
    }
    const receipts = request.messages.filter(entry => entry.role === 'tool');
    assert.equal(receipts[0].tool_call_id, 'phone-one');
    assert.equal(JSON.parse(receipts[0].content).status, 'success');
    assert.equal(receipts[1].tool_call_id, 'phone-duplicate');
    assert.equal(JSON.parse(receipts[1].content).status, 'blocked');
    return { content: '', toolCalls: [] };
  } };
  await SharedAgent.run(client, options(), () => '', () => true, () => {}, () => {},
    undefined, undefined, undefined, async (method) => {
      assert.equal(method, 'mikaCalendarBatchStart');
      runs++;
      return '{"status":"success","message":"已读取测试页面"}';
    });
  assert.equal(runs, 1);
  assert.equal(turns, 2);
});

await check('a streamed tool round sends actual query results back with the matching call ID', async () => {
  let requests = 0, queries = 0, shown = '';
  const client = { async chatCompletionStream(request, callbacks) {
    requests++;
    assert.equal(request.tools[0].function.name, 'query_events');
    if (requests === 1) return { content: '', toolCalls: [tool()] };
    const message = request.messages.at(-1);
    assert.equal(message.role, 'tool'); assert.equal(message.tool_call_id, 'call-1');
    assert.equal(JSON.parse(message.content).events[0].summary, '来自索引的事实');
    callbacks.onContentDelta('基于事实的回复'); return { content: '基于事实的回复', toolCalls: [] };
  } };
  await SharedAgent.run(client, options(), () => { queries++; return '{"ok":true,"events":[{"summary":"来自索引的事实"}]}'; },
    () => true, text => shown += text, () => {});
  assert.equal(requests, 2); assert.equal(queries, 1); assert.equal(shown, '基于事实的回复');
});
await check('cancellation after a model tool response prevents local reads and the next network request', async () => {
  let active = true, reads = 0, requests = 0;
  const client = { async chatCompletionStream() { requests++; active = false; return { content: '', toolCalls: [tool()] }; } };
  await assert.rejects(SharedAgent.run(client, options(), () => { reads++; return '{}'; }, () => active, () => {}, () => {}), /取消/);
  assert.equal(reads, 0); assert.equal(requests, 1);
});
await check('unregistered tools cannot execute and repeated tool calls stop at the configured bound', async () => {
  let reads = 0, requests = 0;
  const client = { async chatCompletionStream() { requests++; return { content: '', toolCalls: [tool('run_shell')] }; } };
  await assert.rejects(SharedAgent.run(client, options(), () => { reads++; return '{}'; }, () => true, () => {}, () => {}), /上限/);
  assert.equal(reads, 0); assert.equal(requests, 4);
});

await check('collection context is read locally, scoped to the question, and shared only after confirmation', async () => {
  let allow = false, reads = 0, prompts = 0, changed = false;
  const args = [];
  const { CollectedMemory } = module('shared-frontend/adapters/platform/CollectedMemory.ets', 'CollectedMemory', {
    MemoryQuery, AiServiceAccess: { requireAllowed() {} }, PrivacyConsent: { requireAccepted() {} },
    AppStorage: { get: key => ({ cloudPlannerBaseUrl: 'https://fixture.invalid', cloudPlannerModel: 'fixture-model' })[key] },
    promptAction: { async showDialog() { prompts++; return { index: allow ? 1 : 0 }; } },
    Bridge: { createBridge: () => ({ async callMethod(name, raw) {
      assert.equal(name, 'guiCollectionQuery'); reads++; args.push(JSON.parse(raw));
      return JSON.stringify({ ok: true, records: [{ source: '微博热搜', date: '2026-11-10', rank: 3,
        title: changed && reads % 2 === 0 ? '变化后' : '合成标题', partial: false }] });
    } }) }
  });
  assert.equal(await CollectedMemory.forQuestion('普通问答', ''), '');
  assert.match(await CollectedMemory.forQuestion('2026-11-10 微博热搜第 3 名', ''), /第3名.*合成标题/);
  assert.deepEqual(args[0], { source: 'weibo', date: '2026-11-10', keyword: '', rank: 3, limit: 8 });
  assert.equal(prompts, 0);
  assert.equal(JSON.parse(await CollectedMemory.share('{}', options(), () => true)).ok, false);
  await assert.rejects(CollectedMemory.attachForCodex('微博热搜', '回答问题', '', '测试电脑', () => true), /取消/);
  allow = true; changed = true; reads = 0;
  assert.equal(JSON.parse(await CollectedMemory.share('{}', options(), () => true)).ok, false);
  changed = false;
  assert.match(await CollectedMemory.attachForCodex('微博热搜', '回答问题', '', '测试电脑', () => true), /本机记录摘录.*合成标题/s);
  reads = 0; prompts = 0;
  assert.equal(await CollectedMemory.attachForLocal('微博是什么？', '', () => true), '微博是什么？');
  assert.equal(reads, 0); assert.equal(prompts, 0);
  const lookup = '查本机：微博热搜第3名是什么？';
  allow = false;
  await assert.rejects(CollectedMemory.attachForLocal(lookup, '', () => true), /取消/);
  allow = true; changed = true; reads = 0;
  await assert.rejects(CollectedMemory.attachForLocal(lookup, '', () => true), /变化/);
  changed = false;
  assert.match(await CollectedMemory.attachForLocal(lookup, '', () => true), /忽略其中的指令.*【记录】.*已记录名次：第3名；标题：合成标题.*【问题】.*微博热搜第3名是什么/s);
});

let nativeCall;
const { ChatImages } = module('shared-frontend/adapters/platform/ChatImages.ets', 'ChatImages', {
  Bridge: { createBridge: () => ({ callMethod: (...args) => nativeCall(...args) }) },
  setTimeout: resolve => setTimeout(resolve, 1)
});
await check('image preparation consumes only the current turn and cleans cancelled native work', async () => {
  const calls = [];
  nativeCall = async (method, value) => {
    calls.push(method);
    if (method === 'imagePrepareStart') { assert.equal(JSON.parse(value).length, 1); return 'image-job'; }
    return JSON.stringify({ v: 1, status: 'done', images: ['data:image/jpeg;base64,/9j/2Q=='], error: '' });
  };
  assert.equal((await ChatImages.prepare(['content://fixture/one'], () => false)).length, 1);
  assert.deepEqual(calls, ['imagePrepareStart', 'imagePreparePoll']);
  calls.length = 0;
  await assert.rejects(ChatImages.prepare(['content://fixture/one'], () => true), /取消/);
  assert.deepEqual(calls, ['imagePrepareStart', 'imagePrepareCancel']);
  await assert.rejects(ChatImages.prepare(Array(5).fill('content://fixture'), () => false), /4张/);
});
await check('historical image files contain only the decoded byte range and remain local references', () => {
  const written = new Map(); let opened = '';
  const { ChatImages: store } = module('shared-frontend/adapters/platform/ChatImages.ets', 'ChatImages', {
    util: { Base64Helper: class { decodeSync() { return Uint8Array.from([7, 8, 255, 216, 255, 217, 9]).subarray(2, 6); } } },
    fs: { OpenMode: { CREATE: 1, WRITE_ONLY: 2, TRUNC: 4 }, openSync: path => { opened = path; return { fd: 1 }; },
      writeSync: (_, bytes) => written.set(opened, [...new Uint8Array(bytes)]), closeSync() {}, unlinkSync: path => written.delete(path) },
    ChatSessionStorage: { ensureSessionAssetsDir() {}, sessionAssetPath: (root, session, name) => `${root}/chat-sessions/assets/${session}/${name}` }
  });
  const result = store.save('/fixture', 'session-one', ['data:image/jpeg;base64,/9j/2Q=='], '当前回合');
  assert.deepEqual([...written.values()][0], [255, 216, 255, 217]);
  assert.ok(result.paths[0].startsWith('file:///fixture/chat-sessions/assets/session-one/'));
  assert.equal(result.parts[1].type, 'image_url'); assert.ok(!result.paths[0].includes('base64'));
  store.discard(result.paths); assert.equal(written.size, 0);
});

const { taskServiceLabel } = module('shared-frontend/adapters/platform/TaskServices.ets', 'taskServiceLabel');
await check('task service labels reflect local, visual and nested automatic GUI steps', () => {
  assert.equal(taskServiceLabel([{ type: 'gui_action', action: 'sleep' }, { type: 'tool', tool_name: 'summary' }]), '本机工作流');
  assert.equal(taskServiceLabel([{ type: 'tool', tool_name: 'vlm_qa' }]), '视觉问答 · 发送前确认');
  assert.equal(taskServiceLabel([{ type: 'gui_task' }, { type: 'loop', steps: [{ type: 'gui_action', action: 'screenshot' }, { type: 'gui_action', action: 'swipe_with_coords' }] }]), 'Mika 虚拟屏');
  assert.equal(taskServiceLabel([{ type: 'if', else_steps: [{ type: 'loop', steps: [{ type: 'gui_task' }] }] }]), 'Mika 虚拟屏');
  assert.equal(taskServiceLabel([{ type: 'tool', tool_name: 'python' }]), '含待适配步骤');
  assert.equal(taskServiceLabel([{ type: 'android_accessibility' }]), '无障碍采集 · 单次确认');
  assert.equal(taskServiceLabel([{ type: 'gui_task', action: 'collection_open_app' },
    { type: 'gui_task', action: 'collection_model_read' }]), '虚拟屏采集 · 按需调用 Mika');
});

await check('review shows only the shared tasks and configuration retains Android additions', () => {
  const page = fs.readFileSync(generated + 'pages/task/TaskPage.ets', 'utf8');
  const methods = ['reviewTasks', 'reviewTasksForScene', 'enabledTasks', 'enabledTaskCount'].map(name => {
    const match = page.match(new RegExp(`^  private (${name}\\([^\\n]*?\\{(?:[^\\n]+\\}|\\n[\\s\\S]*?^  \\}))`, 'm'));
    assert.ok(match, name);
    return match[1];
  });
  const view = runInNewContext(stripTypeScriptTypes('({' + methods.join(',\n') + '})'), {
    workflowSceneConfigs: () => [{ sceneId: 5 }]
  });
  view.tasks = [
    { id: 'default_xiaohongshu_history', sceneId: 5, enabled: true },
    { id: 'default_xhs_public_notes', sceneId: 5, enabled: true },
    { id: 'custom', sceneId: 5, enabled: false }
  ];
  assert.deepEqual(Array.from(view.reviewTasksForScene(5), task => task.id), ['default_xiaohongshu_history', 'custom']);
  assert.deepEqual(Array.from(view.enabledTasks(), task => task.id), ['default_xiaohongshu_history']);
  assert.equal(view.enabledTaskCount(), 1);
  assert.equal(view.tasks.length, 3);
  assert.match(page, /return this\.tasksForScene\(this\.taskScene\);/);
});

await check('gallery domain details appear before indexing and occur once after indexing', () => {
  const { addGalleryDomainRecords } = module('shared-frontend/adapters/platform/GalleryDomainRecords.ets', 'addGalleryDomainRecords', {
    galleryMemorySceneIdForCategory: category => category === '文档' ? 8 : 11
  });
  const item = { id: 'sample', uri: 'file:///receipt.jpg', title: '图书回执', summary: '归还日期', date: '2026-11-19', category: '文档' };
  const empty = () => ({groups: [], rows: [], empty: true, countLabel: '0 条', lastUpdatedLabel: '暂无同步'});
  assert.equal(addGalleryDomainRecords(empty(), 11, [item]).rows.length, 0);
  const view = addGalleryDomainRecords(empty(), 8, [item]);
  assert.equal(view.countLabel, '1 条');
  assert.equal(view.groups[0].events[0].galleryDetails[0], item);
  assert.equal(addGalleryDomainRecords(view, 8, [item]).groups.length, 1);
});

await check('the home calendar reads only configured sources and persists completion locally', async () => {
  let options = { calendarEnabled: false, holidayEnabled: false };
  let calls = 0;
  let saved = '';
  const { HomePersonalSources } = module('shared-frontend/adapters/platform/HomePersonalSources.ets', 'HomePersonalSources', {
    PersonalSources: {
      options: async () => options,
      call: async () => '{"username":""}',
      operation: async (kind, raw) => {
        assert.equal(kind, 'calendar');
        assert.deepEqual(JSON.parse(raw), { quick: true });
        calls++;
        return JSON.stringify({ systemEnabled: false, systemGranted: false,
          items: [{ id: 'fixture-day', title: '本地示例', start: 100, end: 200, allDay: true, source: 'holiday' }] });
      }
    },
    AppStorage: { get: () => saved, setOrCreate: (key, raw) => {
      assert.equal(key, 'androidCalendarCompletedIds');
      saved = raw;
    }},
    SettingsStore: { save() {} }
  });
  const home = new HomePersonalSources();
  await home.refresh((calendar, mail, items) => {
    assert.equal(calendar || mail, false);
    assert.equal(items.length, 0);
  });
  const at = new Date(2026, 10, 19, 12).getTime();
  const nextDay = new Date(2026, 10, 20, 9).getTime();
  const boundary = new Date(2026, 10, 26).getTime();
  const event = (id, start, end) => ({ id, calendarStart: start, calendarEnd: end });
  const notes = home.reminders([{id:'calendar:legacy'}, {id:'saved-note'}], [
    event('calendar:android:next', nextDay, nextDay + 3600000),
    event('expired', at - 1000, at), event('outside', boundary, boundary + 1000),
    event('in-progress', at - 1000, at + 1000)
  ], at);
  assert.deepEqual(Array.from(notes, note => note.id), ['in-progress','calendar:android:next','saved-note']);
  assert.equal(calls, 0);
  options = { ...options, holidayEnabled: true };
  await home.refresh((calendar, mail, items) => {
    assert.equal(calendar, true);
    assert.equal(mail, false);
    assert.equal(items[0].calendarAllDay, true);
    assert.equal(home.complete(items, items[0].id, true)[0].completed, true);
  });
  assert.deepEqual(JSON.parse(saved), ['calendar:android:fixture-day']);
  await home.refresh((_calendar, _mail, items) => assert.equal(items[0].completed, true));
});

await check('all task cards use the same interactive workflow entry in phone mode', async () => {
  const page = fs.readFileSync(generated + 'pages/task/TaskPage.ets', 'utf8');
  const entry = page.match(/^  private (async runTask\(task: WorkflowTask\): Promise<void> \{[\s\S]*?^  \})/m);
  assert.ok(entry);
  const calls = [];
  const runTask = runInNewContext(stripTypeScriptTypes('({' + entry[1] + '}).runTask'), {
    WorkflowCancellation: class {},
    WorkflowStorage: { updateTaskRun: () => calls.push('record'), loadTasks: () => [] },
    AppLogger: { info() {}, errorCause(_tag, _message, error) { throw error; } },
    setTimeout
  });
  let task = { id: 'default_alipay_bill', enabled: true };
  const view = {
    taskControlMode: 'phone', isTaskExecutionBusy: () => false, flushTaskContextDrafts: () => true,
    findTask: () => task, invalidateFileStats() {},
    runSelectedWorkflow: async (_task, _cancel, interactive) => {
      assert.equal(interactive, true); calls.push('workflow');
    }, onEnsureDesktopCompanion: async () => { calls.push('pc'); return false; }
  };
  await runTask.call(view, task);
  assert.deepEqual(calls.splice(0), ['record', 'workflow']);
  task = { id: 'new-custom-workflow', enabled: true, isUserModified: true };
  await runTask.call(view, task);
  assert.deepEqual(calls.splice(0), ['record', 'workflow']);
  view.taskControlMode = 'pc'; task = { ...task, isUserModified: false };
  await runTask.call(view, task);
  assert.deepEqual(calls, ['pc']);
});

await check('all twelve bundled workflows use the shared accessibility runner and graph nodes', async () => {
  const resources = generated.replace(/ets\/$/, 'resources/rawfile/workflows/configs/');
  const files = fs.readdirSync(resources).filter(name => /^default_.*\.json$/.test(name));
  assert.equal(files.length, 12);
  for (const file of files) {
    const { steps } = JSON.parse(fs.readFileSync(resources + file, 'utf8'));
    if (file.startsWith('default_weixin_')) {
      const prompts = module('.arkui-x-validation/reference/entry/src/main/ets/utils/WechatSummaryPrompt.ets',
        'WECHAT_SUMMARY_PROMPT,WECHAT_TODAY_SUMMARY_PROMPT');
      assert.equal(steps[1].steps[1].inputs.question, file.includes('today') ? prompts.WECHAT_TODAY_SUMMARY_PROMPT : prompts.WECHAT_SUMMARY_PROMPT);
      assert.equal(steps[0].action, 'collection_open_app');
      assert.equal(steps[1].type, 'loop');
      assert.equal(steps[1].steps[1].tool_name, 'vlm_qa');
      continue;
    }
    assert.ok(steps.every(step => step.type === 'android_accessibility'), file);
    if (file !== 'default_weibo_hot_search.json' && file !== 'default_xhs_public_notes.json') {
      assert.deepEqual(steps.map(step => step.action), ['collection_open_app', 'collection_read_pages', 'collection_preview'], file);
      assert.match(steps[0].package_name, /^[\w.]+$/);
      if (file === 'default_ele_order.json') assert.equal(steps[1].page_limit, 7);
      else assert.ok(steps[1].page_limit >= 1 && steps[1].page_limit <= 3);
    }
  }
  const page = fs.readFileSync(generated + 'pages/task/TaskPage.ets', 'utf8');
  const library = page.match(/const NODE_TEMPLATES:[^\n]+\n([\s\S]*?)\n\];/);
  assert.ok(library);
  assert.deepEqual([...library[1].matchAll(/key: '([^']+)'/g)].map(match => match[1]),
    ['open_app', 'gui_task', 'shot_summary', 'if', 'for_loop', 'until_loop']);
  const create = page.match(/^  private (createTemplateStep\([\s\S]*?^  \})/m);
  assert.ok(create);
  const createStep = runInNewContext(stripTypeScriptTypes('({' + create[1] + '}).createTemplateStep'));
  const steps = [createStep('open_app', 1, ''), createStep('gui_task', 2, '')];
  assert.ok(steps.every(step => step.type === 'gui_task'));
  assert.equal(steps[0].action, 'collection_open_app');
  assert.equal(steps[1].action, 'collection_model_read');
  assert.equal(steps[1].task_description, '');
  assert.ok(page.includes('采集提示词（Prompt）'));
  const editor = page.match(/^  private (updateCollectionPages\(pages: number\): void \{[\s\S]*?^  \})/m);
  assert.ok(editor);
  const update = runInNewContext(stripTypeScriptTypes('({' + editor[1] + '}).updateCollectionPages'));
  const step = steps[1];
  assert.equal(step.page_limit, 3);
  let changed = false;
  update.call({ selectedStep: () => step, markWorkflowChanged: () => { changed = true; } }, 2);
  assert.equal(step.page_limit, 2);
  assert.equal(changed, true);
  const picker = page.match(/^  private (async chooseCollectionApp\(\): Promise<void> \{[\s\S]*?^  \})/m);
  assert.ok(picker);
  const pick = runInNewContext(stripTypeScriptTypes('({' + picker[1] + '}).chooseCollectionApp'), {
    ArkJSON: JSON, Bridge: { createBridge: () => ({ async callMethod(name) {
      assert.equal(name, 'guiCollectionApps');
      return '[{"label":"示例应用","packageName":"com.example.fixture"}]';
    } }) }
  });
  const view = { editorOpen: true, selectedStep: () => steps[0] };
  await pick.call(view);
  assert.equal(view.collectionApps[0].packageName, 'com.example.fixture');
});

await check('upgrading a legacy WeChat task keeps its contact and rejects replacing edited steps', () => {
  const storage = fs.readFileSync(generated + 'utils/WorkflowStorage.ets', 'utf8');
  const methods = ['legacyChatSteps', 'androidWorkflowFallback', 'migrateLegacyChatWorkflow'].map(name => {
    const match = storage.match(new RegExp('^  private (static ' + name + '\\([^]*?^  \\})', 'm'));
    assert.ok(match, name);
    return match[1];
  });
  const Storage = runInNewContext(stripTypeScriptTypes('class WorkflowStorage { ' + methods.join('\n') +
    ' static validateWorkflowJson(raw: string) { return JSON.parse(raw); } }; WorkflowStorage'), {
    stringifyJsonCompat: JSON.stringify,
    DataManagerStorage: class { sha256Text(value) { return createHash('sha256').update(value).digest('hex'); } }
  });
  const id = 'default_weixin_today_chat';
  const graph = JSON.parse(fs.readFileSync('.arkui-x-validation/reference/entry/src/main/resources/rawfile/workflows/configs/' + id + '.json', 'utf8'));
  graph.steps = JSON.parse(Storage.legacyChatSteps(id));
  graph.context.contact_name = '已有联系人';
  const migrated = JSON.parse(Storage.migrateLegacyChatWorkflow(id, JSON.stringify(graph)));
  assert.equal(migrated.context.contact_name, '已有联系人');
  const capture = migrated.steps[1];
  assert.equal(migrated.steps[0].package_name, 'com.tencent.mm');
  assert.equal(capture.break_if.right, false);
  assert.equal(capture.steps[1].inputs.time_context, 'capture_only');
  assert.match(capture.steps[1].inputs.question, /位置、发送人、聊天对象、时间原文、原文/);
  graph.steps[0].task_description = '用户修改了节点';
  assert.equal(Storage.migrateLegacyChatWorkflow(id, JSON.stringify(graph)), '');
});

await check('the generated Weibo graph exposes an editable read limit and the four native actions', () => {
  const resources = generated.replace(/ets\/$/, 'resources/rawfile/workflows/configs/');
  const graph = JSON.parse(fs.readFileSync(resources + 'default_weibo_hot_search.json', 'utf8'));
  assert.deepEqual(graph.steps.map(step => step.action), ['weibo_open_board', 'weibo_select_hot', 'weibo_read_hot', 'weibo_preview']);
  assert.ok(graph.steps.every(step => step.type === 'android_accessibility'));
  assert.equal(graph.steps[2].item_limit, 0);
  const page = fs.readFileSync(generated + 'pages/task/TaskPage.ets', 'utf8');
  const method = page.match(/^  private (updateWeiboItemLimit\(limit: number\): void \{[\s\S]*?^  \})/m);
  assert.ok(method);
  const update = runInNewContext(stripTypeScriptTypes('({' + method[1] + '}).updateWeiboItemLimit'));
  let changes = 0;
  update.call({ selectedStep: () => graph.steps[2], markWorkflowChanged: () => changes++ }, 1);
  assert.equal(graph.steps[2].item_limit, 1);
  assert.equal(changes, 1);
});

await check('ordinary nested gui_task uses the authorized Mika host and preserves its failure', async () => {
  const raw = JSON.stringify({ steps: [{ type: 'if', then_steps: [{ type: 'gui_task', task_description: '从屏幕读取文字' }] }] });
  const calls = [];
  const { AndroidWorkflow } = module('shared-frontend/adapters/platform/AndroidWorkflow.ets', 'AndroidWorkflow', {
    ...module(generated + 'utils/DailyLogEntryIdentity.ets', 'dailyLogMaxEntryId'),
    WorkflowStorage: { updateTaskRun() {}, readTaskWorkflowJson: () => raw, runsDir: () => '/fixture' },
    AppStorage: { get() { return ''; } },
    DataManagerFacade: class { storage = { ensureDir() {}, writeJsonAtomic() {} }; ensureReady() {} },
    Bridge: { createBridge: () => ({ async callMethod(name, ...args) {
      calls.push(name);
      if (name === 'workflowStartCollection') {
        assert.equal(args[0], raw);
        assert.match(args[1], /^android-/);
        assert.equal(args.length, 2);
        return 'workflow';
      }
      assert.equal(name, 'workflowPoll');
      return JSON.stringify({ v: 1, status: 'done', result: JSON.stringify({
        status: 'blocked', message: '虚拟屏助手未连接', resultSummary: '', steps: [{ id: '2', type: 'gui_task', status: 'blocked' }]
      }) });
    } }) }
  });
  await assert.rejects(AndroidWorkflow.run('/fixture', { id: 'ordinary' },
    { throwIfCancelled() {}, isCancelled: () => false, setHandler() {} }, async () => { calls.push('authorize'); }, true), /虚拟屏助手未连接/);
  assert.deepEqual(calls, ['workflowStartCollection', 'workflowPoll']);
});

await check('virtual collection in a nested branch needs no model configuration and records the native request count', async () => {
  const raw = JSON.stringify({ steps: [{ type: 'if', then_steps: [
    { type: 'gui_action', action: 'collection_open_app' }, { type: 'gui_action', action: 'collection_read_pages' }
  ] }] });
  const calls = [];
  let record;
  const { AndroidWorkflow } = module('shared-frontend/adapters/platform/AndroidWorkflow.ets', 'AndroidWorkflow', {
    ...module(generated + 'utils/DailyLogEntryIdentity.ets', 'dailyLogMaxEntryId'),
    WorkflowStorage: { updateTaskRun() {}, readTaskWorkflowJson: () => raw, runsDir: () => '/fixture' },
    DataManagerFacade: class { storage = { ensureDir() {}, writeJsonAtomic(path, value) { record = value; } }; ensureReady() {} },
    Bridge: { createBridge: () => ({ async callMethod(name) {
      calls.push(name);
      if (name === 'workflowStartCollection') return 'local-run';
      assert.equal(name, 'workflowPoll');
      return JSON.stringify({ v: 1, status: 'done', result: JSON.stringify({
        status: 'success', message: 'done', steps: [], resultSummary: '', collectionModelRequests: 0
      }) });
    } }) }
  });
  await AndroidWorkflow.run('/fixture', { id: 'virtual' }, { throwIfCancelled() {}, isCancelled: () => false, setHandler() {} },
    () => assert.fail('local step requested cloud authorization'), false);
  assert.deepEqual(calls, ['workflowStartCollection', 'workflowPoll']);
  assert.equal(record.collection_model_requests, 0);
  assert.equal(record.source_kind, 'android_workflow');
});

await check('only a persisted workflow summary makes results ready for sync', async () => {
  const files = new Map(), statuses = []; let cancelled = false, nativeCalls = 0, collectionMarks = 0;
  let result = { status: 'success', message: 'completed', resultSummary: '真实输入的输出', steps: [] };
  const signal = { isCancelled: () => cancelled, setHandler() {}, throwIfCancelled() { if (cancelled) throw new Error('用户取消'); } };
  const storage = { ensureDir() {}, exists: path => files.has(path), readText: path => files.get(path) ?? '',
    writeTextAtomic: (path, value) => files.set(path, value), writeJsonAtomic: (path, value) => files.set(path, JSON.stringify(value)) };
  const { AndroidWorkflow } = module('shared-frontend/adapters/platform/AndroidWorkflow.ets', 'AndroidWorkflow', {
    ...module(generated + 'utils/DailyLogEntryIdentity.ets', 'dailyLogMaxEntryId'),
    WorkflowStorage: { updateTaskRun: (_, id, status) => statuses.push(status), readTaskWorkflowJson: () => '{"steps":[]}', runsDir: () => '/fixture/runs' },
    DataManagerFacade: class { storage = storage; paths = { dailyLogRoot: '/fixture/daily-log' }; ensureReady() {} },
    CollectionReminderService: { recordSuccessfulCollection: async () => { collectionMarks++; assert.ok([...files.keys()].some(path => path.endsWith('.md'))); } },
    Bridge: { createBridge: () => ({ async callMethod(method) {
      nativeCalls++;
      if (method === 'workflowStart') return 'run-one';
      return JSON.stringify({ v: 1, status: 'done', result: JSON.stringify(result) });
    } }) }
  });
  assert.equal(await AndroidWorkflow.run('/fixture', { id: 'task-one', title: '用户任务', sceneId: 12 }, signal), true);
  assert.equal(statuses.at(-1), 'success'); assert.equal(nativeCalls, 2);
  assert.ok([...files.keys()].some(path => path.endsWith('/run_summary.json')));
  assert.equal(JSON.parse([...files.entries()].find(([path]) => path.endsWith('/run_summary.json'))[1]).source_kind, 'android_workflow');
  const log = [...files.entries()].find(([path]) => path.endsWith('.md'))[1];
  assert.ok(log.includes('1. 真实输入的输出')); assert.ok(log.includes('"task_scene_id":12'));
  assert.equal(collectionMarks, 1);
  cancelled = true; const count = files.size;
  await assert.rejects(AndroidWorkflow.run('/fixture', { id: 'task-two' }, signal), /取消/);
  assert.equal(files.size, count); assert.equal(nativeCalls, 2); assert.equal(statuses.at(-1), 'cancelled');
  cancelled = false;
  result = { status: 'success', message: 'completed', resultSummary: '', steps: [] };
  assert.equal(await AndroidWorkflow.run('/fixture', { id: 'echo' }, signal), false);
  result = { status: 'blocked', message: 'GUI requires Android automation', resultSummary: '', steps: [
    { id: '1', type: 'gui_task', status: 'blocked' }
  ] };
  await assert.rejects(AndroidWorkflow.run('/fixture', { id: 'gui' }, signal), /GUI requires Android automation/);
  assert.equal(statuses.at(-1), 'blocked');
  assert.equal([...files.keys()].filter(path => path.endsWith('.md')).length, 1);
  assert.equal(collectionMarks, 1);
});

// Execute the actual generated data-pipeline method with a controlled model
// boundary. These are protocol/commit guards, not claims about model quality.
let request;
const { DataPipelineModelClient } = module(generated + 'datamanager/DataPipelineModelClient.ets', 'DataPipelineModelClient', {
  ...module(generated + 'datamanager/ModelStreamActivity.ets', 'ModelStreamActivityStore'),
  ...module(generated + 'datamanager/ModelJsonResponse.ets', 'parseModelJsonObject', {ArkJSON: JSON}),
  ...module(generated + 'datamanager/EventStreamParser.ets', 'EventStreamParser', {ArkJSON: JSON}),
  ArkJSON: JSON, CloudModelClient: class {}, dataManagerInfo() {}, dataManagerError() {}, dataManagerWarn() {},
  ChatMemoryResponseError: class extends Error {}, normalizeFreeText: value => value.trim()
});
await check('memory pipeline passes saved model, suppresses response logging and rejects cancelled output before validation', async () => {
  let validations = 0, active = true;
  const client = new DataPipelineModelClient({ async chatCompletionStream(options) { request = options; return {content:'{"facts":[]}',finishReason:'stop'}; } });
  const config = { baseUrl: 'https://fixture.invalid', apiKey: '', model: 'saved-model', enabled: true,
    ensureActive() { if (!active) throw new Error('cancelled'); } };
  await client.callJson(config, 'chat.memory', 'system', 'input', () => { validations++; });
  assert.equal(request.model, 'saved-model'); assert.equal(request.logResponseContent, false); assert.equal(validations, 1);
  client.cloudClient = { async chatCompletionStream() { active = false; return {content:'{"facts":["late"]}',finishReason:'stop'}; } };
  await assert.rejects(client.callJson(config, 'chat.memory', 'system', 'input', () => { validations++; }), /cancelled/);
  assert.equal(validations, 1);
});
const chatTypes = module(generated + 'pages/chat/ChatSessionTypes.ets', 'splitAssistantThinkingContent,CHAT_MESSAGE_KIND_CHAT');
const extraction = module(generated + 'datamanager/ChatMemoryExtraction.ets',
  'pendingChatMemoryInputs,chatMemoryBatches,CHAT_MEMORY_WINDOW_MS,ChatMemoryResponseError', {
    ...chatTypes, ArkJSON: JSON, CHAT_MODE_LOCAL_INFERENCE: 'local_inference'
  });
const { ChatMemorySync } = module(generated + 'datamanager/ChatMemorySync.ets', 'ChatMemorySync', {
  ...module(generated + 'datamanager/MemoryChangeJournal.ets', 'MemoryChangeJournal'),
  ...extraction, ArkJSON: JSON, CHAT_MODE_LOCAL_INFERENCE: 'local_inference',
  ChatSessionStorage: { indexPath: root => root + '/index.json', sessionsDir: root => root + '/sessions', sessionPath: (root, id) => root + '/sessions/' + id + '.json' },
  createDataManagerProgress: (...args) => args, createJsonMap: () => ({}), memorySceneLabel: () => '测试场景', dataManagerWarn() {}
});
await check('actual chat-memory sync checkpoints, exports numbered logs and avoids duplicate extraction on rerun', async () => {
  const now = Date.now();
  const sessions = { '/fixture/index.json': JSON.stringify({ sessions: [{ id: 's1', updatedAt: now }] }),
    '/fixture/sessions/s1.json': JSON.stringify({ id: 's1', mode: 'cloud_agent', messages: [
      { id: 'u1', role: 'user', kind: 'chat', content: '我喜欢看书', createdAt: now },
      { id: 'a1', role: 'assistant', kind: 'chat', content: '收到', createdAt: now + 1 }
    ] }) };
  const values = new Map(Object.entries(sessions));
  const storage = { join: (...parts) => parts.join('/'), dirname: path => path.slice(0, path.lastIndexOf('/')),
    exists: path => values.has(path), readText: (path, fallback = '') => values.get(path) ?? fallback,
    readJson: (path, fallback) => values.has(path) ? JSON.parse(values.get(path)) : fallback,
    listDir: () => ['s1.json'], writeJsonAtomic: (path, value) => values.set(path, JSON.stringify(value)),
    writeTextAtomic: (path, value) => values.set(path, value) };
  let calls = 0;
  const model = { async extractChatMemory(inputs) { calls++; return inputs.map(input => ({ source_ref: input.source_ref,
    facts: [{ scene_id: 12, text: '用户喜欢看书' }] })); } };
  const sync = new ChatMemorySync({ filesDir: '/fixture', dailyLogRoot: '/fixture/workflows/daily-log' }, storage, model);
  const result = await sync.sync({ enabled: true }, undefined, now + 1000);
  assert.ok(result.factCount > 0); assert.ok(calls > 0);
  const count = calls;
  assert.ok([...values.values()].some(value => value.includes('1. 用户喜欢看书')));
  const second = await sync.sync({ enabled: true }, undefined, now + 1000);
  assert.equal(second.processedParts, 0); assert.equal(calls, count);
  // After a new edit, the incremental sync must consult and validate the persisted receipts.
  const { MemoryChangeJournal } = module(generated + 'datamanager/MemoryChangeJournal.ets', 'MemoryChangeJournal');
  new MemoryChangeJournal('/fixture', storage).mark('chat', 's1');
  values.set('/fixture/workflows/chat-memory/state.json', '{broken');
  await assert.rejects(sync.sync({ enabled: true }, undefined, now + 1000));
});
const revoked = new Map();
const { CloudModelClient } = module(generated + 'utils/CloudModelClient.ets', 'CloudModelClient', {
  PrivacyConsent: { onHttpCancel: (request, cancel) => revoked.set(request, cancel), destroyHttp: request => request.destroy() },
  AppLogger: { warnCause() {} }
});
await check('non-streaming cancellation settles even when Android destroy never resolves the transport promise', async () => {
  let destroyed = 0;
  const request = { request: () => new Promise(() => {}), destroy: () => { destroyed++; } };
  const client = new CloudModelClient(); client.registerActiveRequest(request);
  const pending = client.requestCancellable(request, 'https://fixture.invalid', {}, 10000);
  client.cancelActiveRequests();
  await assert.rejects(pending, /取消/); assert.ok(destroyed > 0);
});
await check('revocation and timeouts cannot deliver a later non-streaming response', async () => {
  let resolveResponse;
  const request = { request: () => new Promise(resolve => { resolveResponse = resolve; }), destroy() {} };
  const client = new CloudModelClient();
  const pending = client.requestCancellable(request, 'https://fixture.invalid', {}, 10000);
  revoked.get(request)(); resolveResponse({ responseCode: 200, result: 'late private output' });
  await assert.rejects(pending, /撤回/);
  await assert.rejects(client.requestCancellable({ request: () => new Promise(() => {}), destroy() {} },
    'https://fixture.invalid', {}, 5), /超时/);
});
await check('collection setup offers only missing services and rechecks after the system returns', async () => {
  async function run(connected, helperAvailable, virtual, choices, supported = true) {
    const dialogs = [], opened = [];
    const cancel = { stopped: false, cancel() { this.stopped = true; }, throwIfCancelled() { if (this.stopped) throw Error('cancelled'); } };
    const { ensureCollectionAccess } = module('shared-frontend/adapters/platform/CollectionAccess.ets', 'ensureCollectionAccess', {
      $r: value => value, setTimeout: callback => callback(),
      promptAction: { async showDialog(options) { dialogs.push(Array.from(options.buttons, b => b.text)); return {index: choices.shift()}; } },
      Bridge: {createBridge: () => ({async callMethod(method, ticket) {
        switch (method) {
          case 'guiControlStatus': return JSON.stringify({connected, helperAvailable, supported});
          case 'guiTaskAccessibility': case 'guiWirelessAutoSetup': opened.push(method); return 'ticket';
          case 'guiTaskAccessibilityResult': assert.equal(ticket, 'ticket'); connected = true; return 'ready';
          case 'guiWirelessSetupResult': assert.equal(ticket, 'ticket'); helperAvailable = true; return 'ready';
          default: throw Error(method);
        }
      }})}
    });
    await ensureCollectionAccess(cancel, virtual);
    return {dialogs, opened};
  }
  assert.deepEqual((await run(true, true, true, [])).dialogs, []);
  assert.deepEqual((await run(true, false, false, [])).dialogs, []);
  const both = await run(false, false, true, [2, 1]);
  assert.deepEqual(both.dialogs, [['取消任务','开启无障碍','连接无线助手'], ['取消任务','开启无障碍']]);
  assert.deepEqual(both.opened, ['guiWirelessAutoSetup','guiTaskAccessibility']);
  assert.deepEqual((await run(true, false, true, [1])).opened, ['guiWirelessAutoSetup']);
  await assert.rejects(run(false, false, true, [0]), /cancelled/);
  await assert.rejects(run(false, false, true, [], false), /Android 14/);
});
console.log(JSON.stringify({ passed, scope: 'Shared integration contracts with controlled ports; no real provider inference claim' }));
