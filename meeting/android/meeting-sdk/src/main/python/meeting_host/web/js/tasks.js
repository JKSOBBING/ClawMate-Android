/* Background task list and task actions. */
async function workspaceTasks() {
  const tasks = await api("/tasks" + (id ? "?meeting_id=" + encodeURIComponent(id) : ""));
  const stamp = JSON.stringify(tasks);
  if (stamp === workspaceTaskStamp) return;
  workspaceTaskStamp = stamp;
  const target = el("workspace-tasks");
  target.replaceChildren();
  if (!tasks.length) target.textContent = "暂无后台任务。";
  for (const task of tasks) {
    const row = document.createElement("p"),
      text = document.createElement("span");
    text.textContent =
      (task.kind === "summary" ? "纪要" : "精修") +
      " · " +
      task.stage +
      " · " +
      Math.round(task.progress * 100) +
      "%" +
      (task.error ? " · " + task.error : "");
    row.append(text);
    if (["queued", "running"].includes(task.status)) {
      const button = document.createElement("button");
      button.textContent = "取消";
      button.onclick = workspaceAction(async () => {
        await api("/tasks/" + task.id + "/cancel", { method: "POST" });
        await workspaceTasks();
      });
      row.append(button);
    }
    if (["failed", "cancelled", "interrupted"].includes(task.status)) {
      const button = document.createElement("button");
      button.textContent = "重试";
      button.onclick = workspaceAction(async () => {
        await api("/tasks/" + task.id + "/retry", { method: "POST" });
        await workspaceTasks();
        await refresh();
      });
      row.append(button);
    }
    const details = document.createElement("button");
    details.textContent = "详情";
    details.onclick = workspaceAction(async () => {
      const detail = await api("/tasks/" + task.id);
      text.textContent = JSON.stringify(detail, null, 2);
    });
    row.append(details);
    target.append(row);
  }
}
el("refresh-tasks").onclick = workspaceAction(workspaceTasks);
