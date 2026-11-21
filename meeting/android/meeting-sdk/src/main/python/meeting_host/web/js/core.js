/* Shared page state and small infrastructure. Android/debug globals stay compatible. */
const uiLifecycle = (() => {
  const listeners = new Map();
  return {
    on(event, callback) {
      if (!listeners.has(event)) listeners.set(event, []);
      listeners.get(event).push(callback);
      return () => {
        const group = listeners.get(event);
        const index = group.indexOf(callback);
        if (index >= 0) group.splice(index, 1);
      };
    },
    emit(event, ...args) {
      for (const callback of [...(listeners.get(event) || [])]) callback(...args);
    },
  };
})();
let id,
  ws,
  ctx,
  source,
  processor,
  media,
  recording = false,
  streaming = false,
  paused = false,
  captureMode = null,
  capturePhase = "idle",
  captureSeconds = 0,
  heartbeat = null,
  finalSegments = [],
  qwenFinalSegments = [],
  livePartial = null,
  liveAudioId = null,
  progressiveRefinement = false,
  modelsReady = !window.PhoneAudio,
  refinementSettings = null,
  settingsBusy = false;
const el = (x) => document.getElementById(x);
const statuses = {
  created: "等待音频",
  recording: "正在录音",
  recorded: "转写完成",
  processing: "正在精修",
  summarizing: "正在生成摘要",
  ready: "处理完成",
  failed: "处理失败",
  interrupted: "录音已中断",
};
async function api(path, options = {}) {
  const r = await fetch("/api/v1" + path, options),
    d = await r.json();
  if (!r.ok)
    throw Error(
      Array.isArray(d.detail)
        ? d.detail.map((e) => e.loc.at(-1) + ": " + e.msg).join("；")
        : d.detail || r.status,
    );
  return d;
}
function showError(e) {
  el("status").textContent = e.message;
  uiLifecycle.emit("error", e);
}
function speakerIdLabel(speaker) {
  const match = /^speaker_(\d+)$/.exec(speaker);
  return match
    ? "说话人 " + (Number(match[1]) + 1)
    : speaker === "unknown"
      ? "说话人待确认"
      : speaker;
}
function speakerName(speaker) {
  return workspaceCurrent?.document?.speaker_names?.[speaker] || speakerIdLabel(speaker);
}
function appendText(left, right) {
  return left + (/[A-Za-z0-9]$/.test(left) && /^[A-Za-z0-9]/.test(right) ? " " : "") + right;
}
function metricText(m) {
  return m
    ? "识别耗时 " +
        m.processing_seconds.toFixed(2) +
        " 秒 · RTF " +
        m.rtf.toFixed(3) +
        "（越小越快，不含公共分段耗时）"
    : "";
}
function resetExtra() {
  [
    "refined-section",
    "summary-section",
    "qwen-live-section",
    "secondary-section",
    "paraformer-offline-section",
    "fusion-audit",
  ].forEach((x) => (el(x).hidden = true));
}
let workspaceDocument = null,
  workspaceCurrent = null,
  workspaceDirty = false,
  workspaceMetaDirty = false,
  workspaceTemplates = [],
  workspaceTemplateEdit = null,
  workspaceTaskStamp = "",
  workspaceRenderStamp = "",
  workspaceAudioMid = null,
  workspaceAudioListStamp = "";
let workspaceEntryBusy = false,
  historyPage = 1,
  historySelected = new Set(),
  historyData = null,
  historySequence = 0,
  historyStamp = "",
  promoteDirty = false;
const requestJSON = (method, body) => ({
  method,
  headers: { "Content-Type": "application/json" },
  body: JSON.stringify(body),
});
