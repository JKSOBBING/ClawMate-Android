/* Browser/native capture protocol and one controls implementation. */
function controls() {
  [
    "create",
    "upload",
    "process",
    "summary",
    "record",
    "replay",
    "offline-model",
    "qwen-live-enabled",
    "auto-refine",
    "profile-editor",
    "profile-name",
    "profile-kind",
    "profile-url",
    "profile-model",
    "profile-key",
    "profile-clear-key",
    "profile-timeout",
    "profile-save",
    "profile-test",
  ].forEach((x) => (el(x).disabled = streaming || settingsBusy));
  el("profile-delete").disabled = streaming || settingsBusy || !el("profile-editor").value;
  if (!refinementSettings)
    ["record", "replay", "process", "upload", "offline-model"].forEach(
      (x) => (el(x).disabled = true),
    );
  if (window.PhoneAudio && !modelsReady)
    ["upload", "process", "summary", "record", "replay"].forEach((x) => (el(x).disabled = true));
  el("stop").disabled = !recording;
  el("history")
    .querySelectorAll("button")
    .forEach((b) => (b.disabled = streaming));
  captureControls();
  uiLifecycle.emit("controls:changed");
}
function formatTime(seconds) {
  const value = Math.max(0, Math.floor(seconds || 0));
  return (
    Math.floor(value / 60)
      .toString()
      .padStart(2, "0") +
    ":" +
    (value % 60).toString().padStart(2, "0")
  );
}
function captureControls() {
  const button = el("pause-recording");
  button.hidden = !streaming || !["native", "browser"].includes(captureMode);
  button.disabled =
    !recording ||
    capturePhase === "finalizing" ||
    capturePhase === "preparing" ||
    (captureMode === "native" && typeof PhoneAudio.pause !== "function");
  button.textContent = paused ? "继续录音" : "暂停录音";
  el("capture-clock").hidden = !streaming;
  el("capture-clock").textContent = (paused ? "已暂停 · " : "已录音 ") + formatTime(captureSeconds);
  if (el("playback-bar"))
    el("playback-bar").hidden = streaming || !workspaceCurrent?.document_audio?.available;
  if (captureMode === "native") el("stop").disabled = !streaming || capturePhase === "finalizing";
  uiLifecycle.emit("capture:changed");
}
function cleanup() {
  recording = false;
  paused = false;
  captureMode = null;
  capturePhase = streaming ? "finalizing" : "idle";
  if (heartbeat) clearInterval(heartbeat);
  heartbeat = null;
  if (processor) processor.disconnect();
  if (source) source.disconnect();
  if (media) media.getTracks().forEach((t) => t.stop());
  if (ctx) ctx.close();
  processor = source = media = ctx = null;
  controls();
}
function guardNewAudio() {
  if (typeof workspaceDirty !== "undefined" && workspaceDirty)
    throw Error("请先保存正文，或放弃未保存修改，再开始新的录音或导入");
}
el("pause-recording").onclick = () => {
  try {
    if (!recording) return;
    el("pause-recording").disabled = true;
    if (captureMode === "native") {
      if (paused) PhoneAudio.resume();
      else PhoneAudio.pause();
    } else if (captureMode === "browser" && ws?.readyState === 1) {
      paused = !paused;
      media?.getAudioTracks().forEach((track) => (track.enabled = !paused));
      ws.send(JSON.stringify({ type: paused ? "pause" : "resume" }));
      captureControls();
      el("status").textContent = paused ? "录音已暂停，点击继续录音后接着录制" : "正在录音";
    }
  } catch (error) {
    captureControls();
    showError(error);
  }
};
function streamEvent(d, onReady = () => {}) {
  uiLifecycle.emit("stream:event", d);
  if (d.type === "ready") {
    capturePhase = captureMode === "native" ? "preparing" : "recording";
    paused = false;
    captureSeconds = 0;
    finalSegments = [];
    qwenFinalSegments = [];
    livePartial = null;
    liveAudioId = d.audio_id || null;
    progressiveRefinement = Boolean(d.progressive_refinement);
    resetExtra();
    el("original-metrics").textContent = "";
    el("original-heading").textContent =
      progressiveRefinement ? "实时草稿 · 逐段精修" : d.mode === "sensevoice_dual" ? "SenseVoice 实时草稿与句末精修" : "Paraformer 实时完整转写";
    renderTranscript(el("result"), [], "正在录音，已完成的转写会持续显示。");
    el("qwen-live-section").hidden = !d.qwen_compare;
    renderTranscript(el("qwen-live-result"), [], "正在等待一段完整语音…");
    el("qwen-live-metrics").textContent = "停顿或连续说话达到 8 秒后，生成这一段的完整文本。";
    recording = true;
    controls();
    if (d.refinement_warning) el("original-metrics").textContent = d.refinement_warning;
    onReady();
  } else if (d.type === "ack") {
    if (captureMode !== "native") captureSeconds = d.audio_seconds || 0;
    captureControls();
  } else if (d.type === "partial") {
    livePartial = d.text?.trim() ? { ...d, refinement_status: "recognizing", segment_id: "partial" } : null;
    renderLive();
  } else if (d.type === "final") {
    const index = d.segment_id ?? finalSegments.length;
    finalSegments[index] = { ...d, segment_id: index, speaker: d.speaker || "unknown" };
    livePartial = null;
    renderLive();
  } else if (d.type === "refined" || d.type === "refinement_failed") {
    if (liveAudioId && d.audio_id !== liveAudioId) return;
    const row = finalSegments[d.segment_id];
    if (!row || (row.refinement_revision || 0) >= d.refinement_revision) return;
    // Text upgrades cannot overwrite attribution or time boundaries.
    for (const key of ["text", "firered_text", "refinement_status", "refinement_revision", "refinement_seconds"])
      if (d[key] !== undefined) row[key] = d[key];
    renderLive();
  } else if (d.type === "speaker_update") {
    if (finalSegments[d.segment_id]) Object.assign(finalSegments[d.segment_id], d);
    renderLive();
  } else if (d.type === "refinement_progress") {
    el("status").textContent = "录音已保存，正在完成剩余 " + Math.max(0, d.submitted - d.completed) + " 段精修…";
  } else if (d.type === "qwen_final") {
    qwenFinalSegments.push(d);
    renderTranscript(el("qwen-live-result"), qwenFinalSegments);
    el("qwen-live-metrics").textContent =
      "VAD 分段识别 · 本段耗时 " + d.processing_seconds.toFixed(2) + " 秒";
  } else if (d.type === "qwen_error")
    el("qwen-live-metrics").textContent = "Qwen 对比未完成：" + d.message;
  else if (d.type === "error") showError(Error(d.message));
  else if (d.type === "done")
    el("status").textContent =
      d.refinement_status === "processing"
        ? "录音已保存，正在自动精修文字并严格复核声纹…"
        : d.progressive_refinement ? (d.refinement_failed ? "转写完成 · 部分片段保留草稿" : "转写与逐段精修完成") : "转写完成";
}
function openStream(onReady) {
  ws = new WebSocket(
    (location.protocol === "https:" ? "wss://" : "ws://") +
      location.host +
      "/api/v1/meetings/" +
      id +
      "/stream?compare_qwen=" +
      el("qwen-live-enabled").checked,
  );
  ws.onopen = () =>
    ws.send(
      JSON.stringify({ type: "start", sample_rate: 16000, channels: 1, format: "pcm_s16le" }),
    );
  ws.onmessage = (e) => streamEvent(JSON.parse(e.data), onReady);
  ws.onclose = () => {
    streaming = false;
    cleanup();
    refresh().catch(showError);
    history().catch(showError);
  };
  ws.onerror = () => {
    showError(Error("录音连接失败"));
    cleanup();
  };
}
el("record").onclick = async () => {
  try {
    await prepareAudioEntry("recording");
    el("meeting-player").pause();
    streaming = true;
    captureMode = window.PhoneAudio ? "native" : "browser";
    capturePhase = "preparing";
    controls();
    if (window.PhoneAudio) {
      el("status").textContent = "正在准备手机录音…";
      PhoneAudio.start(id, el("qwen-live-enabled").checked);
      return;
    }
    media = await navigator.mediaDevices.getUserMedia({
      audio: { channelCount: 1, echoCancellation: true },
    });
    ctx = new AudioContext({ sampleRate: 16000 });
    if (ctx.sampleRate !== 16000) throw Error("浏览器不支持 16kHz 录音，请使用文件上传");
    source = ctx.createMediaStreamSource(media);
    openStream(() => {
      heartbeat = setInterval(() => {
        if (paused && ws?.readyState === 1) ws.send(JSON.stringify({ type: "ping" }));
      }, 15000);
      processor = ctx.createScriptProcessor(2048, 1, 1);
      processor.onaudioprocess = (e) => {
        if (!recording || paused || ws.readyState !== 1) return;
        if (ws.bufferedAmount > 640000) {
          showError(Error("识别处理落后，请结束录音后使用离线精修"));
          return;
        }
        const f = e.inputBuffer.getChannelData(0),
          p = new Int16Array(f.length);
        for (let i = 0; i < f.length; i++)
          p[i] = Math.round(Math.max(-1, Math.min(1, f[i])) * 32767);
        ws.send(p.buffer);
      };
      source.connect(processor);
      processor.connect(ctx.destination);
      el("status").textContent = "正在录音";
    });
  } catch (e) {
    streaming = false;
    cleanup();
    showError(e);
  }
};
el("replay").onclick = async () => {
  try {
    idle();
    guardNewAudio();
    streaming = true;
    controls();
    el("status").textContent = "正在准备音频回放…";
    const file = el("file").files[0];
    let encoded;
    if (file) encoded = await file.arrayBuffer();
    else {
      const r = await fetch("/api/v1/meetings/" + id + "/audio");
      if (!r.ok) throw Error("请选择音频文件，或打开已保存音频的会议");
      encoded = await r.arrayBuffer();
    }
    ctx = new AudioContext({ sampleRate: 16000 });
    if (ctx.sampleRate !== 16000) throw Error("浏览器不支持 16kHz 回放");
    const decoded = await ctx.decodeAudioData(encoded);
    if (decoded.duration > 3600 || !decoded.length) throw Error("音频须为非空且不超过一小时");
    const samples = new Float32Array(decoded.length);
    for (let channel = 0; channel < decoded.numberOfChannels; channel++) {
      const data = decoded.getChannelData(channel);
      for (let i = 0; i < samples.length; i++) samples[i] += data[i] / decoded.numberOfChannels;
    }
    openStream(() => {
      el("status").textContent = "正在按正常速度回放识别…";
      (async () => {
        const began = performance.now();
        for (let offset = 0; offset < samples.length; offset += 1600) {
          const delay = began + offset / 16 - performance.now();
          if (delay > 0) await new Promise((resolve) => setTimeout(resolve, delay));
          if (!recording || ws.readyState !== 1) return;
          if (ws.bufferedAmount > 640000) throw Error("实时识别积压，请结束回放并使用离线精修");
          const data = samples.subarray(offset, offset + 1600),
            pcm = new Int16Array(data.length);
          for (let i = 0; i < data.length; i++)
            pcm[i] = Math.round(Math.max(-1, Math.min(1, data[i])) * 32767);
          ws.send(pcm.buffer);
        }
        const remaining = began + samples.length / 16 - performance.now();
        if (remaining > 0) await new Promise((resolve) => setTimeout(resolve, remaining));
        if (recording && ws.readyState === 1) {
          ws.send(JSON.stringify({ type: "stop" }));
          cleanup();
          el("status").textContent = "正在完成两路转写…";
        }
      })().catch((e) => {
        if (ws && ws.readyState === 1) ws.send(JSON.stringify({ type: "stop" }));
        cleanup();
        showError(e);
      });
    });
  } catch (e) {
    streaming = false;
    cleanup();
    showError(e);
  }
};
el("stop").onclick = () => {
  if (window.PhoneAudio && !(ws && ws.readyState === 1)) PhoneAudio.stop();
  else if (ws && ws.readyState === 1) ws.send(JSON.stringify({ type: "stop" }));
  cleanup();
  el("status").textContent = "正在完成转写…";
};
window.phoneEvent = (message) => {
  const d = JSON.parse(message);
  if (d.type === "capture_phase") {
    capturePhase = d.phase;
    paused = Boolean(d.paused);
    recording = Boolean(d.recording);
    streaming = Boolean(d.streaming);
    if (streaming) captureMode = "native";
    captureSeconds = d.audio_seconds || 0;
    controls();
    el("status").textContent =
      d.message ||
      {
        preparing: "正在准备麦克风…",
        recording: "正在使用手机麦克风录音",
        paused: "录音已暂停，麦克风已停止采集",
        finalizing: "正在保存录音与转写…",
        interrupted: "录音中断，请检查已保存音频",
        idle: "录音已保存",
      }[d.phase] ||
      "";
    return;
  }
  if (d.type === "capture_state") {
    if (d.mid && d.streaming) {
      id = d.mid;
      el("export").href = "/api/v1/meetings/" + id + "/export";
      el("export").hidden = false;
    }
    if (d.streaming) {
      streaming = true;
      captureMode = "native";
      streamEvent({ type: "ready", qwen_compare: false, mode: "sensevoice_dual", audio_id: d.audio_id, progressive_refinement: d.progressive_refinement });
      finalSegments = d.finals || [];
      qwenFinalSegments = d.qwen || [];
      renderLive();
      renderTranscript(el("qwen-live-result"), qwenFinalSegments);
      recording = d.recording;
      paused = Boolean(d.paused);
      captureSeconds = d.audio_seconds || 0;
      el("status").textContent = paused
        ? "录音已暂停，麦克风已停止采集"
        : recording
          ? "正在使用手机麦克风录音"
          : "正在准备或完成转写…";
      controls();
      api("/meetings/" + d.mid)
        .then((meeting) => {
          if (id === d.mid && streaming && el("workspace-current-name"))
            el("workspace-current-name").textContent = meeting.title;
        })
        .catch(showError);
    } else refresh().catch(showError);
    capturePhase = d.phase || capturePhase;
    controls();
  } else if (d.type === "capture_started") {
    recording = true;
    captureMode = "native";
    capturePhase = "recording";
    paused = false;
    controls();
    el("status").textContent = "正在使用手机麦克风录音";
  } else if (d.type === "capture_paused" || d.type === "capture_resumed") {
    paused = d.type === "capture_paused";
    capturePhase = paused ? "paused" : "recording";
    captureControls();
    el("status").textContent = paused
      ? JSON.parse(PhoneAudio.state()).message || "录音已暂停，麦克风已停止采集"
      : "正在使用手机麦克风录音";
  } else if (d.type === "capture_stopping") {
    recording = false;
    controls();
    el("status").textContent = "正在完成转写…";
  } else if (d.type === "capture_closed") {
    streaming = false;
    cleanup();
    refresh().catch(showError);
    history().catch(showError);
  } else if (d.type === "capture_error") {
    streaming = false;
    cleanup();
    showError(Error(d.message));
  } else if (d.type === "operation_error") showError(Error(d.message));
  else streamEvent(d);
};
