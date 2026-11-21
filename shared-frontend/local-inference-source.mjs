import { applyPatches } from './source-patches.mjs';

export function localModelSettings(source) {
  return applyPatches(source, [
    [/^import \{ createLocalModelRecommendation, [^\n]+\n/m,
      "import { AndroidLocalModelSettings } from './AndroidLocalModelSettings';\nimport { DownloadedModel } from '../../platform/LocalModelAccess';\n"],
    [/^  @Prop localNpuExecutionMode:[^\n]+\n/m, ''],
    [/^  @Link chunkOmCacheOutput:[^\n]+\n/m, ''],
    [/^  onListChunkVisionOmFiles:[^\n]+\n/m, ''],
    [/^  @Prop localModelRecommendation:[^\n]+$/m, "  @Prop pausedModelRepo: string = '';"],
    [/^  @StorageLink\('customModelScopeMnnRepo'\)[^\n]+\n  @State private modelCacheExpanded:[^\n]+\n/m, ''],
    ['  @Prop downloadedModels: DownloadedModelItem[]', '  @Prop downloadedModels: DownloadedModel[]'],
    ['  DownloadedModelItem,\n', ''],
    ['confirmDeleteDownloadedModel(item: DownloadedModelItem)', 'confirmDeleteDownloadedModel(item: DownloadedModel)'],
    [/^  onUseRecommendedLocalModel:[^\n]+$/m, `  @Prop importingGguf: boolean = false;
  onImportGguf: () => Promise<void> = async () => {};`],
    [/^  onLocalNpuExecutionModeChange:[^\n]+\n/m, ''],
    [/  private selectLocalNpuExecutionMode\([^]*?(?=  private confirmClearScreenshots)/, ''],
    [/  @Builder DownloadedModelList\(\)[^]*?(?=  @Builder WechatAutoSummaryInput)/, ''],
    [/      CollectionFormGroup\(\{ title: '本地模型',[^]*?      this\.ModelDownloadGroup\(\)/,
      `      AndroidLocalModelSettings({
        models: this.downloadedModels, selectedId: this.selectedModelId, importing: this.importingGguf,
        threads: $threadNumInput, repo: $modelScopeMnnRepo, pausedRepo: this.pausedModelRepo,
        downloading: this.downloading || this.modelScopeDownloading,
        onRefresh: this.onRefreshDownloadedModels, onSelect: this.onSwitchDownloadedModel,
        onDelete: (item: DownloadedModel): void => { this.confirmDeleteDownloadedModel(item); },
        onImport: this.onImportGguf, onDownload: this.onDownloadModelScopeModel,
        onCloseInputs: (): void => { this.closeSensitiveInputs(); },
        progress: (): void => { this.ModelDownloadProgressPanel(); }
      })`],
    [/  @Builder LocalModelGroup\(\)[^]*?(?=  @Builder HdcConnectionGroup)/, ''],
    [/  @Builder ModelDownloadGroup\(\)[^]*?(?=  @Builder ConfigOutputGroup)/, '']
  ]);
}

export function localChatControls(source) {
  return applyPatches(source, [
    ["return '本地模型未加载';", "return '请先在「我的 → 模型与智能体」导入或选择 GGUF 对话模型';"],
    ["Text('我可以帮你整理信息、执行任务')", `Text(this.isCloudMode() ? '我可以帮你整理信息、执行任务' :
          (this.modelLoaded ? '可以直接聊天，或输入「查本机：」加问题，选择已有采集记录作为参考。' : '请先在「我的 → 模型与智能体」导入 GGUF 对话模型'))`],
    ["        Column() {\n          this.WelcomeActionCard('flow'", "        if (this.isCloudMode()) {\n        Column() {\n          this.WelcomeActionCard('flow'"],
    ["        .margin({ top: 14 })\n\n        Blank()", "        .margin({ top: 14 })\n        }\n\n        Blank()"],
    ["          Text('下发任务')", "          if (this.isCloudMode()) {\n          Text('下发任务')"],
    ["            .onClick(async () => { await this.runTaskAction(); })", "            .onClick(async () => { await this.runTaskAction(); })\n          }"],
    ["          Text('+')", "          if (this.isCloudMode()) {\n          Text('+')"],
    ["            .onClick(() => { this.openAttachmentSourceMenu(); })", "            .onClick(() => { this.openAttachmentSourceMenu(); })\n          }"]
  ]);
}
