import { DownloadedModel } from '../../platform/LocalModelAccess';
import { AppTheme } from '../../utils/AppTheme';
import { DEFAULT_MODELSCOPE_MNN_REPO } from '../../config/ModelDownloadConfig';
import { SettingInput, SettingsGroupCard, SettingsOutputPanel } from './SettingsCenterControls';
import { SETTINGS_BUTTON_HEIGHT, SETTINGS_CONTROL_RADIUS, SETTINGS_SECONDARY_TEXT_COLOR, settingsActionColor } from './SettingsCenterTheme';
import { SettingsActionKind } from './SettingsCenterTypes';

// The two model cards follow SettingsCenterPage on clawmate_dev (49fc716).
@Component
export struct AndroidLocalModelSettings {
  @Prop models: DownloadedModel[] = [];
  @Prop selectedId: string = '';
  @Prop importing: boolean = false;
  @Prop downloading: boolean = false;
  @Prop pausedRepo: string = '';
  @Link repo: string;
  @Link threads: string;
  onRefresh: () => void = () => {};
  onSelect: (id: string) => void = () => {};
  onDelete: (item: DownloadedModel) => void = () => {};
  onImport: () => Promise<void> = async () => {};
  onDownload: () => Promise<void> = async () => {};
  onCloseInputs: () => void = () => {};
  @BuilderParam progress: () => void = this.EmptyProgress;

  @Builder EmptyProgress() {}

  @Builder Models() {
    Button('刷新')
      .height(32).width(72).fontSize(12).borderRadius(10)
      .backgroundColor(AppTheme.background('#607D8B')).margin({ bottom: 8 })
      .onClick(() => { this.onRefresh(); })
    ForEach(this.models, (item: DownloadedModel) => {
      Row() {
        Column() {
          Text(item.label).fontSize(13)
            .fontWeight(this.selectedId === item.id ? FontWeight.Bold : FontWeight.Regular)
            .fontColor(AppTheme.text(this.selectedId === item.id ? '#4F68FF' : '#11182F'))
            .maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis }).width('100%')
          Text(item.statusText + ' · ' + item.sizeText).fontSize(11)
            .fontColor(AppTheme.text(item.runnable ? '#2D9B73' : '#D26B31')).width('100%')
        }.layoutWeight(1).alignItems(HorizontalAlign.Start)
        Button(!item.ready ? '未完成' : !item.runnable ? '仅文件' : this.selectedId === item.id ? '当前' : '切换')
          .height(32).width(64).fontSize(12).borderRadius(10)
          .backgroundColor(AppTheme.background(this.selectedId === item.id || !item.runnable ? '#A9B0BE' : '#4F68FF'))
          .enabled(this.selectedId !== item.id && item.runnable && !this.importing && !this.downloading)
          .onClick(() => { this.onSelect(item.id); })
        Button('删除').height(32).width(56).fontSize(12).borderRadius(10).margin({ left: 8 })
          .fontColor(AppTheme.text(Color.White)).backgroundColor(AppTheme.filledBackground(settingsActionColor(SettingsActionKind.Danger)))
          .enabled(!this.downloading && !this.importing).onClick(() => { this.onDelete(item); })
      }.width('100%').padding({ left: 10, right: 10, top: 9, bottom: 9 })
        .backgroundColor(AppTheme.background(this.selectedId === item.id ? '#EEF4FF' : '#F7F9FC')).borderRadius(12)
        .border({ width: 1, color: AppTheme.border(this.selectedId === item.id ? '#BFD3FF' : '#E6EAF2') }).margin({ bottom: 8 })
    }, (item: DownloadedModel) => item.id + ':' + item.label + ':' + item.statusText + ':' + item.sizeText)
  }

  @Builder Mode(label: string, available: boolean) {
    Button(label).height(SETTINGS_BUTTON_HEIGHT).width('31%').fontSize(12).borderRadius(SETTINGS_CONTROL_RADIUS)
      .fontColor(AppTheme.text(available ? Color.White : SETTINGS_SECONDARY_TEXT_COLOR))
      .backgroundColor(available ? AppTheme.filledBackground(settingsActionColor(SettingsActionKind.Primary)) : $r('app.color.ui_surface_soft'))
      .enabled(available)
  }

  @Builder LocalModelGroup() {
    this.Models()
    Text('本地推理运行方式').fontSize(13).width('100%').margin({ top: 8, bottom: 6 })
    Row({ space: 8 }) {
      this.Mode('全 CPU', true)
      this.Mode('在线 NPU', false)
      this.Mode('离线NPU', false)
    }.width('100%').justifyContent(FlexAlign.SpaceBetween)
    Row() { this.Mode('全图 NPU', false) }.width('100%').margin({ top: 8 })
    Text('Android 使用 CPU 运行单文件 GGUF 文字模型。').fontSize(12)
      .fontColor(AppTheme.text(SETTINGS_SECONDARY_TEXT_COLOR)).width('100%').margin({ top: 8, bottom: 4 })
    Text('在线 NPU 编图缓存').fontSize(13).width('100%').margin({ top: 8, bottom: 6 })
    Row({ space: 8 }) {
      Button('查看 chunk OM').height(SETTINGS_BUTTON_HEIGHT).layoutWeight(1).fontSize(12)
        .borderRadius(SETTINGS_CONTROL_RADIUS).backgroundColor(AppTheme.background('#A9B0BE')).enabled(false)
      Button('删除 chunk OM').height(SETTINGS_BUTTON_HEIGHT).layoutWeight(1).fontSize(12)
        .borderRadius(SETTINGS_CONTROL_RADIUS).backgroundColor(AppTheme.background('#A9B0BE')).enabled(false)
    }.width('100%').margin({ top: 8 })
    SettingsOutputPanel({ title: '当前模型 chunk OM', content: 'Android 的 CPU 推理不产生 NPU 编图缓存。' })
    SettingInput({ label: 'CPU 线程数', value: this.threads, placeholder: '1–8，默认 4', inputType: InputType.Number,
      onValueChange: (value: string): void => { this.threads = value; } })
    Button(this.importing ? '取消导入' : '导入 GGUF 模型').width('100%').height(SETTINGS_BUTTON_HEIGHT)
      .fontSize(12).fontColor(Color.White).margin({ top: 8 }).borderRadius(SETTINGS_CONTROL_RADIUS)
      .backgroundColor(AppTheme.filledBackground(settingsActionColor(SettingsActionKind.Primary)))
      .onClick(async (): Promise<void> => { await this.onImport(); })
  }

  @Builder DownloadGroup() {
    SettingInput({ label: '', value: this.repo, placeholder: '输入 owner/repo', inputEnabled: !this.downloading,
      onInputFocus: (): void => { this.onCloseInputs(); },
      onValueChange: (value: string): void => { this.repo = value; } })
    if (this.repo.trim() === DEFAULT_MODELSCOPE_MNN_REPO) {
      Text('默认仓库提供 MNN 模型包。Android 本地聊天需要单文件 GGUF 文字模型。')
        .fontSize(12).fontColor(AppTheme.text(SETTINGS_SECONDARY_TEXT_COLOR)).width('100%')
    }
    Button(this.pausedRepo.length > 0 && this.pausedRepo === this.repo.trim() ? '继续下载' : '从 ModelScope 下载')
      .width('100%').height(SETTINGS_BUTTON_HEIGHT).fontSize(12).fontColor(Color.White)
      .margin({ top: 8 }).borderRadius(SETTINGS_CONTROL_RADIUS).enabled(!this.downloading)
      .backgroundColor(AppTheme.filledBackground(settingsActionColor(SettingsActionKind.Primary)))
      .onClick(async (): Promise<void> => { this.onCloseInputs(); await this.onDownload(); })
    this.progress()
  }

  build() {
    Column() {
      SettingsGroupCard({ title: '本地模型', subtitle: '已下载模型管理与加载路径切换',
        purpose: '在手机上加载和运行端侧大模型。', onHeaderClick: this.onCloseInputs,
        contentBuilder: () => { this.LocalModelGroup(); } })
      SettingsGroupCard({ title: '本地模型下载', subtitle: '从 ModelScope 仓库下载本地模型',
        purpose: '下载端侧推理所需的模型文件。', onHeaderClick: this.onCloseInputs,
        contentBuilder: () => { this.DownloadGroup(); } })
    }.width('100%')
  }
}
