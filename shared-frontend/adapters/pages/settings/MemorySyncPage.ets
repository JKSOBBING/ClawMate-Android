import Bridge from '@arkui-x.bridge';
import { FrostedPage } from '../../components/FrostedPage';
import { AppHeader } from '../../components/AppHeader';
import { common } from '@kit.AbilityKit';
import { router, promptAction } from '@kit.ArkUI';
import { DataManagerFacade } from '../../datamanager/DataManagerFacade';
import { DataPipelineModelClient } from '../../datamanager/DataPipelineModelClient';
import { DataManagerModelConfig, DataManagerProgress } from '../../datamanager/DataManagerTypes';
import { IndexService } from '../../datamanager/IndexService';
import { CloudModelClient } from '../../utils/CloudModelClient';
import { AiServiceAccess } from '../../utils/AiServiceAccess';
import { PrivacyConsentDialog } from '../../components/PrivacyConsentDialog';
import { AppTheme } from '../../utils/AppTheme';

interface PageAction { title: string; detail: string; action: () => void; }
@Entry
@Component
struct MemorySyncPage {
  private scroller: Scroller = new Scroller();
  @State busy: boolean = false;
  @State status: string = '尚未执行同步';
  @State progress: number = 0;
  private active: boolean = false;
  private generation: number = 0;
  private client: CloudModelClient = new CloudModelClient();
  private facade: DataManagerFacade | null = null;
  private ensureActive(): void { if (!this.active) { throw new Error('用户取消记忆同步；已提交的批次保留，下次可继续'); } }
  private config(): DataManagerModelConfig {
    let baseUrl = (AppStorage.get<string>('cloudPlannerBaseUrl') || '').trim();
    let model = (AppStorage.get<string>('cloudPlannerModel') || '').trim();
    if (!baseUrl.startsWith('https://') || model.length === 0) { throw new Error('请先在“模型与智能体”保存 HTTPS 地址和模型名称'); }
    return { baseUrl: baseUrl, model: model, apiKey: AppStorage.get<string>('cloudPlannerApiKey') ||
      AppStorage.get<string>('cloudApiKey') || '', enabled: true, ensureActive: (): void => { this.ensureActive(); } };
  }
  private async sync(): Promise<void> {
    if (this.busy || AppStorage.get<boolean>('androidMemorySyncBusy') === true) { return; }
    this.busy = true; AppStorage.setOrCreate('androidMemorySyncBusy', true);
    let generation = this.generation;
    try {
      AiServiceAccess.requireAllowed(); let config = this.config();
      if (!await PrivacyConsentDialog.show(this.getUIContext())) { return; }
      let choice = await promptAction.showDialog({ title: '同步聊天长期记忆？',
        message: '将向 ' + config.baseUrl + ' 的模型 ' + config.model + ' 发送近7天未处理的云端及本地聊天文字、有限上下文，以及待整理日志。排除工具记录和推理。提取结果写入本机长期记忆；删除聊天不会删除已保存记忆。可取消并保留已完成批次。',
        buttons: [{ text: '取消', color: '#65718B' }, { text: '确认同步', color: '#4F68FF' }] });
      if (choice.index !== 1) { return; }
      if (generation !== this.generation) { throw new Error('页面已关闭，同步未执行'); }
      let latest = this.config();
      if (latest.baseUrl !== config.baseUrl || latest.model !== config.model || latest.apiKey !== config.apiKey) { throw new Error('模型配置发生变化，请重新确认'); }
      this.active = true; this.progress = 0; this.status = '正在检查待同步记录';
      let context = getContext(this) as common.UIAbilityContext;
      let facade = new DataManagerFacade(context.filesDir, new DataPipelineModelClient(this.client));
      this.facade = facade;
      let result = await facade.refresh(config, (value: DataManagerProgress): void => {
        this.ensureActive(); this.status = value.stage + '：' + value.message; this.progress = value.percent;
      });
      this.ensureActive(); this.status = result.message + '\n索引事件：' + result.eventCount.toString(); this.progress = 100;
    } catch (error) { this.status = (error as Error).message; }
    finally { this.active = false; this.busy = false; AppStorage.setOrCreate('androidMemorySyncBusy', false); }
  }
  private async rebuildLocal(): Promise<void> {
    if (this.busy || AppStorage.get<boolean>('androidMemorySyncBusy') === true) { return; }
    this.busy = true; AppStorage.setOrCreate('androidMemorySyncBusy', true);
    try {
      let context = getContext(this) as common.UIAbilityContext;
      let facade = new DataManagerFacade(context.filesDir); facade.ensureReady();
      if (facade.storage.findFiles(facade.paths.outputsRoot, '.md.state.json').length === 0) {
        throw new Error('尚无已整理的长期记忆，请先同步；此操作不会调用模型或把原始聊天当作已整理记录');
      }
      let service = new IndexService(facade.paths, facade.storage, new DataPipelineModelClient());
      let result = await service.build({ baseUrl: '', apiKey: '', enabled: false });
      this.status = '本机索引已重建：' + result.eventCount.toString() + ' 条事件；未调用云服务';
    } catch (error) { this.status = (error as Error).message; }
    finally { this.busy = false; AppStorage.setOrCreate('androidMemorySyncBusy', false); }
  }
  private cancel(): void { this.generation += 1; this.active = false; this.client.cancelActiveRequests(); }
  onPageHide(): void { this.cancel(); }
  aboutToDisappear(): void { this.cancel(); }
  onBackPress(): boolean { this.cancel(); router.back(); return true; }
  @Builder Action($$: PageAction) {
    Row({ space: 12 }) {
      Column({ space: 5 }) {
        Text($$.title).fontSize(16).fontColor(AppTheme.text('#161B28')).width('100%')
        Text($$.detail).fontSize(12).lineHeight(18).fontColor(AppTheme.text('#7C879A')).width('100%')
      }.layoutWeight(1).alignItems(HorizontalAlign.Start)
      Text('›').fontSize(26).fontColor(AppTheme.text('#8993A4'))
    }.padding(18).width('100%').constraintSize({ minHeight: 78 }).enabled(!this.busy).onClick($$.action)
  }
  @Builder Line() { Divider().color(AppTheme.border('#EDF0F5')).margin({ left: 18, right: 18 }) }
  @Builder Header() { AppHeader({ title: '长期记忆与技能', showBackButton: true, onBackClick: () => { this.cancel(); router.back(); } }) }
  @Builder Content() {
    Scroll(this.scroller) {
      Column({ space: 20 }) {
        Column({ space: 8 }) {
          Text('越用，越了解你').fontSize(21).fontWeight(FontWeight.Bold).fontColor(AppTheme.text('#161B28')).width('100%')
          Text('管理助手记住的偏好、用户画像和学会的做法。新会话也能使用；每一项都可以查看和删除。')
            .fontSize(14).lineHeight(22).fontColor(AppTheme.text('#68758B')).width('100%')
        }.padding(20).width('100%').backgroundColor(AppTheme.background('#EDF3FC')).borderRadius(20)
        Column() {
          this.Action({ title: 'Mika 记忆与技能', detail: '用户记忆、画像、任务摘要、学习草稿与开关', action: () => {
            Bridge.createBridge('ClawMatePlatform').callMethod('mikaMemorySettings').catch(() => { this.status = '记忆管理暂不可用，请重试'; });
          } })
        }.width('100%').backgroundColor(AppTheme.background('#FFFFFF')).borderRadius(20)
        Text('聊天与采集资料').fontSize(14).fontWeight(FontWeight.Medium).fontColor(AppTheme.text('#68758B')).width('100%').padding({ left: 8 })
        Column() {
          this.Action({ title: '整理聊天与采集记忆', detail: '确认后由所选模型整理近期文字，保存为可检索资料', action: () => { this.sync(); } })
          this.Line()
          this.Action({ title: '重建本机索引', detail: '刷新已整理资料的检索索引，无需联网', action: () => { this.rebuildLocal(); } })
        }.width('100%').backgroundColor(AppTheme.background('#FFFFFF')).borderRadius(20)
        Column({ space: 12 }) {
          Text(this.status).fontSize(13).lineHeight(20).fontColor(AppTheme.text('#68758B')).width('100%').copyOption(CopyOptions.InApp)
          if (this.busy) {
            Progress({ value: this.progress, total: 100, type: ProgressType.Linear }).width('100%')
            Button('取消整理').height(40).fontSize(14).fontColor(AppTheme.text('#3978E8')).backgroundColor(AppTheme.background('#EEF3FC')).onClick(() => { this.cancel(); })
          }
        }.padding(18).width('100%').backgroundColor(AppTheme.background('#FFFFFF')).borderRadius(20)
        Text('聊天资料索引和 Mika 用户记忆分别管理。删除聊天不会自动删除已保存的记忆。')
          .fontSize(12).lineHeight(18).fontColor(AppTheme.text('#8993A4')).width('100%').padding({ left: 8, right: 8 })
      }.width('92%').constraintSize({ maxWidth: 720 }).padding({ top: 16, bottom: 40 })
    }.width('100%').height('100%').scrollBar(BarState.Off)
  }
  build() {
    Column() { FrostedPage({ pageBackgroundColor: '#F4F6FA', scrollers: [this.scroller], headerBuilder: () => { this.Header(); }, contentBuilder: () => { this.Content(); } }) }.width('100%').height('100%')
  }
}
