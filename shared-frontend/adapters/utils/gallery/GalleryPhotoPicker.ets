import Bridge from '@arkui-x.bridge';

interface PhotoPickerResult { v: number; status: string; uris: string[]; }

export class GalleryPhotoPicker {
  async selectImageUris(maxSelectNumber: number = 9): Promise<string[]> {
    let limit = Math.max(1, Math.min(9, maxSelectNumber));
    let bridge = Bridge.createBridge('ClawMatePlatform');
    let id = await bridge.callMethod('photoPickerStart', String(limit)) as string;
    try {
      while (true) {
        let result = JSON.parse(await bridge.callMethod('photoPickerPoll', id) as string) as PhotoPickerResult;
        if (result.v !== 1) { throw new Error('系统选图接口版本不兼容，请更新应用'); }
        if (result.status === 'done') { return result.uris.slice(0, limit); }
        if (result.status === 'cancelled') { return []; }
        if (result.status !== 'running') { throw new Error('系统选图未完成，请重新选择图片'); }
        await new Promise<void>((resolve): void => { setTimeout(resolve, 200); });
      }
    } finally { await bridge.callMethod('photoPickerCancel', id); }
  }
}
