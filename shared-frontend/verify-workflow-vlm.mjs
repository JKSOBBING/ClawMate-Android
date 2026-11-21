import assert from 'node:assert/strict';
import fs from 'node:fs';
import { stripTypeScriptTypes } from 'node:module';
import { runInNewContext } from 'node:vm';
function module(file, name, ports) {
  const source = fs.readFileSync(file, 'utf8').replace(/^import\s+[^;]+;\s*/gm, '');
  return runInNewContext(stripTypeScriptTypes(source).replace(/^export /gm, '') + `\n${name};`, { setTimeout, clearTimeout, setInterval, clearInterval, ensureCollectionAccess: async () => {}, ...ports });
}
const sourceRoot = '.arkui-x-validation/application/project/entry/src/main/ets/';
const dailyLogMaxEntryId = module(sourceRoot + 'utils/DailyLogEntryIdentity.ets', 'dailyLogMaxEntryId', {});
const nonTodayWechatTimeLabel = module(sourceRoot + 'utils/WechatSummaryConsistency.ets', 'nonTodayWechatTimeLabel', {});
let passed = 0;
async function check(name, test) { await test(); console.log('PASS '+name); passed++; }
function harness() {
  const settings = new Map([['cloudDeciderBaseUrl','https://vision.invalid'],['cloudDeciderModel','saved-vision'],['cloudDeciderApiKey','synthetic-key']]);
  const state = { pending: true, accepted: true, choice: 1, images: 0, requests: [], cancelled: false,
    dialog: async () => ({index: state.choice}), prepare: async () => ['data:image/jpeg;base64,/9j/2Q=='], response: async () => 'actual model output' };
  let reject;
  const Vlm = module('shared-frontend/adapters/platform/WorkflowVlm.ets', 'WorkflowVlm', { nonTodayWechatTimeLabel,
    AppStorage: { get: key => settings.get(key) },
    promptAction: { showDialog: (...args) => state.dialog(...args) },
    Bridge: { createBridge: () => ({callMethod: async method => { if(method === 'workflowDeciderHttpAllowed') return false; assert.equal(method,'workflowToolPending'); return state.pending; }}) },
    PrivacyConsent: { requireAccepted: () => { if (!state.accepted) throw Error('revoked'); } },
    AiServiceAccess: { requireAllowed: () => { if (!state.accepted) throw Error('revoked'); } },
    ChatImages: { prepare: async (...args) => { state.images++; return state.prepare(...args); } },
    CloudModelClient: class {
      async chatCompletionText(options) { state.requests.push(options); return new Promise((resolve, fail) => { reject=fail; Promise.resolve().then(() => state.response()).then(resolve,fail); }); }
      cancelActiveRequests() { state.cancelled=true; reject?.(Error('cancelled')); }
    },
    setInterval: callback => setInterval(callback, 3)
  });
  return {state, settings, model: new Vlm(), request: {id:'request',mode:'answer',question:'fixture question',image:'file:///fixture.jpg'}, authorize: async () => {} };
}
await check('confirmed image uses saved Decider model and actual prepared JPEG with response logging disabled', async () => {
  const h=harness(); let dialog;
  h.state.dialog=async options => { dialog=options; return {index:1}; };
  assert.equal(await h.model.execute('run',h.request,()=>false,h.authorize),'actual model output');
  const sent=h.state.requests[0]; assert.equal(sent.model,'saved-vision'); assert.equal(sent.apiKey,'synthetic-key');
  assert.equal(sent.logResponseContent,false); assert.equal(sent.messages[0].content[0].image_url.url,'data:image/jpeg;base64,/9j/2Q==');
  assert.ok(dialog.message.includes('fixture question')); assert.ok(dialog.message.includes('https://vision.invalid'));
  assert.ok(!dialog.message.includes('synthetic-key'));
});
await check('today WeChat checks each visible time field and preserves dates in message bodies', async () => {
  for (const [label, today] of [['未显示', true], ['下午 3:05', true], ['昨天 下午3:05', false]]) {
    const h = harness();
    const summary = '会话名称：合成联系人\n- 位置：右侧｜发送人：我｜聊天对象：合成联系人｜时间原文：' + label + '｜原文：昨天确认了材料';
    h.state.response = async () => JSON.stringify({summary, contains_today_chat:true});
    const result = JSON.parse(await h.model.execute('run', {...h.request, mode:'summary', captureOnly:true,
      captureDay:'2026-11-20', schema:{summary:{type:'string'},contains_today_chat:{type:'boolean'}}}, () => false, h.authorize));
    assert.equal(result.contains_today_chat, today); assert.equal(result.summary, summary);
    assert.match(h.state.requests[0].messages[0].content[1].text, /日期换算由App后续处理/);
    assert.equal(h.state.requests[0].repetition_penalty, 1.1);
  }
});
await check('Mika result image resolves inside the current run and requires confirmation', async () => {
  const h = harness();
  const runName = 'android-1234-56';
  const prefix = 'workflows/runs/' + runName + '/';
  const request = { ...h.request, image: prefix + 'gui-2/result_screenshot_02.jpg' };
  h.state.prepare = async images => {
    assert.equal(images[0], 'file:///app/files/' + request.image);
    return ['data:image/jpeg;base64,/9j/2Q=='];
  };
  for (const image of [request.image.replace(runName, 'android-1-2'), prefix + '../other.jpg']) {
    await assert.rejects(h.model.execute('run', { ...request, image }, () => false, h.authorize, '/app/files', runName), /私有图片/);
  }
  h.state.choice = 0;
  await assert.rejects(h.model.execute('run', request, () => false, h.authorize, '/app/files', runName), /取消/);
  assert.equal(h.state.images, 0);
  h.state.choice = 1;
  assert.equal(await h.model.execute('run', request, () => false, h.authorize, '/app/files', runName), 'actual model output');
});

await check('standalone loop image uses the exact named JPEG in its own run', async () => {
  const h = harness();
  const runName = 'android-120-8';
  const prefix = 'workflows/runs/' + runName + '/';
  const request = { ...h.request, image: prefix + 'shot-3/chat_2.jpg' };
  h.state.prepare = async ([image]) => {
    assert.equal(image, 'file:///app/files/' + request.image);
    return ['data:image/jpeg;base64,/9j/2Q=='];
  };
  for (const path of ['shot-3/../chat_2.jpg', 'shot-3/nested/chat_2.jpg', 'shot-3/chat_2.exe']) {
    await assert.rejects(h.model.execute('run', { ...request, image: prefix + path }, () => false, h.authorize, '/app/files', runName), /私有图片/);
  }
  assert.equal(await h.model.execute('run', request, () => false, h.authorize, '/app/files', runName), 'actual model output');
});

await check('Xiaohongshu sends text choices only and discards a reply after leaving the authorized window', async () => {
  const h = harness();
  const config = await h.model.prepareGui(h.authorize);
  const request = { id: 'choice', mode: 'xhs_public_choice', question: '{"task":"先读这一页","choices":[{"id":"read_visible"}]}', image: '' };
  h.state.response = async () => '{"action":"read_visible"}';
  assert.equal(await h.model.executeGui('run', request, config, () => false), '{"action":"read_visible"}');
  assert.equal(h.state.images, 0);
  assert.equal(h.state.requests[0].messages[1].content, request.question);
  assert.equal(h.state.requests[0].logResponseContent, false);
  h.state.response = async () => { h.state.pending = false; return '{"action":"read_visible"}'; };
  await assert.rejects(h.model.executeGui('run', request, config, () => false), /窗口已变化/);
});
await check('model screen collection sends the current window JPEG and stops accepting replies after the window changes', async () => {
  const h = harness();
  const config = await h.model.prepareGui(h.authorize);
  const request = { id: 'screen', mode: 'screen_collection', question: '{"task":"抄录标题","choices":["finish"]}',
    image: 'data:image/jpeg;base64,/9j/2Q==' };
  h.state.response = async () => '{"action":"finish","lines":["标题"]}';
  assert.equal(await h.model.executeGui('run', request, config, () => false), '{"action":"finish","lines":["标题"]}');
  assert.equal(h.state.requests[0].messages[1].content[0].image_url.url, request.image);
  assert.equal(h.state.requests[0].messages[1].content[1].text, request.question);
  assert.equal(h.state.images, 0);
  await assert.rejects(h.model.executeGui('run', { ...request, image: 'file:///outside.jpg' }, config, () => false), /请求格式/);
  h.state.response = async () => { h.state.pending = false; return '{"action":"finish","lines":[]}'; };
  await assert.rejects(h.model.executeGui('run', request, config, () => false), /窗口已变化/);
});
await check('blank Decider name survives workflow consent and uses the server default', async () => {
  const h = harness();
  h.settings.set('cloudDeciderModel', '  ');
  h.state.dialog = async options => { assert.ok(options.message.includes('服务端默认 Decider')); return {index: 1}; };
  const result = JSON.parse(await h.model.authorizeCollection(h.request, h.authorize, () => false));
  assert.equal(result.model, ''); assert.equal(result.url, h.settings.get('cloudDeciderBaseUrl'));
  assert.equal(h.state.requests.length, 0);
});
await check('missing or unsafe endpoint fails before authorization, image reads and requests', async () => {
  for(const endpoint of ['', 'http://vision.invalid','https://user:password@vision.invalid','https://vision.invalid?secret=x']) {
    const h=harness(); h.settings.set('cloudDeciderBaseUrl',endpoint);
    await assert.rejects(h.model.execute('run',h.request,()=>false,async()=>assert.fail('no authorization')),/Decider/);
    assert.equal(h.state.images,0); assert.equal(h.state.requests.length,0);
  }
});
await check('Android canonical file URI spelling remains accepted before native private-root validation', async () => {
  const h=harness(); h.request.image='file:/fixture.jpg';
  assert.equal(await h.model.execute('run',h.request,()=>false,h.authorize),'actual model output');
});
await check('remote, content and raw base64 inputs are rejected before any read or upload', async () => {
  for(const image of ['https://image.invalid/x','content://photos/one','data:image/jpeg;base64,xxx','../secret']) {
    const h=harness(); h.request.image=image;
    await assert.rejects(h.model.execute('run',h.request,()=>false,h.authorize),/私有图片/);
    assert.equal(h.state.images,0); assert.equal(h.state.requests.length,0);
  }
});
await check('cancelling confirmation causes no image preparation or network request', async () => {
  const h=harness(); h.state.choice=0;
  await assert.rejects(h.model.execute('run',h.request,()=>false,h.authorize),/取消/);
  assert.equal(h.state.images,0); assert.equal(h.state.requests.length,0);
});
await check('configuration change during confirmation or image preparation requires a fresh confirmation', async () => {
  for(const when of ['dialog','image']) {
    const h=harness(); const mutate=()=>h.settings.set('cloudDeciderModel','different');
    if(when==='dialog') h.state.dialog=async()=>{mutate();return {index:1};};
    else h.state.prepare=async()=>{mutate();return ['data:image/jpeg;base64,/9j/2Q=='];};
    await assert.rejects(h.model.execute('run',h.request,()=>false,h.authorize),/配置已变化/);
    assert.equal(h.state.requests.length,0);
  }
});
await check('revocation during image preparation stops upload', async () => {
  const h=harness(); h.state.prepare=async()=>{h.state.accepted=false;return ['image'];};
  await assert.rejects(h.model.execute('run',h.request,()=>false,h.authorize),/revoked/); assert.equal(h.state.requests.length,0);
});
await check('late output after configuration change or background transition cannot be returned', async () => {
  for(const change of ['model','background']) {
    const h=harness(); h.state.response=async()=>{if(change==='model') h.settings.set('cloudDeciderModel','new');else h.state.pending=false;return 'late';};
    await assert.rejects(h.model.execute('run',h.request,()=>false,h.authorize),/配置已变化|前台/);
  }
});
await check('background transition actively cancels an outstanding HTTP request', async () => {
  const h=harness(); h.state.response=()=>{h.state.pending=false;return new Promise(()=>{});};
  await assert.rejects(h.model.execute('run',h.request,()=>false,h.authorize),/cancelled/); assert.equal(h.state.cancelled,true);
});
await check('cancelled or stale run never prompts or reads images', async () => {
  const h=harness(); h.state.dialog=()=>assert.fail('must not prompt'); h.state.pending=false;
  await assert.rejects(h.model.execute('run',h.request,()=>false,h.authorize),/前台/);
  await assert.rejects(h.model.execute('run',h.request,()=>true,h.authorize),/取消/); assert.equal(h.state.images,0);
});
await check('empty and oversized model responses fail without substituting fabricated text', async () => {
  for(const response of ['', 'x'.repeat(16001)]) {
    const h=harness(); h.state.response=async()=>response;
    await assert.rejects(h.model.execute('run',h.request,()=>false,h.authorize),/为空或超出/);
  }
});
await check('summary prefix and configured JSON output requirements preserve the original model contract', async () => {
  const h=harness(); h.request.mode='summary'; h.request.schema={summary:{type:'string'}};
  h.state.response=async()=>'{"summary":"real"}'; await h.model.execute('run',h.request,()=>false,h.authorize);
  const question=h.state.requests[0].messages[0].content[1].text;
  assert.ok(question.startsWith('请基于给定图片做总结：')); assert.ok(question.includes(JSON.stringify(h.request.schema)));
});
await check('actual workflow loop forwards model result, records provenance and only then saves real summary', async () => {
  const files=new Map(); let replied=false, marked=0, closed=0;
  const Workflow=module('shared-frontend/adapters/platform/AndroidWorkflow.ets','AndroidWorkflow', { dailyLogMaxEntryId,
    Bridge:{createBridge:()=>({async callMethod(method,run,request,raw) {
      if(method==='workflowStart') return 'run';
      if(method==='workflowToolReply') {assert.equal(run,'run');assert.equal(request,'request');assert.equal(JSON.parse(raw).response,'model answer');replied=true;return true;}
      if(!replied) return JSON.stringify({v:1,status:'running',tool:{id:'request'}});
      return JSON.stringify({v:1,status:'done',result:JSON.stringify({status:'success',message:'done',resultSummary:'model summary',steps:[]})});
    }})},
    WorkflowVlm:class {async execute(){return 'model answer';}cancel(){closed++;}},
    WorkflowStorage:{updateTaskRun(){},readTaskWorkflowJson:()=>'{"steps":[]}',runsDir:()=>'/fixture/runs'},
    DataManagerFacade:class{paths={dailyLogRoot:'/fixture/log'};storage={ensureDir(){},exists:()=>false,readText:()=>'',writeJsonAtomic:(p,v)=>files.set(p,JSON.stringify(v)),writeTextAtomic:(p,v)=>files.set(p,v)};ensureReady(){}},
    CollectionReminderService:{async recordSuccessfulCollection(){assert.ok([...files.keys()].some(p=>p.endsWith('.md')));marked++;}}
  });
  await Workflow.run('/fixture',{id:'task',title:'fixture',sceneId:12},{throwIfCancelled(){},isCancelled:()=>false,setHandler(){}});
  assert.equal(marked,1); assert.equal(closed,1); assert.ok([...files.values()].every(v=>v.includes('android_workflow_model')));
});
await check('interactive Xiaohongshu run binds Decider and forwards a single native action choice', async () => {
  const raw = '{"steps":[{"type":"android_accessibility","action":"xhs_public_notes"}]}';
  const config = { baseUrl: 'https://vision.invalid', model: 'saved-model', apiKey: 'private-key' };
  let authorized = false; let replied = false; let receipt;
  const Workflow = module('shared-frontend/adapters/platform/AndroidWorkflow.ets', 'AndroidWorkflow', { dailyLogMaxEntryId,
    Bridge: { createBridge: () => ({ async callMethod(method, run, request, payload) {
      if (method === 'workflowStartGui') {
        assert.equal(authorized, true); assert.equal(run, raw);
        assert.equal(request, 'https://vision.invalid（saved-model）'); return 'native-run';
      }
      assert.equal(run, 'native-run');
      if (method === 'workflowToolReply') {
        assert.equal(request, 'choice');
        assert.equal(JSON.parse(payload).response, '{"action":"read_visible"}');
        replied = true; return true;
      }
      assert.equal(method, 'workflowPoll');
      return JSON.stringify(replied ? { v: 1, status: 'done', result: JSON.stringify({
        status: 'success', message: 'native save', steps: [], resultSummary: ''
      }) } : { v: 1, status: 'running', tool: { id: 'choice', mode: 'xhs_public_choice' } });
    } }) },
    WorkflowVlm: class {
      async prepareGui(authorize) { await authorize(); return config; }
      async executeGui(run, tool, savedConfig) {
        assert.equal(savedConfig, config); assert.equal(tool.mode, 'xhs_public_choice'); return '{"action":"read_visible"}';
      }
      cancel() {}
    },
    WorkflowStorage: { updateTaskRun() {}, readTaskWorkflowJson: () => raw, runsDir: () => '/fixture/runs' },
    DataManagerFacade: class { storage = { ensureDir() {}, writeJsonAtomic: (_, value) => { receipt = value; } }; ensureReady() {} }
  });
  assert.equal(await Workflow.run('/fixture', { id: 'xhs' }, {
    throwIfCancelled() {}, isCancelled: () => false, setHandler() {}
  }, async () => { authorized = true; }, true), false);
  assert.equal(receipt.message, 'native save');
  assert.equal(receipt.result_summary, '');
});
await check('later deterministic summary preserves existing model provenance in an appended log', async () => {
  let saved='';
  const Workflow=module('shared-frontend/adapters/platform/AndroidWorkflow.ets','AndroidWorkflow', { dailyLogMaxEntryId,
    Bridge:{createBridge:()=>({callMethod:async method=>method==='workflowStart'?'run':JSON.stringify({v:1,status:'done',result:JSON.stringify({status:'success',message:'done',resultSummary:'deterministic',steps:[]})})})},
    WorkflowStorage:{updateTaskRun(){},readTaskWorkflowJson:()=>'{"steps":[]}',runsDir:()=>'/fixture/runs'},
    DataManagerFacade:class{paths={dailyLogRoot:'/fixture/log'};storage={ensureDir(){},exists:()=>true,readText:()=> '<!-- DAILY_LOG_METADATA\n{"workflow_metadata":{"source_kind":"android_workflow_model"}}\n-->\n1. model text',writeJsonAtomic(){},writeTextAtomic:(_,text)=>{saved=text;}};ensureReady(){}},
    CollectionReminderService:{async recordSuccessfulCollection(){}}
  });
  await Workflow.run('/fixture',{id:'task',title:'fixture',sceneId:12},{throwIfCancelled(){},isCancelled:()=>false,setHandler(){}});
  assert.ok(saved.includes('android_workflow_model'));assert.ok(saved.includes('model text'));assert.ok(saved.includes('model text\n\n2. deterministic'));
});
await check('cross-midnight captures retain their dates, long evidence and numbered text in daily logs', async () => {
  const saved = new Map();
  const long = '合成记录'.repeat(1200) + '\n2. 证据末行';
  const summaryEntries = [{ day: '2026-11-19', text: long }, { day: '2026-11-20', text: '第二天' }];
  const result = { status: 'success', message: '完成', resultSummary: long + '\n第二天', summaryEntries, steps: [] };
  const Workflow = module('shared-frontend/adapters/platform/AndroidWorkflow.ets', 'AndroidWorkflow', { dailyLogMaxEntryId,
    Bridge: { createBridge: () => ({ callMethod: async method => method === 'workflowStart' ? 'run' :
      JSON.stringify({ v: 1, status: 'done', result: JSON.stringify(result) }) }) },
    WorkflowStorage: { updateTaskRun() {}, readTaskWorkflowJson: () => '{"steps":[]}', runsDir: () => '/runs' },
    DataManagerFacade: class { paths = { dailyLogRoot: '/log' }; storage = { ensureDir() {}, exists: () => false, readText: () => '',
      writeJsonAtomic() {}, writeTextAtomic: (path, text) => saved.set(path, text) }; ensureReady() {} },
    CollectionReminderService: { async recordSuccessfulCollection() {} }
  });
  await Workflow.run('/files', { id: 'task', title: 'test', sceneId: 1 }, { throwIfCancelled() {}, isCancelled: () => false, setHandler() {} });
  assert.ok(saved.get('/log/2026-11-19/android-task.md').includes(long.replace('\n2.', '\n  2.')));
  assert.ok(saved.get('/log/2026-11-20/android-task.md').includes('1. 第二天'));
  assert.equal(saved.size, 2);
});
await check('picker cancellation and stale editor selection write no image', async () => {
  let prepared=0;
  const Vlm=module('shared-frontend/adapters/platform/WorkflowVlm.ets','WorkflowVlm', {
    GalleryPhotoPicker:class{async selectImageUris(){return ['content://fixture'];}},
    ChatImages:{async prepare(){prepared++;assert.fail('no decode');}},
    WorkflowStorage:{ensureDirectory(){assert.fail('no directory');}}
  });
  assert.equal(await Vlm.pickImage('/fixture',()=>true),'');assert.equal(prepared,0);
});
await check('chosen image is reencoded locally and only decoded byte range is atomically saved', async () => {
  const files=new Map();let source;
  const Vlm=module('shared-frontend/adapters/platform/WorkflowVlm.ets','WorkflowVlm', {
    GalleryPhotoPicker:class{async selectImageUris(max){assert.equal(max,1);return ['content://fixture'];}},
    ChatImages:{async prepare(uris){source=uris[0];return ['data:image/jpeg;base64,encoded'];}},
    WorkflowStorage:{ensureDirectory(){}},
    GalleryStorage:{writeBytes:(path,data)=>files.set(path,[...new Uint8Array(data)])},
    util:{Base64Helper:class{decodeSync(){return Uint8Array.from([1,2,255,216,255,217,3]).subarray(2,6);}}},
    fs:{statSync:path=>({size:files.get(path).length}),renameSync:(a,b)=>{files.set(b,files.get(a));files.delete(a);}}
  });
  const path=await Vlm.pickImage('/fixture',()=>false);assert.equal(source,'content://fixture');
  assert.deepEqual(files.get(path.slice(7)),[255,216,255,217]);assert.equal(files.size,1);
});
await check('editor leaving during decode prevents persistent image creation', async () => {
  let stopped=false;
  const Vlm=module('shared-frontend/adapters/platform/WorkflowVlm.ets','WorkflowVlm', {
    GalleryPhotoPicker:class{async selectImageUris(){return ['content://fixture'];}},
    ChatImages:{async prepare(){stopped=true;return ['image'];}},
    WorkflowStorage:{ensureDirectory(){assert.fail('no directory');}}
  });
  assert.equal(await Vlm.pickImage('/fixture',()=>stopped),'');
});
for (const rejected of [true, false]) {
  await check(rejected ? 'expired mailbox preserves the original image refusal' : 'expired mailbox never saves a late successful model answer', async () => {
    const updates=[]; let cancelled=0, replies=0;
    const Workflow=module('shared-frontend/adapters/platform/AndroidWorkflow.ets','AndroidWorkflow', { dailyLogMaxEntryId,
      Bridge:{createBridge:()=>({async callMethod(method) {
        if(method==='workflowStart') return 'expired-run';
        if(method==='workflowPoll') return JSON.stringify({v:1,status:'running',tool:{id:'expired-request'}});
        if(method==='workflowToolReply'){replies++;return false;}
        if(method==='workflowCancel'){cancelled++;return true;}
        assert.fail(method);
      }})},
      WorkflowVlm:class{async execute(){if(rejected)throw Error('用户取消视觉问答，未发送图片');return 'late answer must stay private';}cancel(){}},
      WorkflowStorage:{readTaskWorkflowJson:()=>'{"steps":[]}',updateTaskRun:(_,__,status,message)=>updates.push({status,message})},
      DataManagerFacade:class{constructor(){assert.fail('No terminal native result: never invent a summary or run record');}},
      CollectionReminderService:{recordSuccessfulCollection(){assert.fail('No collection success');}}
    });
    await assert.rejects(Workflow.run('/fixture',{id:'task'},{throwIfCancelled(){},isCancelled:()=>false,setHandler(){}}),
      rejected ? /用户取消视觉问答/ : /所属任务已结束/);
    assert.equal(replies,1);assert.equal(cancelled,1);
    assert.equal(updates.at(-1).status,'failed');
    assert.match(updates.at(-1).message,/所属任务已结束/);
    assert.ok(!updates.at(-1).message.includes('late answer must stay private'));
  });
}
console.log(JSON.stringify({passed,scope:'Controlled model ports, real ArkTS authorization/transport, local image import and workflow dispatch; no actual model quality claim'}));
