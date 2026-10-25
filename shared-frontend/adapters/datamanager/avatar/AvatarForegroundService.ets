import { DataManagerPaths } from '../DataManagerPaths';
import { DataManagerStorage } from '../DataManagerStorage';
import { AvatarStorage } from './AvatarStorage';
export class AvatarForegroundService {
  constructor(paths: DataManagerPaths, storage: DataManagerStorage, avatarStorage: AvatarStorage) {}
  async ensureForegroundForVersion(versionId: string, imagePath: string): Promise<string> {
    throw new Error('安卓人物分割尚未接入；未生成透明前景');
  }
}
