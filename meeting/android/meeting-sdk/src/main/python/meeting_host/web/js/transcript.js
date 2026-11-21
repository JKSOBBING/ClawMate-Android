/* Rendering of model results and transcripts; no startup side effects. */
function stageLabel(status) {
  return {recognizing: "识别中", draft: "待精修", refined: "已精修", failed: "保留草稿"}[status] || "";
}
function renderLive() {
  const target = el("result"), rows = [...finalSegments, ...(livePartial ? [livePartial] : [])];
  const existing = new Map([...target.querySelectorAll(".live-turn")].map(n => [n.dataset.segmentId, n]));
  const keep = new Set();
  for (const [index, row] of rows.entries()) {
    if (!row) continue;
    const key = String(row.segment_id ?? index);
    keep.add(key);
    let turn = existing.get(key);
    if (!turn) {
      turn = document.createElement("div");
      turn.className = "turn live-turn";
      turn.dataset.segmentId = key;
      for (const cls of ["speaker", "transcript-stage", "speech"]) {
        const child = document.createElement(cls === "speech" ? "p" : "span");
        child.className = cls;
        turn.append(child);
      }
    }
    const name = speakerName(row.speaker || "unknown");
    const speaker = turn.querySelector(".speaker"), badge = turn.querySelector(".transcript-stage"), speech = turn.querySelector(".speech");
    if (speaker.textContent !== name) speaker.textContent = name;
    const status = row.refinement_status || "";
    if (badge.textContent !== stageLabel(status)) badge.textContent = stageLabel(status);
    badge.hidden = !stageLabel(status);
    if (speech.textContent !== row.text) speech.textContent = row.text || "";
    if (turn.dataset.stage !== status && status === "refined") {
      turn.classList.add("refined-flash");
      setTimeout(() => turn.classList.remove("refined-flash"), 900);
    }
    turn.dataset.stage = status;
    if (turn.parentNode !== target) {
      const partial = target.querySelector('[data-segment-id="partial"]');
      target.insertBefore(turn, key !== "partial" ? partial : null);
    }
  }
  for (const node of [...target.children])
    if (!node.classList.contains("live-turn") || !keep.has(node.dataset.segmentId)) node.remove();
  if (progressiveRefinement) {
    const upgraded = finalSegments.filter(r => r?.refinement_status === "refined").length;
    const failed = finalSegments.filter(r => r?.refinement_status === "failed").length;
    el("original-metrics").textContent = "已精修 " + upgraded + " / " + finalSegments.length + " 段" + (failed ? " · " + failed + " 段保留草稿" : "");
  }
}
function renderFused(d) {
  const result = d.result,
    ref = result.refinement;
  resetExtra();
  el("original-heading").textContent = "会议正文";
  renderTranscript(el("result"), result.segments);
  el("original-metrics").textContent =
    "SenseVoice 底稿 + " +
    ref.profile.name +
    " · 已采纳 " +
    ref.accepted_edits +
    " 处纠错，" +
    (ref.proposed_edits - ref.accepted_edits) +
    " 处差异保留底稿" +
    (ref.failed_segments ? " · " + ref.failed_segments + " 段接口未完成，已保留底稿" : "");
  el("fusion-audit").hidden = false;
  const target = el("fusion-audit-content");
  target.replaceChildren();
  for (const row of ref.audit) {
    if (!row.edits.length && row.baseline_punctuation === row.candidate_punctuation && !row.error)
      continue;
    const p = document.createElement("p");
    p.className = "speech";
    p.textContent =
      row.start.toFixed(1) +
      "–" +
      row.end.toFixed(1) +
      "秒\nSenseVoice：" +
      row.baseline +
      "\n候选：" +
      row.candidate +
      "\n" +
      (row.error ||
        row.edits.map((e) => e.before + " → " + e.after + "（" + e.reason + "）").join("；") ||
        "仅标点差异，沿用 SenseVoice");
    target.append(p);
  }
  el("summary-section").hidden = !d.summary?.text;
  el("summary-text").textContent = d.summary?.text || "";
  el("status").textContent = (statuses[d.status] || d.status) + (d.error ? " — " + d.error : "");
}
function renderTranscript(target, segments, empty = "暂无转写文本") {
  const groups = [],
    interactive = target.id === "result" && !streaming;
  for (const segment of segments || []) {
    const text = (segment.text || "").trim();
    if (!text) continue;
    const speaker = segment.speaker || "unknown",
      previous = groups[groups.length - 1];
    const status = segment.speaker_status || "";
    if (
      !interactive &&
      previous &&
      speaker !== "unknown" &&
      previous.speaker === speaker &&
      previous.status === status
    )
      previous.text = appendText(previous.text, text);
    else groups.push({ speaker, text, status, start: segment.start, end: segment.end });
  }
  target.replaceChildren();
  if (!groups.length) {
    const p = document.createElement("p");
    p.className = "hint";
    p.textContent = empty;
    target.append(p);
    return;
  }
  for (const group of groups) {
    const turn = document.createElement("div");
    turn.className = "turn";
    const name =
      group.status === "overlap"
        ? "多人重叠 · 归属待确认"
        : speakerName(group.speaker) +
          (group.status === "unconfirmed" && group.speaker !== "unknown" ? "（待复核）" : "");
    if (name) {
      const label = document.createElement("span");
      label.className = "speaker";
      label.textContent = name;
      turn.append(label);
    }
    if (
      interactive &&
      Number.isFinite(group.start) &&
      Number.isFinite(group.end) &&
      group.end > group.start
    ) {
      turn.dataset.audioStart = group.start;
      turn.dataset.audioEnd = group.end;
      const play = document.createElement("button");
      play.className = "segment-play";
      play.textContent = "▶ " + formatTime(group.start);
      play.setAttribute("aria-label", "从 " + formatTime(group.start) + " 回听此段");
      play.disabled =
        typeof workspaceCurrent !== "undefined" && !workspaceCurrent?.document_audio?.available;
      play.onclick = () => playSegment(group.start).catch(showError);
      turn.append(play);
    }
    const speech = document.createElement("p");
    speech.className = "speech";
    speech.textContent = group.text;
    turn.append(speech);
    target.append(turn);
  }
}
function renderMachineMeeting(d) {
  if (d.result?.progressive_refinement?.enabled) {
    resetExtra();
    el("original-heading").textContent = "会议正文";
    renderTranscript(el("result"), d.result.segments);
    const p = d.result.progressive_refinement;
    el("original-metrics").textContent = "FireRed 已精修 " + (p.completed - p.failed) + " 段" + (p.failed ? " · " + p.failed + " 段保留草稿" : "");
    el("summary-section").hidden = !d.summary?.text;
    el("summary-text").textContent = d.summary?.text || "";
    el("status").textContent = statuses[d.status] || d.status;
    return;
  }
  if (d.result?.refinement) {
    renderFused(d);
    return;
  }
  const result = d.result || {},
    segments = result.segments || [],
    refined = Boolean(result.engines && result.engines.offline);
  const original = refined
    ? result.original_segments ||
      segments
        .filter((s) => typeof s.paraformer_text === "string")
        .map((s) => ({ ...s, text: s.paraformer_text }))
    : segments;
  el("original-heading").textContent = !d.result
    ? "SenseVoice 实时草稿与句末精修"
    : result.streaming_mode === "sensevoice_dual" ||
        result.engines?.streaming?.startsWith("SenseVoice")
      ? "SenseVoice 句末定稿"
      : refined && !result.original_segments
        ? "Paraformer 对比转写（音频回放）"
        : "Paraformer 实时完整转写";
  const labeled = original.map((s) => {
    if (result.original_segments || (s.speaker && s.speaker !== "unknown")) return s;
    let best = null,
      overlap = 0;
    for (const turn of result.speaker_turns || []) {
      const duration = Math.max(0, Math.min(s.end, turn.end) - Math.max(s.start, turn.start));
      if (duration > overlap) {
        best = turn;
        overlap = duration;
      }
    }
    return best ? { ...s, speaker: best.speaker } : s;
  });
  renderTranscript(
    el("result"),
    labeled,
    refined ? "本次未生成对比转写。" : "完成录音或上传音频后，完整文本将显示在这里。",
  );
  el("refined-section").hidden = !refined;
  renderTranscript(el("refined-result"), refined ? segments : [], "未检测到可识别的语音。");
  const primary = result.offline_primary_model || "sensevoice",
    comparisons = result.comparisons || {};
  el("original-metrics").textContent = result.original_segments
    ? ""
    : metricText(comparisons.paraformer?.metrics);
  el("refined-heading").textContent =
    (primary === "qwen3_asr" ? "Qwen3-ASR 离线精修" : "SenseVoice 离线精修") +
    (result.speaker_verification?.enabled ? " · 全局声纹严格复核（独立编号）" : "");
  el("refined-metrics").textContent = metricText(comparisons[primary]?.metrics);
  const para = comparisons.paraformer;
  el("paraformer-offline-section").hidden = !para || !result.original_segments;
  renderTranscript(el("paraformer-offline-result"), para?.segments || []);
  el("paraformer-offline-metrics").textContent = metricText(para?.metrics);
  const secondaryKey = primary === "qwen3_asr" ? "sensevoice" : "qwen3_asr",
    secondary = comparisons[secondaryKey];
  el("secondary-section").hidden = !secondary;
  el("secondary-heading").textContent =
    secondaryKey === "qwen3_asr" ? "Qwen3-ASR 精修对比" : "SenseVoice 精修对比";
  renderTranscript(el("secondary-result"), secondary?.segments || []);
  el("secondary-metrics").textContent = metricText(secondary?.metrics);
  el("qwen-live-section").hidden = !result.qwen_live;
  renderTranscript(el("qwen-live-result"), result.qwen_live?.segments || []);
  el("qwen-live-metrics").textContent = result.qwen_live
    ? "VAD 分段识别 · " +
      (result.qwen_live.metrics.first_text_seconds == null
        ? "未检测到可识别语音"
        : "首次完整出字 " + result.qwen_live.metrics.first_text_seconds.toFixed(2) + " 秒") +
      (result.qwen_live.errors.length ? " · " + result.qwen_live.errors.join("；") : "")
    : "";
  el("summary-section").hidden = !d.summary?.text;
  el("summary-text").textContent = d.summary?.text || "";
  el("status").textContent =
    (d.status === "processing" && result.auto_refinement
      ? "录音已保存，正在自动精修文字并严格复核声纹…"
      : statuses[d.status] || d.status) + (d.error ? " — " + d.error : "");
}
