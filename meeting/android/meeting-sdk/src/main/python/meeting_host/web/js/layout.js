/* Five-pane navigation, DOM layout and error presentation. */
let workspaceView = "work";
function workspaceSelectView(view, scroll = true) {
  const oldView = workspaceView;
  if (!el("view-" + view)) view = "work";
  workspaceView = view;
  for (const pane of document.querySelectorAll("[data-workspace-view]"))
    pane.hidden = pane.dataset.workspaceView !== view;
  for (const button of el("workspace-nav").querySelectorAll("button")) {
    const active = button.dataset.view === view;
    button.setAttribute("aria-selected", String(active));
    button.tabIndex = active ? 0 : -1;
  }
  try {
    localStorage.setItem("workspace-view", view);
  } catch {}
  if (scroll) window.scrollTo({ top: 0, behavior: "auto" });
  uiLifecycle.emit("view:changed", { view: workspaceView, previous: oldView });
}
function initializeWorkspaceLayout() {
  const navigation = el("workspace-nav");
  navigation.setAttribute("role", "tablist");
  navigation.replaceChildren();
  const panes = {};
  for (const [key, name, description] of [
    ["work", "工作台", "录音、阅读与整理当前会议"],
    ["library", "会议库", "新建、搜索和管理会议"],
    ["tasks", "任务", "查看处理进度与失败记录"],
    ["templates", "模板", "为不同场景保存处理方案"],
    ["settings", "设置", "模型连接与高级选项"],
  ]) {
    const pane = document.createElement("div");
    pane.id = "view-" + key;
    pane.className = "workspace-pane";
    pane.dataset.workspaceView = key;
    pane.setAttribute("role", "tabpanel");
    pane.setAttribute("aria-labelledby", "nav-" + key);
    const header = document.createElement("header"),
      title = document.createElement("h2"),
      hint = document.createElement("p");
    title.textContent = name;
    hint.textContent = description;
    header.append(title, hint);
    pane.append(header);
    panes[key] = pane;
    navigation.parentNode.insertBefore(pane, document.querySelector("body>script"));
    const button = document.createElement("button");
    button.id = "nav-" + key;
    button.type = "button";
    button.dataset.view = key;
    button.textContent = name;
    button.setAttribute("role", "tab");
    button.setAttribute("aria-controls", pane.id);
    button.onclick = () => workspaceSelectView(key);
    navigation.append(button);
  }
  navigation.onkeydown = (event) => {
    const buttons = [...navigation.querySelectorAll("button")],
      index = buttons.indexOf(document.activeElement);
    if (index < 0) return;
    let next = index;
    if (event.key === "ArrowRight") next = (index + 1) % buttons.length;
    else if (event.key === "ArrowLeft") next = (index + buttons.length - 1) % buttons.length;
    else if (event.key === "Home") next = 0;
    else if (event.key === "End") next = buttons.length - 1;
    else return;
    event.preventDefault();
    buttons[next].click();
    buttons[next].focus();
  };
  function fold(parent, title, id) {
    const details = document.createElement("details"),
      summary = document.createElement("summary"),
      content = document.createElement("div");
    details.id = id;
    details.className = "workspace-fold";
    summary.textContent = title;
    content.className = "fold-content";
    details.append(summary, content);
    parent.append(details);
    return { details, content };
  }
  const control = el("record").closest("section"),
    original = el("original-heading").closest("section");
  panes.work.querySelector("header").hidden = true;
  el("process").textContent = "整理正文";
  el("summary").textContent = "生成纪要";
  el("stop").textContent = "停止";
  el("stop").title = "结束当前录音或回放";
  const current = document.createElement("div");
  current.className = "workspace-current";
  const label = document.createElement("div"),
    name = document.createElement("strong"),
    hint = document.createElement("small");
  name.id = "workspace-current-name";
  name.textContent = "尚未选择会议";
  hint.textContent = "从会议库新建或选择一条会议";
  label.append(name, hint);
  const libraryButton = document.createElement("button");
  libraryButton.textContent = "切换会议";
  libraryButton.onclick = () => workspaceSelectView("library");
  current.append(label, libraryButton);
  panes.work.append(current);
  const prefs = document.createElement("section");
  prefs.id = "model-preferences";
  const prefsTitle = document.createElement("h2");
  prefsTitle.textContent = "处理偏好";
  prefs.append(prefsTitle, el("offline-model").closest("p"), el("refinement-hint"));
  panes.settings.append(prefs, el("models-section"), el("refinement-settings"));
  el("refinement-settings").classList.add("workspace-fold");
  el("refinement-settings").querySelector("summary").textContent = "外部模型与连接配置";
  const connectionBody = document.createElement("div");
  connectionBody.className = "fold-content";
  for (const child of [...el("refinement-settings").children])
    if (child.tagName !== "SUMMARY") connectionBody.append(child);
  el("refinement-settings").append(connectionBody);
  const create = document.createElement("section");
  create.id = "create-meeting-card";
  const createTitle = document.createElement("h2");
  createTitle.textContent = "新建会议";
  create.append(createTitle, el("title"), el("create"));
  panes.library.append(create);
  const importCard = fold(panes.work, "导入音频与回放实验", "import-options");
  importCard.content.append(el("file").closest("p"), el("replay"));
  const replayHint = control.querySelector("small");
  if (replayHint) importCard.content.append(replayHint);
  const primary = document.createElement("div");
  primary.className = "record-actions";
  primary.append(el("record"), el("pause-recording"), el("stop"));
  const secondary = document.createElement("div");
  secondary.className = "secondary-actions";
  secondary.append(el("process"), el("summary"));
  control.prepend(primary, secondary);
  for (const p of [...control.querySelectorAll("p")])
    if (!p.textContent.trim() && !p.children.length) p.remove();
  panes.work.insertBefore(control, importCard.details);
  const recovery = document.createElement("div");
  recovery.id = "recording-recovery";
  recovery.hidden = true;
  recovery.setAttribute("role", "status");
  const recoveryText = document.createElement("p");
  recoveryText.id = "recording-recovery-text";
  const recover = document.createElement("button");
  recover.textContent = "恢复此录音";
  recover.onclick = workspaceAction(async () => {
    idle();
    const aid = workspaceCurrent?.document?.audio_id;
    if (!aid) throw Error("没有可恢复音频");
    await api("/meetings/" + id + "/audio/" + aid + "/recover", { method: "POST" });
    await refresh();
    await history();
  });
  recovery.append(recoveryText, recover);
  control.append(recovery);
  panes.work.insertBefore(original, importCard.details);
  el("original-heading").textContent = "会议正文";
  panes.work.insertBefore(el("summary-section"), importCard.details);
  const audio = fold(panes.work, "音频版本与下载", "audio-options");
  audio.content.append(el("audio-workspace"));
  const playback = document.createElement("div");
  playback.id = "playback-bar";
  const follow = document.createElement("label"),
    checkbox = document.createElement("input");
  checkbox.type = "checkbox";
  checkbox.id = "follow-playback";
  follow.append(checkbox, document.createTextNode("跟随播放"));
  const playbackHint = document.createElement("small");
  playbackHint.id = "playback-status";
  playbackHint.textContent = "点击正文中的时间即可回听对应段落";
  playback.append(el("meeting-player"), follow, playbackHint);
  original.prepend(playback);
  const documentTools = fold(panes.work, "编辑正文、说话人与版本", "document-options");
  documentTools.content.append(el("document-workspace"));
  const comparisons = fold(panes.work, "识别对照与修改记录", "comparison-options");
  comparisons.content.append(el("original-metrics"));
  for (const id of [
    "qwen-live-section",
    "refined-section",
    "paraformer-offline-section",
    "secondary-section",
    "fusion-audit",
  ])
    comparisons.content.append(el(id));
  const meetings = el("meeting-workspace"),
    historySection = el("history").closest("section");
  panes.library.append(meetings);
  const meta = fold(panes.library, "当前会议信息与归档", "metadata-options");
  meta.content.append(el("meeting-name").closest("p"), el("meeting-context"));
  meetings.append(el("history"));
  historySection.remove();
  panes.tasks.append(el("task-workspace"));
  panes.templates.append(el("template-workspace"));
  const templateEditor = fold(panes.templates, "编辑模板与输出要求", "template-options");
  const templateSection = el("template-workspace");
  const top = el("workspace-template").closest("p");
  for (const node of [...templateSection.children])
    if (node !== top && node.tagName !== "H2") templateEditor.content.append(node);
  el("new-template").addEventListener("click", () => (templateEditor.details.open = true));
  el("workspace-template").addEventListener("change", () => (templateEditor.details.open = true));
  const advanced = fold(panes.settings, "高级信息与接口说明", "developer-options");
  advanced.content.append(el("device"), el("interface-workspace"));
  const apiDocs = document.querySelector('a[href="/docs"]');
  if (apiDocs) advanced.content.append(apiDocs);
  el("interface-workspace").open = false;
  const exportJSON = el("export");
  exportJSON.textContent = "导出完整 JSON";
  documentTools.content.append(exportJSON);
  for (const node of [...control.childNodes])
    if (node.nodeType === Node.TEXT_NODE && node.textContent.trim() === "·") node.remove();
  const note = document.querySelector("body>small");
  if (note) control.append(note);
  const alert = document.createElement("div");
  alert.id = "workspace-alert";
  alert.hidden = true;
  alert.setAttribute("role", "alert");
  const message = document.createElement("span"),
    close = document.createElement("button");
  close.textContent = "关闭";
  close.onclick = () => (alert.hidden = true);
  alert.append(message, close);
  navigation.after(alert);
  uiLifecycle.on("error", (error) => {
    message.textContent = error.message;
    alert.hidden = false;
  });
  uiLifecycle.on("meeting:rendered", (meeting) => {
    name.textContent = meeting.title;
    hint.textContent = streaming
      ? "录音进行中 · 切换菜单不会结束录音"
      : "录音与整理操作均针对当前会议";
  });
  uiLifecycle.on("meeting:selected", () => workspaceSelectView("work"));
  uiLifecycle.on("stream:event", (event) => {
    if (event.type === "ready") {
      workspaceSelectView("work");
      el("meeting-player").pause();
      workspaceAudioMid = null;
      workspaceRenderStamp = "";
    }
  });
  let preferred = "work";
  try {
    preferred = localStorage.getItem("workspace-view") || "work";
  } catch {}
  workspaceSelectView(preferred, false);
}
async function workspaceCapabilities() {
  const data = await api("/capabilities");
  for (const group of data.groups) {
    const row = document.createElement("p");
    row.textContent = group.name + "：" + group.operations.join("；");
    el("interface-list").append(row);
  }
}
