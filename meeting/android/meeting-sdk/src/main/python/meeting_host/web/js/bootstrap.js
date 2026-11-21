/* One startup sequence. Modules register controls before any asynchronous loading. */
initializeWorkspaceLayout();
initializeHistory();
if (window.PhoneAudio) {
  document.title = "端侧会议纪要";
  document.querySelector("h1").textContent = "端侧会议纪要";
  document.querySelector('a[href="/docs"]').hidden = true;
}
initializeAppearance();
controls();
workspaceCapabilities().catch(showError);
workspaceLoadTemplates().catch(showError);
workspaceTasks().catch(showError);
restoreWorkspaceSelection().catch(showError);
history().catch(showError);
loadProfiles().then(controls).catch(showError);
api("/health")
  .then((d) => {
    el("device").textContent = "运行设备：" + (d.runtime?.device || "本机");
  })
  .catch(showError);
if (window.PhoneAudio) {
  modelStatus().catch(showError);
  setInterval(() => modelStatus().catch(showError), 3000);
}
setInterval(() => refresh().catch(showError), 2000);
setInterval(() => {
  if (!streaming) workspaceTasks().catch(() => {});
}, 2500);
setInterval(() => {
  if (!streaming && el("nav-library").getAttribute("aria-selected") === "true")
    history().catch(() => {});
}, 5000);
setInterval(() => {
  if (captureMode === "native" && streaming) {
    try {
      const state = JSON.parse(PhoneAudio.state());
      captureSeconds = state.audio_seconds || 0;
      captureControls();
    } catch {}
  }
}, 1000);
