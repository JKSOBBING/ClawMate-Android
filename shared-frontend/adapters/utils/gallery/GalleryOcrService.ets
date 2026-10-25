import { image } from '@kit.ImageKit';
export class GalleryOcrService {
  async recognize(pixelMap: image.PixelMap): Promise<string> {
    throw new Error('安卓 OCR 尚未接入；可在图库设置明确选择“跳过 OCR”后使用自配视觉模型');
  }
  async release(): Promise<void> {}
}
