/* Capture first; formal meeting creation is a later, reversible organization step. */
const historyViews = [
  ["meetings", "会议"],
  ["inbox", "待整理"],
  ["pinned", "置顶"],
  ["archived", "归档"],
  ["trash", "回收站"],
  ["all", "全部记录"],
];
function historyNode(tag, text, id) {
  const node = document.createElement(tag);
  if (text) node.textContent = text;
  if (id) node.id = id;
  return node;
}
function historySelect(id, options, label) {
  const select = historyNode("select", null, id);
  select.setAttribute("aria-label", label);
  for (const [value, text] of options) select.append(new Option(text, value));
  return select;
}
function historyButton(text, id, action) {
  const button = historyNode("button", text, id);
  button.type = "button";
  button.onclick = workspaceAction(action);
  return button;
}
function historyGuard() {
  if (streaming || workspaceEntryBusy || settingsBusy) throw Error("请等待当前操作结束");
  guardNewAudio();
  if (workspaceMetaDirty || promoteDirty) throw Error("请先保存信息，或明确放弃未保存修改");
}
async function prepareAudioEntry(source) {
  historyGuard();
  workspaceEntryBusy = true;
  controls();
  try {
    if (el("audio-target-current").checked) {
      idle();
      if (workspaceCurrent?.trashed_at) throw Error("请先恢复回收站中的记录");
    } else {
      const draft = await api("/inbox", requestJSON("POST", { source }));
      await select(draft.id);
      history().catch(() => {});
    }
  } finally {
    workspaceEntryBusy = false;
    controls();
  }
}
function updateHistoryControls() {
  if (workspaceEntryBusy)
    ["record", "upload", "replay", "create", "process", "summary"].forEach(
      (key) => (el(key).disabled = true),
    );
  const busy = streaming || workspaceEntryBusy || settingsBusy;
  if (el("promote-meeting"))
    el("promote-meeting").disabled =
      busy ||
      !workspaceCurrent?.draft ||
      !!workspaceCurrent?.trashed_at ||
      ["created", "recording", "processing", "summarizing"].includes(workspaceCurrent?.status) ||
      !["completed", "recovered"].includes(workspaceCurrent?.document_audio?.state);
  document
    .querySelectorAll(
      ".history-management button,.history-management input[type=checkbox],#audio-target-current",
    )
    .forEach((node) => (node.disabled = busy));
  if (el("history-batch")) el("history-batch").disabled = busy || !historySelected.size;
  if (el("history-prev")) el("history-prev").disabled = busy || historyPage <= 1;
  if (el("history-next"))
    el("history-next").disabled =
      busy || !historyData || historyPage * historyData.page_size >= historyData.total;
}
function guardHistorySelection(mid) {
  if (mid !== id && (workspaceMetaDirty || promoteDirty))
    throw Error("请先保存信息，或明确放弃未保存修改");
}
function rememberHistorySelection(mid) {
  try {
    localStorage.setItem("workspace-record-id", mid);
  } catch {}
}
function renderHistoryDetails(meeting, { changed }) {
  el("workspace-current-name").textContent = (meeting.draft ? "待整理 · " : "") + meeting.title;
  el("workspace-current-name").nextElementSibling.textContent = meeting.trashed_at
    ? "此记录在回收站，恢复后可继续处理"
    : meeting.draft
      ? "音频与文字已自动保存，处理后可保存为会议"
      : "可以开始新录音，或回听、整理当前会议";
  el("draft-save-card").hidden = !meeting.draft || !!meeting.trashed_at;
  if (changed || !promoteDirty) {
    el("promote-title").value = meeting.title;
    el("promote-tags").value = (meeting.tags || []).join(", ");
    promoteDirty = false;
  }
  el("promote-status").textContent = ["processing", "summarizing"].includes(meeting.status)
    ? "正在处理，完成后即可保存为会议。"
    : meeting.document_audio?.recoverable
      ? "请先恢复已保存的录音，再保存为会议。"
      : meeting.status === "created"
        ? "等待录音或上传；未完成的记录会保留在“待整理”。"
        : "处理已结束，可修改名称和标签。也可以暂存，下次继续。";
  controls();
}

function historyParams() {
  const params = new URLSearchParams({
    q: el("meeting-search").value,
    view: el("history-view").value,
    status: el("history-status").value,
    source: el("history-source").value,
    sort: el("history-sort").value,
    page: historyPage,
    page_size: 20,
  });
  const from = el("history-from").value,
    to = el("history-to").value;
  if (from) params.set("since", new Date(from + "T00:00:00").toISOString());
  if (to) {
    const next = new Date(to + "T00:00:00");
    next.setDate(next.getDate() + 1);
    params.set("until", next.toISOString());
  }
  return params;
}
function historySelectionLabel() {
  el("history-selection").textContent = "本页已选 " + historySelected.size + " 条";
  controls();
}
async function history() {
  if (!el("history-view")) return;
  const sequence = ++historySequence,
    data = await api("/history?" + historyParams());
  if (sequence !== historySequence) return;
  if (historyPage > Math.max(1, Math.ceil(data.total / data.page_size))) {
    historyPage = Math.max(1, Math.ceil(data.total / data.page_size));
    return history();
  }
  historyData = data;
  for (const option of el("history-view").options) {
    const label = historyViews.find(([value]) => value === option.value)[1];
    option.textContent =
      label + (data.counts[option.value] === undefined ? "" : " · " + data.counts[option.value]);
  }
  el("history-total").textContent =
    "共 " +
    data.total +
    " 条 · 第 " +
    historyPage +
    " / " +
    Math.max(1, Math.ceil(data.total / data.page_size)) +
    " 页";
  el("history-prev").disabled = historyPage <= 1;
  el("history-next").disabled = historyPage * data.page_size >= data.total;
  const stamp = JSON.stringify([data.items, el("history-view").value, historyPage]);
  if (stamp === historyStamp) return;
  historyStamp = stamp;
  const visible = new Set(data.items.map((item) => item.id));
  historySelected = new Set([...historySelected].filter((mid) => visible.has(mid)));
  const list = el("history");
  list.replaceChildren();
  if (!data.items.length)
    list.append(
      historyNode(
        "p",
        el("history-view").value === "inbox"
          ? "还没有待整理记录。直接开始录音或上传音频即可。"
          : "没有符合条件的记录。",
      ),
    );
  for (const item of data.items) {
    const row = historyNode("article", null);
    row.className = "history-row";
    row.dataset.mid = item.id;
    row.dataset.status = item.status;
    const check = historyNode("input");
    check.type = "checkbox";
    check.checked = historySelected.has(item.id);
    check.setAttribute("aria-label", "选择 " + item.title);
    check.onchange = () => {
      if (check.checked) historySelected.add(item.id);
      else historySelected.delete(item.id);
      historySelectionLabel();
    };
    const content = historyNode("div");
    content.className = "history-row-content";
    const open = historyButton((item.pinned ? "★ " : "") + item.title, null, async () => {
      if (streaming) throw Error("请先结束录音");
      await select(item.id);
    });
    open.className = "history-open";
    const heading = historyNode("div");
    heading.className = "history-row-heading";
    const badge = historyNode("span", statuses[item.status] || item.status);
    badge.className = "history-state";
    heading.append(open, badge);
    content.append(heading);
    content.append(
      historyNode(
        "small",
        [
          item.draft ? "待整理" : "会议",
          new Date(item.created).toLocaleString("zh-CN"),
          item.tags.join(" / "),
        ]
          .filter(Boolean)
          .join(" · "),
      ),
    );
    if (item.preview) content.append(historyNode("p", item.preview));
    const actions = historyNode("div");
    actions.className = "history-row-actions";
    const action = (label, key) =>
      actions.append(historyButton(label, null, () => historyApply([item.id], key)));
    if (item.trashed_at) action("恢复", "restore");
    else {
      action(item.pinned ? "取消置顶" : "置顶", item.pinned ? "unpin" : "pin");
      if (!item.draft)
        action(item.archived ? "取消归档" : "归档", item.archived ? "unarchive" : "archive");
      action("移入回收站", "trash");
    }
    row.append(check, content, actions);
    list.append(row);
  }
  historySelectionLabel();
  uiLifecycle.emit("history:rendered");
}
async function historyApply(ids, action) {
  if (streaming) throw Error("请先结束录音");
  if (!ids.length) throw Error("请先选择记录");
  if (ids.includes(id) && (workspaceDirty || workspaceMetaDirty || promoteDirty))
    throw Error("当前记录有未保存修改，请先保存或放弃修改");
  await api("/history/batch", requestJSON("POST", { ids, action }));
  historySelected.clear();
  historyStamp = "";
  await history();
  await refresh();
  el("history-feedback").textContent =
    action === "trash" ? "已移入回收站，音频和正文均保留，可随时恢复。" : "操作完成。";
}

function initializeHistory() {
  uiLifecycle.on("controls:changed", updateHistoryControls);
  uiLifecycle.on("meeting:selecting", guardHistorySelection);
  uiLifecycle.on("meeting:selected", rememberHistorySelection);
  uiLifecycle.on("meeting:rendered", renderHistoryDetails);
  el("record").textContent = "开始新录音";
  el("upload").textContent = "上传并处理新音频";
  el("status").textContent = "可直接录音或上传音频，处理后再保存为会议";
  el("workspace-current-name").textContent = "从一段录音开始";
  el("workspace-current-name").nextElementSibling.textContent =
    "无需先填写会议名称，未整理的记录会自动保存";
  const control = el("record").closest("section"),
    hint = historyNode("p", "直接录音或上传，结束后再命名并保存为会议。");
  hint.className = "capture-entry-hint";
  control.prepend(hint);
  const target = historyNode("details"),
    targetSummary = historyNode("summary", "更新当前记录的音频"),
    label = historyNode("label"),
    check = historyNode("input", null, "audio-target-current");
  check.type = "checkbox";
  label.append(check, document.createTextNode("使用当前记录（保留旧音频和正文版本）"));
  target.append(targetSummary, label);
  control.append(target);
  check.onchange = () => {
    el("record").textContent = check.checked ? "在当前记录中录音" : "开始新录音";
    el("upload").textContent = check.checked ? "上传到当前记录" : "上传并处理新音频";
    uiLifecycle.emit("audio-target:changed");
  };
  const importOptions = el("import-options");
  importOptions.querySelector("summary").textContent = "上传音频";
  importOptions.open = true;
  const card = historyNode("section", null, "draft-save-card");
  card.hidden = true;
  card.append(historyNode("h2", "保存为会议"));
  for (const [key, label] of [
    ["promote-title", "会议名称"],
    ["promote-tags", "标签，用逗号分隔"],
  ]) {
    const input = historyNode("input", null, key);
    input.placeholder = label;
    input.setAttribute("aria-label", label);
    input.maxLength = key === "promote-title" ? 200 : 820;
    input.oninput = () => (promoteDirty = true);
    card.append(input);
  }
  card.append(
    historyButton("保存为会议", "promote-meeting", async () => {
      idle();
      if (workspaceDirty) throw Error("请先保存正文");
      await api(
        "/meetings/" + id + "/promote",
        requestJSON("POST", {
          title: el("promote-title").value,
          tags: el("promote-tags").value.split(/[,，]/),
        }),
      );
      promoteDirty = false;
      await refresh();
      await history();
      el("status").textContent = "已保存为会议，音频、正文和处理记录均已保留";
    }),
    historyButton("暂存，稍后整理", "keep-draft", async () => {
      idle();
      if (workspaceDirty) throw Error("请先保存正文");
      if (promoteDirty) {
        await api(
          "/meetings/" + id,
          requestJSON("PATCH", {
            title: el("promote-title").value,
            tags: el("promote-tags").value.split(/[,，]/),
          }),
        );
        promoteDirty = false;
        await refresh();
      }
      workspaceSelectView("library");
      el("history-view").value = "inbox";
      historyPage = 1;
      await history();
    }),
    historyButton("放弃信息修改", null, async () => {
      promoteDirty = false;
      await refresh();
    }),
    historyNode("p", null, "promote-status"),
  );
  control.after(card);
  const manager = el("meeting-workspace");
  manager.classList.add("history-management");
  manager.querySelector("h2").textContent = "历史记录";
  el("meeting-search").placeholder = "搜索名称、标签、正文或纪要";
  el("show-archived").closest("label").hidden = true;
  const filters = historyNode("div");
  filters.className = "history-filters";
  filters.append(
    historySelect("history-view", historyViews, "记录分类"),
    historySelect("history-status", [["", "全部状态"], ...Object.entries(statuses)], "处理状态"),
    historySelect(
      "history-source",
      [
        ["", "全部来源"],
        ["recording", "录音"],
        ["upload", "上传"],
        ["manual", "原有会议"],
      ],
      "记录来源",
    ),
    historySelect(
      "history-sort",
      [
        ["updated", "最近更新"],
        ["created", "最近创建"],
        ["oldest", "最早创建"],
        ["title", "名称顺序"],
      ],
      "排列顺序",
    ),
  );
  const dates = historyNode("div");
  dates.className = "history-filters";
  for (const [key, text] of [
    ["history-from", "开始日期"],
    ["history-to", "结束日期"],
  ]) {
    const label = historyNode("label", text),
      input = historyNode("input", null, key);
    input.type = "date";
    input.setAttribute("aria-label", text);
    label.append(input);
    dates.append(label);
  }
  const filterFold = historyNode("details"),
    filterSummary = historyNode("summary", "状态、来源与日期");
  filterFold.append(filterSummary, filters, dates);
  manager.prepend(filterFold);
  manager.prepend(el("history-view"));
  manager.prepend(manager.querySelector("h2"));
  const bulk = historyNode("div");
  bulk.append(
    historyButton("选择本页", "history-select-page", async () => {
      for (const item of historyData?.items || []) historySelected.add(item.id);
      historyStamp = "";
      await history();
    }),
    historyButton("清除选择", null, async () => {
      historySelected.clear();
      historyStamp = "";
      await history();
    }),
    historyNode("small", "本页已选 0 条", "history-selection"),
    historySelect(
      "history-action",
      [
        ["pin", "置顶"],
        ["unpin", "取消置顶"],
        ["archive", "归档"],
        ["unarchive", "取消归档"],
        ["trash", "移入回收站"],
        ["restore", "从回收站恢复"],
      ],
      "批量操作",
    ),
    historyButton("应用到所选", "history-batch", () =>
      historyApply([...historySelected], el("history-action").value),
    ),
  );
  const bulkFold = historyNode("details"),
    bulkSummary = historyNode("summary", "批量管理");
  bulkFold.append(bulkSummary, bulk);
  manager.insertBefore(bulkFold, el("history"));
  const pagination = historyNode("div");
  pagination.className = "history-pagination";
  pagination.append(
    historyButton("上一页", "history-prev", async () => {
      historyPage = Math.max(1, historyPage - 1);
      historySelected.clear();
      await history();
    }),
    historyNode("small", null, "history-total"),
    historyButton("下一页", "history-next", async () => {
      historyPage++;
      historySelected.clear();
      await history();
    }),
  );
  manager.append(pagination, historyNode("p", null, "history-feedback"));
  const reset = async () => {
    historyPage = 1;
    historySelected.clear();
    await history();
  };
  for (const key of [
    "history-view",
    "history-status",
    "history-source",
    "history-sort",
    "history-from",
    "history-to",
  ])
    el(key).onchange = workspaceAction(reset);
  el("search-meetings").onclick = workspaceAction(reset);
  el("meeting-search").onkeydown = (event) => {
    if (event.key === "Enter") workspaceAction(reset)();
  };
  el("show-archived").onchange = workspaceAction(async () => {
    el("history-view").value = el("show-archived").checked ? "archived" : "meetings";
    await reset();
  });
  el("create-meeting-card").querySelector("h2").textContent = "也可以先建空白会议";
  el("title").value = "新会议";
  el("create-meeting-card").hidden = true;
  const empty = historyNode("details"),
    emptySummary = historyNode("summary", "先建空白会议"),
    createCard = el("create-meeting-card");
  empty.append(emptySummary, createCard);
  createCard.hidden = false;
  el("view-library").append(empty);
  const discard = historyButton("放弃未保存的信息修改", null, async () => {
    workspaceMetaDirty = false;
    promoteDirty = false;
    await refresh();
  });
  el("metadata-options").querySelector(".fold-content").append(discard);
  el("archive-meeting").onclick = workspaceAction(async () => {
    idle();
    await historyApply([id], workspaceCurrent.archived ? "unarchive" : "archive");
  });
  controls();
  history().catch(showError);
}
async function restoreWorkspaceSelection() {
  let saved;
  try {
    saved = localStorage.getItem("workspace-record-id");
  } catch {}
  if (!id && !streaming && saved) {
    try {
      await select(saved);
      return;
    } catch {
      try {
        localStorage.removeItem("workspace-record-id");
      } catch {}
    }
  }
  const drafts = await api("/history?view=inbox&sort=updated&page_size=1");
  if (!id && !streaming && drafts.items[0]) await select(drafts.items[0].id);
  else if (!id && !streaming) {
    const previous = await api("/meetings");
    if (previous[0]?.status === "interrupted") await select(previous[0].id);
  }
}
