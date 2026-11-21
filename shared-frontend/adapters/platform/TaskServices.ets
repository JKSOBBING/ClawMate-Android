import { WorkflowStep } from '../utils/WorkflowTypes';

export const ANDROID_PC_TASK_PENDING: string = '电脑控制待接入 Android 设备桥接。可切换到「手机自己控制」运行本机工作流。';

export function taskStatusLabel(status: string): string {
  switch (status) {
    case 'success': return '已完成';
    case 'failed': return '执行失败';
    case 'cancelled': return '已取消';
    case 'not_configured': return '需要配置';
    case 'blocked': return '无法执行';
    default: return status;
  }
}

/** Describes services a task may call, including steps inside conditions and loops. */
export function taskServiceLabel(steps: WorkflowStep[]): string {
  let pending: WorkflowStep[][] = [steps];
  let mika = false;
  let needsDisplay = false;
  let visual = false;
  let other = false;
  let accessibility = false;
  let localCollection = false;
  let modelChoice = false;
  let count = 0;
  while (pending.length > 0) {
    let group = pending.pop();
    if (!Array.isArray(group)) { return '检查任务配置'; }
    for (let step of group) {
      if (!step || ++count > 256) { return '检查任务配置'; }
      let androidGui = step.type === 'gui_task' && (step.action === 'collection_open_app' || step.action === 'collection_model_read');
      const collection = (step.action || '').startsWith('collection_');
      if (collection) { localCollection = true; }
      if (!collection && step.type === 'gui_action' && step.action !== 'sleep') {
        if (step.action === 'screenshot' || step.action === 'swipe_with_coords') { needsDisplay = true; }
        else { return '自动 GUI · 待接入'; }
      }
      if (step.type === 'android_accessibility' || androidGui) {
        accessibility = true;
        if (step.action === 'xhs_public_notes' || step.action === 'collection_model_read') { modelChoice = true; }
      } else if (step.type === 'gui_task') {
        mika = true;
      } else if (step.type === 'tool') {
        if (step.tool_name === 'vlm_qa') { visual = true; }
        else if (step.tool_name !== 'echo' && step.tool_name !== 'summary') { other = true; }
      } else if (step.type !== 'gui_action' && step.type !== 'loop' && step.type !== 'if') {
        other = true;
      }
      for (let branch of [step.steps, step.then_steps, step.else_steps]) {
        if (branch !== undefined) { pending.push(branch); }
      }
    }
  }
  if (localCollection && !other) { return '虚拟屏采集 · 按需调用 Mika'; }
  if (needsDisplay && !mika) { return '自动 GUI · 待接入'; }
  if (other) { return '含待适配步骤'; }
  if (mika && accessibility) { return '含不兼容的采集节点'; }
  if (mika) { return 'Mika 虚拟屏'; }
  if (modelChoice) { return 'AI 界面采集 · 单次确认'; }
  if (accessibility) { return '无障碍采集 · 单次确认'; }
  return visual ? '视觉问答 · 发送前确认' : '本机工作流';
}
