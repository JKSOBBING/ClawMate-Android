/* Presentation only. No microphone, model or persistence work happens here. */
function initializeAppearance() {
  const paths = {
    wave: "M3 10v4m4-8v12m5-16v20m5-16v12m4-8v4",
    mic: "M9 5a3 3 0 0 1 6 0v6a3 3 0 0 1-6 0V5zm-3 5v1a6 6 0 0 0 12 0v-1m-6 7v4m-3 0h6",
    library: "M4 5h5l2 2h9v12H4V5zm3 6h10m-10 4h7",
    tasks: "M8 4h8v3H8V4zm0 1H5v16h14V5h-3M8 12l2 2 5-5m-7 9h8",
    template: "M4 4h6v6H4V4zm10 0h6v6h-6V4zM4 14h6v6H4v-6zm10 0h6v6h-6v-6z",
    settings: "M4 7h16M4 17h16M8 4v6m8 4v6",
    upload: "M12 16V3m-4 4 4-4 4 4M4 15v5h16v-5",
    sparkle: "m12 3 2.3 6.7L21 12l-6.7 2.3L12 21l-2.3-6.7L3 12l6.7-2.3L12 3z",
    note: "M6 3h9l4 4v14H6V3zm9 0v5h4M9 12h7m-7 4h5",
    pin: "m8 3 8 0-1 6 3 3H6l3-3-1-6zm4 9v9",
    archive: "M4 4h16v4H4V4zm1 4v12h14V8M9 12h6",
    trash: "M4 6h16M9 6V3h6v3M6 6l1 15h10l1-15M10 10v7m4-7v7",
    restore: "M4 5v5h5M4 10a8 8 0 1 1 1 8m7-12v6l4 2",
    switch: "M5 8h14m-4-4 4 4-4 4M19 16H5m4-4-4 4 4 4",
  };
  const node = (tag, cls, text) => {
    const element = document.createElement(tag);
    if (cls) element.className = cls;
    if (text) element.textContent = text;
    return element;
  };
  function icon(name) {
    const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg"),
      path = document.createElementNS(svg.namespaceURI, "path");
    svg.setAttribute("viewBox", "0 0 24 24");
    svg.setAttribute("class", "ui-icon");
    svg.setAttribute("aria-hidden", "true");
    svg.setAttribute("focusable", "false");
    path.setAttribute("d", paths[name]);
    svg.append(path);
    return svg;
  }
  function decorateButton(button, name) {
    if (button && !button.querySelector(".ui-icon")) button.prepend(icon(name));
  }
  const motionAllowed = () => !window.matchMedia("(prefers-reduced-motion: reduce)").matches;

  const heading = document.querySelector("body>h1"),
    subtitle = heading.nextElementSibling,
    header = node("header", "app-header"),
    logo = node("div", "app-logo"),
    copy = node("div", "app-header-copy"),
    date = node("div", "app-date");
  logo.append(icon("wave"));
  subtitle.textContent = "让每一段对话，都有迹可循";
  date.textContent = new Intl.DateTimeFormat("zh-CN", {
    month: "long",
    day: "numeric",
    weekday: "long",
  }).format(new Date());
  heading.before(header);
  copy.append(heading, subtitle);
  header.append(logo, copy, date);
  const navIcons = {
    work: "mic",
    library: "library",
    tasks: "tasks",
    templates: "template",
    settings: "settings",
  };
  for (const [key, name] of Object.entries(navIcons)) {
    const button = el("nav-" + key),
      text = node("span", "nav-label", button.textContent);
    button.replaceChildren(icon(name), text);
  }
  const studio = el("record").closest("section");
  studio.id = "capture-studio";
  const intro = node("div", "studio-heading"),
    eyebrow = node("span", "eyebrow", "A SPACE FOR YOUR VOICE"),
    title = node("h2"),
    description = node("p", null, "无需先建会议，录完再命名整理。");
  title.append(
    document.createTextNode("记下此刻，"),
    document.createElement("br"),
    document.createTextNode("整理每个好想法。"),
  );
  intro.append(eyebrow, title, description);
  const state = node("div", "studio-state"),
    stateLabel = node("span", null, "随时开始");
  state.id = "studio-state";
  state.setAttribute("role", "status");
  state.append(stateLabel);
  const visual = node("div", "studio-visual"),
    orbit = node("div", "studio-orbit"),
    wave = node("div", "studio-wave");
  visual.setAttribute("aria-hidden", "true");
  for (const [index, height] of [
    12, 20, 32, 22, 44, 55, 36, 62, 42, 54, 30, 46, 24, 34, 17, 10,
  ].entries()) {
    const bar = node("i");
    bar.style.setProperty("--bar-height", height + "px");
    bar.style.setProperty("--bar-delay", -index * 0.13 + "s");
    wave.append(bar);
  }
  visual.append(orbit, wave, node("span", "studio-caption", "CAPTURE · REFLECT · KEEP"));
  studio.prepend(intro, state, visual);
  studio.after(el("import-options"));
  const advanced = studio.querySelector("details");
  for (const note of [...studio.querySelectorAll(":scope>small")]) advanced?.append(note);
  const replay = el("replay"),
    replayNote = el("import-options").querySelector("small");
  el("comparison-options").querySelector(".fold-content").append(replay);
  if (replayNote) el("comparison-options").querySelector(".fold-content").append(replayNote);

  const input = el("file"),
    upload = el("upload"),
    line = input.closest("p"),
    drop = node("label", "audio-dropzone"),
    dropCopy = node("span"),
    dropTitle = node("strong", null, "选择一份音频"),
    dropHint = node("small", null, "从手机或电脑中导入");
  drop.htmlFor = "file";
  dropCopy.append(dropTitle, dropHint);
  drop.append(icon("upload"), dropCopy, input);
  line.className = "import-upload-grid";
  line.replaceChildren(drop, upload);
  const fileLabel = node("p", null);
  fileLabel.id = "selected-audio-name";
  fileLabel.hidden = true;
  fileLabel.setAttribute("aria-live", "polite");
  line.after(fileLabel);
  input.addEventListener("change", () => {
    fileLabel.hidden = !input.files.length;
    fileLabel.textContent = input.files[0]?.name || "";
    dropTitle.textContent = input.files.length ? "已选择音频" : "选择一份音频";
  });
  const buttons = {
    record: "mic",
    process: "sparkle",
    summary: "note",
    upload: "upload",
    "promote-meeting": "note",
    "save-document": "note",
  };
  for (const [id, name] of Object.entries(buttons)) decorateButton(el(id), name);
  decorateButton(document.querySelector(".workspace-current>button"), "switch");
  uiLifecycle.on("audio-target:changed", () => {
    decorateButton(el("record"), "mic");
    decorateButton(el("upload"), "upload");
  });
  const footer = node("p", "app-footer", "记录当下 · 从容整理");
  document.querySelector("body>script").before(footer);

  let lastCompact = null;
  function studioState() {
    const compact = !streaming && !!workspaceCurrent?.document?.segments?.length;
    studio.classList.toggle("has-content", compact);
    if (compact !== lastCompact) {
      if (compact) {
        title.textContent = "继续记录新的想法";
        description.textContent = "开始一段新录音，或继续整理下方的正文。";
      } else {
        title.replaceChildren(
          document.createTextNode("记下此刻，"),
          document.createElement("br"),
          document.createTextNode("整理每个好想法。"),
        );
        description.textContent = "无需先建会议，录完再命名整理。";
      }
      lastCompact = compact;
    }
    const active = streaming && recording && !paused && capturePhase === "recording";
    studio.classList.toggle("capture-session", !!streaming);
    studio.classList.toggle("is-capturing", !!active);
    studio.classList.toggle("is-paused", !!(streaming && paused));
    studio.classList.toggle(
      "is-preparing",
      !!((streaming && !active && !paused) || workspaceEntryBusy),
    );
    let text = "随时开始";
    if (streaming)
      text =
        capturePhase === "finalizing"
          ? "正在保存"
          : paused
            ? "已暂停"
            : active
              ? "正在录音"
              : "准备录音";
    else if (workspaceEntryBusy) text = "准备音频";
    else if (!modelsReady) text = "准备模型";
    else if (["processing", "summarizing"].includes(workspaceCurrent?.status)) text = "正在整理";
    stateLabel.textContent = text;
    if (!workspaceCurrent?.document_audio?.available || workspaceCurrent?.trashed_at)
      el("process").disabled = true;
    if (!workspaceCurrent?.document?.segments?.length || workspaceCurrent?.trashed_at)
      el("summary").disabled = true;
  }
  uiLifecycle.on("controls:changed", studioState);
  uiLifecycle.on("capture:changed", studioState);
  uiLifecycle.on("view:changed", ({ view, previous: oldView }) => {
    const index = Object.keys(navIcons).indexOf(workspaceView);
    el("workspace-nav").style.setProperty("--nav-index", Math.max(0, index));
    if (oldView !== workspaceView && motionAllowed())
      el("view-" + workspaceView).animate(
        [
          { opacity: 0.2, transform: "translateY(8px)" },
          { opacity: 1, transform: "translateY(0)" },
        ],
        { duration: 220, easing: "cubic-bezier(.2,.65,.3,1)" },
      );
  });
  workspaceSelectView(workspaceView, false);
  studioState();

  const decorateHistory = () => {
    for (const row of document.querySelectorAll(".history-row")) {
      for (const button of row.querySelectorAll(".history-row-actions button")) {
        const text = button.textContent;
        decorateButton(
          button,
          text.includes("置顶")
            ? "pin"
            : text.includes("归档")
              ? "archive"
              : text === "恢复"
                ? "restore"
                : "trash",
        );
      }
    }
  };
  uiLifecycle.on("history:rendered", decorateHistory);
  decorateHistory();
  uiLifecycle.on("meeting:rendered", studioState);

  document.addEventListener(
    "toggle",
    (event) => {
      const details = event.target;
      if (details.tagName !== "DETAILS" || !details.open || !motionAllowed()) return;
      const content = details.querySelector(":scope>.fold-content");
      if (content)
        content.animate(
          [
            { opacity: 0, transform: "translateY(-4px)" },
            { opacity: 1, transform: "translateY(0)" },
          ],
          { duration: 180, easing: "ease-out" },
        );
    },
    true,
  );
  document.addEventListener("pointerdown", (event) => {
    const button = event.target.closest("button");
    if (!button || button.disabled || !motionAllowed() || button.closest("#workspace-nav")) return;
    button.querySelector(".button-ink")?.remove();
    const ink = node("span", "button-ink"),
      rect = button.getBoundingClientRect();
    ink.style.setProperty("--ink-x", event.clientX - rect.left + "px");
    ink.style.setProperty("--ink-y", event.clientY - rect.top + "px");
    ink.setAttribute("aria-hidden", "true");
    button.append(ink);
    ink.addEventListener("animationend", () => ink.remove(), { once: true });
    setTimeout(() => ink.remove(), 700);
  });
}
