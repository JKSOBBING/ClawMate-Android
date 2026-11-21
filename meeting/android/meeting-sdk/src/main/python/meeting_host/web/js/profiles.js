/* Refinement profiles and model readiness controls. */
function populateProfiles(data) {
  refinementSettings = data;
  const select = el("offline-model");
  select.replaceChildren();
  for (const p of data.profiles) {
    const option = document.createElement("option");
    option.value = p.id;
    option.textContent = p.name + (p.builtin ? " · 本地" : " · 外部");
    select.append(option);
  }
  select.value = data.active_id;
  select.disabled = streaming;
  el("auto-refine").checked = data.auto_enabled;
  const editor = el("profile-editor"),
    old = editor.value;
  editor.replaceChildren(new Option("添加外部模型", ""));
  for (const p of data.profiles.filter((x) => !x.builtin)) editor.append(new Option(p.name, p.id));
  editor.value = old;
  if (!editor.value) el("profile-delete").disabled = true;
  const profile = data.profiles.find((p) => p.id === data.active_id);
  el("auto-refine-label").textContent = profile.kind === "local_firered" ? "录音时逐段精修" : "录音结束后自动精修";
  if (profile.kind === "local_firered") {
    el("refinement-hint").textContent = "SenseVoice 即时显示草稿，FireRed 完成一段后原位更新。只显示一份正文，保留说话人和时间位置；全部在手机本地运行。";
    return;
  }
  el("refinement-hint").textContent =
    "SenseVoice 保留标点，" +
    profile.name +
    " 提供纠错候选。" +
    (profile.kind === "remote_audio"
      ? "此模型会发送分段音频到外部接口。"
      : profile.kind === "remote_text"
        ? "此模型只发送底稿文本到外部接口。"
        : "全部在手机本地运行。");
}
async function loadProfiles() {
  populateProfiles(await api("/refinement"));
}
async function saveSelection() {
  settingsBusy = true;
  controls();
  try {
    populateProfiles(
      await api("/refinement", {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          active_id: el("offline-model").value,
          auto_enabled: el("auto-refine").checked,
        }),
      }),
    );
    el("profile-status").textContent = "精修设置已保存，下次录音使用此配置。";
  } catch (e) {
    showError(e);
    await loadProfiles();
  } finally {
    settingsBusy = false;
    controls();
  }
}
el("offline-model").onchange = saveSelection;
el("auto-refine").onchange = saveSelection;
el("profile-editor").onchange = () => {
  const p = refinementSettings.profiles.find((p) => p.id === el("profile-editor").value) || {};
  for (const [field, key] of [
    ["name", "name"],
    ["kind", "kind"],
    ["url", "base_url"],
    ["model", "model"],
    ["timeout", "timeout_seconds"],
  ])
    el("profile-" + field).value =
      p[key] || (field === "kind" ? "remote_audio" : field === "timeout" ? 60 : "");
  el("profile-key").value = "";
  el("profile-clear-key").checked = false;
  el("profile-delete").disabled = !p.id;
  el("profile-status").textContent = p.has_api_key ? "已保存密钥，留空可保留。" : "";
};
el("profile-save").onclick = async () => {
  settingsBusy = true;
  controls();
  try {
    const profileId = el("profile-editor").value,
      body = {
        name: el("profile-name").value,
        kind: el("profile-kind").value,
        base_url: el("profile-url").value,
        model: el("profile-model").value,
        timeout_seconds: Number(el("profile-timeout").value),
      };
    if (el("profile-key").value || el("profile-clear-key").checked)
      body.api_key = el("profile-clear-key").checked ? "" : el("profile-key").value;
    const saved = await api(
      "/refinement/profiles" + (profileId ? "/" + encodeURIComponent(profileId) : ""),
      {
        method: profileId ? "PUT" : "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(body),
      },
    );
    el("profile-key").value = "";
    el("profile-clear-key").checked = false;
    populateProfiles(
      await api("/refinement", {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ active_id: saved.id, auto_enabled: el("auto-refine").checked }),
      }),
    );
    el("profile-editor").value = saved.id;
    el("profile-delete").disabled = false;
    el("profile-status").textContent = "已保存并选用。可先测试接口，再录音。";
  } catch (e) {
    el("profile-status").textContent = e.message;
  } finally {
    settingsBusy = false;
    controls();
  }
};
el("profile-delete").onclick = async () => {
  try {
    populateProfiles(
      await api("/refinement/profiles/" + encodeURIComponent(el("profile-editor").value), {
        method: "DELETE",
      }),
    );
    el("profile-editor").value = "";
    el("profile-editor").onchange();
    el("profile-status").textContent = "配置已删除。";
  } catch (e) {
    el("profile-status").textContent = e.message;
  }
};
el("profile-test").onclick = async () => {
  const button = el("profile-test");
  button.disabled = true;
  el("profile-status").textContent = "正在测试已保存的当前模型，外部接口使用测试样本…";
  try {
    const d = await api(
      "/refinement/profiles/" + encodeURIComponent(el("offline-model").value) + "/test",
      { method: "POST" },
    );
    el("profile-status").textContent = d.message;
  } catch (e) {
    el("profile-status").textContent = e.message;
  } finally {
    button.disabled = streaming;
  }
};

async function modelStatus() {
  const health = await api("/health");
  modelsReady = health.status === "ready";
  controls();
  if (window.PhoneAudio) {
    el("models-section").hidden = health.status === "ready";
    if (health.status !== "ready") {
      const state = await api("/models/status");
      el("models-status").textContent =
        state.message || "首次使用需要约 2 GB 模型。可通过电脑导入，或点击下载。";
      el("download-models").disabled = state.state === "downloading";
    }
  }
}
el("download-models").onclick = async () => {
  try {
    await api("/models/download", { method: "POST" });
    await modelStatus();
  } catch (e) {
    showError(e);
  }
};
