import Bridge from '@arkui-x.bridge';
import { ChatMessageDetailUiModel } from './ChatMessageUiModel';
import { AppTheme } from '../../utils/AppTheme';

@Component
export struct PhoneReadEvidence {
  @ObjectLink detail: ChatMessageDetailUiModel;
  @State expanded: boolean = false;
  @State saved: string = '';
  @State writing: boolean = false;

  private async exportText(): Promise<void> {
    if (this.writing) { return; }
    this.writing = true;
    try {
      const file = await Bridge.createBridge('ClawMatePlatform').callMethod('exportPhoneEvidence', this.detail.readEvidence) as string;
      this.saved = '已保存到 下载/ClawMate/' + file;
    } catch (error) { this.saved = '未能保存，请重试。'; }
    finally { this.writing = false; }
  }

  build() {
    if (this.detail.readEvidence) {
      Column({ space: 6 }) {
        Text('已读摘录').fontSize(14).fontWeight(FontWeight.Bold)
        Text(this.detail.readEvidence).fontSize(13).lineHeight(19)
          .maxLines(this.expanded ? 1000 : 4).textOverflow({ overflow: TextOverflow.Ellipsis })
        Row({ space: 15 }) {
          Button(this.expanded ? '收起' : '展开').fontSize(13).height(32)
            .onClick(() => { this.expanded = !this.expanded; })
          Button('导出已读').fontSize(13).height(32).enabled(!this.writing)
            .onClick(() => { this.exportText(); })
        }
        if (this.saved.length > 0) { Text(this.saved).fontSize(12) }
      }.width('100%').alignItems(HorizontalAlign.Start).padding(10).margin({ top: 8 })
        .backgroundColor(AppTheme.background('#F2F5FA')).borderRadius(10)
    }
  }
}
