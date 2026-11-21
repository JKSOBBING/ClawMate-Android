/* Audio versions, seek and playback highlighting. */
async function workspaceAudioVersions() {
  if (!id) return;
  const mid = id,
    data = await api("/meetings/" + mid + "/audio/versions");
  if (id !== mid) return;
  const target = el("audio-versions");
  target.replaceChildren();
  if (!data.items.length) {
    target.textContent = "尚无录音或导入音频。";
    return;
  }
  for (const [index, asset] of data.items.entries()) {
    const row = document.createElement("div");
    row.className = "audio-version-row";
    const text = document.createElement("p");
    text.textContent =
      "第 " +
      (data.items.length - index) +
      " 份音频 · " +
      ({ recording: "录音", upload: "导入", legacy: "旧版音频" }[asset.source] || asset.source) +
      " · " +
      audioDurationLabel(asset.duration) +
      (asset.id === workspaceCurrent?.document?.audio_id ? " · 当前正文" : "") +
      " · " +
      ({
        completed: "已保存",
        recovered: "已恢复",
        interrupted: "录音中断",
        unavailable: "文件不可恢复",
        recording: "录音中",
        paused: "已暂停",
        preparing: "准备中",
        finalizing: "保存中",
      }[asset.state] || asset.state);
    const date = document.createElement("small");
    date.textContent = new Date(asset.created).toLocaleString("zh-CN");
    row.append(text, date);
    if (asset.available) {
      const download = document.createElement("a");
      download.href = "/api/v1/meetings/" + mid + "/audio?audio_id=" + encodeURIComponent(asset.id);
      download.textContent = "下载此音频";
      download.setAttribute("download", "");
      row.append(download);
    }
    if (asset.recoverable) {
      const button = document.createElement("button");
      button.textContent = "恢复已保存部分";
      button.onclick = workspaceAction(async () => {
        idle();
        await api("/meetings/" + mid + "/audio/" + asset.id + "/recover", { method: "POST" });
        await refresh();
        await workspaceAudioVersions();
      });
      row.append(button);
    }
    target.append(row);
  }
}
el("refresh-audio-versions").onclick = workspaceAction(workspaceAudioVersions);
let playbackTurn = null,
  playbackSeekSequence = 0;
async function playSegment(start) {
  idle();
  if (!workspaceCurrent?.document_audio?.available) throw Error("此正文没有可回听的关联音频");
  const player = el("meeting-player"),
    sequence = ++playbackSeekSequence;
  if (!Number.isFinite(start) || start < 0) throw Error("无效的音频位置");
  if (player.readyState === 0) {
    await new Promise((resolve, reject) => {
      const clean = () => {
          player.removeEventListener("loadedmetadata", ready);
          player.removeEventListener("error", error);
          clearTimeout(timer);
        },
        ready = () => {
          clean();
          resolve();
        },
        error = () => {
          clean();
          reject(Error("音频尚未保存或无法读取"));
        },
        timer = setTimeout(error, 15000);
      player.addEventListener("loadedmetadata", ready, { once: true });
      player.addEventListener("error", error, { once: true });
      player.load();
    });
  }
  if (streaming || sequence !== playbackSeekSequence) return;
  player.currentTime = Math.min(start, Number.isFinite(player.duration) ? player.duration : start);
  await player.play();
  updatePlaybackHighlight();
}
function updatePlaybackHighlight() {
  const player = el("meeting-player"),
    time = player.currentTime;
  const active =
    workspaceCurrent?.document_audio?.available && player.readyState > 0
      ? [...el("result").querySelectorAll("[data-audio-start]")].find(
          (turn) => time >= Number(turn.dataset.audioStart) && time < Number(turn.dataset.audioEnd),
        )
      : null;
  for (const turn of el("result").querySelectorAll("[data-audio-start]")) {
    const selected = turn === active;
    turn.classList.toggle("playing", selected);
    if (selected) turn.setAttribute("aria-current", "true");
    else turn.removeAttribute("aria-current");
  }
  if (active !== playbackTurn) {
    playbackTurn = active;
    if (active && el("follow-playback")?.checked && !player.paused && workspaceView === "work")
      active.scrollIntoView({ block: "center", behavior: "smooth" });
  }
}
for (const event of ["timeupdate", "seeked", "play", "pause", "ended"])
  el("meeting-player").addEventListener(event, updatePlaybackHighlight);
el("meeting-player").addEventListener("error", () => {
  if (el("playback-status")) el("playback-status").textContent = "音频尚未保存或无法读取";
});
el("meeting-player").addEventListener("loadedmetadata", () => {
  if (el("playback-status")) el("playback-status").textContent = "点击正文中的时间即可回听对应段落";
});
