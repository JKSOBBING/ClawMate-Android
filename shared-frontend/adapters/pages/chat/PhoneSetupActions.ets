import { ChatMessageDetailUiModel } from './ChatMessageUiModel';
import { phoneSetupGuide } from '../../platform/PhoneSetup';
import { AppTheme } from '../../utils/AppTheme';

@Component
export struct PhoneSetupActions {
  @ObjectLink detail: ChatMessageDetailUiModel;
  messageId: string = '';
  onAction: (id: string, retry: boolean) => void = () => {};

  build() {
    if (phoneSetupGuide(this.detail.setupIssue) !== undefined) {
      Column({ space: 9 }) {
        Text(this.detail.setupIssue === 'pair' || this.detail.setupIssue === 'reconnect' ? '连接虚拟屏助手' : '完成任务所需的授权')
          .fontSize(15).fontWeight(FontWeight.Bold).fontColor(AppTheme.text('#253879'))
        Text(phoneSetupGuide(this.detail.setupIssue)?.steps || '')
          .fontSize(13).lineHeight(19).fontColor(AppTheme.text('#444E64')).width('100%')
        Row({ space: 7 }) {
          Button(phoneSetupGuide(this.detail.setupIssue)?.button || '打开设置')
            .fontSize(13).fontWeight(FontWeight.Medium).fontColor(Color.White)
            .backgroundColor('#4057CA').borderRadius(10).height(36).padding({ left: 12, right: 12 })
            .onClick(() => this.onAction(this.messageId, false))
          Button('已设置，重试').fontSize(13).height(36).borderRadius(10)
            .padding({ left: 8, right: 8 }).backgroundColor(Color.Transparent).fontColor(AppTheme.text('#4055A6'))
            .onClick(() => this.onAction(this.messageId, true))
        }
      }.alignItems(HorizontalAlign.Start).padding(12).margin({ top: 9 }).width('100%')
        .backgroundColor(AppTheme.background('#EEF1FC')).borderRadius(12)
        .border({ width: 1, color: AppTheme.border('#D4DDF3') })
    }
  }
}
