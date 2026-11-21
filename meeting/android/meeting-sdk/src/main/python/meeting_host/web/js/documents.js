/* Editable transcript and document version actions. */
el("export-handoff").onclick = workspaceAction(async () => {
  idle();
  if (workspaceDirty) throw Error("请先保存人工编辑，再导出交接副本");
  const doc = workspaceCurrent?.document;
  if (!id || !doc?.transcript?.trim()) throw Error("请先打开有正文的会议");
  const query = new URLSearchParams({ base_revision: String(doc.revision), source_hash: doc.source_hash });
  location.href = "/api/v1/meetings/" + id + "/handoff?" + query;
});
async function workspaceVersions() {
  need();
  const data = await api("/meetings/" + id + "/versions");
  const select = el("document-versions");
  select.replaceChildren();
  for (const version of data.versions)
    select.append(
      new Option(
        "版本 " +
          version.revision +
          " · " +
          (version.origin === "manual" ? "人工" : "模型") +
          (!version.audio_id ? " · 音频未绑定" : "") +
          (version.revision === data.active_revision ? " · 当前" : ""),
        version.revision,
      ),
    );
  select.value = String(data.active_revision);
  el("document-status").textContent = "当前正文版本 " + data.active_revision;
}
async function workspaceEdit() {
  idle();
  workspaceDocument = { mid: id, ...(await api("/meetings/" + id + "/document")) };
  workspaceDirty = false;
  el("speaker-name-editor").replaceChildren();
  el("segment-editor").replaceChildren();
  const speakers = [...new Set(workspaceDocument.segments.map((s) => s.speaker || "unknown"))];
  for (const speaker of speakers) {
    const label = document.createElement("label");
    label.textContent = speakerIdLabel(speaker) + "：";
    const input = document.createElement("input");
    input.value = workspaceDocument.speaker_names[speaker] || "";
    input.placeholder = "显示姓名";
    input.dataset.speakerName = speaker;
    input.oninput = () => (workspaceDirty = true);
    label.append(input);
    el("speaker-name-editor").append(label);
  }
  workspaceDocument.segments.forEach((segment, index) => {
    const row = document.createElement("div");
    row.className = "turn";
    const play = document.createElement("button");
    play.textContent = segment.start.toFixed(1) + " 秒 · 回听";
    play.onclick = () => playSegment(segment.start).catch(showError);
    const speaker = document.createElement("select");
    speaker.dataset.segmentSpeaker = index;
    for (const value of [...new Set([...speakers, "unknown"])])
      speaker.append(
        new Option(workspaceDocument.speaker_names[value] || speakerIdLabel(value), value),
      );
    speaker.value = segment.speaker || "unknown";
    speaker.onchange = () => (workspaceDirty = true);
    const text = document.createElement("textarea");
    text.dataset.segmentText = index;
    text.value = segment.text;
    text.rows = 3;
    text.style.width = "95%";
    text.oninput = () => (workspaceDirty = true);
    row.append(play, speaker, text);
    el("segment-editor").append(row);
  });
  el("document-editor").open = true;
  await workspaceVersions();
}
el("edit-document").onclick = workspaceAction(workspaceEdit);
el("reload-document").onclick = workspaceAction(workspaceEdit);
el("refresh-versions").onclick = workspaceAction(workspaceVersions);
el("save-document").onclick = workspaceAction(async () => {
  idle();
  if (!workspaceDocument || workspaceDocument.mid !== id) throw Error("请先载入当前正文");
  const names = {};
  for (const input of el("speaker-name-editor").querySelectorAll("[data-speaker-name]"))
    if (input.value.trim()) names[input.dataset.speakerName] = input.value.trim();
  const segments = workspaceDocument.segments.map((s, index) => ({
    text: el("segment-editor").querySelector('[data-segment-text="' + index + '"]').value,
    speaker: el("segment-editor").querySelector('[data-segment-speaker="' + index + '"]').value,
  }));
  workspaceDocument = {
    mid: id,
    ...(await api(
      "/meetings/" + id + "/document",
      requestJSON("PUT", {
        base_revision: workspaceDocument.revision,
        source_hash: workspaceDocument.source_hash,
        segments,
        speaker_names: names,
      }),
    )),
  };
  workspaceDirty = false;
  await refresh();
  await workspaceVersions();
  el("document-editor").open = false;
});
el("restore-document").onclick = workspaceAction(async () => {
  idle();
  const selected = Number(el("document-versions").value);
  if (!selected) throw Error("请选择历史版本");
  const current = await api("/meetings/" + id + "/document");
  await api(
    "/meetings/" + id + "/versions/restore",
    requestJSON("POST", { revision: selected, base_revision: current.revision }),
  );
  workspaceDirty = false;
  await refresh();
  await workspaceVersions();
  el("document-editor").open = false;
});
