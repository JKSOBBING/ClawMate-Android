import assert from 'node:assert/strict';
import fs from 'node:fs';
import { stripTypeScriptTypes } from 'node:module';
import { runInNewContext } from 'node:vm';
const base='.arkui-x-validation/application/project/entry/src/main/ets/';
const text=fs.readFileSync('shared-frontend/adapters/platform/GuiPlan.ets','utf8');
const prompts=fs.readFileSync(base+'utils/CloudDeciderPrompt.ets','utf8');
const source=stripTypeScriptTypes((prompts+'\n'+text.replace(/^import\s+[^;]+;\s*/gm,'')).replace(/^export /gm,''));
let allowed=true,active=true,configuration=new Map([['cloudDeciderBaseUrl','https://fixture.invalid/v1'],['cloudDeciderModel','fixture-model'],['cloudDeciderApiKey','synthetic']]);
let nativeReady=true;
let call,reply='{"reasoning":"A visible button","action":"click","parameters":{"bbox":[10,20,50,60]}}',after=()=>{};
const context={Bridge:{createBridge:()=>({callMethod:async()=>nativeReady})},AppStorage:{get:key=>configuration.get(key)},AiServiceAccess:{requireAllowed(){assert.ok(allowed,'revoked')}},PrivacyConsent:{requireAccepted(){assert.ok(allowed,'revoked')}},setInterval,clearInterval,
 CloudModelClient:class{cancelActiveRequests(){} async chatCompletionText(value){call=value;after();return reply}}};
const {GuiPlan,parseGuiProposal}=runInNewContext(source+'\n({GuiPlan,parseGuiProposal});',context);
let passed=0; const check=async(name,fn)=>{await fn();passed++;console.log('PASS '+name)};
await check('original Decider prompts and screenshot dimensions reach model without execution',async()=>{const p=new GuiPlan();const r=await p.plan('show settings','data:image/jpeg;base64,AAAA',100,200,p.config(),()=>active);assert.equal(r.action,'click');assert.ok(call.messages[0].content.includes('You are a phone-use AI agent.'));assert.ok(call.messages[0].content.includes('100x200'));assert.equal(call.messages[1].content[1].image_url.url,'data:image/jpeg;base64,AAAA');assert.ok(call.messages[1].content[0].text.includes('No actions executed.'));});
await check('upstream 0–1000 coordinates map once to the displayed image',()=>{const p=parseGuiProposal('{"reasoning":"ok","action":"click","parameters":{"bbox":[500,200,1000,800]}}',200,400,true);assert.deepEqual(Array.from(p.parameters.bbox),[100,80,199,320]);});
await check('strict JSON and known actions reject commands and wrapped output',()=>{for(const s of ['[]','null','```json\n{}\n```','{"reasoning":"x","action":"shell","parameters":{}}','{"reasoning":"x","action":"wait","parameters":{},"command":"bad"}'])assert.throws(()=>parseGuiProposal(s,100,200));});
await check('bounds, finite coordinates and positive regions are enforced',()=>{for(const box of [[-1,0,30,40],[10,20,100,60],[50,20,10,60],[0,1,null,3],[1,2,3]])assert.throws(()=>parseGuiProposal(JSON.stringify({reasoning:'x',action:'click',parameters:{bbox:box}}),100,200));});
await check('required parameters and statuses reject incomplete proposals',()=>{for(const action of ['swipe','click_input','input','open_app','done'])assert.throws(()=>parseGuiProposal(JSON.stringify({reasoning:'x',action,parameters:{}}),100,200));assert.equal(parseGuiProposal('{"reasoning":"claim only","action":"done","parameters":{"status":"success"}}',100,200).action,'done');});
await check('unsafe endpoints are rejected before network',()=>{for(const url of ['http://fixture.invalid','https://u:p@fixture.invalid','https://fixture.invalid?k=x','']){configuration.set('cloudDeciderBaseUrl',url);assert.throws(()=>new GuiPlan().config());}configuration.set('cloudDeciderBaseUrl','https://fixture.invalid/v1');});
await check('revocation prevents request and discards pending reply',async()=>{const p=new GuiPlan();allowed=false;await assert.rejects(p.plan('x','data:image/jpeg;base64,AAAA',100,200,p.config(),()=>true));allowed=true;after=()=>{allowed=false};await assert.rejects(p.plan('x','data:image/jpeg;base64,AAAA',100,200,p.config(),()=>true));allowed=true;after=()=>{};});
await check('navigation and configuration changes discard pending reply',async()=>{const p=new GuiPlan();after=()=>{active=false};await assert.rejects(p.plan('x','data:image/jpeg;base64,AAAA',100,200,p.config(),()=>active));active=true;after=()=>configuration.set('cloudDeciderModel','changed');await assert.rejects(p.plan('x','data:image/jpeg;base64,AAAA',100,200,p.config(),()=>true));after=()=>{};});
await check('empty task, untrusted data URL and oversized inputs never send',async()=>{const p=new GuiPlan();for(const args of [['','data:image/jpeg;base64,AAAA'],['x','https://fixture.invalid/image'],['x'.repeat(2001),'data:image/jpeg;base64,AAAA']])await assert.rejects(p.plan(...args,100,200,p.config(),()=>true));});
await check('native foreground rejection blocks starts and drops returned proposals',async()=>{const p=new GuiPlan();nativeReady=false;await assert.rejects(p.plan('x','data:image/jpeg;base64,AAAA',100,200,p.config(),()=>true));nativeReady=true;after=()=>{nativeReady=false};await assert.rejects(p.plan('x','data:image/jpeg;base64,AAAA',100,200,p.config(),()=>true));nativeReady=true;after=()=>{};});
const pageText = fs.readFileSync(base + 'pages/task/GuiPlanPage.ets', 'utf8');
const bridgeText = fs.readFileSync('shared-app/src/main/java/com/clawmate/shared/PlatformBridge.kt', 'utf8');
const pageController = pageText.slice(0, pageText.indexOf('  @Builder'))
  .replace(/^import .*;\r?\n/gm, '').replace(/^@(?:Entry|Component)\s*$/gm, '')
  .replace('struct GuiPlanPage', 'class GuiPlanPage').replace(/@State /g, '');
function pageHarness() {
  const state = { calls: [], supported: true, connected: false, helperAvailable: false, failure: undefined, wait: undefined };
  const bridge = { async callMethod(method) {
    state.calls.push(method);
    if (method === 'guiControlStatus') {
      return JSON.stringify({ supported: state.supported, connected: state.connected, helperAvailable: state.helperAvailable });
    }
    if (state.failure) throw new Error(state.failure);
    if (state.wait) await state.wait;
    return true;
  } };
  const Page = runInNewContext(stripTypeScriptTypes(pageController + '}\nGuiPlanPage'), {
    Scroller: class {}, Bridge: { createBridge: () => bridge },
  });
  return { page: new Page(), state };
}
await check('settings distinguish Android accessibility and the local display helper', async () => {
  const { page, state } = pageHarness();
  state.helperAvailable = true;
  await page.refresh();
  assert.equal(page.connected, false);
  assert.equal(page.helperAvailable, true);
  assert.ok(page.status.includes('请开启无障碍服务'));
  state.connected = true;
  state.helperAvailable = false;
  await page.refresh();
  assert.equal(page.connected, true);
  assert.equal(page.helperAvailable, false);
  assert.ok(page.status.includes('无障碍服务已连接'));
  assert.deepEqual(state.calls, ['guiControlStatus', 'guiControlStatus']);
});
await check('visible manual authorizations, system settings and pairing open separate native entries', async () => {
  const { page, state } = pageHarness();
  for (const [label, method] of [
    ['手动控制授权', 'guiControlGrants'], ['手动控制', 'guiControlOpen'],
    ['无线配对', 'guiWirelessPairing'], ['无障碍服务', 'guiControlSettings'],
  ]) {
    const row = pageText.split('\n').find(line => line.includes("title: '" + label + "'"));
    assert.ok(row?.includes("this.open('" + method + "')"));
    assert.ok(bridgeText.includes('fun ' + method + '()'));
    await page.open(method);
    assert.deepEqual(state.calls.splice(0), [method, 'guiControlStatus']);
  }
});
await check('launch failure remains visible and repeated taps open one native screen', async () => {
  const { page, state } = pageHarness();
  state.failure = '打开页面失败';
  await page.open('guiWirelessPairing');
  assert.equal(page.status, state.failure);
  assert.equal(page.busy, false);
  state.failure = undefined;
  state.calls.length = 0;
  let finish;
  state.wait = new Promise(resolve => { finish = resolve; });
  const first = page.open('guiWirelessPairing');
  await page.open('guiWirelessPairing');
  assert.deepEqual(state.calls, ['guiWirelessPairing']);
  finish();
  await first;
  assert.equal(page.busy, false);
});
console.log(JSON.stringify({passed,scope:'Shared planner contract and native settings routes on the generated page'}));
