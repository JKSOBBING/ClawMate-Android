import Bridge from '@arkui-x.bridge';
import { router } from '@kit.ArkUI';
import { AppTheme } from '../../utils/AppTheme';
import { FrostedPage } from '../../components/FrostedPage';
import { AppHeader } from '../../components/AppHeader';
interface PageAction { title: string; detail: string; action: () => void; }
interface ControlState { supported: boolean; connected: boolean; helperAvailable: boolean; }
@Entry
@Component
struct GuiPlanPage {
  private scroller: Scroller = new Scroller();
  @State status: string = '正在检查连接…';
  @State connected: boolean = false;
  @State helperAvailable: boolean = false;
  @State busy: boolean = false;
  onPageShow(): void { this.refresh(); }
  aboutToAppear(): void { this.refresh(); }
  private async refresh(): Promise<void> {
    try {
      let state = JSON.parse(await Bridge.createBridge('ClawMatePlatform').callMethod('guiControlStatus') as string) as ControlState;
      this.connected = state.connected;
      this.helperAvailable = state.helperAvailable;
      this.status = state.connected ? '无障碍服务已连接' : state.supported ? '请开启无障碍服务以操作其他应用' : '手机操作需要 Android 14 及以上';
    } catch (error) { this.status = '暂时无法检查连接，请稍后重试'; }
  }
  private async open(method: string): Promise<void> {
    if (this.busy) { return; }
    this.busy = true;
    try { await Bridge.createBridge('ClawMatePlatform').callMethod(method); await this.refresh(); }
    catch (error) { this.status = (error as Error).message; }
    finally { this.busy = false; }
  }
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
  @Builder Header() { AppHeader({ title: '界面采集与规划', showBackButton: true, onBackClick: () => { router.back(); } }) }
  @Builder Content() {
    Scroll(this.scroller) {
      Column({ space: 20 }) {
        Column({ space: 8 }) {
          Text('让助手操作手机').fontSize(21).fontWeight(FontWeight.Bold).fontColor(AppTheme.text('#161B28')).width('100%')
          Text('在云端聊天里描述任务，助手会按应用访问设置打开应用、搜索和浏览。这里管理操作权限与节奏。')
            .fontSize(14).lineHeight(22).fontColor(AppTheme.text('#68758B')).width('100%')
          Text(this.status).fontSize(13).fontColor(AppTheme.text(this.connected ? '#287354' : '#976122')).width('100%').margin({ top: 6 })
        }.padding(20).width('100%').backgroundColor(AppTheme.background('#EDF3FC')).borderRadius(20)
        Column() {
          this.Action({ title: '应用访问', detail: '选择云端助手可以操作的应用', action: () => { this.open('mikaAppAccessSettings'); } })
          this.Line()
          this.Action({ title: '操作节奏', detail: '调整操作速度，也可为不同应用单独设置', action: () => { this.open('mikaPaceSettings'); } })
          this.Line()
          this.Action({ title: '无障碍服务', detail: '查看并设置手机操作所需的系统权限', action: () => { this.open('guiControlSettings'); } })
          this.Line()
          this.Action({ title: '无线配对', detail: this.helperAvailable ? '后台屏助手已连接，管理本机无线配对' : '通过手机的无线调试连接后台屏助手', action: () => { this.open('guiWirelessPairing'); } })
        }.width('100%').backgroundColor(AppTheme.background('#FFFFFF')).borderRadius(20)
        Text('手动控制与采集').fontSize(14).fontWeight(FontWeight.Medium).fontColor(AppTheme.text('#68758B')).width('100%').padding({ left: 8 })
        Column() {
          this.Action({ title: '手动控制', detail: '选择要操作的应用', action: () => { this.open('guiControlOpen'); } })
          this.Line()
          this.Action({ title: '手动控制授权', detail: '管理各应用的长期授权，授权后点按、滑动和返回可直接执行', action: () => { this.open('guiControlGrants'); } })
          this.Line()
          this.Action({ title: '采集应用内容', detail: '选择应用，读取当前页或连续浏览；确认后保存到本机', action: () => { this.open('guiCollectionOpen'); } })
          this.Line()
          this.Action({ title: '后台悬浮窗', detail: '管理桌面和其他应用上的悬浮便签', action: () => { this.open('guiFloatingSettings'); } })
          this.Line()
          this.Action({ title: '已采集内容', detail: '查看之前保存在本机的采集记录', action: () => { this.open('guiCollectionHistory'); } })
        }.width('100%').backgroundColor(AppTheme.background('#FFFFFF')).borderRadius(20)
      }.width('92%').constraintSize({ maxWidth: 720 }).padding({ top: 16, bottom: 40 })
    }.width('100%').height('100%').scrollBar(BarState.Off)
  }
  build() {
    Column() { FrostedPage({ pageBackgroundColor: '#F4F6FA', scrollers: [this.scroller], headerBuilder: () => { this.Header(); }, contentBuilder: () => { this.Content(); } }) }.width('100%').height('100%')
  }
}
