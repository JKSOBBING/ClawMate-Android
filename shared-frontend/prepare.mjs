import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';

const here = path.dirname(fileURLToPath(import.meta.url));
const repo = path.resolve(here, '..');
const scratch = path.join(repo, '.arkui-x-validation');
const template = path.join(scratch, 'project');
const project = path.join(scratch, 'application/project');
fs.mkdirSync(project, { recursive: true });
fs.writeFileSync(path.join(project, 'local.properties'),
  'sdk.dir=' + path.join(scratch, 'openharmony-sdk').split(path.sep).join('/') + '\n' +
  'arkui-x.sdk.dir=' + path.join(scratch, 'sdk').split(path.sep).join('/') + '\n');
// Only copy known template configuration and resources, never application data,
// local.properties, signing files, node_modules contents or build outputs.
for (const item of ['AppScope', 'hvigor', 'entry/src/main/resources', 'entry/src/main/ets/entryability']) {
  fs.cpSync(path.join(template, item), path.join(project, item), { recursive: true });
}
for (const item of ['hvigorfile.ts', 'oh-package.json5', 'entry/oh-package.json5', 'entry/hvigorfile.ts', 'entry/src/main/module.json5']) {
  fs.copyFileSync(path.join(template, item), path.join(project, item));
}
const writeJson = (name, value) => {
  const file = path.join(project, name);
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, JSON.stringify(value, null, 2));
};
writeJson('build-profile.json5', { app: { signingConfigs: [], products: [{ name: 'default',
  compileSdkVersion: 20, compatibleSdkVersion: 20, targetSdkVersion: 20, runtimeOS: 'OpenHarmony' }],
  buildModeSet: [{ name: 'debug' }, { name: 'release' }] },
  modules: [{ name: 'entry', srcPath: './entry', targets: [{ name: 'default', applyToProducts: ['default'] }] }] });
writeJson('.arkui-x/arkui-x-config.json5', { crossplatform: true, modules: ['entry'] });
const app = JSON.parse(fs.readFileSync(path.join(project, 'AppScope/app.json5'), 'utf8'));
app.app.bundleName = 'com.clawmate.shared';
writeJson('AppScope/app.json5', app);
const modules = path.join(project, 'node_modules');
if (!fs.existsSync(modules)) fs.symlinkSync(path.join(scratch, 'build-tools/node_modules'), modules, 'junction');
const generatedOverlay = path.join(scratch, 'application/overlays');
fs.cpSync(path.join(here, 'adapters'), generatedOverlay, { recursive: true });
const reference = path.join(scratch, 'reference/entry/src/main/ets');
const read = name => fs.readFileSync(path.join(reference, name), 'utf8');
const overlay = (name, value) => {
  const file = path.join(generatedOverlay, name);
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, value);
};
const mainTabs = read('pages/main/FloatingMainTabs.ets');
const navigationConstants = ['TAB_BAR_HEIGHT', 'TAB_BAR_BOTTOM_MARGIN', 'TAB_BAR_SIDE_MARGIN'].map(name => {
  const match = mainTabs.match(new RegExp(`const ${name}: number = \\d+;`));
  if (!match) throw new Error('Upstream navigation metric changed: ' + name);
  return 'export ' + match[0];
});
const sourceIndex = read('pages/Index.ets');
const navigationMethods = ['tabIconResource', 'tabBarStyle'].map(name => {
  const match = sourceIndex.match(new RegExp(`  private ${name}\\([^]*?\\n  \\}`));
  if (!match) throw new Error('Upstream navigation method changed: ' + name);
  return match[0].replace('private ', 'static ').replaceAll('this.tabIconResource', 'NavigationStyle.tabIconResource');
});
overlay('platform/NavigationStyle.ets', "import { AppTheme } from '../utils/AppTheme';\n" +
  "import { MainTabIndex, mainTabIcon, mainTabLabel } from '../pages/main/MainTabConfig';\n" +
  navigationConstants.join('\n') + '\nexport const MAIN_NAVIGATION_INSET: number = TAB_BAR_HEIGHT + TAB_BAR_BOTTOM_MARGIN + 12;\n' +
  'export class NavigationStyle {\n' + navigationMethods.join('\n') + '\n}\n');
overlay('utils/gallery/GalleryAnalyzer.ets', read('utils/gallery/GalleryAnalyzer.ets')
  .replace('this.ocrService.recognize(pixelMap)', 'this.ocrService.recognizeUri(uri)'));
let messageList = read('pages/chat/ChatMessageList.ets')
  .replace("import { pasteboard } from '@kit.BasicServicesKit';", "import { pasteboard } from '../../platform/Pasteboard';");
messageList = messageList.replace('  private replayAvailable(): boolean {',
  "  private replayAvailable(): boolean {\n    if (this.detail.voice.provider === 'android-system-tts') { return this.voiceStatus !== 'synthesizing'; }");
overlay('pages/chat/ChatMessageList.ets', messageList);
let homePage = read('pages/home/HomePage.ets');
for (const name of ['selectedRecommendationId', 'selectedTaskSuggestionId', 'avatarGenerationOpen', 'avatarHistoryOpen', 'selectedAvatarVersionId']) {
  homePage = homePage.replace('@State ' + name + ':', "@State @Watch('reportAndroidOverlay') " + name + ':');
}
homePage = homePage.replace('  private isWideProfileOverview(): boolean {',
  "  @StorageProp('androidHomeBackRequested') @Watch('handleAndroidBack') private androidBackRequest: number = 0;\n" +
  "  private reportAndroidOverlay(): void { AppStorage.setOrCreate('androidHomeOverlayOpen', this.avatarGenerationOpen || this.avatarHistoryOpen || this.selectedAvatarVersionId.length > 0 || this.selectedRecommendationId.length > 0 || this.selectedTaskSuggestionId.length > 0); }\n" +
  "  private handleAndroidBack(): void {\n    if (this.selectedAvatarVersionId.length > 0) { this.selectedAvatarVersionId = ''; }\n" +
  "    else if (this.avatarHistoryOpen) { this.avatarHistoryOpen = false; }\n    else if (this.avatarGenerationOpen) { this.avatarGenerationOpen = false; }\n" +
  "    else if (this.selectedTaskSuggestionId.length > 0) { this.selectedTaskSuggestionId = ''; }\n    else { this.selectedRecommendationId = ''; }\n  }\n" +
  '  private isWideProfileOverview(): boolean {');
overlay('pages/home/HomePage.ets', homePage);
let debug = read('utils/DebugSettings.ets').replace("import native from 'libentry.so';\n", '')
  .replace(/^.*native\.(initLogFile|setDebugLogging).*\n/gm, '');
overlay('utils/DebugSettings.ets', debug);
let settings = read('pages/settings/SettingsCenterPage.ets');
overlay('pages/settings/SettingsCenterPage.ets', settings
  .replace('开启后显示并记录聊天和手机 HDC 日志', '开启后记录本次前端诊断；原生推理与自动控制日志尚未接入')
  .replace("subtitle: '聊天上下文压缩阈值和保留轮数'", "subtitle: '安卓按阈值提示新建会话，自动摘要尚未接入'")
  .replace('  @Builder ContextCompressionGroup() {',
    "  @Builder ContextCompressionGroup() {\n    Text('安卓尚未启用自动摘要；达到阈值时会提示新建会话。保留轮数仅保存配置，暂未生效。').fontSize(12).fontColor('#65718B').width('100%')"));
// Public defaults contain no environment service endpoints, credentials or tokens.
const endpointNames = new Set([...settings.matchAll(/DEFAULT_ENDPOINT_CONFIG\.(\w+)/g)].map(match => match[1]));
overlay('config/DefaultEndpointConfig.ets',
  'interface AndroidEndpointDefaults {\n' + [...endpointNames].sort().map(name =>
    `  ${name}: ${name === 'voiceTtsStreamingEnabled' ? 'boolean' : 'string'};`).join('\n') + '\n}\n' +
  'export const DEFAULT_ENDPOINT_CONFIG: AndroidEndpointDefaults = {\n' + [...endpointNames].sort().map(name =>
    `  ${name}: ${name === 'voiceTtsStreamingEnabled' ? 'false' : "''"},`).join('\n') + '\n};\n' +
  "export function configuredEndpointSecretOrDefault(value: string, fallback: string = ''): string { return value.trim() || fallback; }\n");
const stringKeys = new Set([...settings.matchAll(/@StorageLink\('([^']+)'\)/g)].map(match => match[1]));
['firstLaunchOnboardingVersion', 'androidTaskControlMode', 'threadNumInput', 'desktopCompanionDownloadUrl'].forEach(key => stringKeys.add(key));
const booleanKeys = ['androidCloudAccessGranted', 'appDarkMode', 'autoConfirm', 'cloudAutoConfirm'];
stringKeys.delete('voiceTtsStreamingEnabled');
booleanKeys.push('voiceTtsStreamingEnabled');
overlay('platform/SettingsKeys.ets',
  'export const SETTINGS_STRING_KEYS: string[] = ' + JSON.stringify([...stringKeys].sort()) + ';\n' +
  'export const SETTINGS_BOOLEAN_KEYS: string[] = ' + JSON.stringify(booleanKeys) + ';\n');
const galleryConstants = [...new Set([...settings.matchAll(/\b(?:DEFAULT_GALLERY_\w+|GALLERY_\w+_DEFAULT_SNAPSHOT)\b/g)].map(match => match[0]))];
overlay('utils/gallery/GallerySettingsDefaults.ets', galleryConstants.sort().map(name => {
  let value = name.endsWith('_SNAPSHOT') ? name.toLowerCase() : /CONCURRENCY$/.test(name) ? '1' :
    name === 'DEFAULT_GALLERY_IMAGE_SCALE' ? '1' : /(?:SKIP_OCR|USE_OCR_KEYWORD_FILTER|PARALLEL_FIELD_REQUESTS)$/.test(name) ? 'false' : '';
  return `export const ${name}: string = ${JSON.stringify(value)};`;
}).join('\n') + '\nexport function registerGallerySettingsDefaults(): void {}\n' +
  "export function galleryApiUsesLegacyDefaults(url: string, model: string): boolean { return url.length === 0 && model.length === 0; }\n");
let controls = read('pages/settings/SettingsCenterControls.ets');
controls = "import { SettingsStore } from '../../platform/SettingsStore';\n" + controls.replaceAll(
  'this.onValueChange(value);', 'this.onValueChange(value); SettingsStore.scheduleSave();');
controls = controls.replace('this.getUIContext().getFocusController().requestFocus(', 'focusControl.requestFocus(');
controls = controls.replace('  private previewText(): string {',
  "  private previewText(): string {\n    if (this.value.trim().length === 0 && this.defaultValue.trim().length === 0) { return '未配置'; }");
overlay('pages/settings/SettingsCenterControls.ets', controls);
overlay('pages/chat/ChatSessionDrawer.ets', read('pages/chat/ChatSessionDrawer.ets')
  .replace('选择会话后由上层恢复上下文', '选择历史会话继续聊天'));
let privacy = read('components/PrivacyConsentDialog.ets');
let start = privacy.indexOf('export const PRIVACY_DECLARATION: string =');
let end = privacy.indexOf('// Native ArkUI alert dialog', start);
if (start < 0 || end < 0) throw new Error('Privacy declaration boundary changed');
privacy = privacy.slice(0, start) + 'export const PRIVACY_DECLARATION: string = ' + JSON.stringify(
  '聊天、配置和索引保存在此应用的本地存储。仅在你主动使用云端功能并同意后，相关输入和上下文才会发送至你配置的模型服务。\n\n' +
  '服务提供方的数据保留规则由该服务决定，本应用无法保证其删除或不留存。不要向不信任的服务发送隐私信息。\n\n' +
  '安卓尚未接入华为账号和小艺 A2A。云服务授权不代表账号登录；应用不会通过该入口对外提供个人记忆。\n\n' +
  '拒绝后仍可浏览和编辑本地内容。可在“我的”撤回授权，停止新的请求并尝试取消正在进行的请求；已发送的数据无法收回。相册和麦克风权限会在使用对应功能时另行申请。\n\n') +
  " + '声明版本：' + PRIVACY_VERSION;\n\n" + privacy.slice(end);
overlay('components/PrivacyConsentDialog.ets', privacy);
overlay('components/AiLoginGuard.ets', read('components/AiLoginGuard.ets').replace("title: '请先登录'", "title: '请先授权云服务'"));
overlay('utils/PrivacyConsent.ets', read('utils/PrivacyConsent.ets').replace(
  "export const PRIVACY_VERSION: string = '2026-09-30-v2';", "export const PRIVACY_VERSION: string = '2026-10-26-android-v1';"));
// Use the cross-platform picker supported by this SDK. Unsupported HarmonyOS
// options are omitted, and the caller-side limit still bounds selected results.
overlay('utils/gallery/GalleryPhotoPicker.ets', read('utils/gallery/GalleryPhotoPicker.ets')
  .replace('      options.maxSelectNumber = maxSelectNumber;\n', '')
  .replace('      options.isOriginalSupported = true;\n', '')
  .replace('return result.photoUris;', 'return result.photoUris.slice(0, Math.max(1, Math.min(9, maxSelectNumber)));'));
let gallery = read('pages/gallery/GalleryPage.ets');
gallery = gallery.replace('import { abilityAccessCtrl, common, Permissions }', 'import { common }');
gallery = gallery.replace(/  private async ensureMediaLocationPermission\([^]*?\n  \}/,
  '  private async ensureMediaLocationPermission(ctx: common.UIAbilityContext): Promise<boolean> {\n' +
  '    return false; // No broad Android library/location permission is requested.\n  }');
gallery = gallery.replace('  onPageHide(): void {',
  '  onBackPress(): boolean {\n' +
  '    if (this.hdcSheetOpen) { this.hdcSheetOpen = false; return true; }\n' +
  '    if (this.settingsOpen) { this.settingsOpen = false; return true; }\n' +
  "    if (this.selectedDetailId.length > 0) { this.closeResultDetail(); return true; }\n" +
  '    router.back(); return true;\n  }\n  onPageHide(): void {');
overlay('pages/gallery/GalleryPage.ets', gallery);
for (const route of ['pages/collection/CollectionGroupDetailPage', 'pages/task/WorkflowFileViewer']) {
  let page = read(route + '.ets');
  page = page.replace(/struct (\w+) \{/, 'struct $1 {\n  onBackPress(): boolean { router.back(); return true; }');
  overlay(route + '.ets', page);
}
overlay('utils/gallery/GalleryImageStore.ets', read('utils/gallery/GalleryImageStore.ets')
  .replace('fileIo as fs, fileUri', 'fileIo as fs')
  .replace('fileUri.getUriFromPath(path)', "'file://' + path.split('/').map((part: string): string => encodeURIComponent(part)).join('/')"));
overlay('components/TaskDeviceControlPanel.ets', read('components/TaskDeviceControlPanel.ets')
  .replace("      Text('任务控制方式').fontSize(15).fontWeight(FontWeight.Bold).width('100%')",
    "      Text('任务控制方式').fontSize(15).fontWeight(FontWeight.Bold).width('100%')\n" +
    "      Text('安卓自动执行尚未接入；开关仅保存任务选择，不会自动执行').fontSize(12).fontColor('#65718B').width('100%')"));
let taskPage = read('pages/task/TaskPage.ets');
taskPage = taskPage.replace('  private pageScroller: Scroller = new Scroller();',
  "  @StorageProp('androidWorkflowBackRequested') @Watch('handleAndroidBack') private androidBackRequest: number = 0;\n" +
  '  private pageScroller: Scroller = new Scroller();\n' +
  "  private androidManagedFiles(category: string): WorkflowManagedFile[] { return category === 'config' ? this.configManagedFiles : category === 'daily' ? this.dailyLogManagedFiles : this.runManagedFiles; }\n" +
  '  private handleAndroidBack(): void { if (this.active && this.configViewOpen) { this.closeConfigView(); } }\n' +
  '  private requestCloseEditor(): void {\n' +
  "    promptAction.showDialog({ title: '放弃当前编辑？', message: '未保存的修改将丢失。', buttons: [\n" +
  "      { text: '继续编辑', color: '#65718B' }, { text: '放弃修改', color: '#B42318' }\n" +
  '    ] }).then((result: promptAction.ShowDialogSuccessResponse): void => { if (result.index === 1) { this.editorOpen = false; } }).catch(() => {});\n  }');
taskPage = taskPage.replace('  private closeConfigView(): void {\n    this.configViewOpen = false;',
  '  private closeConfigView(): void {\n    if (this.editorOpen) { this.requestCloseEditor(); return; }\n    this.configViewOpen = false;');
taskPage = taskPage.replace('.onClick(() => { this.editorOpen = false; })', '.onClick(() => { this.requestCloseEditor(); })');
taskPage = taskPage.replace("Text(subtitle + ' · ' + files.length + ' 个 · ' + WorkflowStorage.formatSize(this.totalFileSize(files)))",
  "Text(subtitle + ' · ' + this.androidManagedFiles(category).length + ' 个 · ' + WorkflowStorage.formatSize(this.totalFileSize(this.androidManagedFiles(category))))");
overlay('pages/task/TaskPage.ets', taskPage);
writeJson('entry/src/main/resources/base/profile/main_pages.json', { src: [
  'pages/Index', 'pages/gallery/GalleryPage', 'pages/collection/CollectionGroupDetailPage',
  'pages/task/WorkflowFileViewer', 'pages/settings/DesktopCompanionPage', 'pages/settings/SelfHdcTestPage',
  'pages/LogView', 'pages/OpTest', 'pages/ProfileTest' ] });
for (const name of ['onboarding_agent', 'onboarding_avatar', 'onboarding_data_home']) {
  fs.copyFileSync(path.join(repo, 'app/src/main/res/drawable-nodpi', name + '.png'),
    path.join(project, 'entry/src/main/resources/base/media', name + '.png'));
}
const prepared = spawnSync(process.execPath, [path.join(repo, 'experiments/arkui-x/prepare-project.mjs'),
  '--project', project, '--entry', path.join(here, 'app/Index.ets'),
  '--extra-lock', path.join(here, 'upstream-lock.json'), '--overlay', generatedOverlay, '--keep-facade',
  '--routes', path.join(project, 'entry/src/main/resources/base/profile/main_pages.json')],
  { cwd: repo, stdio: 'inherit' });
if (prepared.status !== 0) process.exit(prepared.status || 1);
for (const name of ['InputMethod', 'PlatformUi', 'Pasteboard']) {
  const file = path.join(project, 'entry/src/main/ets/platform', name + '.ets');
  fs.writeFileSync(file, fs.readFileSync(file, 'utf8').replaceAll('ClawMateProbe', 'ClawMatePlatform'));
}
for (const route of ['pages/settings/DesktopCompanionPage', 'pages/settings/SelfHdcTestPage', 'pages/LogView', 'pages/OpTest', 'pages/ProfileTest']) {
  const file = path.join(project, 'entry/src/main/ets', route + '.ets');
  const source = fs.readFileSync(file, 'utf8');
  fs.writeFileSync(file, source.replace(/struct (\w+) \{/, 'struct $1 {\n  onBackPress(): boolean { router.back(); return true; }'));
}
console.log(JSON.stringify({ product: 'com.clawmate.shared', project, sourceCommit: app.sourceCommit }));
