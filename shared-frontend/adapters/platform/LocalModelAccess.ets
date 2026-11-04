import Bridge from '@arkui-x.bridge';

export interface DownloadProgress {
  repo: string; state: string; message: string; percent: number; downloaded: number; total: number;
  fileIndex: number; fileCount: number; currentPath: string; bytesPerSecond: number; remainingSeconds: number; failed: string[];
}

export interface DownloadedModel { id: string; label: string; path: string; repo: string; sizeText: string; statusText: string; ready: boolean; }

// 安卓本地模型管理：把 ModelScope 仓库下载到应用私有目录。下载、校验、断点续传和删除都在
// 后台原生代码完成；这里只负责轮询进度。没有下载到完整模型时不会声称可以推理。
export class LocalModelAccess {
  private static bridge(): ESObject { return Bridge.createBridge('ClawMatePlatform'); }

  static async list(): Promise<DownloadedModel[]> {
    let raw = await LocalModelAccess.bridge().callMethod('localModelList') as string;
    return JSON.parse(raw) as DownloadedModel[];
  }

  static async progress(): Promise<DownloadProgress> {
    let raw = await LocalModelAccess.bridge().callMethod('localModelDownloadProgress') as string;
    return JSON.parse(raw) as DownloadProgress;
  }

  static async start(repo: string, includeAuxiliary: boolean): Promise<string> {
    return await LocalModelAccess.bridge().callMethod('localModelDownloadStart', repo, includeAuxiliary) as string;
  }

  static async pause(): Promise<boolean> { return await LocalModelAccess.bridge().callMethod('localModelDownloadPause') as boolean; }
  static async cancel(): Promise<boolean> { return await LocalModelAccess.bridge().callMethod('localModelDownloadCancel') as boolean; }
  static async select(id: string): Promise<string> { return await LocalModelAccess.bridge().callMethod('localModelSelect', id) as string; }
  static async remove(id: string): Promise<string> { return await LocalModelAccess.bridge().callMethod('localModelDelete', id) as string; }
  /** Deep Link 回调：由宿主放进 AppStorage，这里交给本机桥校验 state 并兑换令牌。 */
  static async acceptJAccountLink(url: string): Promise<boolean> {
    return await LocalModelAccess.bridge().callMethod('sourcesJAccountLink', url) as boolean;
  }

  /** 把原生进度转换成设置页显示的文字。 */
  static describe(progress: DownloadProgress): string {
    let percent = Math.max(0, Math.min(100, progress.percent));
    let speed = progress.bytesPerSecond > 0 ? LocalModelAccess.bytes(progress.bytesPerSecond) + '/s' : '';
    let remaining = progress.remainingSeconds >= 0 ? ' · 剩余约 ' + LocalModelAccess.duration(progress.remainingSeconds) : '';
    if (progress.state === 'idle') { return '等待下载'; }
    if (progress.state === 'done') { return progress.message; }
    let prefix = progress.total > 0 ? LocalModelAccess.bytes(progress.downloaded) + ' / ' + LocalModelAccess.bytes(progress.total) + '（' + percent + '%）' : '';
    if (progress.bytesPerSecond > 0) { return prefix + ' · ' + speed + remaining; }
    return progress.message.length > 0 ? progress.message : prefix;
  }

  private static bytes(value: number): string {
    if (value < 1024) { return value.toString() + ' B'; }
    let units: string[] = ['KB', 'MB', 'GB', 'TB']; let v = value / 1024; let i = 0;
    while (v >= 1024 && i < units.length - 1) { v = v / 1024; i++; }
    return v.toFixed(1) + ' ' + units[i];
  }

  private static duration(seconds: number): string {
    if (seconds < 60) { return seconds.toString() + '秒'; }
    if (seconds < 3600) { return Math.round(seconds / 60).toString() + '分钟'; }
    return (seconds / 3600).toFixed(1) + '小时';
  }
}
