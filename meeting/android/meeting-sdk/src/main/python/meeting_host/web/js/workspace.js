/* Meeting selection, document/audio versions, tasks and templates. */
async function select(mid) {
  uiLifecycle.emit("meeting:selecting", mid);
  id = mid;
  el("export").href = "/api/v1/meetings/" + id + "/export";
  el("export").hidden = false;
  renderTranscript(el("result"), [], "正在读取会议…");
  resetExtra();
  await refresh();
  uiLifecycle.emit("meeting:selected", mid);
}
async function refresh() {
  if (!id || streaming) return;
  const mid = id,
    d = await api("/meetings/" + mid);
  if (mid === id && !streaming) renderMeeting(d);
}
function need() {
  if (!id) throw Error("请先新建会议");
}
function idle() {
  need();
  if (streaming) throw Error("请先结束录音");
}
el("create").onclick = async () => {
  try {
    if (streaming) throw Error("请先结束录音");
    const d = await api("/meetings", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ title: el("title").value }),
    });
    await select(d.id);
    await history();
  } catch (e) {
    showError(e);
  }
};
el("upload").onclick = async () => {
  let locked = false;
  try {
    if (!el("file").files[0]) throw Error("请选择音频文件");
    await prepareAudioEntry("upload");
    workspaceEntryBusy = true;
    locked = true;
    controls();
    el("status").textContent = "正在上传并准备音频…";
    const body = new FormData();
    body.append("file", el("file").files[0]);
    body.append("refinement_profile_id", el("offline-model").value);
    await api("/meetings/" + id + "/audio", { method: "POST", body });
    await refresh();
    await history();
  } catch (e) {
    showError(e);
  } finally {
    if (locked) {
      workspaceEntryBusy = false;
      controls();
    }
  }
};
el("summary").onclick = async () => {
  try {
    idle();
    await api("/meetings/" + id + "/summary", { method: "POST" });
    await refresh();
  } catch (e) {
    showError(e);
  }
};

/* Public API workspace, sharing the existing recording controller. */
const audioDurationLabel = (seconds) =>
  seconds < 60 ? Number(seconds.toFixed(1)) + " 秒" : formatTime(seconds);
const workspaceAction = (fn) => async () => {
  try {
    await fn();
  } catch (error) {
    showError(error);
    el("document-status").textContent = error.message;
  }
};
function renderMeeting(meeting) {
  const selectionChanged = workspaceCurrent?.id !== meeting.id;
  workspaceCurrent = meeting;
  let projected = meeting;
  if (meeting.document?.revision && meeting.result)
    projected = {
      ...meeting,
      result: {
        ...meeting.result,
        segments: meeting.document.segments,
        transcript: meeting.document.transcript,
      },
    };
  const stamp = JSON.stringify(projected);
  if (stamp !== workspaceRenderStamp) {
    renderMachineMeeting(projected);
    workspaceRenderStamp = stamp;
    if (meeting.document?.revision) {
      el("original-heading").textContent =
        meeting.document.origin === "manual"
          ? "会议正文 · 人工版本 " + meeting.document.revision
          : "会议正文";
      renderTranscript(el("result"), meeting.document.segments);
    }
    updatePlaybackHighlight();
  }
  const changed = workspaceDocument?.mid !== meeting.id;
  const aid = meeting.document?.audio_id,
    asset = meeting.document_audio,
    audioKey = meeting.id + ":" + (aid || "unbound");
  if (workspaceAudioMid !== audioKey) {
    playbackSeekSequence++;
    const player = el("meeting-player");
    player.pause();
    if (aid)
      player.src = "/api/v1/meetings/" + meeting.id + "/audio?audio_id=" + encodeURIComponent(aid);
    else {
      player.removeAttribute("src");
      player.load();
    }
    workspaceAudioMid = audioKey;
    workspaceAudioListStamp = "";
  }
  if (changed) {
    workspaceDocument = null;
    workspaceDirty = false;
    el("document-editor").open = false;
    workspaceTaskStamp = "";
    el("audio-download").href = "/api/v1/meetings/" + meeting.id + "/audio";
    el("audio-download").hidden = false;
    el("markdown-export").href = "/api/v1/meetings/" + meeting.id + "/export.md";
    el("markdown-export").hidden = false;
  }
  if (changed || !workspaceMetaDirty) {
    el("meeting-name").value = meeting.title;
    el("meeting-tags").value = (meeting.tags || []).join(", ");
    workspaceMetaDirty = false;
  }
  el("meeting-context").textContent =
    (meeting.archived ? "已归档 · " : "") +
    (meeting.template
      ? "本会议模板：" + meeting.template.name + "（快照）"
      : "本会议跟随全局模型设置");
  if (!workspaceDirty && !el("document-editor").open)
    workspaceDocument = { mid: meeting.id, ...meeting.document };
  el("audio-download").hidden = !asset?.available;
  if (aid)
    el("audio-download").href =
      "/api/v1/meetings/" + meeting.id + "/audio?audio_id=" + encodeURIComponent(aid);
  el("audio-binding-status").textContent = asset
    ? "当前正文关联 " +
      new Date(asset.created).toLocaleString("zh-CN") +
      " 的音频 · " +
      audioDurationLabel(asset.duration)
    : "此正文没有可确认的关联音频，回听已停用。可选择其他正文版本。";
  el("playback-bar").hidden = streaming || !asset?.available;
  el("recording-recovery").hidden = !asset?.recoverable;
  el("recording-recovery-text").textContent = asset?.recoverable
    ? "录音中断，已保留 " +
      audioDurationLabel(asset.duration) +
      " 音频和此前完成的文字。恢复后可回听或整理正文。"
    : "";
  const audioStamp = JSON.stringify([meeting.id, aid, asset?.state, asset?.duration]);
  if (audioStamp !== workspaceAudioListStamp) {
    workspaceAudioListStamp = audioStamp;
    workspaceAudioVersions().catch(showError);
  }
  uiLifecycle.emit("meeting:rendered", meeting, { changed: selectionChanged });
}
el("meeting-name").oninput = el("meeting-tags").oninput = () => (workspaceMetaDirty = true);
uiLifecycle.on("meeting:selecting", () => {
  if (workspaceDirty) throw Error("请先保存正文，或重新载入以放弃修改");
});
el("save-meeting").onclick = workspaceAction(async () => {
  idle();
  await api(
    "/meetings/" + id,
    requestJSON("PATCH", {
      title: el("meeting-name").value,
      tags: el("meeting-tags").value.split(/[,，]/),
    }),
  );
  workspaceMetaDirty = false;
  await refresh();
  await history();
});
el("process").onclick = workspaceAction(async () => {
  idle();
  const settings = await api("/meetings/" + id + "/settings");
  const profile = settings.template?.refinement_profile_id || el("offline-model").value;
  await api(
    "/meetings/" + id + "/process",
    requestJSON("POST", { refinement_profile_id: profile }),
  );
  await refresh();
  await workspaceTasks();
});
