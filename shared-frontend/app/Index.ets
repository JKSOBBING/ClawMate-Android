import { common } from '@kit.AbilityKit';
import { promptAction, router } from '@kit.ArkUI';
import { FirstLaunchOnboarding } from '../pages/onboarding/FirstLaunchOnboarding';
import { FIRST_LAUNCH_ONBOARDING_VERSION, FIRST_LAUNCH_ONBOARDING_STORAGE_KEY } from '../pages/onboarding/OnboardingTypes';
import { HomePage } from '../pages/home/HomePage';
import { HomeComfortChatStore } from '../pages/home/HomeComfortChatStore';
import { CollectionPage } from '../pages/collection/CollectionPage';
import { TaskPage } from '../pages/task/TaskPage';
import { ChatPage } from '../pages/chat/ChatPage';
import { SettingsCenterPage } from '../pages/settings/SettingsCenterPage';
import { MAIN_TAB_ITEMS, MainTabItem, MainTabIndex, mainTabBackgroundColor } from '../pages/main/MainTabConfig';
import { CHAT_MODE_CLOUD_AGENT, CHAT_MODE_LOCAL_INFERENCE } from '../pages/chat/ChatPageTypes';
import { buildChatContextBudgetSettings, shouldCompressCloudContext } from '../pages/chat/ChatContextBudget';
import { ChatMessage, ChatSessionSummary, ChatSessionRecord, createChatMessage,
  buildCloudContextMessages, splitAssistantThinkingContent,
  toggleChatMessageCollapsed, toggleChatMessageThinkingCollapsed } from '../pages/chat/ChatSessionTypes';
import { CloudModelClient, CloudChatCompletionOptions } from '../utils/CloudModelClient';
import { ChatSessionStorage } from '../utils/ChatSessionStorage';
import { PrivacyConsent } from '../utils/PrivacyConsent';
import { PrivacyConsentDialog } from '../components/PrivacyConsentDialog';
import { AiServiceAccess } from '../utils/AiServiceAccess';
import { showToastSafely } from '../utils/PromptSafe';
import { SettingsStore } from '../platform/SettingsStore';
import { DataManagerFacade, createEmptyHomeProfileViewModel, createEmptyCollectionViewModels } from '../datamanager/DataManagerFacade';
import { HomeProfileViewModel, CollectionViewModel, IndexedEvent } from '../datamanager/DataManagerTypes';
import { AvatarGenerationMode, AvatarVersionViewModel, AvatarProviderConfig } from '../datamanager/avatar/AvatarTypes';
import { HomeTaskSuggestion, buildHomeTaskSuggestionPrompt, parseHomeTaskSuggestions } from '../pages/home/HomeTaskSuggestion';
import { HomeComfortConcern, HomeComfortChatMessage, buildHomeComfortConcernPrompt,
  parseHomeComfortConcerns, buildHomeComfortChatMessages, parseHomeComfortReply } from '../pages/home/HomeEmotionalSupport';
import { nowIsoLocal } from '../datamanager/DataManagerUtils';

interface HomeModelState { suggestions: HomeTaskSuggestion[]; concerns: HomeComfortConcern[]; }

@Entry
@Component
struct Index {
  @State ready: boolean = false;
  @State startupError: string = '';
  @State onboarding: boolean = true;
  @State @Watch('onNavigationChanged') tab: number = MainTabIndex.Home;
  @State @Watch('saveSettings') settingsSection: number = -1;
  @State collectionScene: number = 1;
  @State taskScene: number = 0;
  @State configViewOpen: boolean = false;
  @State profile: HomeProfileViewModel = createEmptyHomeProfileViewModel();
  @State collectionViews: CollectionViewModel[] = createEmptyCollectionViewModels();
  @State avatarVersions: AvatarVersionViewModel[] = [];
  @State comfort: HomeComfortChatStore = new HomeComfortChatStore();
  @State suggestions: HomeTaskSuggestion[] = [];
  @State concerns: HomeComfortConcern[] = [];
  @State suggestionsLoading: boolean = false;
  @State concernsLoading: boolean = false;
  @State refreshing: boolean = false;
  @State mode: string = CHAT_MODE_CLOUD_AGENT;
  @State cloudInput: string = '';
  @State localInput: string = '';
  @State deepThinking: boolean = false;
  @State thinking: boolean = false;
  @State drawer: boolean = false;
  @State sessions: ChatSessionSummary[] = [];
  @State sessionId: string = '';
  @State messages: ChatMessage[] = [];
  @StorageLink('autoConfirm') @Watch('saveSettings') autoConfirm: boolean = false;
  @StorageLink('cloudAutoConfirm') @Watch('saveSettings') cloudAutoConfirm: boolean = false;
  @StorageLink('threadNumInput') @Watch('saveSettings') threads: string = '4';
  @State output: string = '';
  @State cloudOutput: string = '';
  @State cacheOutput: string = '安卓本地推理尚未接入';
  @State downloading: boolean = false;
  @State modelDownloading: boolean = false;
  @State pauseDownload: boolean = false;
  @State progressVisible: boolean = false;
  @State progressPercent: number = 0;
  @State progressText: string = '';
  @State progressTitle: string = '';
  private client: CloudModelClient = new CloudModelClient();
  private facade: DataManagerFacade | null = null;
  private activeRecord: ChatSessionRecord | null = null;
  private generation: number = 0;
  private streamRaw: string = '';
  private saveTimer: number = -1;

  private context(): common.UIAbilityContext { return getContext(this) as common.UIAbilityContext; }
  aboutToAppear(): void {
    this.initialize().catch((error: Error): void => { this.startupError = error.message; });
  }
  onPageHide(): void { this.saveSettings(); this.persistChat(); }
  aboutToDisappear(): void { this.client.cancelActiveRequests(); this.saveSettings(); this.persistChat(); }
  onBackPress(): boolean {
    this.saveSettings(); this.persistChat();
    if (Number(router.getLength()) > 1) { router.back(); return true; }
    if (this.drawer) { this.drawer = false; return true; }
    if (this.tab === MainTabIndex.Mine && this.settingsSection >= 0) { this.settingsSection = -1; return true; }
    if (this.tab === MainTabIndex.Task && this.configViewOpen) {
      AppStorage.setOrCreate('androidWorkflowBackRequested', Date.now()); return true;
    }
    if (this.tab !== MainTabIndex.Home) { this.tab = MainTabIndex.Home; return true; }
    return false;
  }

  private async initialize(): Promise<void> {
    let context = this.context();
    SettingsStore.initialize(context);
    await PrivacyConsent.initialize(context);
    this.onboarding = AppStorage.get<string>(FIRST_LAUNCH_ONBOARDING_STORAGE_KEY) !== FIRST_LAUNCH_ONBOARDING_VERSION;
    this.facade = new DataManagerFacade(context.filesDir);
    this.facade.ensureReady();
    this.reloadData();
    let homeState = this.facade.storage.readJson<HomeModelState>(context.filesDir + '/shared-home-state.json', { suggestions: [], concerns: [] });
    this.suggestions = homeState.suggestions || []; this.concerns = homeState.concerns || [];
    this.reloadSessions();
    if (this.sessions.length > 0) { await this.selectSession(this.sessions[0].id); }
    this.ready = true;
  }

  private completeOnboarding(): void {
    try {
      AppStorage.setOrCreate(FIRST_LAUNCH_ONBOARDING_STORAGE_KEY, FIRST_LAUNCH_ONBOARDING_VERSION);
      SettingsStore.save();
      this.onboarding = false;
    } catch (error) { showToastSafely('引导状态保存失败，请重试'); }
  }

  private saveSettings(): void {
    try { SettingsStore.save(); } catch (error) { if (this.ready) { showToastSafely('设置保存失败，请重试'); } }
  }
  private onNavigationChanged(): void { this.saveSettings(); this.persistChat(); this.drawer = false; }
  private reloadData(): void {
    if (this.facade === null) { return; }
    this.profile = this.facade.loadHomeViewModel();
    this.collectionViews = this.facade.loadCollectionViewModels();
    this.avatarVersions = this.facade.listAvatarVersions();
  }
  private unavailable(feature: string): void { showToastSafely(feature + '尚未接入安卓适配；可继续使用本地浏览和编辑'); }
  private recordRuntime(message: string): void {
    if (AppStorage.get<boolean>('debugEnabled') !== true) { return; }
    let previous = AppStorage.get<string>('cloudAgentLogs') || '';
    AppStorage.setOrCreate('cloudAgentLogs', (previous + '\n[' + nowIsoLocal() + '] ' + message).slice(-30000));
  }
  private async authorizeCloud(): Promise<void> {
    AiServiceAccess.requireAllowed();
    if (!await PrivacyConsentDialog.show(this.getUIContext())) { throw new Error('未同意云端处理，操作未执行'); }
  }
  private cloudOptions(): CloudChatCompletionOptions {
    let baseUrl = (AppStorage.get<string>('cloudPlannerBaseUrl') || '').trim();
    let model = (AppStorage.get<string>('cloudPlannerModel') || '').trim();
    if (!baseUrl.startsWith('https://') || model.length === 0) { throw new Error('请先配置 HTTPS 模型地址和模型名称'); }
    return { baseUrl: baseUrl, model: model, messages: [], readTimeout: 60000,
      apiKey: AppStorage.get<string>('cloudPlannerApiKey') || AppStorage.get<string>('cloudApiKey') || '' };
  }
  private homeEvents(): IndexedEvent[] {
    if (this.facade === null) { return []; }
    let start = new Date(); start.setDate(start.getDate() - 7);
    let startDate = String(start.getFullYear()) + '-' + String(start.getMonth() + 1).padStart(2, '0') + '-' + String(start.getDate()).padStart(2, '0');
    return this.facade.queryIndexedEvents({ keywords: [], domains: [], entities: [], date: '', month: '',
      startDate: startDate, endDate: nowIsoLocal().slice(0, 10), workflowSceneIds: [], limit: 48 }).events;
  }
  private persistHomeState(): void {
    if (this.facade !== null) { let state: HomeModelState = { suggestions: this.suggestions, concerns: this.concerns };
      this.facade.storage.writeJsonAtomic(this.context().filesDir + '/shared-home-state.json', state as Object); }
  }
  private async refreshSuggestions(): Promise<void> {
    if (this.suggestionsLoading) { return; }
    await this.authorizeCloud(); let options = this.cloudOptions(); let events = this.homeEvents();
    this.suggestionsLoading = true;
    try {
      let previous = this.suggestions.map((item: HomeTaskSuggestion): string => item.task);
      options.messages = [{ role: 'user', content: buildHomeTaskSuggestionPrompt(events, this.profile.domains, previous, nowIsoLocal().slice(0, 10)) }];
      let response = await this.client.chatCompletion(options);
      this.suggestions = parseHomeTaskSuggestions(response.content, String(Date.now()), previous, events, this.profile.domains);
      this.persistHomeState();
    } finally { this.suggestionsLoading = false; }
  }
  private async refreshConcerns(): Promise<void> {
    if (this.concernsLoading) { return; }
    await this.authorizeCloud(); let options = this.cloudOptions(); let events = this.homeEvents();
    this.concernsLoading = true;
    try {
      options.messages = [{ role: 'user', content: buildHomeComfortConcernPrompt(events, nowIsoLocal().slice(0, 10)) }];
      let response = await this.client.chatCompletion(options);
      this.concerns = parseHomeComfortConcerns(response.content, String(Date.now()), events);
      this.comfort.clear(); this.persistHomeState();
    } finally { this.concernsLoading = false; }
  }
  private async sendComfort(concern: HomeComfortConcern, history: HomeComfortChatMessage[]): Promise<string> {
    await this.authorizeCloud(); let options = this.cloudOptions();
    options.messages = buildHomeComfortChatMessages(concern, history, this.homeEvents(), nowIsoLocal().slice(0, 10));
    let response = await this.client.chatCompletion(options); return parseHomeComfortReply(response.content);
  }
  private async refreshCollection(): Promise<void> {
    if (this.refreshing || this.facade === null) { return; }
    if (!this.facade.hasDataToSync()) { showToastSafely('暂无可同步的本地数据，请先完成图库分析或添加采集记录'); return; }
    try {
      await this.authorizeCloud(); let options = this.cloudOptions(); this.refreshing = true;
      await this.facade.refresh({ baseUrl: options.baseUrl, apiKey: options.apiKey, enabled: true });
      this.reloadData(); showToastSafely('本地索引已更新');
    } catch (error) { showToastSafely('同步失败：' + (error as Error).message); }
    finally { this.refreshing = false; }
  }
  private async testCloudService(): Promise<void> {
    try {
      await this.authorizeCloud(); let options = this.cloudOptions();
      options.readTimeout = 10000; options.messages = [{ role: 'user', content: 'Connection test. Reply OK.' }];
      this.cloudOutput = '正在测试配置的服务…';
      let response = await this.client.chatCompletion(options);
      this.cloudOutput = '请求成功，服务返回：' + response.content.slice(0, 500);
    } catch (error) { this.cloudOutput = '测试未完成：' + (error as Error).message; }
  }
  private async generateAvatar(mode: AvatarGenerationMode, source: string): Promise<void> {
    if (mode === 'image_to_image') { throw new Error('安卓照片参考上传适配尚未验收，请先使用文生图；未上传照片'); }
    if (this.facade === null) { throw new Error('数据管理器未就绪'); }
    await this.authorizeCloud();
    let baseUrl = (AppStorage.get<string>('avatarImageBaseUrl') || '').trim();
    let model = (AppStorage.get<string>('avatarTextToImageModel') || '').trim();
    if (!baseUrl.startsWith('https://') || model.length === 0) { throw new Error('请在“个人与隐私”配置 HTTPS 图片服务和文生图模型'); }
    let config: AvatarProviderConfig = { imageBaseUrl: baseUrl, apiKey: AppStorage.get<string>('avatarImageApiKey') || '',
      textToImageModel: model, imageToImageModel: AppStorage.get<string>('avatarImageToImageModel') || '',
      size: AppStorage.get<string>('avatarImageSize') || '1024x1024', responseFormat: 'b64_json', outputFormat: 'png',
      sequentialImageGeneration: 'disabled', stream: false, watermark: true, timeoutMs: 120000 };
    try { await this.facade.generateAvatar(config, this.facade.buildAvatarGenerateRequest(mode, '', nowIsoLocal())); }
    finally { this.reloadData(); }
  }
  private reloadSessions(): void { this.sessions = ChatSessionStorage.loadIndex(this.context().filesDir); }
  private persistChat(): void {
    if (this.activeRecord === null) { return; }
    this.activeRecord.messages = this.messages;
    ChatSessionStorage.saveSession(this.context().filesDir, this.activeRecord);
    this.reloadSessions();
  }
  private scheduleChatSave(): void {
    if (this.saveTimer >= 0) { return; }
    this.saveTimer = setTimeout((): void => { this.saveTimer = -1; this.persistChat(); }, 350);
  }
  private createSession(mode: string): void {
    this.cancelChat(); this.persistChat();
    this.activeRecord = ChatSessionStorage.createSession(this.context().filesDir, mode);
    this.mode = mode; this.sessionId = this.activeRecord.id; this.messages = []; this.drawer = false;
    this.reloadSessions();
  }
  private async selectSession(id: string): Promise<void> {
    this.cancelChat(); this.persistChat();
    let record = ChatSessionStorage.loadSession(this.context().filesDir, id);
    if (record === null) { showToastSafely('会话不存在或读取失败'); return; }
    this.activeRecord = record; this.sessionId = record.id; this.mode = record.mode;
    this.messages = record.messages; this.drawer = false;
  }
  private async deleteSession(id: string): Promise<void> {
    let choice = await promptAction.showDialog({ title: '删除会话', message: '删除后无法恢复，是否继续？',
      buttons: [{ text: '取消', color: '#65718B' }, { text: '删除', color: '#B42318' }] });
    if (choice.index !== 1) { return; }
    if (id === this.sessionId) { this.cancelChat(); this.activeRecord = null; this.sessionId = ''; this.messages = []; }
    if (!ChatSessionStorage.deleteSession(this.context().filesDir, id)) { showToastSafely('删除会话失败'); }
    this.reloadSessions();
    if (this.activeRecord === null && this.sessions.length > 0) { await this.selectSession(this.sessions[0].id); }
  }
  private async renameSession(id: string, title: string): Promise<boolean> {
    let record = ChatSessionStorage.renameSession(this.context().filesDir, id, title);
    if (record === null) { return false; }
    if (id === this.sessionId) { this.activeRecord = record; }
    this.reloadSessions(); return true;
  }
  private async pinSession(id: string): Promise<void> {
    let record = ChatSessionStorage.loadSession(this.context().filesDir, id);
    if (record === null) { return; }
    let changed = ChatSessionStorage.setSessionPinned(this.context().filesDir, id, record.pinned !== true, Date.now());
    if (id === this.sessionId && changed !== null) { this.activeRecord = changed; }
    this.reloadSessions();
  }
  private cancelChat(): void {
    if (this.thinking) { this.recordRuntime('已请求取消当前回复'); }
    this.client.cancelActiveRequests();
    this.generation += 1;
    this.messages.forEach((message: ChatMessage): void => { message.streamingActive = false; });
    this.messages = this.messages.slice(); this.thinking = false;
  }
  private async sendMessage(): Promise<void> {
    if (this.thinking || this.cloudInput.trim().length === 0) { return; }
    try {
      AiServiceAccess.requireAllowed();
      if (!await PrivacyConsentDialog.show(this.getUIContext())) { return; }
      let baseUrl = (AppStorage.get<string>('cloudPlannerBaseUrl') || '').trim();
      let model = (AppStorage.get<string>('cloudPlannerModel') || '').trim();
      if (!baseUrl.startsWith('https://') || model.length === 0) {
        throw new Error('请在“我的 → 模型与智能体”填写 HTTPS 模型地址和模型名称');
      }
      if (this.activeRecord === null || this.mode !== CHAT_MODE_CLOUD_AGENT) { this.createSession(CHAT_MODE_CLOUD_AGENT); }
      let now = Date.now();
      let user = createChatMessage('user-' + now.toString(), 'user', 'chat', this.cloudInput.trim(), now);
      let history = buildCloudContextMessages(this.messages.concat([user]));
      let contextSettings = buildChatContextBudgetSettings(AppStorage.get<string>('chatContextCompressionThresholdPercent') || '',
        AppStorage.get<string>('chatContextRecentRounds') || '');
      if (shouldCompressCloudContext(history, contextSettings)) {
        throw new Error('会话已达到配置的上下文预算。安卓自动摘要尚未接入，请新建会话；历史消息仍保存在本机。');
      }
      let assistant = createChatMessage('assistant-' + now.toString(), 'assistant', 'chat', '', now);
      assistant.streamingActive = true;
      this.messages = this.messages.concat([user, assistant]); this.cloudInput = ''; this.streamRaw = '';
      this.thinking = true; this.persistChat();
      this.recordRuntime('开始模型流式请求');
      let generation = ++this.generation;
      let options: CloudChatCompletionOptions = { baseUrl: baseUrl, model: model,
        apiKey: AppStorage.get<string>('cloudPlannerApiKey') || AppStorage.get<string>('cloudApiKey') || '',
        messages: history, readTimeout: 60000, chatTemplateKwargs: { enable_thinking: this.deepThinking } };
      try {
        let response = await this.client.chatCompletionStream(options, {
          onContentDelta: (delta: string): void => {
            if (generation !== this.generation) { return; }
            this.streamRaw += delta;
            let parts = splitAssistantThinkingContent(this.streamRaw);
            assistant.content = parts.content; assistant.thinkingContent = parts.thinkingContent;
            assistant.streamingVersion = (assistant.streamingVersion || 0) + 1;
            this.messages = this.messages.slice(); this.scheduleChatSave();
          }
        });
        this.recordRuntime('流式请求完成；内容字符数=' + response.content.length.toString());
        if (generation === this.generation && response.toolCalls.length > 0) {
          assistant.trace = response.toolCalls.map((call): string => '模型请求工具：' + call.function.name + '；安卓执行器尚未接入，未执行');
        }
      } catch (error) {
        this.recordRuntime('流式请求失败');
        if (generation === this.generation) {
          assistant.trace = assistant.trace.concat(['请求失败：' + (error as Error).message]);
          if (assistant.content.length === 0) {
            assistant.kind = 'context_notice'; assistant.role = 'system';
            assistant.content = '未获得模型回复：' + (error as Error).message;
          }
        }
      } finally {
        if (generation === this.generation) {
          assistant.streamingActive = false; this.thinking = false; this.messages = this.messages.slice(); this.persistChat();
        }
      }
    } catch (error) { showToastSafely((error as Error).message, 3000); }
  }

  @Builder private TabLabel(item: MainTabItem) {
    Column({ space: 3 }) {
      Image($r('app.media.' + item.icon)).width(23).height(23)
      Text(item.label).fontSize(11).fontColor(this.tab === item.index ? '#5B5CE2' : '#65718B')
    }.width('100%').height(58).justifyContent(FlexAlign.Center)
  }

  @Builder private Home() {
    HomePage({ comfortChatStore: this.comfort, profile: this.profile, avatarVersions: this.avatarVersions,
      taskSuggestions: this.suggestions, taskSuggestionsLoading: this.suggestionsLoading,
      comfortConcerns: this.concerns, comfortConcernsLoading: this.concernsLoading,
      onOpenTasks: (): void => { this.tab = MainTabIndex.Task; },
      onSetRecommendationCompleted: (id: string, completed: boolean): void => {
        if (this.facade !== null) { this.facade.setHomeRecommendationCompleted(id, completed); this.reloadData(); }
      },
      onExecuteAgentTask: async (): Promise<void> => { throw new Error('安卓自动控制执行器尚未接入；任务未执行'); },
      onGenerateAvatar: async (mode: AvatarGenerationMode, source: string): Promise<void> => { await this.generateAvatar(mode, source); },
      onSaveAvatarReferencePhoto: async (source: string): Promise<string> => {
        if (this.facade === null) { throw new Error('数据管理器未就绪'); }
        let view = await this.facade.saveAvatarReferencePhoto(source); this.reloadData(); return view.avatarReferencePhotoUri;
      },
      onRefreshTaskSuggestions: async (): Promise<void> => { await this.refreshSuggestions(); },
      onRefreshComfortConcerns: async (): Promise<void> => { await this.refreshConcerns(); },
      onSendComfortMessage: async (concern: HomeComfortConcern, history: HomeComfortChatMessage[]): Promise<string> => { return await this.sendComfort(concern, history); }
    })
  }
  @Builder private Chat() {
    ChatPage({ chatMode: $mode, cloudUserInput: $cloudInput, userInput: $localInput, chatDeepThinkingEnabled: $deepThinking,
      sessions: this.sessions, activeSessionId: this.sessionId, messages: this.messages,
      cloudThinking: this.thinking, sessionDrawerOpen: this.drawer,
      onRunCloudPlanner: async (): Promise<void> => { await this.sendMessage(); },
      onCancelCloudChat: (): void => { this.cancelChat(); this.persistChat(); },
      onOpenSessionDrawer: (): void => { this.drawer = true; }, onCloseSessionDrawer: (): void => { this.drawer = false; },
      onCreateSession: (mode: string): void => { this.createSession(mode); },
      onSelectSession: async (id: string): Promise<void> => { await this.selectSession(id); },
      onDeleteSession: async (id: string): Promise<void> => { await this.deleteSession(id); },
      onRenameSession: async (id: string, title: string): Promise<boolean> => { return await this.renameSession(id, title); },
      onToggleSessionPinned: async (id: string): Promise<void> => { await this.pinSession(id); },
      onSwitchMode: async (mode: string): Promise<void> => {
        this.cancelChat(); this.mode = mode;
        if (mode === CHAT_MODE_LOCAL_INFERENCE) { this.unavailable('本地模型推理'); }
      },
      onToggleThinking: (id: string): void => { this.messages = toggleChatMessageThinkingCollapsed(this.messages, id); this.persistChat(); },
      onToggleTaskTrace: (id: string): void => { this.messages = toggleChatMessageCollapsed(this.messages, id); this.persistChat(); },
      onSendLocalMessage: async (): Promise<void> => { this.unavailable('本地推理'); },
      onSubmitLocalTask: async (): Promise<void> => { this.unavailable('本地任务执行'); },
      onSubmitCloudTask: async (): Promise<void> => { this.unavailable('云端任务执行'); },
      onTakePhotoAttachment: async (): Promise<void> => { this.unavailable('拍照附件'); },
      onPickImageAttachment: async (): Promise<void> => { this.unavailable('图片附件'); },
      onToggleVoiceInput: async (): Promise<void> => { this.unavailable('语音识别'); },
      onPlayVoiceMessage: async (): Promise<void> => { this.unavailable('语音朗读'); },
      onReplayVoiceMessage: async (): Promise<void> => { this.unavailable('语音重播'); },
      onOpenRuntimeLogs: (): void => { router.pushUrl({ url: 'pages/LogView' }); }
    })
  }
  @Builder private Settings() {
    SettingsCenterPage({ autoConfirm: $autoConfirm, cloudAutoConfirm: $cloudAutoConfirm, threadNumInput: $threads,
      selectedSectionId: $settingsSection, output: $output, chunkOmCacheOutput: $cacheOutput, cloudOutput: $cloudOutput,
      modelDownloadProgressVisible: $progressVisible, modelDownloadProgressPercent: $progressPercent,
      modelDownloadProgressText: $progressText, modelDownloadProgressTitle: $progressTitle,
      downloading: $downloading, modelScopeDownloading: $modelDownloading, modelScopePauseRequested: $pauseDownload,
      onDownloadModelScopeModel: async (): Promise<void> => { this.unavailable('本地模型下载'); },
      onPauseModelScopeDownload: (): void => { this.unavailable('本地模型下载'); },
      onUpdateConfigThreadNum: (): void => { this.saveSettings(); },
      onShowConfigJson: (): void => { this.output = '安卓尚未接入 MNN 配置；未创建或加载模型配置'; },
      onDiscoverHdc: async (): Promise<void> => { this.unavailable('HDC 发现'); },
      onSetHdcManualPairing: (): void => { this.unavailable('HDC 配对'); },
      onMarkHdcManualEditing: (): void => { this.unavailable('HDC 配对'); },
      onTestAndConnectHdc: async (): Promise<void> => { this.unavailable('HDC 连接'); },
      onTestCloudPlanner: async (): Promise<void> => { this.saveSettings(); await this.testCloudService(); },
      onStartCloudBackend: async (): Promise<void> => { this.unavailable('桌面后端启动'); },
      onClearScreenshots: (): void => { this.unavailable('截图清理'); },
      onDeleteDebugOutputs: (): void => { this.unavailable('调试文件清理'); },
      onSwitchDownloadedModel: (): void => { this.unavailable('本地模型切换'); },
      onDeleteDownloadedModel: (): void => { this.unavailable('模型删除'); },
      onRefreshDownloadedModels: (): void => { this.output = '尚无通过安卓模型管理器下载的模型'; },
      onListScreenshots: (): void => { this.output = '安卓自动控制尚未接入，未生成截图'; },
      onDeleteModelConfigJson: (): void => { this.unavailable('MNN 配置删除'); },
      onListModelFilesTree: (): void => { this.output = '尚无安卓模型文件目录'; },
      onListChunkVisionOmFiles: (): void => { this.cacheOutput = '安卓没有已注册的 OM 缓存'; },
      onDeleteChunkVisionOmFiles: (): void => { this.unavailable('OM 缓存删除'); },
      onLocalNpuExecutionModeChange: (): void => { this.unavailable('NPU 推理'); }
    })
  }

  build() {
    if (!this.ready) {
      Column({ space: 16 }) {
        Text(this.startupError.length > 0 ? '启动失败：' + this.startupError : '正在读取本地数据…')
        if (this.startupError.length > 0) { Button('重试').onClick(() => { this.initialize().catch((e: Error) => { this.startupError = e.message; }); }) }
      }.width('100%').height('100%').justifyContent(FlexAlign.Center).padding(20)
    } else if (this.onboarding) {
      FirstLaunchOnboarding({ onComplete: (): void => { this.completeOnboarding(); } })
    } else {
      Tabs({ index: this.tab, barPosition: BarPosition.End }) {
        TabContent() { this.Home() }.tabBar(this.TabLabel(MAIN_TAB_ITEMS[0]))
        TabContent() {
          CollectionPage({ collectionModule: $collectionScene, viewModels: this.collectionViews, refreshing: this.refreshing,
            onRefresh: (): void => { this.refreshCollection(); },
            onOpenDataCollectionSettings: (): void => { this.settingsSection = 1; this.tab = MainTabIndex.Mine; },
            onRunWechatAutoSummary: async (): Promise<void> => { throw new Error('安卓微信自动采集尚未接入，未读取聊天数据'); }
          })
        }.tabBar(this.TabLabel(MAIN_TAB_ITEMS[1]))
        TabContent() { this.Chat() }.tabBar(this.TabLabel(MAIN_TAB_ITEMS[2]))
        TabContent() {
          TaskPage({ taskScene: $taskScene, configViewOpen: $configViewOpen, active: this.tab === MainTabIndex.Task,
            onEnsureDesktopCompanion: async (): Promise<boolean> => { this.unavailable('桌面控制'); return false; },
            onRunWorkflow: async (): Promise<void> => { throw new Error('安卓工作流执行器尚未接入；配置可以浏览、编辑和保存'); },
            onRunPhoneWorkflow: async (): Promise<void> => { throw new Error('安卓手机自动控制尚未接入；任务未执行'); }
          })
        }.tabBar(this.TabLabel(MAIN_TAB_ITEMS[3]))
        TabContent() { this.Settings() }.tabBar(this.TabLabel(MAIN_TAB_ITEMS[4]))
      }.barMode(BarMode.Fixed).barHeight(64).scrollable(false)
        .onChange((index: number): void => { this.tab = index; })
        .width('100%').height('100%').backgroundColor(mainTabBackgroundColor(this.tab, this.messages.length > 0))
    }
  }
}
