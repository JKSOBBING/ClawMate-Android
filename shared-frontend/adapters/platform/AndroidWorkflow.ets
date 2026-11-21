import { promptAction } from '@kit.ArkUI';
import { dailyLogMaxEntryId } from '../utils/DailyLogEntryIdentity';
import Bridge from '@arkui-x.bridge';
import { WorkflowTask, WorkflowDefinition, WorkflowStep } from '../utils/WorkflowTypes';
import { WorkflowStorage } from '../utils/WorkflowStorage';
import { WorkflowCancellation } from '../utils/WorkflowCancellation';
import { DataManagerFacade } from '../datamanager/DataManagerFacade';
import { CollectionReminderService } from '../utils/CollectionReminderService';
import { ensureCollectionAccess } from './CollectionAccess';
import { WorkflowVlm, WorkflowVlmRequest } from './WorkflowVlm';
import { CloudChatCompletionOptions } from '../utils/CloudModelClient';

interface GuiOutput { mode?: string; capture_day?: string; daily_log_path?: string; model_requests?: number; tool_name?: string; result_screenshot_path?: string; result_screenshot_paths?: string[]; }
interface ExecutionStep { id: string; type?: string; status: string; output?: GuiOutput; }
interface WorkflowSummaryEntry { day: string; text: string; }
interface ExecutionResult { collectionModelRequests?: number; summaryEntries?: WorkflowSummaryEntry[]; status: string; message: string; resultSummary: string; steps: ExecutionStep[]; }
interface ExecutionPoll { v: number; status: string; result: string; tool?: WorkflowVlmRequest; }
interface SummaryTaskMetadata { name: string; task_scene_id: number; source_kind: string; }
interface SummaryMetadata { entry_batch_ids?: Record<string, string>; workflow_metadata: SummaryTaskMetadata; latest_entry_index: number; }
interface SharedRunRecord { collection_model_requests?: number; elapsed_ms?: number; source_kind: string; workflow_file: string; run_dir: string; status: string; message: string; result_summary: string; steps: Object[]; result_screenshot_path?: string; result_screenshot_paths?: string[]; }
export class AndroidWorkflow {
  private static needsMika(steps: WorkflowStep[]): boolean {
    return steps.some((step: WorkflowStep): boolean =>
      (step.type === 'gui_task' && step.action !== 'collection_open_app' && step.action !== 'collection_model_read') ||
      AndroidWorkflow.needsMika(step.steps || []) || AndroidWorkflow.needsMika(step.then_steps || []) ||
      AndroidWorkflow.needsMika(step.else_steps || []));
  }

  static async run(filesDir: string, task: WorkflowTask, cancellation: WorkflowCancellation,
    authorize: () => Promise<void> = async (): Promise<void> => { throw new Error('请在共享任务页确认云端授权'); },
    interactive: boolean = false): Promise<boolean> {
    let bridge = Bridge.createBridge('ClawMatePlatform');
    let id = '';
    let started = new Date();
    let runName = 'android-' + started.getTime().toString() + '-' + Math.floor(Math.random() * 1000000000).toString();
    let complete = false;
    let model: WorkflowVlm | null = null;
    let usedModel = false;
    WorkflowStorage.updateTaskRun(filesDir, task.id, '运行中', '正在执行 Android 有界工作流');
    try {
      cancellation.throwIfCancelled();
      let raw = WorkflowStorage.readTaskWorkflowJson(filesDir, task);
      let guiConfig: CloudChatCompletionOptions | null = null;
      let needsDecider = /"action"\s*:\s*"(xhs_public_notes|collection_model_read)"/.test(raw);
      let definition = JSON.parse(raw) as WorkflowDefinition;
      let steps = definition.steps || [];
      let wechatCapture = steps[0]?.package_name === 'com.tencent.mm' && /"time_context"\s*:\s*"capture_only"/.test(raw);
      let needsMika = AndroidWorkflow.needsMika(steps);
      let nativePublic = steps.length > 0 && (steps[0].action === 'weibo_open_board' ||
        (steps[0].action === 'xhs_public_notes' && steps[0].task_description === '采集当前页的公开笔记摘录，然后向下翻一页，再采集这一页，最后打开预览。'));
      let localCollection = /"action"\s*:\s*"collection_(open_app|navigate|read_pages|model_read|preview)"/.test(raw);
      if (localCollection || needsMika || nativePublic || /"type"\s*:\s*"android_accessibility"/.test(raw)) {
        await ensureCollectionAccess(cancellation, localCollection || needsMika || nativePublic || needsDecider);
        cancellation.throwIfCancelled();
      }
      if (localCollection || (needsMika && !nativePublic)) {
        id = await bridge.callMethod('workflowStartCollection', raw, runName) as string;
      } else if (nativePublic) {
        id = await bridge.callMethod('workflowStartInteractive', raw) as string;
      } else if (interactive && needsDecider) {
        model = new WorkflowVlm();
        guiConfig = await model.prepareGui(authorize);
        cancellation.throwIfCancelled();
        id = await bridge.callMethod('workflowStartGui', raw, guiConfig.baseUrl + '（' + (guiConfig.model || '服务端默认 Decider') + '）') as string;
      } else {
        id = await bridge.callMethod(interactive ? 'workflowStartInteractive' : 'workflowStart', raw) as string;
      }
      cancellation.setHandler((): void => { model?.cancel(); bridge.callMethod('workflowCancel', id).catch(() => {}); });
      let deadline = Date.now() + (needsMika || localCollection ? 665000 : needsDecider || /"type"\s*:\s*"android_accessibility"/.test(raw) ? 110000 : 65000);
      while (Date.now() < deadline) {
        cancellation.throwIfCancelled();
        let poll = JSON.parse(await bridge.callMethod('workflowPoll', id) as string) as ExecutionPoll;
        if (poll.v !== 1) { throw new Error('工作流接口版本不兼容'); }
        cancellation.throwIfCancelled();
        if (poll.status === 'running' && poll.tool) {
          if (model === null) { model = new WorkflowVlm(); }
          usedModel = true;
          let response = ''; let error = '';
          try {
            response = poll.tool.mode === 'collection_authorize' ? await model.authorizeCollection(poll.tool, authorize, (): boolean => cancellation.isCancelled()) :
              guiConfig !== null ? await model.executeGui(id, poll.tool, guiConfig, (): boolean => cancellation.isCancelled()) :
              await model.execute(id, poll.tool, (): boolean => cancellation.isCancelled(), authorize, filesDir, runName);
          }
          catch (failure) { error = (failure as Error).message.slice(0, 500) || '视觉问答失败'; }
          cancellation.throwIfCancelled();
          if (!await bridge.callMethod('workflowToolReply', id, poll.tool.id, JSON.stringify({ response: response, error: error }))) {
            // Keep the user's refusal or transport error even if the bounded
            // native task expired while its confirmation dialog was open.
            let expired = '视觉问答所属任务已结束，未提交迟到结果';
            throw new Error(error.length > 0 ? error + '；' + expired : expired);
          }
          continue;
        }
        if (poll.status === 'done') {
          let result = JSON.parse(poll.result) as ExecutionResult;
          let facade = new DataManagerFacade(filesDir); facade.ensureReady();
          // ArkTS owns this package's files. The native runner returns results
          // without touching a second repository or generating fabricated logs.
          let runDir = WorkflowStorage.runsDir(filesDir) + '/' + runName;
          facade.storage.ensureDir(runDir);
          if (result.collectionModelRequests !== undefined) {
            usedModel = (result.collectionModelRequests || 0) > 0 ||
              result.steps.some((step: ExecutionStep): boolean => step.type === 'tool' && step.output?.tool_name === 'vlm_qa');
          }
          let source = usedModel ? 'android_workflow_model' : 'android_workflow';
          let gui = result.steps.find((step: ExecutionStep): boolean =>
            !localCollection && step.status === 'blocked' && step.type === 'gui_action');
          let message = result.message;
          if (result.status !== 'success' && gui !== undefined) {
            message = '第 ' + gui.id + ' 步需要自动 GUI。请核对设备控制方式。';
          }
          let savedSummary = result.status === 'success' && result.resultSummary.trim().length > 0;
          let record: SharedRunRecord = { source_kind: source, workflow_file: task.configFileName, run_dir: runDir,
            status: result.status, message: message, result_summary: result.resultSummary, steps: result.steps };
          if (result.collectionModelRequests !== undefined) {
            record.collection_model_requests = result.collectionModelRequests;
            record.elapsed_ms = Date.now() - started.getTime();
            message += ' · 采集模型请求 ' + result.collectionModelRequests + ' 次';
            record.message = message;
          }
          for (let step of result.steps) {
            if ((step.type === 'gui_task' || step.type === 'gui_action') && step.status === 'success' && step.output?.result_screenshot_path) {
              record.result_screenshot_path = step.output.result_screenshot_path;
              record.result_screenshot_paths = step.output.result_screenshot_paths;
            }
          }
          if (wechatCapture && savedSummary) {
            let choice = await promptAction.showDialog({ title: '保存此次微信采集？', message: result.resultSummary,
              buttons: [{ text: '取消', color: '#65718B' }, { text: '保存到本机', color: '#276EF1' }] });
            cancellation.throwIfCancelled();
            if (choice.index !== 1) { throw new Error('已取消保存微信采集'); }
          }
          if (savedSummary) {
            let startDay = started.getFullYear().toString() + '-' + String(started.getMonth() + 1).padStart(2, '0') + '-' + String(started.getDate()).padStart(2, '0');
            let entries: WorkflowSummaryEntry[] = result.summaryEntries?.length ? result.summaryEntries : [{ day: startDay, text: result.resultSummary }];
            let writes = new Map<string, string>();
            let name = task.id.replace(/[^A-Za-z0-9_-]/g, '_').slice(0, 80);
            let fileName = 'android-' + name + '.md';
            if (wechatCapture) {
              let contact = String(definition.context?.['contact_name'] || '').trim();
              let encoded = encodeURIComponent(contact);
              let key = encoded.length <= 150 ? encoded : facade.storage.sha256Text(contact);
              fileName = 'com.tencent.mm__' + name + '__' + key + '.md';
            }
            for (let step of result.steps) {
              if (step.output?.mode === 'summary' && step.output.capture_day) {
                step.output.daily_log_path = 'daily-log/' + step.output.capture_day + '/' + fileName;
              }
            }
            for (let entry of entries) {
              if (!/^\d{4}-\d{2}-\d{2}$/.test(entry.day)) { throw new Error('摘要缺少采集日期'); }
              let path = facade.paths.dailyLogRoot + '/' + entry.day + '/' + fileName;
              let previous = writes.get(path) ?? (facade.storage.exists(path) ? facade.storage.readText(path, '\u0000') : '');
              if (previous === '\u0000') { throw new Error('读取已有采集日志失败'); }
              let header = /^<!-- DAILY_LOG_METADATA\s*([\s\S]*?)-->/.exec(previous);
              if (!header && previous.startsWith('<!-- DAILY_LOG_METADATA')) { throw new Error('采集日志元数据不完整'); }
              let existing: SummaryMetadata | null = header ? JSON.parse(header[1]) as SummaryMetadata : null;
              let batches: Record<string, string> = existing?.entry_batch_ids || {};
              let count = (previous.match(/^\d+\. /gm) || []).length;
              let body = previous.replace(/<!-- DAILY_LOG_METADATA[\s\S]*?-->/, '').trim();
              // The Harmony parser separates records at a blank line followed by an entry number.
              if (body && count > 1) {
                let numbers = body.match(/^\d+(?=\. )/gm) || [];
                if (numbers.every((number: string, index: number): boolean => Number(number) === index + 1) &&
                  new RegExp('"latest_entry_index"\\s*:\\s*' + count + '(?=[,}\\s])').test(previous)) {
                  body = body.replace(/\n+(?=\d+\. )/g, '\n\n');
                }
              }
              let next = dailyLogMaxEntryId(body, existing?.latest_entry_index || 0, batches) + 1;
              batches[String(next)] = 'workflow:' + runName;
              let logSource = usedModel || /"source_kind"\s*:\s*"android_workflow_model"/.test(previous) ? 'android_workflow_model' : source;
              let envelope: SummaryMetadata = { workflow_metadata: { name: task.title, task_scene_id: task.sceneId, source_kind: logSource }, latest_entry_index: next, entry_batch_ids: batches };
              let content = '<!-- DAILY_LOG_METADATA\n' + JSON.stringify(envelope) + '\n-->\n\n' + (body ? body + '\n\n' : '') + String(next) + '. ' + entry.text.replace(/\n(?=\d+\. )/g, '\n  ') + '\n\n';
              if (content.length > 200000 || count >= 60) { throw new Error('当日日志已达保存上限'); }
              writes.set(path, content);
            }
            facade.storage.writeJsonAtomic(runDir + '/run_summary.json', record as Object);
            writes.forEach((content: string, path: string): void => { facade.storage.writeTextAtomic(path, content); });
            CollectionReminderService.recordSuccessfulCollection().catch(() => {});
          } else { facade.storage.writeJsonAtomic(runDir + '/run_summary.json', record as Object); }
          WorkflowStorage.updateTaskRun(filesDir, task.id, result.status, message +
            (result.resultSummary.length > 0 ? '\n' + result.resultSummary : ''));
          complete = true;
          if (result.status !== 'success') { throw new Error(message); }
          return savedSummary;
        }
        if (poll.status !== 'running') { throw new Error('工作流未完成或已取消'); }
        await new Promise<void>((resolve): void => { setTimeout(resolve, 120); });
      }
      throw new Error('工作流超过执行时限');
    } catch (error) {
      if (!complete) { WorkflowStorage.updateTaskRun(filesDir, task.id, cancellation.isCancelled() ? 'cancelled' : 'failed', (error as Error).message); }
      throw error as Error;
    } finally {
      model?.cancel();
      cancellation.setHandler(null);
      if (!complete && id.length > 0) { await bridge.callMethod('workflowCancel', id); }
    }
  }
}
